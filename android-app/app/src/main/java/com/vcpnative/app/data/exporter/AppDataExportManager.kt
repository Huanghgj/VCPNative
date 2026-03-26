package com.vcpnative.app.data.exporter

import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.room.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface AppDataExportResult {
    data class Exported(
        val exportId: String,
        val appDataPath: String,
        val zipPath: String,
        val reportPath: String,
        val warnings: List<String>,
    ) : AppDataExportResult

    data class Failed(
        val message: String,
        val reportPath: String? = null,
    ) : AppDataExportResult
}

class AppDataExportManager(
    private val database: AppDatabase,
    private val fileStore: AppFileStore,
) {
    suspend fun exportCurrentSnapshot(): AppDataExportResult = withContext(Dispatchers.IO) {
        val exportId = buildExportId()
        val sessionDir = File(fileStore.exportsDir, exportId).apply {
            deleteRecursively()
            mkdirs()
        }
        val exportAppDataDir = File(sessionDir, APP_DATA_DIR_NAME).apply { mkdirs() }
        val warnings = mutableListOf<String>()

        return@withContext try {
            copyCompatSnapshot(exportAppDataDir, warnings)
            val copiedAttachmentCount = copyDirectoryContents(
                sourceDir = fileStore.attachmentsDir,
                targetDir = File(exportAppDataDir, USER_DATA_ATTACHMENTS_PATH),
            )
            copyAgentAvatars(exportAppDataDir)
            copyUserAvatar(exportAppDataDir)
            val passthroughStats = mergePassthrough(exportAppDataDir)
            val zipFile = buildExportZip(
                sessionDir = sessionDir,
                exportId = exportId,
                appDataDir = exportAppDataDir,
            )

            val manifestFile = writeManifest(
                sessionDir = sessionDir,
                exportId = exportId,
                appDataDir = exportAppDataDir,
                zipFile = zipFile,
                copiedAttachmentCount = copiedAttachmentCount,
                passthroughStats = passthroughStats,
            )
            val reportFile = writeReport(
                sessionDir = sessionDir,
                exportId = exportId,
                appDataDir = exportAppDataDir,
                zipFile = zipFile,
                copiedAttachmentCount = copiedAttachmentCount,
                passthroughStats = passthroughStats,
                warnings = warnings,
            )

            if (!manifestFile.isFile) {
                warnings += "manifest.json 写入失败"
            }

            AppDataExportResult.Exported(
                exportId = exportId,
                appDataPath = exportAppDataDir.absolutePath,
                zipPath = zipFile.absolutePath,
                reportPath = reportFile.absolutePath,
                warnings = warnings.toList(),
            )
        } catch (error: Throwable) {
            val reportFile = writeFailureReport(
                sessionDir = sessionDir,
                exportId = exportId,
                failureMessage = error.message ?: "导出失败",
                warnings = warnings,
            )
            AppDataExportResult.Failed(
                message = error.message ?: "导出失败",
                reportPath = reportFile.absolutePath,
            )
        }
    }

    private fun copyCompatSnapshot(
        exportAppDataDir: File,
        warnings: MutableList<String>,
    ) {
        val compatAppDataDir = fileStore.compatAppDataDir()
        if (!compatAppDataDir.isDirectory) {
            warnings += "compat/AppData 不存在，已导出空骨架"
            return
        }
        copyDirectoryContents(
            sourceDir = compatAppDataDir,
            targetDir = exportAppDataDir,
        )
    }

    private fun copyUserAvatar(
        exportAppDataDir: File,
    ) {
        val userDataDir = File(exportAppDataDir, USER_DATA_DIR_NAME).apply { mkdirs() }
        fileStore.userAvatarsDir().listFiles().orEmpty()
            .filter(File::isFile)
            .forEach { avatarFile ->
                avatarFile.copyTo(File(userDataDir, avatarFile.name), overwrite = true)
            }
    }

    private fun copyAgentAvatars(
        exportAppDataDir: File,
    ) {
        val agentsDir = File(exportAppDataDir, AGENTS_DIR_NAME).apply { mkdirs() }
        fileStore.agentAvatarsDir().listFiles().orEmpty()
            .filter(File::isFile)
            .forEach { avatarFile ->
                val agentId = avatarFile.nameWithoutExtension
                if (agentId.isBlank()) {
                    return@forEach
                }
                val extension = avatarFile.extension.ifBlank { return@forEach }
                val targetDir = File(agentsDir, agentId).apply { mkdirs() }
                avatarFile.copyTo(File(targetDir, "avatar.$extension"), overwrite = true)
            }
    }

    private fun mergePassthrough(
        exportAppDataDir: File,
    ): PassthroughStats {
        val mergedPaths = mutableListOf<String>()
        val skippedPaths = mutableListOf<String>()
        var copiedFileCount = 0

        fileStore.passthroughDir.listFiles().orEmpty()
            .sortedBy { it.name }
            .forEach { entry ->
                if (entry.isDirectory) {
                    entry.walkTopDown()
                        .filter { it.isFile }
                        .forEach { sourceFile ->
                            val relativePath = sourceFile.relativeTo(entry).invariantSeparatorsPath
                            val targetFile = File(exportAppDataDir, relativePath)
                            if (targetFile.exists()) {
                                skippedPaths += relativePath
                                return@forEach
                            }
                            targetFile.parentFile?.mkdirs()
                            sourceFile.copyTo(targetFile, overwrite = false)
                            mergedPaths += relativePath
                            copiedFileCount += 1
                        }
                } else if (entry.isFile) {
                    val targetFile = File(exportAppDataDir, entry.name)
                    if (targetFile.exists()) {
                        skippedPaths += entry.name
                    } else {
                        targetFile.parentFile?.mkdirs()
                        entry.copyTo(targetFile, overwrite = false)
                        mergedPaths += entry.name
                        copiedFileCount += 1
                    }
                }
            }

        return PassthroughStats(
            copiedFileCount = copiedFileCount,
            mergedPaths = mergedPaths,
            skippedPaths = skippedPaths,
        )
    }

    private suspend fun writeManifest(
        sessionDir: File,
        exportId: String,
        appDataDir: File,
        zipFile: File,
        copiedAttachmentCount: Int,
        passthroughStats: PassthroughStats,
    ): File {
        val manifestFile = File(sessionDir, MANIFEST_FILE_NAME)
        val json = JSONObject()
            .put("exportId", exportId)
            .put("generatedAt", REPORT_TIME_FORMATTER.format(Instant.now()))
            .put("appDataPath", appDataDir.absolutePath)
            .put("zipPath", zipFile.absolutePath)
            .put("zipSizeBytes", zipFile.length())
            .put("copiedAttachments", copiedAttachmentCount)
            .put("passthroughCopied", passthroughStats.copiedFileCount)
            .put("passthroughMergedPaths", JSONArray(passthroughStats.mergedPaths))
            .put("passthroughSkippedPaths", JSONArray(passthroughStats.skippedPaths))
        manifestFile.writeText(json.toString(2))
        return manifestFile
    }

    private suspend fun writeReport(
        sessionDir: File,
        exportId: String,
        appDataDir: File,
        zipFile: File,
        copiedAttachmentCount: Int,
        passthroughStats: PassthroughStats,
        warnings: List<String>,
    ): File {
        val reportFile = File(sessionDir, REPORT_FILE_NAME)
        val json = JSONObject()
            .put("exportId", exportId)
            .put("status", "exported")
            .put("generatedAt", REPORT_TIME_FORMATTER.format(Instant.now()))
            .put("appDataPath", appDataDir.absolutePath)
            .put("zipPath", zipFile.absolutePath)
            .put("zipSizeBytes", zipFile.length())
            .put("agents", database.agentDao().count())
            .put("topics", database.topicDao().countAll())
            .put("messages", database.messageDao().countAll())
            .put("messageAttachments", database.messageAttachmentDao().countAll())
            .put("copiedAttachments", copiedAttachmentCount)
            .put("passthroughCopied", passthroughStats.copiedFileCount)
            .put("passthroughMergedPaths", JSONArray(passthroughStats.mergedPaths))
            .put("passthroughSkippedPaths", JSONArray(passthroughStats.skippedPaths))
            .put("warnings", JSONArray(warnings))
        reportFile.writeText(json.toString(2))
        return reportFile
    }

    private fun buildExportZip(
        sessionDir: File,
        exportId: String,
        appDataDir: File,
    ): File {
        val zipFile = File(sessionDir, "$exportId.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOutput ->
            val zipRootParent = appDataDir.parentFile ?: sessionDir
            appDataDir.walkTopDown()
                .filter { it != zipRootParent }
                .forEach { source ->
                    val relativePath = source.relativeTo(zipRootParent).invariantSeparatorsPath
                    val entryName = if (source.isDirectory) {
                        "$relativePath/"
                    } else {
                        relativePath
                    }
                    val entry = ZipEntry(entryName).apply {
                        time = source.lastModified()
                    }
                    zipOutput.putNextEntry(entry)
                    if (source.isFile) {
                        source.inputStream().use { input ->
                            input.copyTo(zipOutput)
                        }
                    }
                    zipOutput.closeEntry()
                }
        }
        return zipFile
    }

    private fun writeFailureReport(
        sessionDir: File,
        exportId: String,
        failureMessage: String,
        warnings: List<String>,
    ): File {
        val reportFile = File(sessionDir, REPORT_FILE_NAME)
        val json = JSONObject()
            .put("exportId", exportId)
            .put("status", "failed")
            .put("generatedAt", REPORT_TIME_FORMATTER.format(Instant.now()))
            .put("failureMessage", failureMessage)
            .put("warnings", JSONArray(warnings))
        reportFile.writeText(json.toString(2))
        return reportFile
    }

    private fun copyDirectoryContents(
        sourceDir: File,
        targetDir: File,
    ): Int {
        if (!sourceDir.isDirectory) {
            return 0
        }
        var copiedCount = 0
        sourceDir.walkTopDown()
            .filter { it != sourceDir }
            .forEach { source ->
                val relativePath = source.relativeTo(sourceDir).invariantSeparatorsPath
                val target = File(targetDir, relativePath)
                if (source.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    copiedCount += 1
                }
            }
        return copiedCount
    }

    private fun buildExportId(): String =
        "export_${java.lang.Long.toString(System.currentTimeMillis(), 36)}"

    private data class PassthroughStats(
        val copiedFileCount: Int,
        val mergedPaths: List<String>,
        val skippedPaths: List<String>,
    )

    private companion object {
        const val APP_DATA_DIR_NAME = "AppData"
        const val AGENTS_DIR_NAME = "Agents"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val REPORT_FILE_NAME = "report.json"
        const val USER_DATA_DIR_NAME = "UserData"
        const val USER_DATA_ATTACHMENTS_PATH = "UserData/attachments"
        val REPORT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }
}
