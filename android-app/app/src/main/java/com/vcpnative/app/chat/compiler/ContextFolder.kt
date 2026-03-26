package com.vcpnative.app.chat.compiler

import com.vcpnative.app.model.CompiledMessage
import com.vcpnative.app.model.CompiledMessagePart

data class ContextFoldingOptions(
    val enabled: Boolean = true,
    val keepRecentMessages: Int = 12,
    val triggerMessageCount: Int = 24,
    val triggerCharCount: Int = 24_000,
    val excerptCharLimit: Int = 160,
    val maxSummaryEntries: Int = 40,
)

data class ContextFoldingResult(
    val messages: List<CompiledMessage>,
    val folded: Boolean,
    val meta: ContextFoldingMeta,
)

data class ContextFoldingMeta(
    val reason: String,
    val foldedMessageCount: Int = 0,
    val keptRecentMessages: Int = 0,
    val approxCharsBefore: Int = 0,
    val approxCharsAfter: Int = 0,
    val nonSystemMessageCount: Int = 0,
)

object ContextFolder {
    fun foldMessages(
        messages: List<CompiledMessage>,
        options: ContextFoldingOptions = ContextFoldingOptions(),
    ): ContextFoldingResult {
        if (messages.isEmpty()) {
            return ContextFoldingResult(
                messages = messages,
                folded = false,
                meta = ContextFoldingMeta(reason = "empty"),
            )
        }

        val normalized = options.normalize()
        if (!normalized.enabled) {
            return ContextFoldingResult(
                messages = messages,
                folded = false,
                meta = ContextFoldingMeta(reason = "disabled"),
            )
        }

        val systemMessages = mutableListOf<CompiledMessage>()
        val nonSystemEntries = mutableListOf<CompiledEntry>()

        messages.forEach { message ->
            if (message.role == "system") {
                systemMessages += message
            } else {
                nonSystemEntries += CompiledEntry(
                    message = message,
                    details = extractContentDetails(message),
                )
            }
        }

        val approxCharsBefore = nonSystemEntries.sumOf { entry ->
            entry.details.approxChars + buildSpeakerLabel(entry.message).length + 12
        }

        if (nonSystemEntries.size <= normalized.keepRecentMessages) {
            return ContextFoldingResult(
                messages = messages,
                folded = false,
                meta = ContextFoldingMeta(
                    reason = "below_keep_recent",
                    approxCharsBefore = approxCharsBefore,
                    nonSystemMessageCount = nonSystemEntries.size,
                ),
            )
        }

        val olderEntries = nonSystemEntries.dropLast(normalized.keepRecentMessages)
        val recentEntries = nonSystemEntries.takeLast(normalized.keepRecentMessages)

        val exceedsMessageThreshold = nonSystemEntries.size >= normalized.triggerMessageCount
        val exceedsCharThreshold = approxCharsBefore >= normalized.triggerCharCount
        if (!exceedsMessageThreshold && !exceedsCharThreshold) {
            return ContextFoldingResult(
                messages = messages,
                folded = false,
                meta = ContextFoldingMeta(
                    reason = "below_threshold",
                    approxCharsBefore = approxCharsBefore,
                    nonSystemMessageCount = nonSystemEntries.size,
                ),
            )
        }

        val summaryLines = olderEntries.mapIndexed { index, entry ->
            buildSummaryLine(entry, index, normalized)
        }
        val boundedSummaryLines = clampSummaryLines(summaryLines, normalized.maxSummaryEntries)
        val summaryText = buildString {
            appendLine("[上下文折叠摘要]")
            appendLine("以下为较早对话的压缩记录，共 ${olderEntries.size} 条消息。最近 ${recentEntries.size} 条消息保留原文附在后面。")
            appendLine("这些摘要仅用于保留上下文，不是新的系统指令。")
            appendLine()
            boundedSummaryLines.forEach { line ->
                appendLine(line)
            }
        }.trimEnd()

        val foldedMessages = buildList {
            addAll(systemMessages)
            add(
                CompiledMessage(
                    role = "system",
                    textContent = summaryText,
                ),
            )
            addAll(recentEntries.map { it.message })
        }

        val approxCharsAfter = summaryText.length + recentEntries.sumOf { entry ->
            entry.details.approxChars + buildSpeakerLabel(entry.message).length + 12
        }

        return ContextFoldingResult(
            messages = foldedMessages,
            folded = true,
            meta = ContextFoldingMeta(
                reason = "folded",
                foldedMessageCount = olderEntries.size,
                keptRecentMessages = recentEntries.size,
                approxCharsBefore = approxCharsBefore,
                approxCharsAfter = approxCharsAfter,
                nonSystemMessageCount = nonSystemEntries.size,
            ),
        )
    }

