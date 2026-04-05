package com.vcpnative.app.data.files

import com.vcpnative.app.bridge.BridgeLogger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Atomic file writer: temp write -> validate -> backup original -> atomic rename.
 *
 * Ported from VCPMobile `app_settings_manager.rs` internal_write_app_settings().
 * Prevents data loss from interrupted writes (power loss, crash, etc.).
 */
object AtomicFileWriter {

    private const val TAG = "AtomicFileWriter"

    /**
     * Write [content] to [target] atomically.
     *
     * Steps:
     * 1. Write bytes to a temp file in the same directory
     * 2. Optionally validate the written content
     * 3. Backup the existing target file (if any) to `<name>.bak`
     * 4. Atomic rename temp -> target
     *
     * @param target    Destination file
     * @param content   Bytes to write
     * @param validate  Optional validator; throw or return false to abort
     */
    suspend fun write(
        target: File,
        content: ByteArray,
        validate: ((ByteArray) -> Boolean)? = null,
    ) = withContext(Dispatchers.IO) {
        val parentDir = target.parentFile
            ?: throw IOException("Target file has no parent directory: ${target.absolutePath}")
        parentDir.mkdirs()

        val temp = File(parentDir, "${target.name}.tmp.${System.nanoTime()}")
        val backup = File(parentDir, "${target.name}.bak")

        try {
            // Step 1: write to temp
            temp.writeBytes(content)

            // Step 2: validate
            if (validate != null) {
                val written = temp.readBytes()
                if (!validate(written)) {
                    throw IOException("Validation failed for ${target.name}")
                }
            }

            // Step 3: backup current file
            if (target.exists()) {
                target.copyTo(backup, overwrite = true)
            }

            // Step 4: atomic rename
            if (!temp.renameTo(target)) {
                // renameTo can fail across filesystems; fall back to copy+delete
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            BridgeLogger.d(TAG, "Wrote ${target.name} (${content.size} bytes)")
        } catch (e: Exception) {
            temp.delete()
            BridgeLogger.e(TAG, "Failed to write ${target.name}: ${e.message}")
            throw e
        }
    }

    /**
     * Write a string atomically.
     */
    suspend fun writeText(target: File, text: String) {
        write(target, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Write JSON atomically with format validation.
     * Accepts both JSONObject and JSONArray payloads.
     */
    suspend fun writeJson(target: File, json: String) {
        write(target, json.toByteArray(Charsets.UTF_8)) { bytes ->
            val str = String(bytes, Charsets.UTF_8)
            isValidJson(str)
        }
    }

    /**
     * Read with three-layer fallback: primary -> backup -> default.
     *
     * Ported from VCPMobile's 3-stage recovery pattern.
     */
    suspend fun readWithFallback(
        target: File,
        defaultContent: String = "{}",
    ): String = withContext(Dispatchers.IO) {
        // Layer 1: read primary
        if (target.exists()) {
            try {
                val text = target.readText(Charsets.UTF_8)
                if (text.isNotBlank()) return@withContext text
            } catch (e: Exception) {
                BridgeLogger.w(TAG, "Primary read failed for ${target.name}: ${e.message}")
            }
        }

        // Layer 2: read backup
        val backup = File(target.parentFile, "${target.name}.bak")
        if (backup.exists()) {
            try {
                val text = backup.readText(Charsets.UTF_8)
                if (text.isNotBlank()) {
                    BridgeLogger.i(TAG, "Recovered ${target.name} from backup")
                    // Restore backup to primary
                    backup.copyTo(target, overwrite = true)
                    return@withContext text
                }
            } catch (e: Exception) {
                BridgeLogger.w(TAG, "Backup read failed for ${target.name}: ${e.message}")
            }
        }

        // Layer 3: return default
        BridgeLogger.w(TAG, "Using default content for ${target.name}")
        defaultContent
    }

    /**
     * Retry a block with exponential backoff.
     *
     * Ported from VCPMobile's exponential backoff retry logic (50ms, 100ms, 200ms).
     */
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        initialDelayMs: Long = 50,
        block: suspend () -> T,
    ): T {
        var delay = initialDelayMs
        repeat(maxRetries - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                BridgeLogger.w(TAG, "Retry ${attempt + 1}/$maxRetries after ${delay}ms: ${e.message}")
                kotlinx.coroutines.delay(delay)
                delay *= 2
            }
        }
        return block() // last attempt throws on failure
    }

    private fun isValidJson(str: String): Boolean {
        val trimmed = str.trim()
        return try {
            when {
                trimmed.startsWith("{") -> { JSONObject(trimmed); true }
                trimmed.startsWith("[") -> { JSONArray(trimmed); true }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}
