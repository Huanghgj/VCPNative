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
import com.vcpnative.app.chat.skill.SkillRegistry
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
    private val skillRegistry: SkillRegistry? = null,
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
        val stripThoughtChains = !settings.enableThoughtChainInjection
        val history = historyMessages.map { message ->
            // Step 1: Strip thought chains (if disabled in settings)
            val textAfterThoughtStrip = if (stripThoughtChains) {
                ThoughtChainStripper.strip(message.content)
            } else {
                message.content
            }
            // Step 2: Apply regex rules BEFORE sanitization
            // (Rules expect original HTML/Markdown structure to be intact)
            // Fixed: was previously sanitize→regex which broke pattern matching
            val textAfterRegex = applyContextRegexRules(
                text = textAfterThoughtStrip,
                role = message.role,
                depth = depthMap[message.id],
                regexRules = regexRules,
            )
            // Step 3: Sanitize HTML→Markdown AFTER regex rules
            // (Now preserves structure via tree-walk instead of Html.fromHtml)
            val finalText = if (settings.enableContextSanitizer && message.role == "assistant") {
                val depth = depthMap[message.id]
                if (depth != null && depth >= settings.contextSanitizerDepth) {
                    ContextSanitizer.sanitize(textAfterRegex)
                } else {
                    textAfterRegex
                }
            } else {
                textAfterRegex
            }
            compileMessage(
                message = message,
                normalizedText = finalText,
                compatHistoryEntry = compatHistoryByMessageId[message.id],
            )
        }
        val foldedHistory = ContextFolder
            .foldMessages(
                messages = history,
                options = settings.toContextFoldingOptions(
                    agentContextTokenLimit = agent?.contextTokenLimit ?: 0,
                ),
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

        val trimmedMessages = trimExcessMedia(compiledMessages)

        val enableThinking = agent?.extraJson?.let { json ->
            runCatching {
                val extra = JSONObject(json)
                extra.optJSONObject("thinking")?.optString("type") == "enabled"
            }.getOrNull()
        } ?: false

        return CompiledChatRequest(
            agentId = agentId,
            topicId = topicId,
            requestId = "msg_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}",
            endpoint = serviceConfig.chatUrl(settings.enableVcpToolInjection),
            apiBaseUrl = serviceConfig.apiRootUrl,
            apiKey = serviceConfig.apiKey,
            model = agent?.model?.takeIf { it.isNotBlank() } ?: "gemini-2.5-flash",
            temperature = agent?.temperature ?: 0.7,
            maxTokens = agent?.maxOutputTokens,
            contextTokenLimit = agent?.contextTokenLimit,
            topP = agent?.topP,
            topK = agent?.topK,
            stream = agent?.streamOutput ?: true,
            thinking = if (enableThinking) true else null,
            messages = trimmedMessages,
        )
    }

    /**
     * 从后往前扫描编译后的消息列表，只保留最近 [MAX_MEDIA_IN_REQUEST] 个
     * image_url 类型的 content part。更早的图片/媒体替换为文本占位符，
     * 防止请求体过大导致 413。
     */
    private fun trimExcessMedia(messages: List<CompiledMessage>): List<CompiledMessage> {
        // 先统计总 media 数量，不超限则直接返回原列表
        val totalMedia = messages.sumOf { msg ->
            msg.contentParts.count { it.type == "image_url" }
        }
        if (totalMedia <= MAX_MEDIA_IN_REQUEST) return messages

        // 从后往前计数，标记允许保留的消息索引+part索引
        var remaining = MAX_MEDIA_IN_REQUEST
        // 用 (messageIndex, partIndex) 集合记录允许保留的 media
        val allowed = mutableSetOf<Pair<Int, Int>>()
        for (msgIdx in messages.indices.reversed()) {
            val parts = messages[msgIdx].contentParts
            for (partIdx in parts.indices.reversed()) {
                if (parts[partIdx].type == "image_url") {
                    if (remaining > 0) {
                        allowed += msgIdx to partIdx
                        remaining--
                    }
                }
            }
        }

        return messages.mapIndexed { msgIdx, msg ->
            if (msg.contentParts.none { it.type == "image_url" }) return@mapIndexed msg
            val newParts = msg.contentParts.mapIndexed { partIdx, part ->
                if (part.type == "image_url" && (msgIdx to partIdx) !in allowed) {
                    CompiledMessagePart(type = "text", text = "[历史图片已省略]")
                } else {
                    part
                }
            }
            msg.copy(contentParts = newParts)
        }
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

        skillRegistry?.buildManifestPrompt()?.takeIf { it.isNotBlank() }?.let { manifest ->
            finalParts += manifest
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
            output = regex.replace(output, Regex.escapeReplacement(rule.replaceWith))
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

            // PDF without inline imageFrames: render from disk on demand
            val pdfFrameCount = fileManagerData?.optInt("pdfFrameCount", 0) ?: 0
            if (pdfFrameCount > 0 || mimeType == "application/pdf") {
                val sourceFile = resolveAttachmentFile(attachment, fileManagerData)
                if (sourceFile != null) {
                    val renderedFrames = renderPdfFrames(sourceFile)
                    renderedFrames.forEach { frameData ->
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
        if (sourceFile.length() > MAX_ATTACHMENT_BYTES) return null
        // 大图片压缩：先检查是否存在预压缩的 .compressed.jpg 缓存文件，
        // 如果没有才现场压缩（并缓存结果供下次复用）。
        // 这样第二次发送同一张图就不需要再解码+压缩了。
        if (mimeType.startsWith("image/") && sourceFile.length() > IMAGE_COMPRESS_THRESHOLD) {
            val compressed = loadOrCompressImage(sourceFile) ?: return null
            return "data:image/jpeg;base64,$compressed"
        }
        val encoded = runCatching {
            Base64.encodeToString(sourceFile.readBytes(), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return "data:$mimeType;base64,$encoded"
    }

    private fun resolveAttachmentDataUrl(
        attachment: ChatAttachment,
    ): String? {
        val sourceFile = resolveAttachmentFile(attachment) ?: return null
        if (sourceFile.length() > MAX_ATTACHMENT_BYTES) return null
        if (attachment.mimeType.startsWith("image/") && sourceFile.length() > IMAGE_COMPRESS_THRESHOLD) {
            val compressed = loadOrCompressImage(sourceFile) ?: return null
            return "data:image/jpeg;base64,$compressed"
        }
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

    /**
     * 渲染 PDF 每页为 JPEG base64 帧，逻辑与 ChatAttachmentManager 一致。
     * 用于历史 PDF 附件的 compat JSON 中不再内联 imageFrames 的情况。
     */
    private fun renderPdfFrames(file: File): List<String> {
        if (!file.isFile || file.length() > MAX_ATTACHMENT_BYTES) return emptyList()
        return runCatching {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    val pageCount = minOf(renderer.pageCount, 12)
                    (0 until pageCount).map { pageIndex ->
                        renderer.openPage(pageIndex).use { page ->
                            val baseEdge = maxOf(page.width, page.height).coerceAtLeast(1)
                            val scale = minOf(2f, 1600f / baseEdge.toFloat())
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val output = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
                            bitmap.recycle()
                            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
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

    /**
     * 先找缓存的压缩文件（避免重复解码），没有才现场压缩并写入缓存。
     * 缓存文件名 = 原文件名 + ".compressed.jpg"，和原始文件在同一目录。
     */
    private fun loadOrCompressImage(file: File): String? {
        val cached = File(file.parentFile, "${file.name}.compressed.jpg")
        if (cached.isFile && cached.lastModified() >= file.lastModified()) {
            return runCatching {
                Base64.encodeToString(cached.readBytes(), Base64.NO_WRAP)
            }.getOrNull()
        }
        val result = compressImage(file) ?: return null
        // 异步写缓存，不阻塞当前发送路径
        runCatching {
            cached.writeBytes(android.util.Base64.decode(result, android.util.Base64.NO_WRAP))
        }
        return result
    }

    private fun compressImage(file: File): String? = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        // 仅对超大分辨率图片做采样缩放（最长边 > 4096px）
        val maxDim = maxOf(options.outWidth, options.outHeight)
        val sampleSize = if (maxDim > MAX_IMAGE_DIMENSION) {
            var s = 1
            while (maxDim / s > MAX_IMAGE_DIMENSION) s *= 2
            s
        } else 1
        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: return@runCatching null
        val outputStream = java.io.ByteArrayOutputStream()
        // 高质量 JPEG 92%，保留画质
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, outputStream)
        bitmap.recycle()
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private companion object {
        const val PENDING_USER_MESSAGE_ID = "__pending_user__"
        const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024 // 20 MB
        const val IMAGE_COMPRESS_THRESHOLD = 4L * 1024 * 1024 // 4 MB 以上的图片才压缩
        const val MAX_IMAGE_DIMENSION = 4096 // 仅缩放超大分辨率（>4K）
        /** 请求中最多保留的图片/媒体附件数量，防止 413 Payload Too Large */
        const val MAX_MEDIA_IN_REQUEST = 5
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

private fun AppSettings.toContextFoldingOptions(
    agentContextTokenLimit: Int = 0,
): ContextFoldingOptions =
    ContextFoldingOptions(
        enabled = enableContextFolding,
        keepRecentMessages = contextFoldingKeepRecentMessages,
        triggerMessageCount = contextFoldingTriggerMessageCount,
        triggerCharCount = contextFoldingTriggerCharCount,
        excerptCharLimit = contextFoldingExcerptCharLimit,
        maxSummaryEntries = contextFoldingMaxSummaryEntries,
        contextTokenLimit = agentContextTokenLimit,
    )

private val compiledRegexCache: MutableMap<String, Regex?> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Regex?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>?): Boolean =
                size > 128
        },
    )
private val REGEX_LITERAL_PATTERN = Regex("^/(.+)/([a-zA-Z]*)$")

private fun String.toRegexOrNull(): Regex? =
    compiledRegexCache.getOrPut(this) {
        runCatching {
            val regexMatch = REGEX_LITERAL_PATTERN.matchEntire(this)
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
    }
