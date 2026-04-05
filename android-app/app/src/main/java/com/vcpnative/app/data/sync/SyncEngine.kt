package com.vcpnative.app.data.sync

import com.vcpnative.app.bridge.BridgeLogger
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.files.AtomicFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manifest-based incremental sync engine.
 *
 * Ported from VCPMobile `sync_handlers.rs`.
 * Supports ping, manifest fetch, delta computation, and sequential download.
 */
class SyncEngine(
    private val httpClient: OkHttpClient,
    private val fileStore: AppFileStore,
) {
    data class SyncConfig(
        val serverIp: String,
        val serverPort: Int,
        val syncToken: String,
    ) {
        val baseUrl: String get() = "http://$serverIp:$serverPort"
    }

    data class RemoteFileInfo(
        val path: String,
        val mtimeMs: Long,
        val size: Long,
        val hash: String? = null,
    )

    data class LocalFileInfo(
        val mtimeMs: Long,
        val size: Long,
    )

    data class SyncDelta(
        val toDownload: List<String>,
        val toDelete: List<String>,
        val unchanged: List<String>,
    )

    data class SyncResult(
        val succeeded: Int,
        val failed: List<Pair<String, String>>,
    )

    private val boundedClient = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Step 1: Connectivity check (5s timeout, matching VCPMobile sync_ping). */
    suspend fun ping(config: SyncConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${config.baseUrl}/sync/ping")
                .header("x-sync-token", config.syncToken)
                .build()
            boundedClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "Ping failed: ${e.message}")
            false
        }
    }

    /** Step 2: Fetch remote file manifest. */
    suspend fun fetchManifest(config: SyncConfig): Map<String, RemoteFileInfo> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${config.baseUrl}/sync/manifest")
                .header("x-sync-token", config.syncToken)
                .header("Accept-Encoding", "gzip")
                .build()
            val response = boundedClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw SyncException("Manifest fetch failed: HTTP ${response.code}")
            }
            val json = JSONObject(response.body.string())
            val result = mutableMapOf<String, RemoteFileInfo>()
            json.keys().forEach { path ->
                val info = json.getJSONObject(path)
                result[path] = RemoteFileInfo(
                    path = path,
                    mtimeMs = info.optLong("mtime_ms", 0),
                    size = info.optLong("size", 0),
                    hash = info.optString("hash").takeIf { it.isNotBlank() },
                )
            }
            result
        }

    /** Step 3: Build local manifest from compat directory. */
    fun getLocalManifest(): Map<String, LocalFileInfo> {
        val result = mutableMapOf<String, LocalFileInfo>()
        val compatDir = fileStore.compatAppDataDir()
        if (!compatDir.exists()) return result

        compatDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(compatDir).path
            result[relativePath] = LocalFileInfo(
                mtimeMs = file.lastModified(),
                size = file.length(),
            )
        }
        return result
    }

    /** Step 4: Compute delta between remote and local manifests. */
    fun computeDelta(
        remote: Map<String, RemoteFileInfo>,
        local: Map<String, LocalFileInfo>,
    ): SyncDelta {
        val toDownload = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val toDelete = mutableListOf<String>()

        for ((path, remoteInfo) in remote) {
            val localInfo = local[path]
            if (localInfo == null || localInfo.mtimeMs < remoteInfo.mtimeMs || localInfo.size != remoteInfo.size) {
                toDownload.add(path)
            } else {
                unchanged.add(path)
            }
        }
        for (path in local.keys) {
            if (path !in remote) {
                toDelete.add(path)
            }
        }
        return SyncDelta(toDownload, toDelete, unchanged)
    }

    /**
     * Step 5: Execute sync — download files sequentially (prevents OOM).
     * Ported from VCPMobile's sequential download pattern.
     */
    suspend fun executeSync(
        config: SyncConfig,
        delta: SyncDelta,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
    ): SyncResult = withContext(Dispatchers.IO) {
        var succeeded = 0
        val failed = mutableListOf<Pair<String, String>>()
        val total = delta.toDownload.size

        for ((index, path) in delta.toDownload.withIndex()) {
            onProgress(index + 1, total, path)
            try {
                downloadFile(config, path)
                succeeded++
            } catch (e: Exception) {
                BridgeLogger.w(TAG, "Failed to download $path: ${e.message}")
                failed.add(path to (e.message ?: "Unknown error"))
            }
        }

        // Handle settings.json with smart merge
        if (delta.toDownload.contains("settings.json")) {
            mergeSettings(config)
        }

        SyncResult(succeeded, failed)
    }

    /** Download a single file atomically (temp -> rename). */
    private suspend fun downloadFile(config: SyncConfig, path: String) {
        val request = Request.Builder()
            .url("${config.baseUrl}/sync/file?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .header("x-sync-token", config.syncToken)
            .build()
        val response = boundedClient.newCall(request).execute()
        if (!response.isSuccessful) throw SyncException("Download failed: HTTP ${response.code}")

        val targetFile = File(fileStore.compatAppDataDir(), path)
        AtomicFileWriter.write(targetFile, response.body.bytes())
    }

    /**
     * Smart merge for settings.json — preserves mobile-only fields.
     * Ported from VCPMobile sync_handlers.rs JSON merge strategy.
     */
    private suspend fun mergeSettings(config: SyncConfig) {
        val localFile = fileStore.compatSettingsFile()
        if (!localFile.exists()) return

        try {
            val localJson = JSONObject(localFile.readText())
            val remoteFile = File(fileStore.compatAppDataDir(), "settings.json")
            if (!remoteFile.exists()) return
            val remoteJson = JSONObject(remoteFile.readText())
            val merged = JsonMerger.mergeSettings(remoteJson, localJson)
            AtomicFileWriter.writeJson(localFile, merged.toString(2))
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "Settings merge failed: ${e.message}")
        }
    }

    class SyncException(message: String) : Exception(message)

    companion object {
        private const val TAG = "SyncEngine"
    }
}
