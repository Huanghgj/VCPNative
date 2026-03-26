package com.vcpnative.app.chat.compiler

import android.util.Base64
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.data.room.RegexRuleEntity
import com.vcpnative.app.model.AppSettings
import com.vcpnative.app.model.ChatAttachment
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.CompiledMessage
import com.vcpnative.app.model.CompiledMessagePart
import com.vcpnative.app.network.vcp.toServiceConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

interface ChatRequestCompiler {
    suspend fun compile(
        agentId: String,
        topicId: String,
        userDraft: String,
        attachments: List<ChatAttachment> = emptyList(),
    ): CompiledChatRequest

    suspend fun compileFromHistory(
        agentId: String,
        topicId: String,
    ): CompiledChatRequest
}

class VcpCompatChatRequestCompiler(
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val fileStore: AppFileStore,
) : ChatRequestCompiler {
    override suspend fun compile(
        agentId: String,
        topicId: String,
        userDraft: String,
        attachments: List<ChatAttachment>,
    ): CompiledChatRequest = compileInternal(
        agentId = agentId,
        topicId = topicId,
        pendingUserDraft = userDraft,
        attachments = attachments,
    )

    override suspend fun compileFromHistory(
        agentId: String,
        topicId: String,
    ): CompiledChatRequest = compileInternal(
        agentId = agentId,
        topicId = topicId,
        pendingUserDraft = null,
        attachments = emptyList(),
    )

    private suspend fun compileInternal(
        agentId: String,
        topicId: String,
        pendingUserDraft: String?,
        attachments: List<ChatAttachment>,
    ): CompiledChatRequest {
        val settings = settingsRepository.currentSettings()
        val serviceConfig = settings.toServiceConfig()
        val agent = workspaceRepository.findAgent(agentId)
        val topic = workspaceRepository.findTopic(topicId)
        val historyMessages = workspaceRepository.loadMessages(topicId)
            .filter { message ->
                message.status !in setOf("draft", "streaming") &&
                    (message.role == "user" || message.role == "assistant" || (message.role == "system" && message.status != "error"))
                }
        val regexRules = workspaceRepository.loadRegexRules(agentId)
        val depthMap = buildMessageDepthMap(historyMessages)
        val compatHistoryByMessageId = topic?.let { loadCompatHistoryEntries(agentId, it.sourceTopicId) }.orEmpty()
        val history = historyMessages.map { message ->
            compileMessage(
                message = message,
                normalizedText = applyContextRegexRules(
                    text = message.content,
                    role = message.role,
                    depth = depthMap[message.id],
                    regexRules = regexRules,
                ),
                compatHistoryEntry = compatHistoryByMessageId[message.id],
            )
        }
        val foldedHistory = ContextFolder
            .foldMessages(
                messages = history,
                options = settings.toContextFoldingOptions(),
            )
            .messages
        val normalizedUserDraft = pendingUserDraft?.let { draft ->
            applyContextRegexRules(
                text = draft.trim(),
                role = "user",
                depth = depthMap[PENDING_USER_MESSAGE_ID],
                regexRules = regexRules,
            )
        }

        val compiledMessages = buildList {
            buildSystemPrompt(
                agentName = agent?.name ?: agentId,
                systemPrompt = resolveActiveSystemPrompt(agent),
                topicId = topic?.sourceTopicId ?: topicId,
                topicCreatedAt = topic?.createdAt,
                enableAgentBubbleTheme = settings.enableAgentBubbleTheme,
                agentId = agentId,
            )?.let { systemContent ->
                add(CompiledMessage(role = "system", textContent = systemContent))
            }

            addAll(foldedHistory)
            if (normalizedUserDraft != null || attachments.isNotEmpty()) {
                add(
                    compilePendingUserMessage(
                        normalizedText = normalizedUserDraft.orEmpty(),
                        attachments = attachments,
                    ),
                )
            }
        }

        return CompiledChatRequest(
            agentId = agentId,
            topicId = topicId,
            requestId = "msg_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}",
            endpoint = serviceConfig.chatUrl(settings.enableVcpToolInjection),
            apiKey = serviceConfig.apiKey,
            model = agent?.model?.takeIf { it.isNotBlank() } ?: "gemini-pro",
            temperature = agent?.temperature ?: 0.7,
            maxTokens = agent?.maxOutputTokens,
            contextTokenLimit = agent?.contextTokenLimit,
            topP = agent?.topP,
            topK = agent?.topK,
            stream = agent?.streamOutput ?: true,
            messages = compiledMessages,
        )
    }

    private fun resolveActiveSystemPrompt(agent: AgentEntity?): String =
        AgentPromptResolver.resolveActiveSystemPrompt(agent)

    private fun buildSystemPrompt(
        agentName: String,
        systemPrompt: String,
        topicId: String,
        topicCreatedAt: Long?,
        enableAgentBubbleTheme: Boolean,
        agentId: String,
    ): String? {
        val normalizedPrompt = systemPrompt
            .replace("{{AgentName}}", agentName)
            .trim()

        if (normalizedPrompt.isBlank() && !enableAgentBubbleTheme) {
            return null
        }

        val prependedLines = buildList {
            val historyPath = fileStore.agentHistoryFile(agentId, topicId).absolutePath
            add("当前聊天记录文件路径: $historyPath")

            topicCreatedAt?.let { createdAt ->
                add("当前话题创建于: ${TOPIC_TIME_FORMATTER.format(Instant.ofEpochMilli(createdAt))}")
            }
        }

        val finalParts = mutableListOf<String>()
        if (prependedLines.isNotEmpty()) {
            finalParts += prependedLines.joinToString(separator = "\n")
        }
        if (normalizedPrompt.isNotBlank()) {
            finalParts += normalizedPrompt
        }
        if (enableAgentBubbleTheme) {
            val injection = "输出规范要求：{{VarDivRender}}"
            if (finalParts.none { injection in it }) {
                finalParts += injection
            }
        }

        return finalParts
            .joinToString(separator = "\n\n")
            .trim()
            .ifBlank { null }
    }

    private fun compileMessage(
        message: MessageEntity,
        normalizedText: String,
        compatHistoryEntry: JSONObject?,
    ): CompiledMessage {
        val attachments = compatHistoryEntry?.optJSONArray("attachments")
        if (attachments == null || attachments.length() == 0) {
            return CompiledMessage(
                role = message.role,
                textContent = normalizedText,
            )
        }

        val enrichedText = buildAttachmentContextText(
            baseText = normalizedText,
            attachments = attachments,
        )
        val contentParts = buildAttachmentContentParts(
            text = enrichedText,
            attachments = attachments,
        )
        return if (contentParts.isEmpty()) {
            CompiledMessage(
                role = message.role,
                textContent = enrichedText,
            )
        } else {
            CompiledMessage(
                role = message.role,
                contentParts = contentParts,
            )
        }
    }

    private fun compilePendingUserMessage(
        normalizedText: String,
        attachments: List<ChatAttachment>,
    ): CompiledMessage {
        if (attachments.isEmpty()) {
            return CompiledMessage(
                role = "user",
                textContent = normalizedText,
            )
        }

        val enrichedText = buildPendingAttachmentContextText(
            baseText = normalizedText,
            attachments = attachments,
        )
        val contentParts = buildAttachmentContentParts(
            text = enrichedText,
            attachments = attachments,
        )
        return if (contentParts.isEmpty()) {
            CompiledMessage(
                role = "user",
                textContent = enrichedText,
            )
        } else {
            CompiledMessage(
                role = "user",
                contentParts = contentParts,
            )
        }
    }

    private fun applyContextRegexRules(
        text: String,
        role: String,
        depth: Int?,
        regexRules: List<RegexRuleEntity>,
    ): String {
        if (text.isBlank() || regexRules.isEmpty() || depth == null) {
            return text
        }

        var output = text
        regexRules.forEach { rule ->
            if (!rule.applyToContext) {
                return@forEach
            }

            val applyToRoles = parseRoles(rule.applyToRolesJson)
            if (role !in applyToRoles) {
                return@forEach
            }

            val minDepthOk = rule.minDepth == -1 || depth >= rule.minDepth
            val maxDepthOk = rule.maxDepth == -1 || depth <= rule.maxDepth
            if (!minDepthOk || !maxDepthOk) {
                return@forEach
            }

            val regex = rule.findPattern.toRegexOrNull() ?: return@forEach
            output = regex.replace(output, rule.replaceWith)
        }
        return output
    }

    private fun buildMessageDepthMap(historyMessages: List<MessageEntity>): Map<String, Int> {
        val turnRefs = mutableListOf<TurnRef>()
        var index = historyMessages.lastIndex
        while (index >= 0) {
            val message = historyMessages[index]
            when (message.role) {
                "assistant" -> {
                    val userMessage = historyMessages.getOrNull(index - 1)
                        ?.takeIf { it.role == "user" }
                    turnRefs += TurnRef(
                        assistantMessageId = message.id,
                        userMessageId = userMessage?.id,
                    )
                    index -= if (userMessage != null) 2 else 1
                }

                "user" -> {
                    turnRefs += TurnRef(
                        assistantMessageId = null,
                        userMessageId = message.id,
                    )
                    index -= 1
                }

                else -> index -= 1
            }
        }
        turnRefs += TurnRef(
            assistantMessageId = null,
            userMessageId = PENDING_USER_MESSAGE_ID,
        )

        return buildMap {
            turnRefs.asReversed().forEachIndexed { turnIndex, turn ->
                val depth = turnRefs.size - 1 - turnIndex
                turn.assistantMessageId?.let { put(it, depth) }
                turn.userMessageId?.let { put(it, depth) }
            }
        }
    }

    private fun parseRoles(applyToRolesJson: String): Set<String> =
        runCatching {
            JSONArray(applyToRolesJson).let { array ->
                buildSet {
                    for (index in 0 until array.length()) {
                        val role = array.optString(index)
                        if (role.isNotBlank()) {
                            add(role)
                        }
                    }
                }
            }
        }.getOrDefault(emptySet())

    private fun buildPendingAttachmentContextText(
        baseText: String,
        attachments: List<ChatAttachment>,
    ): String {
        val attachmentSummary = buildString {
            attachments.forEach { attachment ->
                val displayPath = attachment.src.ifBlank {
                    attachment.internalPath.removePrefix("file://")
                }.ifBlank {
                    attachment.name
                }.ifBlank {
                    "未知文件"
                }

                when {
                    attachment.imageFrames.isNotEmpty() ->
                        append("\n\n[附加文件: $displayPath (PDF，已转换为图片)]")

                    attachment.mimeType.startsWith("image/") ->
                        append("\n\n[附加图片: $displayPath]")

                    attachment.extractedText?.isNotBlank() == true ->
                        append(
                            "\n\n[附加文件: $displayPath]\n${attachment.extractedText}\n[/附加文件结束: ${attachment.name}]",
                        )

                    else ->
                        append("\n\n[附加文件: $displayPath]")
                }
            }
        }

        return buildString {
            append(baseText)
            append(attachmentSummary)
        }.trim()
    }

    private fun buildAttachmentContextText(
        baseText: String,
        attachments: JSONArray,
    ): String {
        val attachmentSummary = buildString {
            for (index in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(index) ?: continue
                val fileManagerData = attachment.optJSONObject("_fileManagerData")
                val displayPath = attachment.optString("src").ifBlank {
                    fileManagerData?.optString("internalPath")
                        ?.removePrefix("file://")
                        .orEmpty()
                        .ifBlank { attachment.optString("name") }
                }.ifBlank { "未知文件" }
                val displayName = attachment.optString("name").ifBlank { "未知文件" }
                val imageFrames = fileManagerData?.optJSONArray("imageFrames")
                val extractedText = fileManagerData?.optString("extractedText").orEmpty()

                when {
                    imageFrames != null && imageFrames.length() > 0 ->
                        append("\n\n[附加文件: $displayPath (扫描版PDF，已转换为图片)]")

                    extractedText.isNotBlank() ->
                        append("\n\n[附加文件: $displayPath]\n$extractedText\n[/附加文件结束: $displayName]")

                    else ->
                        append("\n\n[附加文件: $displayPath]")
                }
            }
        }

        return buildString {
            append(baseText)
            append(attachmentSummary)
        }.trim()
    }

    private fun buildAttachmentContentParts(
        text: String,
        attachments: List<ChatAttachment>,
    ): List<CompiledMessagePart> {
        val parts = mutableListOf<CompiledMessagePart>()
        if (text.isNotBlank()) {
            parts += CompiledMessagePart(
                type = "text",
                text = text,
            )
        }

        attachments.forEach { attachment ->
            if (attachment.imageFrames.isNotEmpty()) {
                attachment.imageFrames.forEach { frameData ->
                    if (frameData.isNotBlank()) {
                        parts += CompiledMessagePart(
                            type = "image_url",
                            dataUrl = "data:image/jpeg;base64,$frameData",
                        )
                    }
                }
                return@forEach
            }

            if (
                !attachment.mimeType.startsWith("image/") &&
                attachment.mimeType !in SUPPORTED_AUDIO_TYPES &&
                !attachment.mimeType.startsWith("video/")
            ) {
                return@forEach
            }

            val dataUrl = resolveAttachmentDataUrl(attachment) ?: return@forEach
            parts += CompiledMessagePart(
                type = "image_url",
                dataUrl = dataUrl,
            )
        }

        return if (parts.size == 1 && parts.first().type == "text") {
            emptyList()
        } else {
            parts
        }
    }

    private fun buildAttachmentContentParts(
        text: String,
        attachments: JSONArray,
    ): List<CompiledMessagePart> {
        val parts = mutableListOf<CompiledMessagePart>()
        if (text.isNotBlank()) {
            parts += CompiledMessagePart(
                type = "text",
                text = text,
            )
        }

        for (index in 0 until attachments.length()) {
            val attachment = attachments.optJSONObject(index) ?: continue
            val mimeType = attachment.optString("type")
                .ifBlank { attachment.optJSONObject("_fileManagerData")?.optString("type").orEmpty() }
            val fileManagerData = attachment.optJSONObject("_fileManagerData")
            val imageFrames = fileManagerData?.optJSONArray("imageFrames")
            if (imageFrames != null && imageFrames.length() > 0) {
                for (frameIndex in 0 until imageFrames.length()) {
                    val frameData = imageFrames.optString(frameIndex)
                    if (frameData.isNotBlank()) {
                        parts += CompiledMessagePart(
                            type = "image_url",
                            dataUrl = "data:image/jpeg;base64,$frameData",
                        )
                    }
                }
                continue
            }

            if (!mimeType.startsWith("image/") && mimeType !in SUPPORTED_AUDIO_TYPES && !mimeType.startsWith("video/")) {
                continue
            }

            val dataUrl = resolveAttachmentDataUrl(
                attachment = attachment,
                fileManagerData = fileManagerData,
                mimeType = mimeType,
            ) ?: continue
            parts += CompiledMessagePart(
                type = "image_url",
                dataUrl = dataUrl,
            )
        }

        return if (parts.size == 1 && parts.first().type == "text") {
            emptyList()
        } else {
            parts
        }
    }

    private fun resolveAttachmentDataUrl(
        attachment: JSONObject,
        fileManagerData: JSONObject?,
        mimeType: String,
    ): String? {
        val sourceFile = resolveAttachmentFile(attachment, fileManagerData) ?: return null
        val encoded = runCatching {
            Base64.encodeToString(sourceFile.readBytes(), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return "data:$mimeType;base64,$encoded"
    }

    private fun resolveAttachmentDataUrl(
        attachment: ChatAttachment,
    ): String? {
        val sourceFile = resolveAttachmentFile(attachment) ?: return null
        val encoded = runCatching {
            Base64.encodeToString(sourceFile.readBytes(), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return "data:${attachment.mimeType};base64,$encoded"
    }

    private fun resolveAttachmentFile(
        attachment: JSONObject,
        fileManagerData: JSONObject?,
    ): File? {
        val internalFileName = fileManagerData?.optString("internalFileName").orEmpty()
        if (internalFileName.isNotBlank()) {
            File(fileStore.attachmentsDir, internalFileName).takeIf(File::isFile)?.let { return it }
        }

        val hash = fileManagerData?.optString("hash").orEmpty()
        val displayName = attachment.optString("name")
        val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        if (hash.isNotBlank() && extension != null) {
            File(fileStore.attachmentsDir, "$hash.$extension").takeIf(File::isFile)?.let { return it }
        }

        val srcName = attachment.optString("src")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBefore('?')
        if (srcName.isNotBlank()) {
            File(fileStore.attachmentsDir, srcName).takeIf(File::isFile)?.let { return it }
        }

        return null
    }

    private fun resolveAttachmentFile(
        attachment: ChatAttachment,
    ): File? {
        if (attachment.internalFileName.isNotBlank()) {
            File(fileStore.attachmentsDir, attachment.internalFileName).takeIf(File::isFile)?.let { return it }
        }
        if (attachment.src.isNotBlank()) {
            File(attachment.src).takeIf(File::isFile)?.let { return it }
        }
        if (attachment.hash.isNotBlank()) {
            val extension = attachment.name.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            if (extension != null) {
                File(fileStore.attachmentsDir, "${attachment.hash}.$extension").takeIf(File::isFile)?.let { return it }
            }
        }
        return null
    }

    private fun loadCompatHistoryEntries(
        agentId: String,
        sourceTopicId: String,
    ): Map<String, JSONObject> {
        val historyFile = fileStore.agentHistoryFile(agentId, sourceTopicId)
        if (!historyFile.isFile) {
            return emptyMap()
        }

        return try {
            val historyArray = JSONArray(historyFile.readText())
            buildMap {
                for (index in 0 until historyArray.length()) {
                    val messageObject = historyArray.optJSONObject(index) ?: continue
                    val messageId = messageObject.optString("id")
                    if (messageId.isNotBlank()) {
                        put(messageId, messageObject)
                    }
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }
    }

    private companion object {
        const val PENDING_USER_MESSAGE_ID = "__pending_user__"
        val SUPPORTED_AUDIO_TYPES = setOf(
            "audio/wav",
            "audio/mpeg",
            "audio/mp3",
            "audio/aiff",
            "audio/aac",
            "audio/ogg",
            "audio/flac",
        )
        val TOPIC_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    private data class TurnRef(
        val assistantMessageId: String?,
        val userMessageId: String?,
    )
}

private fun AppSettings.toContextFoldingOptions(): ContextFoldingOptions =
    ContextFoldingOptions(
        enabled = enableContextFolding,
        keepRecentMessages = contextFoldingKeepRecentMessages,
        triggerMessageCount = contextFoldingTriggerMessageCount,
        triggerCharCount = contextFoldingTriggerCharCount,
        excerptCharLimit = contextFoldingExcerptCharLimit,
        maxSummaryEntries = contextFoldingMaxSummaryEntries,
    )

private fun String.toRegexOrNull(): Regex? =
    runCatching {
        val regexMatch = Regex("^/(.+)/([a-zA-Z]*)$").matchEntire(this)
        if (regexMatch != null) {
            val pattern = regexMatch.groupValues[1]
            val options = regexMatch.groupValues[2]
                .mapNotNull { flag ->
                    when (flag.lowercaseChar()) {
                        'i' -> RegexOption.IGNORE_CASE
                        'm' -> RegexOption.MULTILINE
                        's' -> RegexOption.DOT_MATCHES_ALL
                        else -> null
                    }
                }
                .toSet()
            Regex(pattern, options)
        } else {
            Regex(this)
        }
    }.getOrNull()
