package com.vcpnative.app.data.files

import android.content.Context
import java.io.File

interface AppFileStore {
    val rootDir: File
    val attachmentsDir: File
    val avatarsDir: File
    val importsDir: File
    val exportsDir: File
    val compatDir: File
    val passthroughDir: File

    fun agentAvatarsDir(): File = File(avatarsDir, "agents")

    fun userAvatarsDir(): File = File(avatarsDir, "user")

    fun agentAvatarFile(agentId: String, extension: String): File =
        File(agentAvatarsDir(), "$agentId.${extension.trimStart('.')}")

    fun userAvatarFile(extension: String): File =
        File(userAvatarsDir(), "user_avatar.${extension.trimStart('.')}")

    fun compatAppDataDir(): File = File(compatDir, "AppData")

    fun systemPromptPresetsDir(): File = File(compatAppDataDir(), "systemPromptPresets")

    fun compatAgentsDir(): File = File(compatAppDataDir(), "Agents")

    fun compatSettingsFile(): File = File(compatAppDataDir(), "settings.json")

    fun compatUserDataDir(): File = File(compatAppDataDir(), "UserData")

    fun compatAgentDir(agentId: String): File = File(compatAgentsDir(), agentId)

    fun agentCompatDataDir(agentId: String): File = File(compatUserDataDir(), agentId)

    fun agentHistoryFile(agentId: String, topicId: String): File =
        File(agentCompatDataDir(agentId), "topics/$topicId/history.json")
}

class AndroidPrivateFileStore(
    context: Context,
) : AppFileStore {
    override val rootDir: File = resolveRootDir(context).ensureDir()
    override val attachmentsDir: File = File(rootDir, "attachments").ensureDir()
    override val avatarsDir: File = File(rootDir, "avatars").ensureDir()
    override val importsDir: File = File(rootDir, "imports").ensureDir()
    override val exportsDir: File = File(rootDir, "exports").ensureDir()
    override val compatDir: File = File(rootDir, "compat").ensureDir()
    override val passthroughDir: File = File(rootDir, "passthrough").ensureDir()

    init {
        agentAvatarsDir().ensureDir()
        userAvatarsDir().ensureDir()
        compatAppDataDir().ensureDir()
        systemPromptPresetsDir().ensureDir()
        compatAgentsDir().ensureDir()
        compatUserDataDir().ensureDir()
    }

    private fun resolveRootDir(context: Context): File =
        context.getExternalFilesDir(null)
            ?.parentFile
            ?.takeIf { it.path.contains("/Android/data/${context.packageName}") }
            ?: File(context.filesDir, "vcpnative")

    private fun File.ensureDir(): File = apply {
        if (!exists()) {
            mkdirs()
        }
    }
}
