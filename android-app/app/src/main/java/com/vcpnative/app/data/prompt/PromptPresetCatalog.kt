package com.vcpnative.app.data.prompt

import com.vcpnative.app.data.files.AppFileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val DEFAULT_PRESET_PROMPT_PATH = "./AppData/systemPromptPresets"

data class PromptPresetInfo(
    val name: String,
    val path: String,
    val extension: String,
    val size: Long,
    val modifiedAt: Long,
)

data class PromptPresetListing(
    val requestedPath: String,
    val resolvedPath: String,
    val presets: List<PromptPresetInfo>,
    val message: String? = null,
)

interface PromptPresetCatalog {
    suspend fun listPresets(
        presetPath: String = DEFAULT_PRESET_PROMPT_PATH,
    ): PromptPresetListing

    suspend fun loadPresetContent(presetFilePath: String): String
}

class FileBackedPromptPresetCatalog(
    private val fileStore: AppFileStore,
) : PromptPresetCatalog {
    override suspend fun listPresets(presetPath: String): PromptPresetListing =
        withContext(Dispatchers.IO) {
            val requestedPath = presetPath.ifBlank { DEFAULT_PRESET_PROMPT_PATH }
            val directory = resolvePath(requestedPath)
            ensureManagedDirectory(directory)

            if (!directory.exists() || !directory.isDirectory) {
                return@withContext PromptPresetListing(
                    requestedPath = requestedPath,
                    resolvedPath = directory.absolutePath,
                    presets = emptyList(),
                    message = "预设目录不存在或为空",
                )
            }

            val presets = directory.listFiles().orEmpty()
                .filter(File::isFile)
                .mapNotNull { file ->
                    val extension = file.extension.lowercase().let { ext ->
                        if (ext == "md" || ext == "txt") ext else null
                    } ?: return@mapNotNull null
                    PromptPresetInfo(
                        name = file.nameWithoutExtension,
                        path = file.absolutePath,
                        extension = ".$extension",
                        size = file.length(),
                        modifiedAt = file.lastModified(),
                    )
                }
                .sortedByDescending { it.modifiedAt }

            PromptPresetListing(
                requestedPath = requestedPath,
                resolvedPath = directory.absolutePath,
                presets = presets,
                message = if (presets.isEmpty()) "预设目录不存在或为空" else null,
            )
        }

    override suspend fun loadPresetContent(presetFilePath: String): String =
        withContext(Dispatchers.IO) {
            val file = resolvePath(presetFilePath)
            val managedRoot = fileStore.rootDir.canonicalPath
            val canonicalPath = file.canonicalFile.path
            require(canonicalPath.startsWith(managedRoot)) {
                "预设文件路径不在可管理范围内"
            }
            require(file.isFile) { "预设文件不存在: $presetFilePath" }
            file.readText()
        }

    private fun resolvePath(rawPath: String): File {
        val nextPath = rawPath.ifBlank { DEFAULT_PRESET_PROMPT_PATH }
        val directFile = File(nextPath)
        if (directFile.isAbsolute) {
            return directFile.normalize()
        }

        val cleanPath = nextPath.replace(RELATIVE_PREFIX_REGEX, "")
        return when {
            cleanPath.startsWith(APP_DATA_PREFIX) -> {
                val suffix = cleanPath.removePrefix(APP_DATA_PREFIX).trimStart('/', '\\')
                if (suffix.isBlank()) {
                    fileStore.compatAppDataDir()
                } else {
                    File(fileStore.compatAppDataDir(), suffix)
                }
            }

            else -> File(fileStore.rootDir, cleanPath)
        }.normalize()
    }

    private fun ensureManagedDirectory(directory: File) {
        if (directory.exists()) {
            return
        }
        val managedRoot = fileStore.rootDir.canonicalPath
        val targetPath = directory.canonicalFile.path
        if (targetPath.startsWith(managedRoot)) {
            directory.mkdirs()
        }
    }

    companion object {
        private const val APP_DATA_PREFIX = "AppData"
        private val RELATIVE_PREFIX_REGEX = Regex("^\\.[/\\\\]")
    }
}
