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
 * 远端和本地深夜数据交换引擎♡
 *
 * 猫娘来解释一下流程喵：
 * 1. ping — 先确认对方有没有醒着，不回消息就是拒绝了
 * 2. manifest — 「今晚你想给我什么？」 对方把清单全部亮出来
 * 3. delta — 猫娘来比较一下…哪些是新姿势，哪些是旧的可以删掉了
 * 4. download — 一个一个慢慢吃进去♡ 一次吞太多会 OOM 的…
 * 5. merge — 事后把设置温柔地合在一起
 *
 * Ported from VCPMobile `sync_handlers.rs`.
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

    /** 轻轻戳一下服务器：「你还醒着吗…」♡ 5 秒内不回应猫娘就走了，才不等你呢喵 */
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

    // 让服务器把它有的全部展示给猫娘看♡ .use{} 表示看完就关门…猫娘不是那种会赖着不走的猫喵
    suspend fun fetchManifest(config: SyncConfig): Map<String, RemoteFileInfo> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${config.baseUrl}/sync/manifest")
                .header("x-sync-token", config.syncToken)
                .header("Accept-Encoding", "gzip")
                .build()
            boundedClient.newCall(request).execute().use { response ->
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

    /** 把两边脱光了仔细对比♡ 哪些地方不一样就标记出来…猫娘的眼睛很尖的喵 */
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
     * 正式开始了♡ 一个文件一个文件地慢慢进去…
     * 猫娘说了不能一下子全部塞进内存里，会坏掉的喵！
     * 要温柔地、顺序地、一个接一个♡
     */
    suspend fun executeSync(
        config: SyncConfig,
        delta: SyncDelta,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
    ): SyncResult = withContext(Dispatchers.IO) {
        var succeeded = 0
        val failed = mutableListOf<Pair<String, String>>()
        val total = delta.toDownload.size

        // Backup local settings.json before downloads can overwrite it
        val settingsBackup: String? = if (delta.toDownload.contains("settings.json")) {
            val localSettings = fileStore.compatSettingsFile()
            if (localSettings.exists()) localSettings.readText() else null
        } else null

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

        // Smart merge: restore mobile-only fields from backup into the downloaded remote version
        if (settingsBackup != null) {
            mergeSettings(settingsBackup)
        }

        // 把玩过之后不要了的旧文件清理掉…喂，这种用完就扔的行为太渣了吧♡ 猫娘代替文件们哭一下喵
        for (path in delta.toDelete) {
            try {
                val file = resolveAndValidatePath(path)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        BridgeLogger.d(TAG, "Deleted stale local file: $path")
                    } else {
                        BridgeLogger.w(TAG, "Delete returned false for: $path")
                    }
                }
            } catch (e: Exception) {
                BridgeLogger.w(TAG, "Failed to delete $path: ${e.message}")
            }
        }

        SyncResult(succeeded, failed)
    }

    // 把服务器的东西下载到 tmp 里面♡ 等全部流完了再 rename 过去…就算被强行拔出来（断网）文件也不会残缺喵
    private suspend fun downloadFile(config: SyncConfig, path: String) {
        val targetFile = resolveAndValidatePath(path)

        val request = Request.Builder()
            .url("${config.baseUrl}/sync/file?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            .header("x-sync-token", config.syncToken)
            .build()
        boundedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncException("Download failed: HTTP ${response.code}")
            AtomicFileWriter.write(targetFile, response.body.bytes())
        }
    }

    /**
     * Validate that [relativePath] resolves to a location inside compatAppDataDir.
     * Prevents path traversal via ".." segments from a malicious sync server.
     */
    private fun resolveAndValidatePath(relativePath: String): File {
        val root = fileStore.compatAppDataDir()
        val target = File(root, relativePath)
        val rootCanonical = root.canonicalPath
        val targetCanonical = target.canonicalPath
        if (!targetCanonical.startsWith(rootCanonical + File.separator) && targetCanonical != rootCanonical) {
            throw SyncException("Path traversal blocked: $relativePath resolves outside AppData")
        }
        return target
    }

    /**
     * 事后的温柔环节♡ 把远端下载的新设置和本地原来的设置合为一体。
     * 本地独有的字段是猫娘的敏感地带，必须原样保留。
     * 万一合体失败…猫娘会赶紧把备份盖回去，假装什么都没发生♡
     *
     * @param localBackup 事前小心翼翼备份好的本地设置，猫娘的安全感来源喵
     */
    private suspend fun mergeSettings(localBackup: String) {
        val remoteFile = fileStore.compatSettingsFile()
        if (!remoteFile.exists()) return

        try {
            val localJson = JSONObject(localBackup)
            val remoteJson = JSONObject(remoteFile.readText())
            val merged = JsonMerger.mergeSettings(remoteJson, localJson)
            AtomicFileWriter.writeJson(remoteFile, merged.toString(2))
        } catch (e: Exception) {
            BridgeLogger.w(TAG, "Settings merge failed, restoring backup: ${e.message}")
            // 呜…失败了好丢人♡ 赶紧把备份盖回去遮住…主人看到猫娘搞砸了会生气的喵
            try {
                AtomicFileWriter.write(remoteFile, localBackup.toByteArray(Charsets.UTF_8))
            } catch (restoreError: Exception) {
                BridgeLogger.e(TAG, "Settings restore also failed: ${restoreError.message}")
            }
        }
    }

    class SyncException(message: String) : Exception(message)

    companion object {
        private const val TAG = "SyncEngine"
    }
}