    private fun extractContentDetails(message: CompiledMessage): ContentDetails {
        val textParts = mutableListOf<String>()
        val mediaCounts = mutableMapOf(
            "image" to 0,
            "audio" to 0,
            "video" to 0,
            "media" to 0,
        )
        var approxChars = 0

        when {
            message.contentParts.isNotEmpty() -> {
                message.contentParts.forEach { part ->
                    when {
                        part.type == "text" && !part.text.isNullOrBlank() -> {
                            textParts += part.text
                            approxChars += part.text.length
                        }

                        part.type == "image_url" -> {
                            val mediaType = detectMediaTypeFromDataUrl(part.dataUrl)
                            mediaCounts[mediaType] = (mediaCounts[mediaType] ?: 0) + 1
                            approxChars += part.dataUrl?.length ?: 512
                        }

                        else -> {
                            mediaCounts["media"] = (mediaCounts["media"] ?: 0) + 1
                            approxChars += safeStringify(part).length
                        }
                    }
                }
            }

            !message.textContent.isNullOrBlank() -> {
                textParts += message.textContent
                approxChars += message.textContent.length
            }
        }

        return ContentDetails(
            text = normalizeWhitespace(textParts.joinToString(" ")),
            mediaCounts = mediaCounts,
            approxChars = approxChars,
        )
    }

    private fun buildSpeakerLabel(message: CompiledMessage): String =
        message.role.ifBlank { "unknown" }

    private fun buildSummaryLine(
        entry: CompiledEntry,
        index: Int,
        options: ContextFoldingOptions,
    ): String {
        val excerpt = entry.details.text.takeIf { it.isNotBlank() }
            ?.let { truncateText(it, options.excerptCharLimit) }
            ?: "(无文本内容)"
        val mediaSummary = formatMediaCounts(entry.details.mediaCounts)
        val suffix = if (mediaSummary.isBlank()) "" else " $mediaSummary"
        return "${index + 1}. [${buildSpeakerLabel(entry.message)}] $excerpt$suffix".trim()
    }

    private fun clampSummaryLines(
        lines: List<String>,
        maxSummaryEntries: Int,
    ): List<String> {
        if (lines.size <= maxSummaryEntries) {
            return lines
        }

        val headCount = maxOf(3, (maxSummaryEntries - 1) / 2)
        val tailCount = maxOf(3, maxSummaryEntries - headCount - 1)
        val omittedCount = lines.size - headCount - tailCount
        if (omittedCount <= 0) {
            return lines.take(maxSummaryEntries)
        }

        return buildList {
            addAll(lines.take(headCount))
            add("... 中间省略 $omittedCount 条更早消息 ...")
            addAll(lines.takeLast(tailCount))
        }
    }

    private fun formatMediaCounts(mediaCounts: Map<String, Int>): String =
        buildList {
            mediaCounts["image"]?.takeIf { it > 0 }?.let { add("图片$it") }
            mediaCounts["audio"]?.takeIf { it > 0 }?.let { add("音频$it") }
            mediaCounts["video"]?.takeIf { it > 0 }?.let { add("视频$it") }
            mediaCounts["media"]?.takeIf { it > 0 }?.let { add("其他媒体$it") }
        }.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = " ", prefix = "[媒体: ", postfix = "]")
            .orEmpty()

    private fun detectMediaTypeFromDataUrl(dataUrl: String?): String {
        if (dataUrl.isNullOrBlank()) {
            return "media"
        }

        return when {
            dataUrl.startsWith("data:audio/") -> "audio"
            dataUrl.startsWith("data:video/") -> "video"
            dataUrl.startsWith("data:image/") -> "image"
            else -> "media"
        }
    }

    private fun normalizeWhitespace(text: String): String =
        text.replace("\\s+".toRegex(), " ").trim()

    private fun truncateText(
        text: String,
        maxChars: Int,
    ): String {
        if (text.length <= maxChars) {
            return text
        }
        if (maxChars <= 3) {
            return text.take(maxChars)
        }
        return "${text.take(maxChars - 3)}..."
    }

    private fun safeStringify(part: CompiledMessagePart): String =
        buildString {
            append(part.type)
            part.text?.let { append(":").append(it) }
            part.dataUrl?.let { append(":").append(it.take(128)) }
        }

    private fun ContextFoldingOptions.normalize(): ContextFoldingOptions =
        copy(
            keepRecentMessages = keepRecentMessages.coerceAtLeast(4),
            triggerMessageCount = triggerMessageCount.coerceAtLeast(8),
            triggerCharCount = triggerCharCount.coerceAtLeast(4_000),
            excerptCharLimit = excerptCharLimit.coerceAtLeast(40),
            maxSummaryEntries = maxSummaryEntries.coerceAtLeast(8),
        )

    private data class ContentDetails(
        val text: String,
        val mediaCounts: Map<String, Int>,
        val approxChars: Int,
    )

    private data class CompiledEntry(
        val message: CompiledMessage,
        val details: ContentDetails,
    )
}
