package com.vcpnative.app.data.importer

import androidx.room.withTransaction
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.data.room.AppDatabase
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.data.room.RegexRuleEntity
import com.vcpnative.app.data.room.TopicEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface AppDataImportResult {
    data object NoPendingImport : AppDataImportResult

    data class Imported(
        val sourceName: String,
        val agents: Int,
        val topics: Int,
        val messages: Int,
        val reportPath: String,
        val warnings: List<String>,
    ) : AppDataImportResult

    data class Failed(
        val sourceName: String?,
        val message: String,
        val reportPath: String? = null,
    ) : AppDataImportResult
}

class AppDataImportManager(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val fileStore: AppFileStore,
) {
    suspend fun importPending(): AppDataImportResult = withContext(Dispatchers.IO) {
        val candidate = findImportCandidate() ?: return@withContext AppDataImportResult.NoPendingImport
        val sessionId = buildSessionId()
        var preparedFiles: PreparedImportFiles? = null
        val workingRoot = try {
            when (candidate.type) {
                ImportSourceType.Directory -> candidate.root
                ImportSourceType.Zip -> extractZipCandidate(candidate.source, sessionId)
            }
        } catch (error: Throwable) {
            return@withContext AppDataImportResult.Failed(
                sourceName = candidate.source.name,
                message = error.message ?: "导入源解包失败",
            )
        }

        val appDataRoot = resolveAppDataRoot(workingRoot)
            ?: return@withContext AppDataImportResult.Failed(
                sourceName = candidate.source.name,
                message = "未发现可导入的 AppData 根目录",
            )

        val reportDir = File(fileStore.importsDir, "processed/$sessionId").apply { mkdirs() }
        val warnings = mutableListOf<String>()

        try {
            val parsed = parseAppDataRoot(appDataRoot, warnings)
            val prepared = prepareImportFiles(
                appDataRoot = appDataRoot,
                parsed = parsed,
                sessionId = sessionId,
            )
            preparedFiles = prepared
            writeRuntimeData(
                parsed = parsed,
                preparedFiles = prepared,
            )
            archiveImportSource(candidate, reportDir)
            val reportFile = writeReport(
                reportDir = reportDir,
                sourceName = candidate.source.name,
                status = "imported",
                parsed = parsed,
                warnings = warnings,
                failureMessage = null,
            )
            AppDataImportResult.Imported(
                sourceName = candidate.source.name,
                agents = parsed.agents.size,
                topics = parsed.topics.size,
                messages = parsed.messages.size,
                reportPath = reportFile.absolutePath,
                warnings = warnings.toList(),
            )
        } catch (error: Throwable) {
            val reportFile = writeReport(
                reportDir = reportDir,
                sourceName = candidate.source.name,
                status = "failed",
                parsed = null,
                warnings = warnings,
                failureMessage = error.message ?: "导入失败",
            )
            AppDataImportResult.Failed(
                sourceName = candidate.source.name,
                message = error.message ?: "导入失败",
                reportPath = reportFile.absolutePath,
            )
        } finally {
            preparedFiles?.cleanup()
            if (candidate.type == ImportSourceType.Zip) {
                workingRoot.deleteRecursively()
            }
        }
    }

    private fun findImportCandidate(): ImportCandidate? {
        val directAppData = File(fileStore.importsDir, "AppData")
        if (directAppData.isDirectory) {
            return ImportCandidate(
                source = directAppData,
                root = directAppData,
                type = ImportSourceType.Directory,
            )
        }

        val importEntries = fileStore.importsDir.listFiles().orEmpty()
            .filterNot { it.name in setOf("processed", ".staging") }
            .sortedByDescending { it.lastModified() }

        importEntries.firstOrNull { it.isFile && it.extension.equals("zip", ignoreCase = true) }?.let { zipFile ->
            return ImportCandidate(
                source = zipFile,
                root = zipFile,
                type = ImportSourceType.Zip,
            )
        }

        importEntries.firstOrNull { it.isDirectory && resolveAppDataRoot(it) != null }?.let { directory ->
            return ImportCandidate(
                source = directory,
                root = directory,
                type = ImportSourceType.Directory,
            )
        }

        return null
    }

    private fun extractZipCandidate(zipFile: File, sessionId: String): File {
        val stagingDir = File(fileStore.importsDir, ".staging/$sessionId").apply {
            deleteRecursively()
            mkdirs()
        }
        ZipInputStream(FileInputStream(zipFile)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val outputFile = File(stagingDir, entry.name).normalize()
                val canonicalRoot = stagingDir.canonicalFile
                val canonicalTarget = outputFile.canonicalFile
                if (!canonicalTarget.path.startsWith(canonicalRoot.path)) {
                    throw IllegalArgumentException("Zip 条目包含非法路径: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        zipInput.copyTo(output)
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
        return stagingDir
    }

    private fun resolveAppDataRoot(root: File): File? {
        val direct = File(root, "AppData")
        if (isAppDataRoot(direct)) {
            return direct
        }
        return root.takeIf(::isAppDataRoot)
    }

    private fun isAppDataRoot(dir: File): Boolean =
        dir.isDirectory && File(dir, "settings.json").isFile

    private fun parseAppDataRoot(
        appDataRoot: File,
        warnings: MutableList<String>,
    ): ParsedAppData {
        val settingsJson = try {
            JSONObject(File(appDataRoot, "settings.json").readText())
        } catch (error: JSONException) {
            throw IllegalArgumentException("settings.json 无法解析: ${error.message}")
        }

        val agentOrder = settingsJson.optJSONArray("agentOrder").toStringList()
        val orderedAgentIds = linkedMapOf<String, Int>().apply {
            agentOrder.forEachIndexed { index, agentId ->
                put(agentId, index)
            }
        }

        val agentsDir = File(appDataRoot, "Agents")
        val userDataDir = File(appDataRoot, "UserData")
        if (!agentsDir.isDirectory) {
            throw IllegalArgumentException("导入包缺少 Agents 目录")
        }

        val seenConversationIds = mutableSetOf<String>()
        val seenMessageIds = mutableSetOf<String>()
        val importedAgents = mutableListOf<AgentEntity>()
        val importedTopics = mutableListOf<TopicEntity>()
        val importedMessages = mutableListOf<MessageEntity>()
        val importedAttachments = mutableListOf<MessageAttachmentEntity>()
        val importedRegexRules = mutableListOf<RegexRuleEntity>()
        val importedAgentAvatars = mutableListOf<FileImportSpec>()
        val topicIdMapping = mutableMapOf<Pair<String, String>, String>()

        val agentDirs = agentsDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { orderedAgentIds[it.name] ?: Int.MAX_VALUE }

        agentDirs.forEachIndexed { defaultIndex, agentDir ->
            val agentId = agentDir.name
            val configFile = File(agentDir, "config.json")
            if (!configFile.isFile) {
                warnings += "跳过 Agent $agentId：缺少 config.json"
                return@forEachIndexed
            }

            val config = try {
                JSONObject(configFile.readText())
            } catch (error: JSONException) {
                warnings += "跳过 Agent $agentId：config.json 无法解析"
                return@forEachIndexed
            }

            val agentSortOrder = orderedAgentIds[agentId] ?: defaultIndex
            val avatarImport = findAvatarFile(agentDir, AVATAR_BASENAME)?.let { avatarFile ->
                FileImportSpec(
                    sourceFile = avatarFile,
                    targetRelativePath = relativePathFromRoot(
                        fileStore.agentAvatarFile(
                            agentId = agentId,
                            extension = avatarFile.extension.lowercase(),
                        ),
                    ),
                )
            }
            avatarImport?.let(importedAgentAvatars::add)
            importedRegexRules += parseRegexRules(
                agentId = agentId,
                agentDir = agentDir,
                config = config,
                warnings = warnings,
            )
            val importedAgent = AgentEntity(
                id = agentId,
                name = config.optString("name").ifBlank { agentId },
                systemPrompt = config.optString("systemPrompt"),
                promptMode = config.optString("promptMode").ifBlank { "original" },
                originalSystemPrompt = config.optString("originalSystemPrompt"),
                advancedSystemPromptJson = config.opt("advancedSystemPrompt")?.toString().orEmpty(),
                presetSystemPrompt = config.optString("presetSystemPrompt"),
                presetPromptPath = config.optString("presetPromptPath"),
                selectedPreset = config.optString("selectedPreset"),
                model = config.optString("model").ifBlank { "gemini-pro" },
                temperature = config.optDouble("temperature", 0.7),
                contextTokenLimit = config.optNullableInt("contextTokenLimit"),
                maxOutputTokens = config.optNullableInt("maxOutputTokens"),
                topP = config.optNullableDouble("top_p"),
                topK = config.optNullableInt("top_k"),
                streamOutput = config.optFlexibleBoolean("streamOutput", true),
                avatarPath = avatarImport?.targetRelativePath,
                sortOrder = agentSortOrder,
                updatedAt = System.currentTimeMillis(),
            )
            importedAgents += importedAgent

            val topicsArray = config.optJSONArray("topics")
            if (topicsArray == null) {
                warnings += "Agent $agentId 没有 topics 数组"
                return@forEachIndexed
            }

            for (topicIndex in 0 until topicsArray.length()) {
                val topicObject = topicsArray.optJSONObject(topicIndex) ?: continue
                val sourceTopicId = topicObject.optString("id").ifBlank { "topic_imported_$topicIndex" }
                val conversationId = uniqueConversationId(
                    agentId = agentId,
                    sourceTopicId = sourceTopicId,
                    seenConversationIds = seenConversationIds,
                )
                topicIdMapping[agentId to sourceTopicId] = conversationId

                val createdAt = topicObject.optLong("createdAt").takeIf { it > 0L }
                    ?: (System.currentTimeMillis() + topicIndex)
                val historyFile = File(userDataDir, "$agentId/topics/$sourceTopicId/history.json")
                val parsedHistory = parseHistoryFile(
                    historyFile = historyFile,
                    conversationId = conversationId,
                    sourceTopicId = sourceTopicId,
                    seenMessageIds = seenMessageIds,
                    warnings = warnings,
                )
                importedMessages += parsedHistory.messages
                importedAttachments += parsedHistory.attachments
                val updatedAt = parsedHistory.messages.maxOfOrNull { it.updatedAt } ?: createdAt

                importedTopics += TopicEntity(
                    id = conversationId,
                    agentId = agentId,
                    sourceTopicId = sourceTopicId,
                    title = topicObject.optString("name").ifBlank { sourceTopicId },
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            }
        }

        if (importedAgents.isEmpty()) {
            throw IllegalArgumentException("没有解析出可导入的 Agent 配置")
        }

        val lastAgentId = settingsJson.optString("lastOpenItemId")
            .ifBlank { settingsJson.optString("lastAgentId") }
            .takeIf { it.isNotBlank() && importedAgents.any { agent -> agent.id == it } }
        val lastTopicSourceId = settingsJson.optString("lastOpenTopicId")
            .ifBlank { settingsJson.optString("lastTopicId") }
        val lastTopicConversationId = if (!lastAgentId.isNullOrBlank() && lastTopicSourceId.isNotBlank()) {
            topicIdMapping[lastAgentId to lastTopicSourceId]
        } else {
            null
        }

        return ParsedAppData(
            settings = ImportedSettings(
                serverUrl = settingsJson.optString("vcpServerUrl"),
                apiKey = settingsJson.optString("vcpApiKey"),
                enableVcpToolInjection = settingsJson.optBoolean("enableVcpToolInjection", false),
                enableAgentBubbleTheme = settingsJson.optBoolean("enableAgentBubbleTheme", false),
                enableContextFolding = settingsJson.optBoolean("enableContextFolding", true),
                contextFoldingKeepRecentMessages = settingsJson.optInt("contextFoldingKeepRecentMessages", 12),
                contextFoldingTriggerMessageCount = settingsJson.optInt("contextFoldingTriggerMessageCount", 24),
                contextFoldingTriggerCharCount = settingsJson.optInt("contextFoldingTriggerCharCount", 24_000),
                contextFoldingExcerptCharLimit = settingsJson.optInt("contextFoldingExcerptCharLimit", 160),
                contextFoldingMaxSummaryEntries = settingsJson.optInt("contextFoldingMaxSummaryEntries", 40),
                lastAgentId = lastAgentId,
                lastTopicConversationId = lastTopicConversationId,
            ),
            agents = importedAgents,
            topics = importedTopics,
            messages = importedMessages.sortedBy { it.createdAt },
            attachments = importedAttachments.sortedWith(compareBy<MessageAttachmentEntity> { it.messageId }.thenBy { it.attachmentOrder }),
            regexRules = importedRegexRules,
            importedAgentIds = importedAgents.mapTo(linkedSetOf()) { it.id },
            fileImports = ParsedFileImports(
                agentAvatars = importedAgentAvatars.toList(),
                userAvatar = findAvatarFile(userDataDir, USER_AVATAR_BASENAME)?.let { avatarFile ->
                    FileImportSpec(
                        sourceFile = avatarFile,
                        targetRelativePath = relativePathFromRoot(
                            fileStore.userAvatarFile(
                                extension = avatarFile.extension.lowercase(),
                            ),
                        ),
                    )
                },
            ),
        )
    }

    private fun parseHistoryFile(
        historyFile: File,
        conversationId: String,
        sourceTopicId: String,
        seenMessageIds: MutableSet<String>,
        warnings: MutableList<String>,
    ): ParsedHistory {
        if (!historyFile.isFile) {
            return ParsedHistory()
        }

        val historyArray = try {
            JSONArray(historyFile.readText())
        } catch (error: JSONException) {
            warnings += "话题 $sourceTopicId 的 history.json 无法解析"
            return ParsedHistory()
        }

        val fallbackBase = System.currentTimeMillis()
        val messages = mutableListOf<MessageEntity>()
        val attachments = mutableListOf<MessageAttachmentEntity>()
        for (index in 0 until historyArray.length()) {
            val messageObject = historyArray.optJSONObject(index) ?: continue
            if (messageObject.optBoolean("isThinking", false)) {
                continue
            }

            val role = normalizeRole(messageObject.optString("role"))
            val content = extractContentText(messageObject.opt("content"))
                ?: summarizeAttachments(messageObject.optJSONArray("attachments"))
                ?: ""
            val createdAt = messageObject.optLong("timestamp").takeIf { it > 0L }
                ?: messageObject.optLong("createdAt").takeIf { it > 0L }
                ?: (fallbackBase + index)
            val updatedAt = messageObject.optLong("updatedAt").takeIf { it > 0L } ?: createdAt
            val rawId = messageObject.optString("id").ifBlank {
                "imported_${conversationId}_${index}"
            }
            val finalId = uniqueMessageId(rawId, seenMessageIds)

            messages += MessageEntity(
                id = finalId,
                topicId = conversationId,
                role = role,
                content = content,
                status = determineStatus(messageObject),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
            attachments += parseHistoryAttachments(
                messageId = finalId,
                attachmentsArray = messageObject.optJSONArray("attachments"),
                createdAt = createdAt,
            )
        }

        return ParsedHistory(
            messages = messages,
            attachments = attachments,
        )
    }

    private fun parseHistoryAttachments(
        messageId: String,
        attachmentsArray: JSONArray?,
        createdAt: Long,
    ): List<MessageAttachmentEntity> {
        if (attachmentsArray == null || attachmentsArray.length() == 0) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until attachmentsArray.length()) {
                val attachmentObject = attachmentsArray.optJSONObject(index) ?: continue
                val fileManagerData = attachmentObject.optJSONObject("_fileManagerData")
                val name = attachmentObject.optString("name")
                    .ifBlank { fileManagerData?.optString("name").orEmpty() }
                    .ifBlank {
                        attachmentObject.optString("src")
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                    }
                    .ifBlank { "附件_${index + 1}" }
                val hash = fileManagerData?.optString("hash").orEmpty()
                val internalFileName = fileManagerData?.optString("internalFileName")
                    .orEmpty()
                    .ifBlank { inferInternalFileName(hash, name) }
                val runtimeFile = internalFileName.takeIf { it.isNotBlank() }
                    ?.let { File(fileStore.attachmentsDir, it) }
                val runtimePath = runtimeFile?.absolutePath.orEmpty()
                val sourcePath = attachmentObject.optString("src")
                val internalPath = fileManagerData?.optString("internalPath")
                    .orEmpty()
                    .ifBlank { runtimePath.takeIf { it.isNotBlank() }?.let { path -> "file://$path" }.orEmpty() }
                val mimeType = attachmentObject.optString("type")
                    .ifBlank { fileManagerData?.optString("type").orEmpty() }
                    .ifBlank { "application/octet-stream" }
                val size = when {
                    attachmentObject.has("size") -> attachmentObject.optLong("size")
                    fileManagerData?.has("size") == true -> fileManagerData.optLong("size")
                    else -> 0L
                }
                add(
                    MessageAttachmentEntity(
                        id = "${messageId}_att_$index",
                        messageId = messageId,
                        attachmentOrder = index,
                        name = name,
                        mimeType = mimeType,
                        size = size,
                        src = runtimePath.ifBlank {
                            sourcePath.ifBlank {
                                internalPath.removePrefix("file://")
                            }
                        },
                        internalFileName = internalFileName,
                        internalPath = internalPath,
                        hash = hash,
                        createdAt = fileManagerData?.optLong("createdAt")?.takeIf { it > 0L } ?: createdAt,
                        extractedText = fileManagerData?.optString("extractedText")?.takeIf { it.isNotBlank() },
                        imageFramesJson = fileManagerData?.optJSONArray("imageFrames")?.toString(),
                    ),
                )
            }
        }
    }

    private suspend fun writeRuntimeData(
        parsed: ParsedAppData,
        preparedFiles: PreparedImportFiles,
    ) {
        database.withTransaction {
            database.agentDao().deleteAll()
            parsed.agents.forEach { agent ->
                database.agentDao().insert(agent)
            }
            parsed.regexRules.forEach { rule ->
                database.regexRuleDao().insert(rule)
            }
            parsed.topics.forEach { topic ->
                database.topicDao().insert(topic)
            }
            parsed.messages.forEach { message ->
                database.messageDao().insert(message)
            }
            parsed.attachments.forEach { attachment ->
                database.messageAttachmentDao().insert(attachment)
            }
        }

        settingsRepository.saveConnection(
            serverUrl = parsed.settings.serverUrl,
            apiKey = parsed.settings.apiKey,
        )
        settingsRepository.saveCompilerOptions(
            enableVcpToolInjection = parsed.settings.enableVcpToolInjection,
            enableAgentBubbleTheme = parsed.settings.enableAgentBubbleTheme,
            enableContextFolding = parsed.settings.enableContextFolding,
            contextFoldingKeepRecentMessages = parsed.settings.contextFoldingKeepRecentMessages,
            contextFoldingTriggerMessageCount = parsed.settings.contextFoldingTriggerMessageCount,
            contextFoldingTriggerCharCount = parsed.settings.contextFoldingTriggerCharCount,
            contextFoldingExcerptCharLimit = parsed.settings.contextFoldingExcerptCharLimit,
            contextFoldingMaxSummaryEntries = parsed.settings.contextFoldingMaxSummaryEntries,
        )
        settingsRepository.saveLastSession(
            agentId = parsed.settings.lastAgentId,
            topicId = parsed.settings.lastTopicConversationId,
        )
        commitPreparedFiles(preparedFiles)
    }

    private fun prepareImportFiles(
        appDataRoot: File,
        parsed: ParsedAppData,
        sessionId: String,
    ): PreparedImportFiles {
        val stagingRoot = File(fileStore.importsDir, ".staging/runtime_$sessionId").apply {
            deleteRecursively()
            mkdirs()
        }
        val attachmentsDir = File(stagingRoot, "attachments").apply { mkdirs() }
        val avatarsDir = File(stagingRoot, "avatars").apply { mkdirs() }
        File(avatarsDir, "agents").mkdirs()
        File(avatarsDir, "user").mkdirs()
        val compatDir = File(stagingRoot, "compat").apply { mkdirs() }
        val passthroughDir = File(stagingRoot, "passthrough").apply { mkdirs() }

        copyDirectoryContents(
            sourceDir = File(appDataRoot, "UserData/attachments"),
            targetDir = attachmentsDir,
        )

        parsed.fileImports.agentAvatars.forEach { avatarImport ->
            copyEntry(
                source = avatarImport.sourceFile,
                target = File(stagingRoot, avatarImport.targetRelativePath),
            )
        }
        parsed.fileImports.userAvatar?.let { avatarImport ->
            copyEntry(
                source = avatarImport.sourceFile,
                target = File(stagingRoot, avatarImport.targetRelativePath),
            )
        }

        copyEntry(
            source = appDataRoot,
            target = File(compatDir, "AppData"),
        )

        stagePassthroughData(
            appDataRoot = appDataRoot,
            importedAgentIds = parsed.importedAgentIds,
            targetDir = File(passthroughDir, sessionId).apply { mkdirs() },
        )

        return PreparedImportFiles(
            stagingRoot = stagingRoot,
            attachmentsDir = attachmentsDir,
            avatarsDir = avatarsDir,
            compatDir = compatDir,
            passthroughDir = passthroughDir,
        )
    }

    private fun stagePassthroughData(
        appDataRoot: File,
        importedAgentIds: Set<String>,
        targetDir: File,
    ) {
        appDataRoot.listFiles().orEmpty()
            .filterNot { it.name in HANDLED_APP_DATA_ROOT_NAMES }
            .forEach { entry ->
                copyEntry(
                    source = entry,
                    target = File(targetDir, entry.name),
                )
            }

        val userDataDir = File(appDataRoot, "UserData")
        if (!userDataDir.isDirectory) {
            return
        }

        userDataDir.listFiles().orEmpty()
            .filterNot { entry ->
                entry.name == ATTACHMENTS_DIR_NAME ||
                    entry.nameWithoutExtension.equals(USER_AVATAR_BASENAME, ignoreCase = true) ||
                    entry.name in importedAgentIds
            }
            .forEach { entry ->
                copyEntry(
                    source = entry,
                    target = File(targetDir, "UserData/${entry.name}"),
                )
            }
    }

    private fun commitPreparedFiles(preparedFiles: PreparedImportFiles) {
        replaceDirectory(
            stagedDir = preparedFiles.attachmentsDir,
            targetDir = fileStore.attachmentsDir,
        )
        replaceDirectory(
            stagedDir = preparedFiles.avatarsDir,
            targetDir = fileStore.avatarsDir,
        )
        replaceDirectory(
            stagedDir = preparedFiles.compatDir,
            targetDir = fileStore.compatDir,
        )
        replaceDirectory(
            stagedDir = preparedFiles.passthroughDir,
            targetDir = fileStore.passthroughDir,
        )
    }

    private fun replaceDirectory(
        stagedDir: File,
        targetDir: File,
    ) {
        targetDir.deleteRecursively()
        targetDir.parentFile?.mkdirs()
        if (!stagedDir.renameTo(targetDir)) {
            stagedDir.copyRecursively(targetDir, overwrite = true)
        }
    }

    private fun archiveImportSource(
        candidate: ImportCandidate,
        reportDir: File,
    ) {
        when (candidate.type) {
            ImportSourceType.Directory -> {
                val archivedSource = File(reportDir, candidate.source.name)
                if (!candidate.source.renameTo(archivedSource)) {
                    candidate.source.copyRecursively(archivedSource, overwrite = true)
                    candidate.source.deleteRecursively()
                }
            }

            ImportSourceType.Zip -> {
                val archivedZip = File(reportDir, candidate.source.name)
                candidate.source.copyTo(archivedZip, overwrite = true)
                candidate.source.delete()
            }
        }
    }

    private fun writeReport(
        reportDir: File,
        sourceName: String,
        status: String,
        parsed: ParsedAppData?,
        warnings: List<String>,
        failureMessage: String?,
    ): File {
        val reportFile = File(reportDir, "report.json")
        val json = JSONObject()
            .put("sessionId", reportDir.name)
            .put("sourceName", sourceName)
            .put("status", status)
            .put("generatedAt", REPORT_TIME_FORMATTER.format(Instant.now()))
            .put("warnings", JSONArray(warnings))

        parsed?.let {
            json.put("agents", it.agents.size)
            json.put("topics", it.topics.size)
            json.put("messages", it.messages.size)
        }
        failureMessage?.let { json.put("failureMessage", it) }

        reportFile.writeText(json.toString(2))
        return reportFile
    }

    private fun uniqueConversationId(
        agentId: String,
        sourceTopicId: String,
        seenConversationIds: MutableSet<String>,
    ): String {
        var suffix = 0
        while (true) {
            val candidate = buildConversationId(
                agentId = agentId,
                sourceTopicId = sourceTopicId,
                duplicateSuffix = suffix.takeIf { it > 0 },
            )
            if (seenConversationIds.add(candidate)) {
                return candidate
            }
            suffix += 1
        }
    }

    private fun uniqueMessageId(
        rawId: String,
        seenMessageIds: MutableSet<String>,
    ): String {
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) rawId else "${rawId}_$suffix"
            if (seenMessageIds.add(candidate)) {
                return candidate
            }
            suffix += 1
        }
    }

    private fun inferInternalFileName(
        hash: String,
        displayName: String,
    ): String =
        if (hash.isBlank()) {
            ""
        } else {
            val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            if (extension == null) {
                hash
            } else {
                "$hash.$extension"
            }
        }

    private fun buildConversationId(
        agentId: String,
        sourceTopicId: String,
        duplicateSuffix: Int? = null,
    ): String {
        val base = "${agentId}:${sourceTopicId}"
        return duplicateSuffix?.let { "$base#$it" } ?: base
    }

    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "user", "assistant", "system" -> role.lowercase()
        else -> "system"
    }

    private fun determineStatus(messageObject: JSONObject): String {
        if (messageObject.optBoolean("interrupted", false)) {
            return "interrupted"
        }

        val finishReason = messageObject.optString("finishReason")
        if (finishReason.equals("interrupted", ignoreCase = true)) {
            return "interrupted"
        }

        return if (messageObject.optBoolean("isError", false)) {
            "error"
        } else {
            "complete"
        }
    }

    private fun extractContentText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is JSONObject -> {
            value.optString("text").takeIf { it.isNotBlank() }
                ?: extractContentText(value.opt("content"))
                ?: value.toString()
        }

        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                extractContentText(value.opt(index))
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::append)
            }
        }.ifBlank { null }

        else -> value.toString()
    }

    private fun summarizeAttachments(attachments: JSONArray?): String? {
        if (attachments == null || attachments.length() == 0) {
            return null
        }

        val names = buildList {
            for (index in 0 until attachments.length()) {
                val item = attachments.optJSONObject(index) ?: continue
                val name = item.optString("name").ifBlank { item.optString("src") }
                if (name.isNotBlank()) {
                    add(name)
                }
            }
        }
        if (names.isEmpty()) {
            return "[导入附件消息：该消息包含附件，但当前 Android 主链路尚未接入附件历史回放]"
        }
        return "[导入附件消息：${names.joinToString(separator = ", ")}]"
    }

    private fun parseRegexRules(
        agentId: String,
        agentDir: File,
        config: JSONObject,
        warnings: MutableList<String>,
    ): List<RegexRuleEntity> {
        val rulesArray = loadRegexRuleArray(
            agentId = agentId,
            agentDir = agentDir,
            config = config,
            warnings = warnings,
        ) ?: return emptyList()

        return buildList {
            for (index in 0 until rulesArray.length()) {
                val ruleObject = rulesArray.optJSONObject(index) ?: continue
                val normalizedRule = normalizeRegexRule(ruleObject) ?: run {
                    warnings += "Agent $agentId 的 regex_rules.json 第 ${index + 1} 条缺少 findPattern，已跳过"
                    continue
                }
                add(
                    RegexRuleEntity(
                        agentId = agentId,
                        ruleOrder = index,
                        findPattern = normalizedRule.findPattern,
                        replaceWith = normalizedRule.replaceWith,
                        applyToContext = normalizedRule.applyToContext,
                        applyToFrontend = normalizedRule.applyToFrontend,
                        applyToRolesJson = JSONArray(normalizedRule.applyToRoles).toString(),
                        minDepth = normalizedRule.minDepth,
                        maxDepth = normalizedRule.maxDepth,
                        extraJson = ruleObject.toString(),
                    ),
                )
            }
        }
    }

    private fun loadRegexRuleArray(
        agentId: String,
        agentDir: File,
        config: JSONObject,
        warnings: MutableList<String>,
    ): JSONArray? {
        val regexFile = File(agentDir, REGEX_RULES_FILE_NAME)
        if (regexFile.isFile) {
            return try {
                JSONArray(regexFile.readText())
            } catch (error: JSONException) {
                warnings += "Agent $agentId 的 regex_rules.json 无法解析"
                config.optJSONArray("stripRegexes")
            }
        }

        return config.optJSONArray("stripRegexes")
    }

    private fun normalizeRegexRule(ruleObject: JSONObject): NormalizedRegexRule? {
        val findPattern = ruleObject.optString("findPattern")
            .ifBlank { ruleObject.optString("findRegex") }
            .trim()
        if (findPattern.isBlank()) {
            return null
        }

        val scopes = ruleObject.optJSONArray("applyToScopes").toStringList().toSet()
        return NormalizedRegexRule(
            findPattern = findPattern,
            replaceWith = ruleObject.optString("replaceWith")
                .ifBlank { ruleObject.optString("replaceString") },
            applyToContext = when {
                ruleObject.has("applyToContext") -> ruleObject.optFlexibleBoolean("applyToContext", true)
                scopes.isNotEmpty() -> "context" in scopes
                else -> true
            },
            applyToFrontend = when {
                ruleObject.has("applyToFrontend") -> ruleObject.optFlexibleBoolean("applyToFrontend", true)
                scopes.isNotEmpty() -> "frontend" in scopes
                else -> true
            },
            applyToRoles = ruleObject.optJSONArray("applyToRoles").toStringList(),
            minDepth = ruleObject.optNullableInt("minDepth") ?: 0,
            maxDepth = ruleObject.optNullableInt("maxDepth") ?: -1,
        )
    }

    private fun copyDirectoryContents(
        sourceDir: File,
        targetDir: File,
    ) {
        if (!sourceDir.isDirectory) {
            return
        }

        sourceDir.listFiles().orEmpty().forEach { entry ->
            copyEntry(
                source = entry,
                target = File(targetDir, entry.name),
            )
        }
    }

    private fun copyEntry(
        source: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        } else if (source.isFile) {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun findAvatarFile(
        dir: File,
        baseName: String,
    ): File? =
        dir.listFiles().orEmpty().firstOrNull { file ->
            file.isFile &&
                file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                file.extension.lowercase() in SUPPORTED_AVATAR_EXTENSIONS
        }

    private fun relativePathFromRoot(file: File): String =
        file.relativeTo(fileStore.rootDir).path.replace(File.separatorChar, '/')

    private fun JSONArray?.toStringList(): List<String> =
        this?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        }.orEmpty()

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONObject.optFlexibleBoolean(key: String, defaultValue: Boolean): Boolean {
        if (!has(key) || isNull(key)) {
            return defaultValue
        }
        return when (val value = opt(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> defaultValue
        }
    }

    private fun buildSessionId(): String =
        "import_${java.lang.Long.toString(System.currentTimeMillis(), 36)}"

    private data class ImportCandidate(
        val source: File,
        val root: File,
        val type: ImportSourceType,
    )

    private enum class ImportSourceType {
        Directory,
        Zip,
    }

    private data class ParsedAppData(
        val settings: ImportedSettings,
        val agents: List<AgentEntity>,
        val topics: List<TopicEntity>,
        val messages: List<MessageEntity>,
        val attachments: List<MessageAttachmentEntity>,
        val regexRules: List<RegexRuleEntity>,
        val importedAgentIds: Set<String>,
        val fileImports: ParsedFileImports,
    )

    private data class ParsedHistory(
        val messages: List<MessageEntity> = emptyList(),
        val attachments: List<MessageAttachmentEntity> = emptyList(),
    )

    private data class ImportedSettings(
        val serverUrl: String,
        val apiKey: String,
        val enableVcpToolInjection: Boolean,
        val enableAgentBubbleTheme: Boolean,
        val enableContextFolding: Boolean,
        val contextFoldingKeepRecentMessages: Int,
        val contextFoldingTriggerMessageCount: Int,
        val contextFoldingTriggerCharCount: Int,
        val contextFoldingExcerptCharLimit: Int,
        val contextFoldingMaxSummaryEntries: Int,
        val lastAgentId: String?,
        val lastTopicConversationId: String?,
    )

    private data class ParsedFileImports(
        val agentAvatars: List<FileImportSpec>,
        val userAvatar: FileImportSpec?,
    )

    private data class NormalizedRegexRule(
        val findPattern: String,
        val replaceWith: String,
        val applyToContext: Boolean,
        val applyToFrontend: Boolean,
        val applyToRoles: List<String>,
        val minDepth: Int,
        val maxDepth: Int,
    )

    private data class FileImportSpec(
        val sourceFile: File,
        val targetRelativePath: String,
    )

    private data class PreparedImportFiles(
        val stagingRoot: File,
        val attachmentsDir: File,
        val avatarsDir: File,
        val compatDir: File,
        val passthroughDir: File,
    ) {
        fun cleanup() {
            stagingRoot.deleteRecursively()
        }
    }

    private companion object {
        const val ATTACHMENTS_DIR_NAME = "attachments"
        const val AVATAR_BASENAME = "avatar"
        const val REGEX_RULES_FILE_NAME = "regex_rules.json"
        const val USER_AVATAR_BASENAME = "user_avatar"

        val HANDLED_APP_DATA_ROOT_NAMES = setOf(
            "settings.json",
            "Agents",
            "UserData",
        )
        val SUPPORTED_AVATAR_EXTENSIONS = setOf(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
        )
        val REPORT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }
}
