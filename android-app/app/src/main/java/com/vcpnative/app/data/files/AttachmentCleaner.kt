package com.vcpnative.app.data.files

import com.vcpnative.app.bridge.BridgeLogger
import com.vcpnative.app.data.room.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Attachment file management with SHA256 content-addressing and orphan cleanup.
 *
 * Ported from VCPMobile `file_manager.rs`.
 */
class AttachmentCleaner(
    private val database: AppDatabase,
    private val fileStore: AppFileStore,
) {
    data class CleanupResult(
        val deletedFiles: Int,
        val freedBytes: Long,
    )

    /**
     * Delete attachment files that are not referenced by any message.
     *
     * Ported from VCPMobile cleanup_orphaned_attachments().
     */
    suspend fun cleanup(): CleanupResult = withContext(Dispatchers.IO) {
        val referencedHashes = database.messageAttachmentDao()
            .loadAllHashes()
            .toSet()

        var deletedCount = 0
        var freedBytes = 0L

        fileStore.attachmentsDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("tmp_")) {
                // Clean up temp files older than 1 hour
                if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000) {
                    freedBytes += file.length()
                    file.delete()
                    deletedCount++
                }
                return@forEach
            }

            val hash = file.nameWithoutExtension
            if (hash !in referencedHashes) {
                freedBytes += file.length()
                file.delete()
                deletedCount++
            }
        }

        if (deletedCount > 0) {
            BridgeLogger.i(TAG, "Cleaned up $deletedCount orphaned attachments, freed ${freedBytes / 1024}KB")
        }

        CleanupResult(deletedCount, freedBytes)
    }

    companion object {
        private const val TAG = "AttachmentCleaner"

        /**
         * Compute SHA256 hash of an input stream (streaming, no full load).
         *
         * Ported from VCPMobile's streaming hash computation for large files.
         */
        suspend fun computeSha256(inputStream: InputStream): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024) // 128KB chunks
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Store a file using content-addressed naming (SHA256 hash).
         * Returns the hash. If a file with the same hash exists, skips writing.
         */
        suspend fun storeContentAddressed(
            inputStream: InputStream,
            attachmentsDir: File,
            extension: String,
        ): Pair<File, String> = withContext(Dispatchers.IO) {
            // Write to temp file while computing hash
            val tempFile = File(attachmentsDir, "tmp_${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)

            tempFile.outputStream().use { out ->
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val ext = extension.trimStart('.')
            val targetFile = File(attachmentsDir, "$hash.$ext")

            if (targetFile.exists()) {
                // Deduplication: file already exists
                tempFile.delete()
            } else {
                // Atomic rename
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            targetFile to hash
        }
    }
}
