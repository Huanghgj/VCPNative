package com.vcpnative.app.chat.render

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

enum class ChatRenderMode {
    Streaming,
    Final,
}

@Composable
fun ChatMessageContent(
    content: String,
    mode: ChatRenderMode,
    role: String? = null,
    onActionMessage: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    pauseDynamicContent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (content.isBlank()) {
        return
    }

    if (shouldRenderMessageInSafeMode(content)) {
        SafePlainTextBlockView(
            text = buildSafeRenderPreview(content),
        )
        return
    }

    val documentResult = remember(content, mode, role) {
        runCatching {
            parseChatRenderDocumentCached(
                content = content,
                mode = mode,
                role = role,
            )
        }
    }

    val document = documentResult.getOrNull()
    if (document == null) {
        NativeMarkdownBlockView(
            text = content,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )
        return
    }

    key(mode, content.hashCode()) {
        ChatRenderBlocksView(
            blocks = document.blocks,
            mode = mode,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            pauseDynamicContent = pauseDynamicContent,
            modifier = modifier,
            nested = false,
        )
    }
}

@Composable
fun ChatMessageReaderContent(
    content: String,
    onActionMessage: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (content.isBlank()) {
        return
    }

    if (shouldUseBrowserHtmlRenderer(content)) {
        BrowserHtmlBlockView(
            html = content,
            onActionMessage = onActionMessage,
            onLongPress = null,
        )
        return
    }

    if (shouldRenderMessageInSafeMode(content)) {
        SafePlainTextBlockView(
            text = buildSafeRenderPreview(content),
        )
        return
    }

    NativeMarkdownBlockView(
        text = content,
        onActionMessage = onActionMessage,
        onLongPress = null,
        modifier = modifier,
    )
}

private data class ChatRenderDocument(
    val blocks: List<ChatRenderBlock>,
)

private data class ChatRenderDocumentCacheKey(
    val content: String,
    val mode: ChatRenderMode,
    val role: String?,
)

private sealed interface ChatRenderBlock {
    data class MarkdownDocument(
        val text: String,
    ) : ChatRenderBlock

    data class Paragraph(
        val text: String,
    ) : ChatRenderBlock

    data class Heading(
        val level: Int,
        val text: String,
    ) : ChatRenderBlock

    data class UserButtonClick(
        val label: String,
    ) : ChatRenderBlock

    data class InteractiveButtons(
        val buttons: List<InteractiveButtonSpec>,
    ) : ChatRenderBlock

    data class RemoteImage(
        val url: String,
        val alt: String?,
    ) : ChatRenderBlock

    data class CodeFence(
        val language: String?,
        val code: String,
    ) : ChatRenderBlock

    data class HtmlDocument(
        val code: String,
    ) : ChatRenderBlock

    data class MermaidDiagram(
        val diagramType: String,
        val code: String,
    ) : ChatRenderBlock

    data class Quote(
        val blocks: List<ChatRenderBlock>,
    ) : ChatRenderBlock

    data class MarkdownList(
        val ordered: Boolean,
        val startNumber: Int?,
        val items: List<List<ChatRenderBlock>>,
    ) : ChatRenderBlock

    data class ToolUse(
        val toolName: String,
        val rawContent: String,
    ) : ChatRenderBlock

    data class ToolResult(
        val toolName: String,
        val status: String,
        val details: List<ToolResultDetail>,
        val footerBlocks: List<ChatRenderBlock>,
    ) : ChatRenderBlock

    data class Thought(
        val title: String,
        val blocks: List<ChatRenderBlock>,
    ) : ChatRenderBlock

    data class DesktopPush(
        val preview: String,
        val streaming: Boolean,
    ) : ChatRenderBlock

    data class DailyNote(
        val maid: String?,
        val date: String?,
        val blocks: List<ChatRenderBlock>,
    ) : ChatRenderBlock

    data class RoleDivider(
        val role: String,
        val isEnd: Boolean,
    ) : ChatRenderBlock

    data object CanvasPlaceholder : ChatRenderBlock
}

private data class ToolResultDetail(
    val key: String,
    val value: String,
    val richBlocks: List<ChatRenderBlock>,
    val isRichText: Boolean,
    val imageUrl: String?,
)

private data class InteractiveButtonSpec(
    val label: String,
    val sendText: String,
)

private object VcpChatMessageParser {
    private val headingRegex = Regex("""^(#{1,6})\s+(.*)$""")
    private val unorderedListRegex = Regex("""^[-*]\s+(.*)$""")
    private val orderedListRegex = Regex("""^(\d+)\.\s+(.*)$""")
    private val roleDividerRegex = Regex("""^<<<\[(END_)?ROLE_DIVIDE_(SYSTEM|ASSISTANT|USER)\]>>>$""")
    private val userButtonClickRegex = Regex("""^\[\[点击按钮:(.*?)\]\]$""")

    fun parse(rawContent: String, mode: ChatRenderMode, role: String?): ChatRenderDocument {
        val normalized = preprocess(
            rawContent = rawContent,
            mode = mode,
            role = role,
        )
        return ChatRenderDocument(
            blocks = parseBlocks(normalized),
        )
    }

    private fun preprocess(
        rawContent: String,
        mode: ChatRenderMode,
        role: String?,
    ): String {
        var text = canonicalizeLineBreaks(rawContent)
        text = convertMermaidHtmlCodeBlocks(text)
        text = deIndentMisinterpretedCodeBlocks(text)
        text = deIndentToolRequestBlocks(text)

        if (mode == ChatRenderMode.Final) {
            text = processStartEndMarkers(text)
            text = deIndentHtml(text)
            text = applyContentProcessors(text)
        } else {
            text = processStartEndMarkers(text)
            text = removeSpeakerTags(text)
            text = ensureNewlineAfterCodeBlock(text)
            text = ensureSpaceAfterTilde(text)
            text = ensureSeparatorBetweenImgAndCode(text)
        }

        if (role == "user") {
            text = transformUserSpecialMarkers(text)
        }

        return text.trim('\n')
    }

    private fun parseBlocks(text: String): List<ChatRenderBlock> {
        if (text.isBlank()) {
            return emptyList()
        }

        val lines = text.lines()
        val blocks = mutableListOf<ChatRenderBlock>()
        val markdownBuffer = mutableListOf<String>()
        var index = 0

        fun flushMarkdownBuffer() {
            if (markdownBuffer.isEmpty()) {
                return
            }
            val bufferedText = markdownBuffer.joinToString("\n").trim('\n')
            if (bufferedText.isNotBlank()) {
                blocks += splitParagraphBlocks(bufferedText)
            }
            markdownBuffer.clear()
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()

            when {
                trimmed.startsWith("```") -> {
                    val parsed = parseCodeFence(lines, index)
                    val mermaidBlock = parsed.blocks.singleOrNull() as? ChatRenderBlock.MermaidDiagram
                    if (mermaidBlock != null) {
                        flushMarkdownBuffer()
                        blocks += mermaidBlock
                    } else {
                        markdownBuffer += lines.subList(index, parsed.nextIndex)
                    }
                    index = parsed.nextIndex
                }

                trimmed.startsWith("<<<[TOOL_REQUEST]>>>") -> {
                    flushMarkdownBuffer()
                    val parsed = parseDelimitedBlock(
                        lines = lines,
                        startIndex = index,
                        endMarker = "<<<[END_TOOL_REQUEST]>>>",
                    )
                    blocks += parseToolRequestBlock(parsed.rawBlock)
                    index = parsed.nextIndex
                }

                trimmed.startsWith("<<<DailyNoteStart>>>") -> {
                    flushMarkdownBuffer()
                    val parsed = parseDelimitedBlock(
                        lines = lines,
                        startIndex = index,
                        endMarker = "<<<DailyNoteEnd>>>",
                    )
                    blocks += parseDailyNoteBlock(parsed.rawBlock)
                    index = parsed.nextIndex
                }

                trimmed.startsWith("[[VCP调用结果信息汇总:") -> {
                    flushMarkdownBuffer()
                    val parsed = parseDelimitedBlock(
                        lines = lines,
                        startIndex = index,
                        endMarker = "VCP调用结果结束]]",
                    )
                    blocks += parseToolResultBlock(parsed.rawBlock)
                    index = parsed.nextIndex
                }

                trimmed.startsWith("[--- VCP元思考链") -> {
                    flushMarkdownBuffer()
                    val parsed = parseDelimitedBlock(
                        lines = lines,
                        startIndex = index,
                        endMarker = "[--- 元思考链结束 ---]",
                    )
                    blocks += parseThoughtBlock(parsed.rawBlock)
                    index = parsed.nextIndex
                }

                trimmed.startsWith("<think>") || trimmed.startsWith("<thinking>") -> {
                    flushMarkdownBuffer()
                    val parsed = parseDelimitedBlock(
                        lines = lines,
                        startIndex = index,
                        endMarker = "</think>",
                        alternativeEndMarker = "</thinking>",
                    )
                    blocks += parseConventionalThoughtBlock(parsed.rawBlock)
                    index = parsed.nextIndex
                }

                trimmed.startsWith("<<<[DESKTOP_PUSH]>>>") -> {
                    flushMarkdownBuffer()
                    val parsed = parseDelimitedBlock(
                        lines = lines,
                        startIndex = index,
                        endMarker = "<<<[DESKTOP_PUSH_END]>>>",
                    )
                    blocks += parseDesktopPushBlock(parsed.rawBlock)
                    index = parsed.nextIndex
                }

                looksLikeHtmlContainerStart(trimmed) -> {
                    flushMarkdownBuffer()
                    val parsed = parseHtmlDocumentBlock(
                        lines = lines,
                        startIndex = index,
                    )
                    blocks += parsed.blocks
                    index = parsed.nextIndex
                }

                trimmed == "{{VCPChatCanvas}}" -> {
                    flushMarkdownBuffer()
                    blocks += ChatRenderBlock.CanvasPlaceholder
                    index += 1
                }

                roleDividerRegex.matches(trimmed) -> {
                    flushMarkdownBuffer()
                    blocks += parseRoleDivider(trimmed)
                    index += 1
                }

                userButtonClickRegex.matches(trimmed) -> {
                    flushMarkdownBuffer()
                    val label = userButtonClickRegex.matchEntire(trimmed)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        .orEmpty()
                    blocks += ChatRenderBlock.UserButtonClick(label = label)
                    index += 1
                }

                else -> {
                    markdownBuffer += line
                    index += 1
                }
            }
        }

        flushMarkdownBuffer()

        return blocks
    }

    private fun parseCodeFence(
        lines: List<String>,
        startIndex: Int,
    ): ParsedBlock {
        val firstLine = lines[startIndex].trimStart()
        val language = firstLine.removePrefix("```").trim().ifBlank { null }
        val normalizedLanguage = language?.lowercase()
        val codeLines = mutableListOf<String>()
        var index = startIndex + 1

        while (index < lines.size) {
            val currentLine = lines[index]
            if (currentLine.trimStart().startsWith("```")) {
                index += 1
                break
            }
            codeLines += currentLine
            index += 1
        }

        val code = codeLines.joinToString("\n")
        val block = when {
            normalizedLanguage in MERMAID_LANGUAGES -> {
                ChatRenderBlock.MermaidDiagram(
                    diagramType = normalizedLanguage ?: "mermaid",
                    code = code,
                )
            }

            normalizedLanguage == "html" ||
                looksLikeRenderableHtml(code) -> {
                ChatRenderBlock.HtmlDocument(code = code)
            }

            else -> {
                ChatRenderBlock.CodeFence(
                    language = language,
                    code = code,
                )
            }
        }

        return ParsedBlock(
            blocks = listOf(block),
            nextIndex = index,
        )
    }

    private fun parseQuote(
        lines: List<String>,
        startIndex: Int,
    ): ParsedBlock {
        val quoteLines = mutableListOf<String>()
        var index = startIndex

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()

            if (trimmed.isBlank()) {
                quoteLines += ""
                index += 1
                continue
            }

            if (!trimmed.startsWith(">")) {
                break
            }

            quoteLines += trimmed.removePrefix(">").removePrefix(" ").trimEnd()
            index += 1
        }

        return ParsedBlock(
            blocks = listOf(
                ChatRenderBlock.Quote(
                    blocks = parseBlocks(quoteLines.joinToString("\n").trim('\n')),
                ),
            ),
            nextIndex = index,
        )
    }

    private fun parseList(
        lines: List<String>,
        startIndex: Int,
    ): ParsedBlock {
        val firstTrimmed = lines[startIndex].trimStart()
        val firstOrderedMatch = orderedListRegex.matchEntire(firstTrimmed)
        val ordered = firstOrderedMatch != null
        val startNumber = firstOrderedMatch?.groupValues?.get(1)?.toIntOrNull()
        val items = mutableListOf<List<ChatRenderBlock>>()
        var index = startIndex

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            val match = if (ordered) {
                orderedListRegex.matchEntire(trimmed)
            } else {
                unorderedListRegex.matchEntire(trimmed)
            } ?: break

            val itemLines = mutableListOf(
                if (ordered) match.groupValues[2] else match.groupValues[1],
            )
            index += 1

            while (index < lines.size) {
                val continuation = lines[index]
                val continuationTrimmed = continuation.trimStart()

                if (continuationTrimmed.isBlank()) {
                    break
                }

                val nextItem = if (ordered) {
                    orderedListRegex.matches(continuationTrimmed)
                } else {
                    unorderedListRegex.matches(continuationTrimmed)
                }

                if (nextItem) {
                    break
                }

                if (startsNewBlock(continuationTrimmed) && !isListContinuation(continuation)) {
                    break
                }

                itemLines += continuationTrimmed
                index += 1
            }

            items += parseBlocks(itemLines.joinToString("\n").trim())
        }

        return ParsedBlock(
            blocks = listOf(
                ChatRenderBlock.MarkdownList(
                    ordered = ordered,
                    startNumber = startNumber,
                    items = items,
                ),
            ),
            nextIndex = index,
        )
    }

    private fun parseParagraph(
        lines: List<String>,
        startIndex: Int,
    ): ParsedBlock {
        val paragraphLines = mutableListOf<String>()
        var index = startIndex

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()

            if (trimmed.isBlank()) {
                break
            }

            if (index != startIndex && startsNewBlock(trimmed)) {
                break
            }

            paragraphLines += line.trimEnd()
            index += 1
        }

        return ParsedBlock(
            blocks = splitParagraphBlocks(paragraphLines.joinToString("\n").trim()),
            nextIndex = index,
        )
    }

    private fun splitParagraphBlocks(text: String): List<ChatRenderBlock> {
        if (text.isBlank()) {
            return emptyList()
        }

        parseStandaloneParagraphBlock(text)?.let { return listOf(it) }

        val result = mutableListOf<ChatRenderBlock>()
        val paragraphBuffer = mutableListOf<String>()

        fun flushParagraphBuffer() {
            if (paragraphBuffer.isEmpty()) {
                return
            }
            val bufferedText = paragraphBuffer.joinToString("\n").trim()
            if (bufferedText.isNotBlank()) {
                result += splitInlineSpecialBlocks(bufferedText)
            }
            paragraphBuffer.clear()
        }

        text.lines().forEach { line ->
            val trimmed = line.trim()
            val standaloneBlock = parseStandaloneParagraphBlock(trimmed)
            if (trimmed.isNotBlank() && standaloneBlock != null) {
                flushParagraphBuffer()
                result += standaloneBlock
            } else {
                paragraphBuffer += line
            }
        }

        flushParagraphBuffer()

        return result.ifEmpty {
            listOf(
                ChatRenderBlock.MarkdownDocument(text = text),
            )
        }
    }

    private fun splitInlineSpecialBlocks(text: String): List<ChatRenderBlock> {
        val blocks = mutableListOf<ChatRenderBlock>()
        var cursor = 0

        INLINE_SPECIAL_BLOCK_REGEX.findAll(text).forEach { match ->
            val start = match.range.first
            if (start > cursor) {
                emitParagraphSegment(blocks, text.substring(cursor, start))
            }

            val buttonLabel = match.groups[1]?.value
            if (buttonLabel != null) {
                blocks += ChatRenderBlock.UserButtonClick(
                    label = buttonLabel.trim(),
                )
            } else {
                blocks += ChatRenderBlock.CanvasPlaceholder
            }

            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            emitParagraphSegment(blocks, text.substring(cursor))
        }

        return blocks.ifEmpty {
            listOf(
                ChatRenderBlock.MarkdownDocument(text = text),
            )
        }
    }

    private fun emitParagraphSegment(
        blocks: MutableList<ChatRenderBlock>,
        segment: String,
    ) {
        val trimmed = segment.trim()
        if (trimmed.isBlank()) {
            return
        }

        parseStandaloneParagraphBlock(trimmed)?.let {
            blocks += it
            return
        }

        blocks += ChatRenderBlock.MarkdownDocument(text = trimmed)
    }

    private fun parseStandaloneParagraphBlock(text: String): ChatRenderBlock? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return null
        }

        parseInteractiveButtons(trimmed)?.let {
            return ChatRenderBlock.InteractiveButtons(buttons = it)
        }

        parseRemoteImage(trimmed)?.let { return it }

        if (looksLikeRenderableHtml(trimmed)) {
            return ChatRenderBlock.HtmlDocument(code = trimmed)
        }

        if (trimmed == "{{VCPChatCanvas}}") {
            return ChatRenderBlock.CanvasPlaceholder
        }

        userButtonClickRegex.matchEntire(trimmed)?.let { match ->
            return ChatRenderBlock.UserButtonClick(
                label = match.groupValues.getOrNull(1)?.trim().orEmpty(),
            )
        }

        return null
    }

    private fun parseInteractiveButtons(text: String): List<InteractiveButtonSpec>? {
        val matches = htmlButtonRegex.findAll(text).toList()
        if (matches.isEmpty()) {
            return null
        }

        val remainder = htmlButtonRegex.replace(text, "").trim()
        if (remainder.isNotEmpty()) {
            return null
        }

        return matches.mapNotNull { match ->
            val attributes = match.groups[1]?.value.orEmpty()
            val rawLabel = match.groups[2]?.value.orEmpty()
            val sendText = extractHtmlAttribute(attributes, "data-send")
                ?.trim()
                ?.ifBlank { null }
                ?: extractOnclickInputText(
                    extractHtmlAttribute(attributes, "onclick"),
                )
                ?: stripHtmlTags(rawLabel).trim()

            val label = stripHtmlTags(rawLabel).trim().ifBlank {
                sendText.ifBlank { "按钮" }
            }

            sendText
                .takeIf { it.isNotBlank() }
                ?.let { InteractiveButtonSpec(label = label, sendText = it) }
        }.takeIf { it.isNotEmpty() }
    }

    private fun extractOnclickInputText(onclick: String?): String? =
        onclick
            ?.let {
                Regex("""(?is)\binput\s*\(\s*(['"])(.*?)\1\s*\)""")
                    .find(it)
                    ?.groupValues
                    ?.getOrNull(2)
            }
            ?.let(::decodeHtml)
            ?.trim()
            ?.ifBlank { null }

    private fun parseRemoteImage(text: String): ChatRenderBlock.RemoteImage? {
        markdownImageRegex.matchEntire(text)?.let { match ->
            return ChatRenderBlock.RemoteImage(
                url = match.groupValues[2].trim(),
                alt = match.groupValues[1].trim().ifBlank { null },
            )
        }

        htmlImgRegex.matchEntire(text)?.let { match ->
            val attributes = match.groupValues[1]
            val src = extractHtmlAttribute(attributes, "src")?.trim().orEmpty()
            if (src.isNotBlank()) {
                return ChatRenderBlock.RemoteImage(
                    url = src,
                    alt = extractHtmlAttribute(attributes, "alt")?.trim()?.ifBlank { null },
                )
            }
        }

        if (directImageUrlRegex.matches(text)) {
            return ChatRenderBlock.RemoteImage(
                url = text,
                alt = null,
            )
        }

        return null
    }

    private fun extractHtmlAttribute(
        attributes: String,
        name: String,
    ): String? {
        val regex = Regex("""(?is)\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""")
        return regex.find(attributes)
            ?.groupValues
            ?.getOrNull(2)
            ?.let(::decodeHtml)
    }

    private fun stripHtmlTags(value: String): String =
        decodeHtml(value.replace(Regex("""<[^>]+>"""), " "))
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun decodeHtml(value: String): String =
        if (!value.contains('&')) {
            value
        } else {
            runCatching {
                HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            }.getOrElse {
                decodeCommonHtmlEntities(value)
            }
        }

    private fun decodeCommonHtmlEntities(value: String): String =
        value
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun convertMermaidHtmlCodeBlocks(text: String): String =
        mermaidHtmlCodeRegex.replace(text) { match ->
            val language = match.groupValues.getOrElse(1) { "mermaid" }.trim().lowercase()
            val rawCode = match.groupValues.getOrElse(2) { "" }
            val decoded = decodeHtml(rawCode).trim()
            buildString {
                append("```")
                append(language)
                append('\n')
                append(decoded)
                append("\n```")
            }
        }

    private fun parseToolRequestBlock(rawBlock: String): ChatRenderBlock {
        val content = rawBlock
            .substringAfter("<<<[TOOL_REQUEST]>>>", rawBlock)
            .substringBefore("<<<[END_TOOL_REQUEST]>>>")
            .trim()

        val toolName = extractVcpField(content, "tool_name")
            ?.removeSuffix(",")
            ?.trim()
            ?.ifBlank { null }
            ?: "Processing..."
        val command = extractVcpField(content, "command")?.trim()
        val isDailyNoteCreate = toolName == "DailyNote" && command == "create"

        if (isDailyNoteCreate) {
            val maid = extractVcpField(content, "maid") ?: extractVcpField(content, "maidName")
            val date = extractVcpField(content, "Date")
            val diaryContent = extractVcpField(content, "Content") ?: "[日记内容解析失败]"
            return ChatRenderBlock.DailyNote(
                maid = maid?.trim()?.ifBlank { null },
                date = date?.trim()?.ifBlank { null },
                blocks = parseBlocks(diaryContent.trim()),
            )
        }

        return ChatRenderBlock.ToolUse(
            toolName = toolName,
            rawContent = content,
        )
    }

    private fun parseDailyNoteBlock(rawBlock: String): ChatRenderBlock {
        val content = rawBlock
            .substringAfter("<<<DailyNoteStart>>>", rawBlock)
            .substringBefore("<<<DailyNoteEnd>>>")
            .trim()
        val maid = Regex("""(?im)^Maid:\s*(.*)$""")
            .find(content)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.ifBlank { null }
        val date = Regex("""(?im)^Date:\s*(.*)$""")
            .find(content)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.ifBlank { null }
        val diaryContent = Regex("""(?is)(?:^|\n)Content:\s*(.*)$""")
            .find(content)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: content

        return ChatRenderBlock.DailyNote(
            maid = maid,
            date = date,
            blocks = parseBlocks(diaryContent),
        )
    }

    private fun parseToolResultBlock(rawBlock: String): ChatRenderBlock {
        val content = rawBlock
            .removePrefix("[[VCP调用结果信息汇总:")
            .removeSuffix("VCP调用结果结束]]")
            .trim()
        val lines = content.lines()

        var toolName = "Unknown Tool"
        var status = "Unknown Status"
        val details = mutableListOf<ToolResultDetail>()
        val footerLines = mutableListOf<String>()

        var currentKey: String? = null
        val currentValue = mutableListOf<String>()

        fun flushCurrentEntry() {
            val key = currentKey ?: return
            val value = currentValue.joinToString("\n").trim()
            when (key) {
                "工具名称" -> toolName = value.ifBlank { toolName }
                "执行状态" -> status = value.ifBlank { status }
                else -> {
                    val richTextKeys = setOf("返回内容", "内容", "Result", "返回结果", "output")
                    val isRichText = key in richTextKeys
                    val imageUrl = value.takeIf { isImageField(key, it) }
                    details += ToolResultDetail(
                        key = key,
                        value = value,
                        richBlocks = if (imageUrl == null && isRichText && value.isNotBlank()) parseBlocks(value) else emptyList(),
                        isRichText = imageUrl == null && isRichText,
                        imageUrl = imageUrl,
                    )
                }
            }
            currentKey = null
            currentValue.clear()
        }

        lines.forEach { line ->
            val match = Regex("""^-\s*([^:]+):\s*(.*)$""").matchEntire(line)
            if (match != null) {
                flushCurrentEntry()
                currentKey = match.groupValues[1].trim()
                currentValue += match.groupValues[2].trim()
            } else if (currentKey != null) {
                currentValue += line
            } else if (line.isNotBlank()) {
                footerLines += line
            }
        }
        flushCurrentEntry()

        return ChatRenderBlock.ToolResult(
            toolName = toolName,
            status = status,
            details = details,
            footerBlocks = if (footerLines.isEmpty()) {
                emptyList()
            } else {
                parseBlocks(footerLines.joinToString("\n"))
            },
        )
    }

    private fun parseThoughtBlock(rawBlock: String): ChatRenderBlock {
        val regex = Regex("""(?s)^\[--- VCP元思考链(?:\:\s*"([^"]*)")?\s*---\](.*?)(?:\[--- 元思考链结束 ---\])?$""")
        val match = regex.find(rawBlock.trim())
        val title = match?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "元思考链" }
        val body = match?.groupValues?.getOrNull(2)?.trim().orEmpty()
        return ChatRenderBlock.Thought(
            title = title,
            blocks = parseBlocks(body),
        )
    }

    private fun parseConventionalThoughtBlock(rawBlock: String): ChatRenderBlock {
        val regex = Regex("""(?is)^<think(?:ing)?>(.*?)(?:</think(?:ing)?>)?$""")
        val body = regex.find(rawBlock.trim())?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return ChatRenderBlock.Thought(
            title = "思维链",
            blocks = parseBlocks(body),
        )
    }

    private fun parseDesktopPushBlock(rawBlock: String): ChatRenderBlock {
        val hasEndMarker = rawBlock.contains("<<<[DESKTOP_PUSH_END]>>>")
        val content = rawBlock
            .substringAfter("<<<[DESKTOP_PUSH]>>>", rawBlock)
            .substringBefore("<<<[DESKTOP_PUSH_END]>>>")
            .trim()
        val previewLength = if (hasEndMarker) 120 else 80
        val preview = if (content.length > previewLength) {
            content.take(previewLength).trimEnd() + "..."
        } else {
            content
        }

        return ChatRenderBlock.DesktopPush(
            preview = preview,
            streaming = !hasEndMarker,
        )
    }

    private fun parseRoleDivider(line: String): ChatRenderBlock.RoleDivider {
        val match = roleDividerRegex.matchEntire(line) ?: error("role divider regex should match")
        return ChatRenderBlock.RoleDivider(
            role = match.groupValues[2].lowercase(),
            isEnd = match.groupValues[1].isNotBlank(),
        )
    }

    private fun parseDelimitedBlock(
        lines: List<String>,
        startIndex: Int,
        endMarker: String,
        alternativeEndMarker: String? = null,
    ): ParsedDelimitedBlock {
        val blockLines = mutableListOf<String>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            blockLines += line
            index += 1
            if (line.contains(endMarker) || (alternativeEndMarker != null && line.contains(alternativeEndMarker))) {
                break
            }
        }
        return ParsedDelimitedBlock(
            rawBlock = blockLines.joinToString("\n"),
            nextIndex = index,
        )
    }

    private fun parseHtmlDocumentBlock(
        lines: List<String>,
        startIndex: Int,
    ): ParsedBlock {
        val htmlLines = mutableListOf<String>()
        var index = startIndex
        val tagRegex = Regex(
            """<!--[\s\S]*?-->|<!DOCTYPE[^>]*>|</?([a-zA-Z][\w:-]*)\b[^>]*?>""",
            RegexOption.IGNORE_CASE,
        )

        data class HtmlParseState(
            val openTags: ArrayDeque<String>,
            val rawTextTag: String?,
            val hasDanglingTag: Boolean,
        )

        fun isHtmlContinuationLine(trimmedLine: String): Boolean =
            trimmedLine.isBlank() ||
                trimmedLine.startsWith("<!--") ||
                trimmedLine.startsWith("<!DOCTYPE", ignoreCase = true) ||
                extractHtmlStartTagName(trimmedLine) != null ||
                Regex("""^</[a-zA-Z]""").containsMatchIn(trimmedLine)

        fun rebuildHtmlState(htmlText: String): HtmlParseState {
            val openTags = ArrayDeque<String>()
            var rawTextTag: String? = null

            tagRegex.findAll(htmlText).forEach { match ->
                val tag = match.value
                val tagName = match.groups[1]?.value?.lowercase() ?: return@forEach
                val isClosing = tag.startsWith("</")
                val isSelfClosing = tag.endsWith("/>") || tagName in HTML_VOID_TAGS

                if (rawTextTag != null && tagName != rawTextTag) {
                    return@forEach
                }

                if (isClosing) {
                    if (openTags.isNotEmpty() && openTags.last() == tagName) {
                        openTags.removeLast()
                    } else {
                        removeLastMatching(openTags, tagName)
                    }
                    if (rawTextTag == tagName) {
                        rawTextTag = null
                    }
                    return@forEach
                }

                if (!isSelfClosing) {
                    openTags.addLast(tagName)
                    if (tagName in HTML_RAW_TEXT_TAGS) {
                        rawTextTag = tagName
                    }
                }
            }

            return HtmlParseState(
                openTags = openTags,
                rawTextTag = rawTextTag,
                hasDanglingTag = hasDanglingTrailingHtmlTag(htmlText),
            )
        }

        var parseState = HtmlParseState(
            openTags = ArrayDeque(),
            rawTextTag = null,
            hasDanglingTag = false,
        )

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            if (htmlLines.isNotEmpty() &&
                parseState.rawTextTag == null &&
                parseState.openTags.isEmpty() &&
                !parseState.hasDanglingTag &&
                !isHtmlContinuationLine(trimmed)
            ) {
                break
            }

            htmlLines += line.trimEnd()
            parseState = rebuildHtmlState(htmlLines.joinToString("\n"))
            index += 1

            if (htmlLines.isNotEmpty() &&
                parseState.rawTextTag == null &&
                parseState.openTags.isEmpty() &&
                !parseState.hasDanglingTag
            ) {
                val nextTrimmed = lines.getOrNull(index)?.trim().orEmpty()
                if (!isHtmlContinuationLine(nextTrimmed)) {
                    break
                }
            }
        }

        return ParsedBlock(
            blocks = listOf(
                ChatRenderBlock.HtmlDocument(
                    code = htmlLines.joinToString("\n").trim(),
                ),
            ),
            nextIndex = index,
        )
    }

    private fun startsNewBlock(trimmedLine: String): Boolean =
        trimmedLine.startsWith("```") ||
            trimmedLine.startsWith("<<<[TOOL_REQUEST]>>>") ||
            trimmedLine.startsWith("<<<DailyNoteStart>>>") ||
            trimmedLine.startsWith("[[VCP调用结果信息汇总:") ||
            trimmedLine.startsWith("[--- VCP元思考链") ||
            trimmedLine.startsWith("<think>") ||
            trimmedLine.startsWith("<thinking>") ||
            trimmedLine.startsWith("<<<[DESKTOP_PUSH]>>>") ||
            looksLikeHtmlContainerStart(trimmedLine) ||
            trimmedLine == "{{VCPChatCanvas}}" ||
            roleDividerRegex.matches(trimmedLine) ||
            headingRegex.matches(trimmedLine) ||
            userButtonClickRegex.matches(trimmedLine) ||
            trimmedLine.startsWith(">") ||
            unorderedListRegex.matches(trimmedLine) ||
            orderedListRegex.matches(trimmedLine)

    private fun isListContinuation(line: String): Boolean =
        line.startsWith("  ") || line.startsWith("\t")

    private fun removeLastMatching(
        deque: ArrayDeque<String>,
        value: String,
    ) {
        if (deque.isEmpty()) {
            return
        }

        val buffer = mutableListOf<String>()
        var removed = false

        while (deque.isNotEmpty()) {
            val current = deque.removeLast()
            if (!removed && current == value) {
                removed = true
                break
            }
            buffer += current
        }

        for (i in buffer.indices.reversed()) {
            deque.addLast(buffer[i])
        }
    }

    private fun canonicalizeLineBreaks(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun removeSpeakerTags(text: String): String {
        return SPEAKER_TAG_REGEX.replace(text, "")
    }

    private fun ensureNewlineAfterCodeBlock(text: String): String =
        text.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("```")) {
                return@joinToString line
            }

            val suffix = trimmed.removePrefix("```")
            val looksLikeLanguageTag = suffix.isNotBlank() &&
                LANGUAGE_TAG_REGEX.matches(suffix.trim())

            if (looksLikeLanguageTag || suffix.isBlank()) {
                line
            } else {
                line.replaceFirst("```", "```\n")
            }
        }

    private fun ensureSpaceAfterTilde(text: String): String =
        TILDE_SPACE_REGEX.replace(text, "$1~ ")

    private fun ensureSeparatorBetweenImgAndCode(text: String): String =
        IMG_CODE_SEPARATOR_REGEX.replace(text, "$1\n\n<!-- VCP-Renderer-Separator -->\n\n$2")

    private fun transformUserSpecialMarkers(text: String): String =
        text.replace(BUTTON_CLICK_REGEX) { matchResult ->
            matchResult.value
        }.replace(CANVAS_PLACEHOLDER_REGEX, "{{VCPChatCanvas}}")

    private fun processStartEndMarkers(text: String): String =
        text.replace(START_END_MARKER_REGEX) { matchResult ->
            val content = matchResult.groupValues.getOrElse(1) { "" }
            val end = matchResult.groupValues.getOrElse(2) { "" }
            "「始」$content$end"
        }

    private fun removeIndentationFromCodeBlockMarkers(text: String): String {
        if (text.isBlank()) {
            return text
        }

        val lines = text.lines()
        var inCodeBlock = false

        return lines.joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                trimmed
            } else {
                line
            }
        }
    }

    private fun applyContentProcessors(text: String): String =
        ensureSeparatorBetweenImgAndCode(
            ensureSpaceAfterTilde(
                ensureNewlineAfterCodeBlock(
                    removeSpeakerTags(
                        removeIndentationFromCodeBlockMarkers(text),
                    ),
                ),
            ),
        )

    private fun isImageField(
        key: String,
        value: String,
    ): Boolean {
        val normalizedKey = key.lowercase()
        val imageLikeKey = normalizedKey in setOf("可访问url", "url", "image", "返回内容")
        return imageLikeKey && directImageUrlRegex.matches(value.trim())
    }

    private fun deIndentToolRequestBlocks(text: String): String {
        val lines = text.lines()
        var inToolBlock = false

        return lines.joinToString("\n") { line ->
            val isStart = line.contains("<<<[TOOL_REQUEST]>>>")
            val isEnd = line.contains("<<<[END_TOOL_REQUEST]>>>")
            val shouldTrim = isStart || inToolBlock

            if (isStart) {
                inToolBlock = true
            }

            val processed = if (shouldTrim) line.trimStart() else line

            if (isEnd) {
                inToolBlock = false
            }

            processed
        }
    }

    private fun deIndentHtml(text: String): String {
        val lines = text.lines()
        var inFence = false
        return lines.joinToString("\n") { line ->
            when {
                line.trim().startsWith("```") -> {
                    inFence = !inFence
                    line
                }

                !inFence && line.contains("<img") -> line
                !inFence && Regex("""^\s+<(!|[a-zA-Z])""").containsMatchIn(line) -> line.trimStart()
                else -> line
            }
        }
    }

    private fun deIndentMisinterpretedCodeBlocks(text: String): String {
        if (text.isBlank()) {
            return text
        }

        val lines = text.lines()
        var inFence = false

        return lines.joinToString("\n") { line ->
            if (line.trim().startsWith("```")) {
                inFence = !inFence
                return@joinToString line.trimStart()
            }

            if (inFence) {
                return@joinToString line
            }

            val trimmed = line.trimStart()
            val hasIndentation = line.length > trimmed.length
            if (hasIndentation) {
                when {
                    LIST_ITEM_REGEX.containsMatchIn(line) -> line
                    HTML_BLOCK_TAG_REGEX.containsMatchIn(line) -> trimmed
                    CHINESE_PARAGRAPH_REGEX.containsMatchIn(trimmed) -> trimmed
                    else -> line
                }
            } else {
                line
            }
        }
    }

    private fun looksLikeHtmlContainerStart(trimmedLine: String): Boolean =
        trimmedLine.startsWith("<!--") ||
            trimmedLine.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
            extractHtmlStartTagName(trimmedLine)
                ?.let { tagName -> tagName !in HTML_NATIVE_RENDER_PRIORITY_TAGS }
                ?: false

    private fun extractHtmlStartTagName(trimmedLine: String): String? =
        HTML_START_TAG_NAME_REGEX
            .find(trimmedLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()

    private fun looksLikeRenderableHtml(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return false
        }

        if (trimmed.contains("「始」") || trimmed.contains("「末」")) {
            return false
        }

        if (trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return true
        }

        if (!looksLikeHtmlContainerStart(trimmed) &&
            !HTML_FORM_ELEMENT_REGEX.containsMatchIn(trimmed)
        ) {
            return false
        }

        val htmlTagCount = HTML_TAG_REGEX.findAll(trimmed).count()
        val containsClosingTag = HTML_CLOSING_TAG_REGEX.containsMatchIn(trimmed)
        return htmlTagCount >= 2 || containsClosingTag || '\n' in trimmed
    }

    private fun hasDanglingTrailingHtmlTag(text: String): Boolean {
        val lastTagStart = text.lastIndexOf('<')
        if (lastTagStart == -1) {
            return false
        }

        val lastTagEnd = text.lastIndexOf('>')
        return lastTagStart > lastTagEnd
    }

    private fun ensureHtmlFenced(text: String): String {
        val doctypeTag = "<!DOCTYPE html>"
        val htmlCloseTag = "</html>"

        if (Regex("""```\w*\n<!DOCTYPE html>""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return text
        }

        if (!text.lowercase().contains(doctypeTag.lowercase())) {
            return text
        }

        val protectedRanges = mutableListOf<IntRange>()
        val startMarker = "「始」"
        val endMarker = "「末」"
        var searchStart = 0

        while (true) {
            val startPos = text.indexOf(startMarker, searchStart)
            if (startPos == -1) {
                break
            }

            val endPos = text.indexOf(endMarker, startPos + startMarker.length)
            if (endPos == -1) {
                protectedRanges += startPos until text.length
                break
            }

            protectedRanges += startPos until (endPos + endMarker.length)
            searchStart = endPos + endMarker.length
        }

        fun isProtected(index: Int): Boolean =
            protectedRanges.any { index in it }

        val builder = StringBuilder()
        var cursor = 0

        while (true) {
            val startIndex = text.lowercase().indexOf(doctypeTag.lowercase(), cursor)
            if (startIndex == -1) {
                builder.append(text.substring(cursor))
                break
            }

            builder.append(text.substring(cursor, startIndex))
            val endIndex = text.lowercase().indexOf(htmlCloseTag.lowercase(), startIndex + doctypeTag.length)
            if (endIndex == -1) {
                builder.append(text.substring(startIndex))
                break
            }

            val block = text.substring(startIndex, endIndex + htmlCloseTag.length)
            if (isProtected(startIndex)) {
                builder.append(block)
            } else {
                builder.append("\n```html\n")
                builder.append(block)
                builder.append("\n```\n")
            }
            cursor = endIndex + htmlCloseTag.length
        }

        return builder.toString()
    }

    private fun extractVcpField(
        content: String,
        fieldName: String,
    ): String? {
        val regex = Regex("""(?ms)(?:^|\n)\s*${Regex.escape(fieldName)}\s*:\s*「始」(.*?)「末」""")
        return regex.find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private val MERMAID_LANGUAGES = setOf("mermaid", "flowchart", "graph")
    private val BUTTON_CLICK_REGEX = Regex("""\[\[点击按钮:(.*?)\]\]""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val CANVAS_PLACEHOLDER_REGEX = Regex("""\{\{VCPChatCanvas\}\}""")
    private val SPEAKER_TAG_REGEX = Regex("""^\[(?:(?!\]:\s).)*的发言\]:\s*""", RegexOption.MULTILINE)
    private val LANGUAGE_TAG_REGEX = Regex("""^[A-Za-z0-9_+#.-]+$""")
    private val TILDE_SPACE_REGEX = Regex("""(^|[^\w/\\=])~(?![\s~])""")
    private val IMG_CODE_SEPARATOR_REGEX = Regex("""(<img[^>]+>)\s*(```)""")
    private val LIST_ITEM_REGEX = Regex("""^\s*([-*]|\d+\.)\s+""")
    private val HTML_BLOCK_TAG_REGEX = Regex(
        """^\s*</?(div|p|img|span|a|h[1-6]|ul|ol|li|table|tr|td|th|section|article|header|footer|nav|aside|main|figure|figcaption|blockquote|pre|code|style|script|button|form|input|textarea|select|label|iframe|video|audio|canvas|svg)[\s>/]""",
        RegexOption.IGNORE_CASE,
    )
    private val CHINESE_PARAGRAPH_REGEX = Regex("""^[\u4e00-\u9fa5]""")
    private val HTML_START_TAG_NAME_REGEX = Regex("""^<(?!/?(?:think|thinking)\b)([a-zA-Z][\w:-]*)\b""", RegexOption.IGNORE_CASE)
    private val HTML_FORM_ELEMENT_REGEX = Regex("""^<(button|input|textarea|select|label)\b""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("""</?[a-zA-Z][^>]*>""")
    private val HTML_CLOSING_TAG_REGEX = Regex("""</[a-zA-Z][^>]*>""")
    private val INLINE_SPECIAL_BLOCK_REGEX = Regex("""\[\[点击按钮:(.*?)\]\]|\{\{VCPChatCanvas\}\}""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val START_END_MARKER_REGEX = Regex("""「始」([\s\S]*?)(「末」|$)""")
    private val markdownImageRegex = Regex("""^!\[([^\]]*)]\(([^)]+)\)$""")
    private val htmlImgRegex = Regex("""(?is)^<img\b([^>]*)/?>$""")
    private val htmlButtonRegex = Regex("""(?is)<button\b([^>]*)>(.*?)</button>""")
    private val mermaidHtmlCodeRegex = Regex("""(?is)<code.*?>\s*(flowchart|graph|mermaid)\s+([\s\S]*?)</code>""")
    private val directImageUrlRegex = Regex("""^https?://[^\s]+\.(?:jpeg|jpg|png|gif|webp)(?:\?[^\s]*)?$""", RegexOption.IGNORE_CASE)
    private val HTML_VOID_TAGS = setOf(
        "area",
        "base",
        "br",
        "col",
        "embed",
        "hr",
        "img",
        "input",
        "link",
        "meta",
        "param",
        "source",
        "track",
        "wbr",
    )
    private val HTML_NATIVE_RENDER_PRIORITY_TAGS = setOf(
        "button",
        "img",
        "input",
        "label",
        "select",
        "textarea",
    )
    private val HTML_RAW_TEXT_TAGS = setOf("script", "style")
}

private data class ParsedBlock(
    val blocks: List<ChatRenderBlock>,
    val nextIndex: Int,
)

private data class ParsedDelimitedBlock(
    val rawBlock: String,
    val nextIndex: Int,
)

@Composable
private fun ChatRenderBlocksView(
    blocks: List<ChatRenderBlock>,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    pauseDynamicContent: Boolean = false,
    modifier: Modifier = Modifier,
    nested: Boolean,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (nested) 6.dp else 8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is ChatRenderBlock.MarkdownDocument -> NativeMarkdownBlockView(
                    text = block.text,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                )

                is ChatRenderBlock.Paragraph -> RichTextBlock(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is ChatRenderBlock.Heading -> HeadingBlockView(block = block)
                is ChatRenderBlock.UserButtonClick -> UserButtonClickView(block = block)
                is ChatRenderBlock.InteractiveButtons -> InteractiveButtonsView(
                    block = block,
                    onActionMessage = onActionMessage,
                )
                is ChatRenderBlock.RemoteImage -> RemoteImageView(block = block)
                is ChatRenderBlock.CodeFence -> CodeFenceView(block = block)
                is ChatRenderBlock.HtmlDocument -> HtmlDocumentView(
                    block = block,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                )
                is ChatRenderBlock.MermaidDiagram -> MermaidDiagramView(block = block)
                is ChatRenderBlock.Quote -> QuoteBlockView(
                    block = block,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                )
                is ChatRenderBlock.MarkdownList -> MarkdownListView(
                    block = block,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                )
                is ChatRenderBlock.ToolUse -> ToolUseView(block = block)
                is ChatRenderBlock.ToolResult -> ToolResultView(
                    block = block,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                )
                is ChatRenderBlock.Thought -> ThoughtView(
                    block = block,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                )
                is ChatRenderBlock.DesktopPush -> DesktopPushView(block = block)
                is ChatRenderBlock.DailyNote -> DailyNoteView(
                    block = block,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                )
                is ChatRenderBlock.RoleDivider -> RoleDividerView(block = block)
                ChatRenderBlock.CanvasPlaceholder -> CanvasPlaceholderView()
            }
        }
    }
}

@Composable
private fun HeadingBlockView(block: ChatRenderBlock.Heading) {
    val textStyle = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    }
    RichTextBlock(
        text = block.text,
        style = textStyle,
    )
}

@Composable
private fun UserButtonClickView(block: ChatRenderBlock.UserButtonClick) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(99.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                shape = RoundedCornerShape(99.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = block.label.ifBlank { "按钮动作" },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun InteractiveButtonsView(
    block: ChatRenderBlock.InteractiveButtons,
    onActionMessage: ((String) -> Unit)?,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        block.buttons.forEach { button ->
            OutlinedButton(
                onClick = {
                    onActionMessage?.invoke(buildActionMessagePayload(button.sendText))
                },
                enabled = onActionMessage != null,
            ) {
                Text(text = button.label)
            }
        }
    }
}

@Composable
private fun RemoteImageView(block: ChatRenderBlock.RemoteImage) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable {
                runCatching {
                    uriHandler.openUri(block.url)
                }
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsyncImage(
            model = block.url,
            contentDescription = block.alt,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
        block.alt?.takeIf { it.isNotBlank() }?.let { alt ->
            Text(
                text = alt,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = block.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun buildActionMessagePayload(sendText: String): String {
    val normalized = sendText.trim()
    val rawPayload = "[[点击按钮:$normalized]]"
    if (rawPayload.length <= 500) {
        return rawPayload
    }

    val maxTextLength = 500 - "[[点击按钮:]]".length
    return "[[点击按钮:${normalized.take(maxTextLength)}]]"
}

@Composable
private fun CodeFenceView(block: ChatRenderBlock.CodeFence) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                shape = RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        if (!block.language.isNullOrBlank()) {
            Text(
                text = block.language,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(
                text = block.code.ifBlank { " " },
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun HtmlDocumentView(
    block: ChatRenderBlock.HtmlDocument,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean,
) {
    if (mode == ChatRenderMode.Streaming) {
        StreamingInlineHtmlBlockView(
            html = block.code,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )
        return
    }

    if (mode == ChatRenderMode.Final && shouldUseBrowserHtmlRenderer(block.code)) {
        BrowserHtmlBlockView(
            html = block.code,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            pauseDynamicContent = pauseDynamicContent,
        )
        return
    }

    val shouldFallback = remember(block.code) {
        shouldFallbackToSafeHtmlRenderer(block.code)
    }

    if (shouldFallback) {
        SafePlainTextBlockView(
            text = buildSafeRenderPreview(block.code),
        )
    } else {
        NativeHtmlBlockView(
            html = block.code,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )
    }
}

private sealed interface NativeHtmlNode {
    data class Element(
        val tagName: String,
        val attributes: Map<String, String>,
        val children: List<NativeHtmlNode>,
    ) : NativeHtmlNode

    data class Text(
        val text: String,
    ) : NativeHtmlNode
}

private data class MutableNativeHtmlElement(
    val tagName: String,
    val attributes: Map<String, String>,
    val children: MutableList<NativeHtmlNode> = mutableListOf(),
)

private data class NativeHtmlDocumentModel(
    val nodes: List<NativeHtmlNode>,
    val styleSheet: List<NativeCssRule>,
)

private data class NativeCssRule(
    val selector: NativeCssSelector,
    val declarations: Map<String, String>,
)

private sealed interface NativeCssSelector {
    data class Tag(val name: String) : NativeCssSelector

    data class Class(val name: String) : NativeCssSelector

    data class Id(val name: String) : NativeCssSelector
}

private data class NativeHtmlEdgeInsets(
    val top: androidx.compose.ui.unit.Dp = 0.dp,
    val end: androidx.compose.ui.unit.Dp = 0.dp,
    val bottom: androidx.compose.ui.unit.Dp = 0.dp,
    val start: androidx.compose.ui.unit.Dp = 0.dp,
)

private data class NativeHtmlStyle(
    val textColor: Color? = null,
    val backgroundColor: Color? = null,
    val padding: NativeHtmlEdgeInsets? = null,
    val margin: NativeHtmlEdgeInsets? = null,
    val borderColor: Color? = null,
    val borderWidth: androidx.compose.ui.unit.Dp? = null,
    val borderRadius: androidx.compose.ui.unit.Dp? = null,
    val fontSizeSp: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val lineThrough: Boolean = false,
    val textAlign: TextAlign? = null,
    val monospace: Boolean = false,
) {
    fun hasContainerDecoration(): Boolean =
        backgroundColor != null ||
            padding != null ||
            margin != null ||
            (borderColor != null && borderWidth != null) ||
            borderRadius != null
}

private data class NativeHtmlTextContext(
    val color: Int? = null,
    val backgroundColor: Int? = null,
    val fontSizeSp: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val lineThrough: Boolean = false,
    val monospace: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val relativeSize: Float = 1f,
    val textAlign: TextAlign? = null,
    val link: String? = null,
) {
    fun merge(style: NativeHtmlStyle): NativeHtmlTextContext =
        copy(
            color = style.textColor?.toArgb() ?: color,
            backgroundColor = style.backgroundColor?.toArgb() ?: backgroundColor,
            fontSizeSp = style.fontSizeSp ?: fontSizeSp,
            bold = bold || style.bold,
            italic = italic || style.italic,
            underline = underline || style.underline,
            lineThrough = lineThrough || style.lineThrough,
            monospace = monospace || style.monospace,
            textAlign = style.textAlign ?: textAlign,
        )

    fun withLink(value: String?): NativeHtmlTextContext =
        copy(link = value)
}

private sealed interface NativeHtmlFlowItem {
    data class Inline(val nodes: List<NativeHtmlNode>) : NativeHtmlFlowItem

    data class Block(val node: NativeHtmlNode) : NativeHtmlFlowItem
}

private data class BrowserHtmlRenderState(
    val html: String,
    val wasTruncated: Boolean,
)

private val chatRenderDocumentCache =
    object : LruCache<ChatRenderDocumentCacheKey, ChatRenderDocument>(CHAT_RENDER_DOCUMENT_CACHE_MAX_CHARS) {
        override fun sizeOf(
            key: ChatRenderDocumentCacheKey,
            value: ChatRenderDocument,
        ): Int = key.content.length.coerceAtLeast(1)
    }

private val nativeHtmlDocumentCache =
    object : LruCache<String, NativeHtmlDocumentModel>(NATIVE_HTML_DOCUMENT_CACHE_MAX_CHARS) {
        override fun sizeOf(
            key: String,
            value: NativeHtmlDocumentModel,
        ): Int = key.length.coerceAtLeast(1)
    }

private fun parseChatRenderDocumentCached(
    content: String,
    mode: ChatRenderMode,
    role: String?,
): ChatRenderDocument {
    val cacheKey = ChatRenderDocumentCacheKey(
        content = content,
        mode = mode,
        role = role,
    )
    synchronized(chatRenderDocumentCache) {
        chatRenderDocumentCache.get(cacheKey)?.let { return it }
    }

    val parsed = VcpChatMessageParser.parse(content, mode, role)
    synchronized(chatRenderDocumentCache) {
        chatRenderDocumentCache.put(cacheKey, parsed)
    }
    return parsed
}

private fun parseNativeHtmlDocumentCached(
    html: String,
): NativeHtmlDocumentModel {
    synchronized(nativeHtmlDocumentCache) {
        nativeHtmlDocumentCache.get(html)?.let { return it }
    }

    val parsed = parseNativeHtmlDocument(html)
    synchronized(nativeHtmlDocumentCache) {
        nativeHtmlDocumentCache.put(html, parsed)
    }
    return parsed
}

@Composable
private fun StreamingInlineHtmlBlockView(
    html: String,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val renderHtml = remember(html) {
        trimDanglingTrailingHtmlFragment(html)
    }

    if (renderHtml.isBlank()) {
        StreamingTextBlockView(text = html)
        return
    }

    if (shouldFallbackToSafeHtmlRenderer(renderHtml)) {
        SafePlainTextBlockView(
            text = buildSafeRenderPreview(html),
        )
        return
    }

    NativeHtmlBlockView(
        html = renderHtml,
        onActionMessage = onActionMessage,
        onLongPress = onLongPress,
    )
}

@Composable
private fun NativeHtmlBlockView(
    html: String,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val documentResult = remember(html) {
        runCatching {
            parseNativeHtmlDocumentCached(html)
        }
    }

    val document = documentResult.getOrNull()
    if (document == null) {
        SafePlainTextBlockView(
            text = buildSafeRenderPreview(html),
        )
        return
    }

    NativeHtmlFlowView(
        nodes = document.nodes,
        styleSheet = document.styleSheet,
        inheritedTextContext = NativeHtmlTextContext(),
        onActionMessage = onActionMessage,
        onLongPress = onLongPress,
        nested = false,
    )
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun BrowserHtmlBlockView(
    html: String,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val actionState = rememberUpdatedState(onActionMessage)
    val longPressState = rememberUpdatedState(onLongPress)
    val renderState = remember(html) {
        BrowserHtmlRenderState(
            html = repairBrowserHtmlForRender(html),
            wasTruncated = isProbablyTruncatedBrowserHtml(html),
        )
    }
    val estimatedHeightPx = with(density) { estimateBrowserHtmlHeightDp(html).roundToPx() }
    var contentHeightPx by rememberSaveable(html) { mutableIntStateOf(estimatedHeightPx) }
    var loadedHtml by rememberSaveable(html) { mutableStateOf<String?>(null) }
    val minHeightPx = with(density) { 48.dp.roundToPx() }
    val wrappedHtml = remember(renderState.html) {
        buildBrowserHtmlDocument(renderState.html)
    }

    LaunchedEffect(wrappedHtml) {
        if (loadedHtml == null) {
            loadedHtml = wrappedHtml
        } else if (loadedHtml != wrappedHtml) {
            delay(BROWSER_HTML_UPDATE_DEBOUNCE_MS)
            loadedHtml = wrappedHtml
        }
    }

    val documentHtml = loadedHtml ?: wrappedHtml

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { contentHeightPx.coerceAtLeast(minHeightPx).toDp() }),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.textZoom = 100
                    settings.mediaPlaybackRequiresUserGesture = true
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        settings.safeBrowsingEnabled = true
                    }
                    isLongClickable = longPressState.value != null
                    setOnLongClickListener {
                        longPressState.value?.invoke()
                        longPressState.value != null
                    }
                    addJavascriptInterface(
                        BrowserHtmlBridge { cssHeight ->
                            val heightPx = scaledBrowserHtmlHeightToPx(
                                cssHeight = cssHeight,
                                scale = scale,
                                density = density.density,
                            )
                            if (abs(heightPx - contentHeightPx) >= 2) {
                                contentHeightPx = heightPx.coerceAtLeast(minHeightPx)
                            }
                        },
                        BROWSER_HTML_BRIDGE_NAME,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = handleBrowserHtmlNavigation(
                            context = context,
                            link = request?.url?.toString(),
                            onActionMessage = actionState.value,
                        )

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            url: String?,
                        ): Boolean = handleBrowserHtmlNavigation(
                            context = context,
                            link = url,
                            onActionMessage = actionState.value,
                        )

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            updateBrowserHtmlContentHeight(
                                webView = view,
                                density = density.density,
                                minHeightPx = minHeightPx,
                                onHeightChanged = { heightPx ->
                                    // Only grow from onPageFinished — contentHeight
                                    // can be 0 before layout completes, which would
                                    // collapse the view to minHeightPx.  The JS bridge
                                    // will report the authoritative height shortly.
                                    if (heightPx > contentHeightPx + 2) {
                                        contentHeightPx = heightPx
                                    }
                                },
                            )
                            view.evaluateJavascript(BROWSER_HTML_HEIGHT_JS, null)
                        }
                    }
                }
            },
            update = { webView ->
                webView.isLongClickable = longPressState.value != null
                webView.setOnLongClickListener {
                    longPressState.value?.invoke()
                    longPressState.value != null
                }
                val targetHeight = contentHeightPx.coerceAtLeast(minHeightPx)
                val layoutParams = webView.layoutParams
                    ?: ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        targetHeight,
                    )
                if (layoutParams.height != targetHeight) {
                    layoutParams.height = targetHeight
                    webView.layoutParams = layoutParams
                }
                if (webView.tag != documentHtml) {
                    webView.tag = documentHtml
                    webView.loadDataWithBaseURL(
                        BROWSER_HTML_BASE_URL,
                        documentHtml,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
                applyBrowserHtmlPausedState(webView, pauseDynamicContent)
            },
            onReset = { webView ->
                webView.stopLoading()
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.removeJavascriptInterface(BROWSER_HTML_BRIDGE_NAME)
                webView.loadUrl("about:blank")
                webView.webViewClient = WebViewClient()
                webView.destroy()
            },
        )

        if (renderState.wasTruncated) {
            Text(
                text = "这段 HTML 原文已在落盘前截断，当前只渲染到最后一个有效标签。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun updateBrowserHtmlContentHeight(
    webView: WebView,
    density: Float,
    minHeightPx: Int,
    onHeightChanged: (Int) -> Unit,
) {
    webView.post {
        val measuredHeight = scaledBrowserHtmlHeightToPx(
            cssHeight = webView.contentHeight.toFloat(),
            scale = webView.scale,
            density = density,
        ).coerceAtLeast(minHeightPx)
        onHeightChanged(measuredHeight)
    }
}

private fun scaledBrowserHtmlHeightToPx(
    cssHeight: Float,
    scale: Float,
    density: Float,
): Int {
    if (cssHeight <= 0f) {
        return 0
    }

    val effectiveScale = scale.takeIf { it > 0f } ?: density
    return (cssHeight * effectiveScale).roundToInt()
}

private fun applyBrowserHtmlPausedState(
    webView: WebView,
    paused: Boolean,
) {
    val currentState = webView.getTag(BROWSER_HTML_PAUSE_TAG_KEY) as? Boolean
    if (currentState == paused) {
        return
    }

    webView.setTag(BROWSER_HTML_PAUSE_TAG_KEY, paused)
    if (paused) {
        webView.onPause()
    } else {
        webView.onResume()
    }
    webView.evaluateJavascript(
        if (paused) BROWSER_HTML_PAUSE_JS else BROWSER_HTML_RESUME_JS,
        null,
    )
}

@Composable
private fun SafePlainTextBlockView(
    text: String,
) {
    SelectionContainer {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun StreamingTextBlockView(
    text: String,
    modifier: Modifier = Modifier,
) {
    val normalizedText = remember(text) {
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd()
            .ifBlank { " " }
    }

    Text(
        text = normalizedText,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun NativeHtmlFlowView(
    nodes: List<NativeHtmlNode>,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    nested: Boolean,
    modifier: Modifier = Modifier,
) {
    val flowItems = remember(nodes) {
        groupNativeHtmlFlowNodes(nodes)
    }

    if (flowItems.isEmpty()) {
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (nested) 6.dp else 8.dp),
    ) {
        flowItems.forEach { item ->
            when (item) {
                is NativeHtmlFlowItem.Inline -> NativeHtmlInlineTextBlock(
                    nodes = item.nodes,
                    styleSheet = styleSheet,
                    inheritedTextContext = inheritedTextContext,
                    baseTextStyle = MaterialTheme.typography.bodyLarge,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                )

                is NativeHtmlFlowItem.Block -> NativeHtmlNodeView(
                    node = item.node,
                    styleSheet = styleSheet,
                    inheritedTextContext = inheritedTextContext,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

@Composable
private fun NativeHtmlNodeView(
    node: NativeHtmlNode,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    when (node) {
        is NativeHtmlNode.Text -> NativeHtmlInlineTextBlock(
            nodes = listOf(node),
            styleSheet = styleSheet,
            inheritedTextContext = inheritedTextContext,
            baseTextStyle = MaterialTheme.typography.bodyLarge,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )

        is NativeHtmlNode.Element -> NativeHtmlElementView(
            element = node,
            styleSheet = styleSheet,
            inheritedTextContext = inheritedTextContext,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun NativeHtmlElementView(
    element: NativeHtmlNode.Element,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val style = remember(element, styleSheet) {
        resolveNativeHtmlStyle(element, styleSheet)
    }
    val nextTextContext = remember(inheritedTextContext, style) {
        inheritedTextContext.merge(style)
    }

    when (element.tagName) {
        "html", "body", "div", "section", "article", "main", "header", "footer", "nav", "aside", "figure", "figcaption" -> {
            NativeHtmlContainerView(
                element = element,
                style = style,
                styleSheet = styleSheet,
                inheritedTextContext = nextTextContext,
                onActionMessage = onActionMessage,
                onLongPress = onLongPress,
            )
        }

        "p" -> NativeHtmlParagraphView(
            children = element.children,
            style = style,
            styleSheet = styleSheet,
            inheritedTextContext = nextTextContext,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )

        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val headingStyle = when (element.tagName) {
                "h1" -> MaterialTheme.typography.headlineSmall
                "h2" -> MaterialTheme.typography.titleLarge
                "h3" -> MaterialTheme.typography.titleMedium
                "h4" -> MaterialTheme.typography.titleSmall
                "h5" -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            }
            NativeHtmlInlineTextBlock(
                nodes = element.children,
                styleSheet = styleSheet,
                inheritedTextContext = nextTextContext.copy(bold = true),
                baseTextStyle = headingStyle,
                onActionMessage = onActionMessage,
                onLongPress = onLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .nativeHtmlContainerStyle(style),
            )
        }

        "blockquote" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .nativeHtmlContainerStyle(style)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                        shape = RoundedCornerShape(style.borderRadius ?: 14.dp),
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = RoundedCornerShape(99.dp),
                        )
                        .height(24.dp),
                )
                NativeHtmlFlowView(
                    nodes = element.children,
                    styleSheet = styleSheet,
                    inheritedTextContext = nextTextContext,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    modifier = Modifier.weight(1f),
                    nested = true,
                )
            }
        }

        "pre" -> {
            val codeElement = element.children
                .filterIsInstance<NativeHtmlNode.Element>()
                .firstOrNull { it.tagName == "code" }
            val code = extractNativeHtmlTextContent(codeElement?.children ?: element.children, preserveWhitespace = true)
            val language = codeElement
                ?.attributes
                ?.get("class")
                ?.split(' ')
                ?.firstOrNull { it.startsWith("language-") }
                ?.removePrefix("language-")
            CodeFenceView(
                block = ChatRenderBlock.CodeFence(
                    language = language,
                    code = code.ifBlank { element.children.joinToString("") { nativeHtmlNodeToRawText(it) } },
                ),
            )
        }

        "ul", "ol" -> NativeHtmlListView(
            element = element,
            style = style,
            styleSheet = styleSheet,
            inheritedTextContext = nextTextContext,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )

        "table" -> NativeHtmlTableView(
            element = element,
            style = style,
            styleSheet = styleSheet,
            inheritedTextContext = nextTextContext,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
        )

        "img" -> extractNativeImageElement(element)?.let { image ->
            RemoteImageView(
                block = ChatRenderBlock.RemoteImage(
                    url = image.first,
                    alt = image.second,
                ),
            )
        }

        "button" -> NativeHtmlButtonView(
            element = element,
            onActionMessage = onActionMessage,
        )

        "hr" -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        )

        "br" -> Spacer(modifier = Modifier.height(4.dp))

        else -> {
            if (containsOnlyInlineNativeHtml(element.children)) {
                NativeHtmlInlineTextBlock(
                    nodes = element.children,
                    styleSheet = styleSheet,
                    inheritedTextContext = nextTextContext,
                    baseTextStyle = MaterialTheme.typography.bodyLarge,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .nativeHtmlContainerStyle(style),
                )
            } else {
                NativeHtmlContainerView(
                    element = element,
                    style = style,
                    styleSheet = styleSheet,
                    inheritedTextContext = nextTextContext,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

@Composable
private fun NativeHtmlContainerView(
    element: NativeHtmlNode.Element,
    style: NativeHtmlStyle,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .nativeHtmlContainerStyle(style)

    if (containsOnlyInlineNativeHtml(element.children)) {
        NativeHtmlInlineTextBlock(
            nodes = element.children,
            styleSheet = styleSheet,
            inheritedTextContext = inheritedTextContext,
            baseTextStyle = MaterialTheme.typography.bodyLarge,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            modifier = modifier,
        )
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NativeHtmlFlowView(
                nodes = element.children,
                styleSheet = styleSheet,
                inheritedTextContext = inheritedTextContext,
                onActionMessage = onActionMessage,
                onLongPress = onLongPress,
                nested = true,
            )
        }
    }
}

@Composable
private fun NativeHtmlParagraphView(
    children: List<NativeHtmlNode>,
    style: NativeHtmlStyle,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    if (containsOnlyInlineNativeHtml(children)) {
        NativeHtmlInlineTextBlock(
            nodes = children,
            styleSheet = styleSheet,
            inheritedTextContext = inheritedTextContext,
            baseTextStyle = MaterialTheme.typography.bodyLarge,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            modifier = Modifier
                .fillMaxWidth()
                .nativeHtmlContainerStyle(style),
            textAlign = style.textAlign,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nativeHtmlContainerStyle(style),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NativeHtmlFlowView(
            nodes = children,
            styleSheet = styleSheet,
            inheritedTextContext = inheritedTextContext,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            nested = true,
        )
    }
}

@Composable
private fun NativeHtmlListView(
    element: NativeHtmlNode.Element,
    style: NativeHtmlStyle,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    val items = remember(element) {
        extractNativeHtmlListItems(element)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nativeHtmlContainerStyle(style),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (element.tagName == "ol") "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 1.dp),
                )
                NativeHtmlFlowView(
                    nodes = item.children,
                    styleSheet = styleSheet,
                    inheritedTextContext = inheritedTextContext,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    modifier = Modifier.weight(1f),
                    nested = true,
                )
            }
        }
    }
}

@Composable
private fun NativeHtmlTableView(
    element: NativeHtmlNode.Element,
    style: NativeHtmlStyle,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)? = null,
) {
    val rows = remember(element) {
        collectNativeHtmlTableRows(element)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nativeHtmlContainerStyle(style)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                shape = RoundedCornerShape(style.borderRadius ?: 12.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            val cells = row.children.filterIsInstance<NativeHtmlNode.Element>()
                .filter { it.tagName == "td" || it.tagName == "th" }
            if (cells.isEmpty()) {
                return@forEachIndexed
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (rowIndex % 2 == 0) {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                cells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        NativeHtmlInlineTextBlock(
                            nodes = cell.children,
                            styleSheet = styleSheet,
                            inheritedTextContext = inheritedTextContext,
                            baseTextStyle = if (cell.tagName == "th") {
                                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            onActionMessage = onActionMessage,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeHtmlButtonView(
    element: NativeHtmlNode.Element,
    onActionMessage: ((String) -> Unit)?,
) {
    val sendText = extractNativeButtonAction(element)
    val label = extractNativeHtmlTextContent(element.children)
        .trim()
        .ifBlank { sendText }
        .ifBlank { "按钮" }

    OutlinedButton(
        onClick = {
            if (sendText.isNotBlank()) {
                onActionMessage?.invoke(buildActionMessagePayload(sendText))
            }
        },
        enabled = onActionMessage != null && sendText.isNotBlank(),
    ) {
        Text(text = label)
    }
}

@Composable
private fun NativeHtmlInlineTextBlock(
    nodes: List<NativeHtmlNode>,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    baseTextStyle: TextStyle,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val context = LocalContext.current
    val actionMessageState = rememberUpdatedState(onActionMessage)
    val longPressState = rememberUpdatedState(onLongPress)
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = remember(onSurfaceColor) { onSurfaceColor.toArgb() }
    val linkColor = remember(primaryColor) { primaryColor.toArgb() }
    val inlineCodeBackground = remember(surfaceVariantColor) { surfaceVariantColor.copy(alpha = 0.92f).toArgb() }
    val spannable = remember(
        nodes,
        styleSheet,
        inheritedTextContext,
        baseTextStyle,
        textColor,
        linkColor,
        inlineCodeBackground,
    ) {
        buildNativeHtmlSpannable(
            nodes = nodes,
            styleSheet = styleSheet,
            inheritedTextContext = inheritedTextContext,
            linkColor = linkColor,
            inlineCodeBackground = inlineCodeBackground,
            onLinkClick = { link ->
                handleNativeHtmlLink(
                    context = context,
                    link = link,
                    onActionMessage = actionMessageState.value,
                )
            },
        )
    }

    if (spannable.isBlank()) {
        return
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { viewContext ->
            TextView(viewContext).apply {
                setPadding(0, 0, 0, 0)
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                includeFontPadding = false
                highlightColor = android.graphics.Color.TRANSPARENT
                isLongClickable = longPressState.value != null
                setOnLongClickListener {
                    longPressState.value?.invoke()
                    longPressState.value != null
                }
            }
        },
        update = { textView ->
            applyTextStyle(textView, baseTextStyle)
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.isLongClickable = longPressState.value != null
            textView.setOnLongClickListener {
                longPressState.value?.invoke()
                longPressState.value != null
            }
            textView.gravity = when (textAlign ?: inheritedTextContext.textAlign) {
                TextAlign.Center -> android.view.Gravity.CENTER_HORIZONTAL
                TextAlign.End, TextAlign.Right -> android.view.Gravity.END
                else -> android.view.Gravity.START
            }
            textView.text = spannable
        },
    )
}

private fun parseNativeHtmlDocument(rawHtml: String): NativeHtmlDocumentModel {
    val root = MutableNativeHtmlElement(tagName = "root", attributes = emptyMap())
    val stack = ArrayDeque<MutableNativeHtmlElement>()
    stack.addLast(root)
    val tokenRegex = Regex("""(?is)<!--.*?-->|<!DOCTYPE[^>]*>|</?[a-zA-Z][\w:-]*(?:\s+[^<>]*?)?/?>""")
    var cursor = 0

    tokenRegex.findAll(rawHtml).forEach { match ->
        if (match.range.first > cursor) {
            stack.lastOrNull()?.children?.add(
                NativeHtmlNode.Text(
                    rawHtml.substring(cursor, match.range.first),
                ),
            )
        }

        val token = match.value
        when {
            token.startsWith("<!--") || token.startsWith("<!DOCTYPE", ignoreCase = true) -> Unit
            token.startsWith("</") -> {
                val tagName = token
                    .removePrefix("</")
                    .removeSuffix(">")
                    .trim()
                    .lowercase()
                while (stack.size > 1) {
                    val current = stack.removeLast()
                    if (current.tagName == tagName) {
                        break
                    }
                }
            }

            else -> {
                val tagName = extractNativeHtmlTagName(token) ?: return@forEach
                val attributes = parseNativeHtmlAttributes(token)
                val element = MutableNativeHtmlElement(
                    tagName = tagName,
                    attributes = attributes,
                )
                stack.lastOrNull()?.children?.add(
                    NativeHtmlNode.Element(
                        tagName = element.tagName,
                        attributes = element.attributes,
                        children = element.children,
                    ),
                )

                if (!isNativeHtmlSelfClosing(tagName, token)) {
                    stack.addLast(element)
                }
            }
        }

        cursor = match.range.last + 1
    }

    if (cursor < rawHtml.length) {
        stack.lastOrNull()?.children?.add(
            NativeHtmlNode.Text(rawHtml.substring(cursor)),
        )
    }

    val immutableRootNodes = materializeNativeHtmlNodes(root.children)
    val styleSheet = collectNativeCssRules(immutableRootNodes)
    val bodyNodes = findFirstNativeHtmlElement(immutableRootNodes, "body")?.children ?: immutableRootNodes
    return NativeHtmlDocumentModel(
        nodes = filterRenderableNativeHtmlNodes(bodyNodes),
        styleSheet = styleSheet,
    )
}

private fun materializeNativeHtmlNodes(nodes: List<NativeHtmlNode>): List<NativeHtmlNode> =
    nodes.map { node ->
        when (node) {
            is NativeHtmlNode.Text -> node
            is NativeHtmlNode.Element -> NativeHtmlNode.Element(
                tagName = node.tagName,
                attributes = node.attributes,
                children = materializeNativeHtmlNodes(node.children),
            )
        }
    }

private fun filterRenderableNativeHtmlNodes(nodes: List<NativeHtmlNode>): List<NativeHtmlNode> =
    nodes.mapNotNull { node ->
        when (node) {
            is NativeHtmlNode.Text -> node.takeIf {
                decodeNativeHtml(it.text).isNotBlank()
            }
            is NativeHtmlNode.Element -> {
                if (node.tagName in NATIVE_HTML_SKIP_TAGS) {
                    null
                } else {
                    node.copy(
                        children = filterRenderableNativeHtmlNodes(node.children),
                    )
                }
            }
        }
    }

private fun collectNativeCssRules(nodes: List<NativeHtmlNode>): List<NativeCssRule> {
    val rules = mutableListOf<NativeCssRule>()

    fun visit(node: NativeHtmlNode) {
        when (node) {
            is NativeHtmlNode.Text -> Unit
            is NativeHtmlNode.Element -> {
                if (node.tagName == "style") {
                    rules += parseNativeCssRules(
                        extractNativeHtmlTextContent(node.children, preserveWhitespace = true),
                    )
                }
                node.children.forEach(::visit)
            }
        }
    }

    nodes.forEach(::visit)
    return rules
}

private fun parseNativeCssRules(styleContent: String): List<NativeCssRule> {
    val cleaned = styleContent.replace(Regex("""(?s)/\*.*?\*/"""), "")
    val blockRegex = Regex("""(?s)([^{}]+)\{([^}]*)\}""")
    val rules = mutableListOf<NativeCssRule>()
    blockRegex.findAll(cleaned).forEach { match ->
        val selectorBlock = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val declarations = parseNativeCssDeclarations(match.groupValues.getOrNull(2).orEmpty())
        if (declarations.isEmpty()) {
            return@forEach
        }
        selectorBlock.split(',').forEach { rawSelector ->
            parseNativeCssSelector(rawSelector.trim())?.let { selector ->
                rules += NativeCssRule(
                    selector = selector,
                    declarations = declarations,
                )
            }
        }
    }
    return rules
}

private fun parseNativeCssSelector(selector: String): NativeCssSelector? {
    if (selector.isBlank() || selector.contains(' ') || selector.contains('>') || selector.contains(':')) {
        return null
    }
    return when {
        selector.startsWith(".") -> selector.removePrefix(".")
            .takeIf { it.isNotBlank() }
            ?.let(NativeCssSelector::Class)

        selector.startsWith("#") -> selector.removePrefix("#")
            .takeIf { it.isNotBlank() }
            ?.let(NativeCssSelector::Id)

        selector.matches(Regex("""[a-zA-Z][\w-]*""")) -> NativeCssSelector.Tag(selector.lowercase())
        else -> null
    }
}

private fun resolveNativeHtmlStyle(
    element: NativeHtmlNode.Element,
    styleSheet: List<NativeCssRule>,
): NativeHtmlStyle {
    val mergedDeclarations = linkedMapOf<String, String>()

    styleSheet.forEach { rule ->
        val matches = when (val selector = rule.selector) {
            is NativeCssSelector.Tag -> selector.name == element.tagName
            is NativeCssSelector.Class -> {
                val classes = element.attributes["class"]
                    ?.split(Regex("""\s+"""))
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                selector.name in classes
            }

            is NativeCssSelector.Id -> selector.name == element.attributes["id"]
        }

        if (matches) {
            mergedDeclarations.putAll(rule.declarations)
        }
    }

    mergedDeclarations.putAll(
        parseNativeCssDeclarations(element.attributes["style"].orEmpty()),
    )

    val backgroundColor = parseNativeCssColor(
        mergedDeclarations["background-color"]
            ?: mergedDeclarations["background"]?.takeIf(::looksLikeSimpleNativeColor),
    )
    val borderInfo = parseNativeBorder(
        mergedDeclarations["border"],
        mergedDeclarations["border-width"],
        mergedDeclarations["border-color"],
    )

    return NativeHtmlStyle(
        textColor = parseNativeCssColor(mergedDeclarations["color"]),
        backgroundColor = backgroundColor,
        padding = resolveNativeEdgeInsets(mergedDeclarations, "padding"),
        margin = resolveNativeEdgeInsets(mergedDeclarations, "margin"),
        borderColor = borderInfo?.first,
        borderWidth = borderInfo?.second,
        borderRadius = parseNativeCssSizeToDp(mergedDeclarations["border-radius"]),
        fontSizeSp = parseNativeCssSizeToSp(mergedDeclarations["font-size"]),
        bold = parseNativeCssFontWeight(mergedDeclarations["font-weight"]),
        italic = parseNativeCssFontStyle(mergedDeclarations["font-style"]),
        underline = mergedDeclarations["text-decoration"]?.contains("underline", ignoreCase = true) == true,
        lineThrough = mergedDeclarations["text-decoration"]?.contains("line-through", ignoreCase = true) == true ||
            mergedDeclarations["text-decoration"]?.contains("strike", ignoreCase = true) == true,
        textAlign = parseNativeCssTextAlign(mergedDeclarations["text-align"] ?: element.attributes["align"]),
        monospace = mergedDeclarations["font-family"]?.contains("mono", ignoreCase = true) == true,
    )
}

private fun groupNativeHtmlFlowNodes(nodes: List<NativeHtmlNode>): List<NativeHtmlFlowItem> {
    val items = mutableListOf<NativeHtmlFlowItem>()
    val inlineBuffer = mutableListOf<NativeHtmlNode>()

    fun flushInline() {
        if (inlineBuffer.isEmpty()) {
            return
        }
        items += NativeHtmlFlowItem.Inline(nodes = inlineBuffer.toList())
        inlineBuffer.clear()
    }

    nodes.forEach { node ->
        if (node is NativeHtmlNode.Text) {
            inlineBuffer += node
            return@forEach
        }

        if (node is NativeHtmlNode.Element && isNativeHtmlBlockElement(node.tagName)) {
            flushInline()
            items += NativeHtmlFlowItem.Block(node)
        } else {
            inlineBuffer += node
        }
    }

    flushInline()
    return items.filterNot { item ->
        item is NativeHtmlFlowItem.Inline && item.nodes.all { inlineNode ->
            inlineNode is NativeHtmlNode.Text && inlineNode.text.isBlank()
        }
    }
}

private fun containsOnlyInlineNativeHtml(nodes: List<NativeHtmlNode>): Boolean =
    nodes.none { node ->
        node is NativeHtmlNode.Element && isNativeHtmlBlockElement(node.tagName)
    }

private fun buildNativeHtmlSpannable(
    nodes: List<NativeHtmlNode>,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    linkColor: Int,
    inlineCodeBackground: Int,
    onLinkClick: (String) -> Unit,
): SpannableStringBuilder {
    val builder = SpannableStringBuilder()
    appendNativeHtmlNodesToSpannable(
        target = builder,
        nodes = nodes,
        styleSheet = styleSheet,
        inheritedTextContext = inheritedTextContext,
        linkColor = linkColor,
        inlineCodeBackground = inlineCodeBackground,
        onLinkClick = onLinkClick,
    )
    while (builder.isNotEmpty() && builder.last().isWhitespace()) {
        builder.delete(builder.length - 1, builder.length)
    }
    return builder
}

private fun appendNativeHtmlNodesToSpannable(
    target: SpannableStringBuilder,
    nodes: List<NativeHtmlNode>,
    styleSheet: List<NativeCssRule>,
    inheritedTextContext: NativeHtmlTextContext,
    linkColor: Int,
    inlineCodeBackground: Int,
    onLinkClick: (String) -> Unit,
) {
    nodes.forEach { node ->
        when (node) {
            is NativeHtmlNode.Text -> appendStyledNativeHtmlText(
                target = target,
                rawText = node.text,
                context = inheritedTextContext,
                linkColor = linkColor,
                onLinkClick = onLinkClick,
            )

            is NativeHtmlNode.Element -> {
                when (node.tagName) {
                    "br" -> {
                        if (target.isNotEmpty() && target.last() != '\n') {
                            target.append('\n')
                        }
                    }

                    "img" -> {
                        val image = extractNativeImageElement(node) ?: return@forEach
                        val label = image.second?.ifBlank { null } ?: "图片"
                        val imageContext = inheritedTextContext.withLink(image.first).copy(
                            color = linkColor,
                            underline = true,
                        )
                        appendStyledNativeHtmlText(
                            target = target,
                            rawText = "[${label}]",
                            context = imageContext,
                            linkColor = linkColor,
                            onLinkClick = onLinkClick,
                        )
                    }

                    "button" -> {
                        val label = extractNativeHtmlTextContent(node.children).trim().ifBlank { "按钮" }
                        val action = extractNativeButtonAction(node)
                        val buttonContext = inheritedTextContext.withLink(
                            if (action.isNotBlank()) {
                                Uri.Builder()
                                    .scheme(VCP_NATIVE_ACTION_SCHEME)
                                    .authority("action")
                                    .appendQueryParameter(VCP_NATIVE_ACTION_QUERY, action)
                                    .build()
                                    .toString()
                            } else {
                                null
                            },
                        ).copy(
                            bold = true,
                            underline = true,
                            color = linkColor,
                            backgroundColor = inlineCodeBackground,
                        )
                        appendStyledNativeHtmlText(
                            target = target,
                            rawText = "[$label]",
                            context = buttonContext,
                            linkColor = linkColor,
                            onLinkClick = onLinkClick,
                        )
                    }

                    else -> {
                        val tagContext = inheritedTextContext.merge(resolveNativeHtmlStyle(node, styleSheet))
                        val semanticContext = when (node.tagName) {
                            "strong", "b" -> tagContext.copy(bold = true)
                            "em", "i", "cite", "dfn" -> tagContext.copy(italic = true)
                            "u", "ins" -> tagContext.copy(underline = true)
                            "s", "del", "strike" -> tagContext.copy(lineThrough = true)
                            "code" -> tagContext.copy(
                                monospace = true,
                                backgroundColor = inlineCodeBackground,
                            )
                            "small" -> tagContext.copy(relativeSize = tagContext.relativeSize * 0.86f)
                            "sup" -> tagContext.copy(
                                superscript = true,
                                relativeSize = tagContext.relativeSize * 0.82f,
                            )
                            "sub" -> tagContext.copy(
                                subscript = true,
                                relativeSize = tagContext.relativeSize * 0.82f,
                            )
                            "a" -> tagContext.withLink(node.attributes["href"]).copy(
                                color = tagContext.color ?: linkColor,
                                underline = true,
                            )
                            "mark" -> tagContext.copy(
                                backgroundColor = parseNativeCssColor("#fff59d")?.toArgb(),
                            )
                            "font" -> {
                                val color = parseNativeCssColor(node.attributes["color"])?.toArgb()
                                val size = parseNativeCssSizeToSp(node.attributes["size"])
                                tagContext.copy(
                                    color = color ?: tagContext.color,
                                    fontSizeSp = size ?: tagContext.fontSizeSp,
                                )
                            }
                            else -> tagContext
                        }

                        appendNativeHtmlNodesToSpannable(
                            target = target,
                            nodes = node.children,
                            styleSheet = styleSheet,
                            inheritedTextContext = semanticContext,
                            linkColor = linkColor,
                            inlineCodeBackground = inlineCodeBackground,
                            onLinkClick = onLinkClick,
                        )
                    }
                }
            }
        }
    }
}

private fun appendStyledNativeHtmlText(
    target: SpannableStringBuilder,
    rawText: String,
    context: NativeHtmlTextContext,
    linkColor: Int,
    onLinkClick: (String) -> Unit,
) {
    var text = decodeNativeHtml(rawText)
        .replace(Regex("""\s+"""), " ")
    if (target.isEmpty()) {
        text = text.trimStart()
    } else if (target.last().isWhitespace() && text.startsWith(" ")) {
        text = text.trimStart()
    }
    if (text.isEmpty()) {
        return
    }

    val start = target.length
    target.append(text)
    val end = target.length

    context.color?.let {
        target.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    context.backgroundColor?.let {
        target.setSpan(BackgroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    context.fontSizeSp?.let {
        target.setSpan(AbsoluteSizeSpan(it.toInt(), true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (context.relativeSize != 1f) {
        target.setSpan(RelativeSizeSpan(context.relativeSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    when {
        context.bold && context.italic -> {
            target.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        context.bold -> {
            target.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        context.italic -> {
            target.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    if (context.underline) {
        target.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (context.lineThrough) {
        target.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (context.monospace) {
        target.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (context.superscript) {
        target.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (context.subscript) {
        target.setSpan(SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    context.link?.takeIf { it.isNotBlank() }?.let { link ->
        target.setSpan(
            NativeHtmlClickableSpan(
                link = link,
                linkColor = linkColor,
                onLinkClick = onLinkClick,
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private fun handleNativeHtmlLink(
    context: android.content.Context,
    link: String,
    onActionMessage: ((String) -> Unit)?,
) {
    val uri = runCatching { Uri.parse(link) }.getOrNull()
    if (uri?.scheme == VCP_NATIVE_ACTION_SCHEME) {
        val actionText = uri.getQueryParameter(VCP_NATIVE_ACTION_QUERY)
            ?.trim()
            .orEmpty()
        if (actionText.isNotBlank()) {
            onActionMessage?.invoke(buildActionMessagePayload(actionText))
        }
        return
    }

    val intentUri = uri ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, intentUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun Modifier.nativeHtmlContainerStyle(style: NativeHtmlStyle): Modifier {
    var result = this
    style.margin?.let { margin ->
        result = result.padding(
            start = margin.start,
            top = margin.top,
            end = margin.end,
            bottom = margin.bottom,
        )
    }

    val shape = RoundedCornerShape(style.borderRadius ?: 0.dp)
    if (style.backgroundColor != null) {
        result = result.background(style.backgroundColor, shape)
    }
    if (style.borderColor != null && style.borderWidth != null && style.borderWidth > 0.dp) {
        result = result.border(style.borderWidth, style.borderColor, shape)
    }
    style.padding?.let { padding ->
        result = result.padding(
            start = padding.start,
            top = padding.top,
            end = padding.end,
            bottom = padding.bottom,
        )
    }
    return result
}

private fun extractNativeHtmlTagName(token: String): String? =
    Regex("""^<\s*([a-zA-Z][\w:-]*)""")
        .find(token)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()

private fun parseNativeHtmlAttributes(token: String): Map<String, String> {
    val attributes = linkedMapOf<String, String>()
    val attrRegex = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
    attrRegex.findAll(token).forEach { match ->
        val key = match.groupValues.getOrNull(1)?.lowercase().orEmpty()
        val value = match.groupValues.getOrNull(3)
            ?.ifBlank { null }
            ?: match.groupValues.getOrNull(4)
            ?.ifBlank { null }
            ?: match.groupValues.getOrNull(5)
            ?.ifBlank { null }
            ?: ""
        if (key.isNotBlank()) {
            attributes[key] = decodeNativeHtml(value)
        }
    }
    return attributes
}

private fun isNativeHtmlSelfClosing(
    tagName: String,
    token: String,
): Boolean = token.endsWith("/>") || tagName in NATIVE_HTML_VOID_TAGS

private fun findFirstNativeHtmlElement(
    nodes: List<NativeHtmlNode>,
    tagName: String,
): NativeHtmlNode.Element? {
    nodes.forEach { node ->
        when (node) {
            is NativeHtmlNode.Text -> Unit
            is NativeHtmlNode.Element -> {
                if (node.tagName == tagName) {
                    return node
                }
                findFirstNativeHtmlElement(node.children, tagName)?.let { return it }
            }
        }
    }
    return null
}

private fun parseNativeCssDeclarations(style: String): Map<String, String> =
    style.split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf(':')
            if (separator == -1) {
                null
            } else {
                val name = entry.substring(0, separator).trim().lowercase()
                val value = entry.substring(separator + 1).trim()
                if (name.isBlank() || value.isBlank()) {
                    null
                } else {
                    name to value
                }
            }
        }
        .toMap(linkedMapOf())

private fun resolveNativeEdgeInsets(
    declarations: Map<String, String>,
    prefix: String,
): NativeHtmlEdgeInsets? {
    val shorthand = declarations[prefix]
    val boxValues = shorthand?.let(::parseNativeCssBoxValues)
    val top = parseNativeCssSizeToDp(declarations["$prefix-top"] ?: boxValues?.top)
    val end = parseNativeCssSizeToDp(declarations["$prefix-right"] ?: boxValues?.end)
    val bottom = parseNativeCssSizeToDp(declarations["$prefix-bottom"] ?: boxValues?.bottom)
    val start = parseNativeCssSizeToDp(declarations["$prefix-left"] ?: boxValues?.start)

    return if (top == null && end == null && bottom == null && start == null) {
        null
    } else {
        NativeHtmlEdgeInsets(
            top = top ?: 0.dp,
            end = end ?: 0.dp,
            bottom = bottom ?: 0.dp,
            start = start ?: 0.dp,
        )
    }
}

private data class NativeCssBoxValues(
    val top: String,
    val end: String,
    val bottom: String,
    val start: String,
)

private fun parseNativeCssBoxValues(value: String): NativeCssBoxValues? {
    val parts = value.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (parts.isEmpty()) {
        return null
    }

    return when (parts.size) {
        1 -> NativeCssBoxValues(parts[0], parts[0], parts[0], parts[0])
        2 -> NativeCssBoxValues(parts[0], parts[1], parts[0], parts[1])
        3 -> NativeCssBoxValues(parts[0], parts[1], parts[2], parts[1])
        else -> NativeCssBoxValues(parts[0], parts[1], parts[2], parts[3])
    }
}

private fun parseNativeBorder(
    shorthand: String?,
    widthValue: String?,
    colorValue: String?,
): Pair<Color, androidx.compose.ui.unit.Dp>? {
    val tokens = shorthand
        ?.split(Regex("""\s+"""))
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val width = parseNativeCssSizeToDp(widthValue ?: tokens.firstOrNull { it.contains("px") || it.contains("dp") || it == "1" || it == "0" })
    val color = parseNativeCssColor(colorValue ?: tokens.lastOrNull(::looksLikeSimpleNativeColor))

    return if (width != null && color != null) {
        color to width
    } else {
        null
    }
}

private fun parseNativeCssFontWeight(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) {
        return false
    }
    return normalized == "bold" || normalized.toIntOrNull()?.let { it >= 600 } == true
}

private fun parseNativeCssFontStyle(value: String?): Boolean =
    value?.contains("italic", ignoreCase = true) == true ||
        value?.contains("oblique", ignoreCase = true) == true

private fun parseNativeCssTextAlign(value: String?): TextAlign? =
    when (value?.trim()?.lowercase()) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "left", "start" -> TextAlign.Start
        else -> null
    }

private fun parseNativeCssSizeToDp(value: String?): androidx.compose.ui.unit.Dp? {
    val trimmed = value?.trim()?.lowercase().orEmpty()
    if (trimmed.isBlank()) {
        return null
    }
    val numeric = Regex("""-?\d+(?:\.\d+)?""")
        .find(trimmed)
        ?.value
        ?.toFloatOrNull()
        ?: return null
    return numeric.dp
}

private fun parseNativeCssSizeToSp(value: String?): Float? {
    val trimmed = value?.trim()?.lowercase().orEmpty()
    if (trimmed.isBlank()) {
        return null
    }
    return Regex("""-?\d+(?:\.\d+)?""")
        .find(trimmed)
        ?.value
        ?.toFloatOrNull()
}

private fun parseNativeCssColor(value: String?): Color? {
    val trimmed = value?.trim()?.lowercase().orEmpty()
    if (trimmed.isBlank()) {
        return null
    }

    if (trimmed == "transparent") {
        return Color.Transparent
    }

    Regex("""#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})""", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { hex ->
            return when (hex.length) {
                3 -> {
                    val r = "${hex[0]}${hex[0]}".toInt(16)
                    val g = "${hex[1]}${hex[1]}".toInt(16)
                    val b = "${hex[2]}${hex[2]}".toInt(16)
                    Color(r, g, b)
                }

                4 -> {
                    val r = "${hex[0]}${hex[0]}".toInt(16)
                    val g = "${hex[1]}${hex[1]}".toInt(16)
                    val b = "${hex[2]}${hex[2]}".toInt(16)
                    val a = "${hex[3]}${hex[3]}".toInt(16)
                    Color(r, g, b, a)
                }

                6 -> {
                    val colorLong = hex.toLong(16)
                    Color(
                        red = ((colorLong shr 16) and 0xFF).toInt(),
                        green = ((colorLong shr 8) and 0xFF).toInt(),
                        blue = (colorLong and 0xFF).toInt(),
                    )
                }

                else -> {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    val a = hex.substring(6, 8).toInt(16)
                    Color(r, g, b, a)
                }
            }
        }

    Regex("""rgba?\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.split(',')
        ?.map { it.trim() }
        ?.let { parts ->
            if (parts.size == 3 || parts.size == 4) {
                val red = parts[0].toFloatOrNull()?.toInt() ?: return@let null
                val green = parts[1].toFloatOrNull()?.toInt() ?: return@let null
                val blue = parts[2].toFloatOrNull()?.toInt() ?: return@let null
                val alpha = if (parts.size == 4) {
                    val rawAlpha = parts[3].removeSuffix("%").toFloatOrNull() ?: 1f
                    if (parts[3].endsWith("%")) {
                        (rawAlpha / 100f * 255f).toInt()
                    } else {
                        (rawAlpha.coerceIn(0f, 1f) * 255f).toInt()
                    }
                } else {
                    255
                }
                return Color(red, green, blue, alpha)
            }
        }

    return NATIVE_HTML_NAMED_COLORS[trimmed]
}

private fun looksLikeSimpleNativeColor(value: String): Boolean =
    parseNativeCssColor(value) != null

private fun extractNativeHtmlListItems(element: NativeHtmlNode.Element): List<NativeHtmlNode.Element> =
    element.children.filterIsInstance<NativeHtmlNode.Element>()
        .flatMap { child ->
            when (child.tagName) {
                "li" -> listOf(child)
                "ul", "ol" -> extractNativeHtmlListItems(child)
                else -> emptyList()
            }
        }

private fun collectNativeHtmlTableRows(element: NativeHtmlNode.Element): List<NativeHtmlNode.Element> =
    element.children.filterIsInstance<NativeHtmlNode.Element>()
        .flatMap { child ->
            when (child.tagName) {
                "tr" -> listOf(child)
                "thead", "tbody", "tfoot" -> collectNativeHtmlTableRows(child)
                else -> emptyList()
            }
        }

private fun extractNativeHtmlTextContent(
    nodes: List<NativeHtmlNode>,
    preserveWhitespace: Boolean = false,
): String {
    val builder = StringBuilder()
    nodes.forEach { node ->
        when (node) {
            is NativeHtmlNode.Text -> builder.append(
                if (preserveWhitespace) {
                    decodeNativeHtml(node.text)
                } else {
                    decodeNativeHtml(node.text).replace(Regex("""\s+"""), " ")
                },
            )

            is NativeHtmlNode.Element -> {
                if (node.tagName == "br") {
                    builder.append('\n')
                } else {
                    builder.append(extractNativeHtmlTextContent(node.children, preserveWhitespace))
                }
            }
        }
    }
    return builder.toString()
}

private fun extractNativeImageElement(element: NativeHtmlNode.Element): Pair<String, String?>? {
    val src = element.attributes["src"]?.trim().orEmpty()
    if (src.isBlank()) {
        return null
    }
    return src to element.attributes["alt"]?.trim()?.ifBlank { null }
}

private fun extractNativeButtonAction(element: NativeHtmlNode.Element): String {
    return element.attributes["data-send"]
        ?.trim()
        ?.ifBlank { null }
        ?: extractNativeOnclickInputText(element.attributes["onclick"])
        ?: extractNativeHtmlTextContent(element.children).trim()
}

private fun nativeHtmlNodeToRawText(node: NativeHtmlNode): String =
    when (node) {
        is NativeHtmlNode.Text -> node.text
        is NativeHtmlNode.Element -> node.children.joinToString(separator = "") { nativeHtmlNodeToRawText(it) }
    }

private fun isNativeHtmlBlockElement(tagName: String): Boolean =
    tagName in NATIVE_HTML_BLOCK_TAGS || tagName in NATIVE_HTML_SPECIAL_BLOCK_TAGS

private val NATIVE_HTML_BLOCK_TAGS = setOf(
    "html",
    "body",
    "div",
    "section",
    "article",
    "main",
    "header",
    "footer",
    "nav",
    "aside",
    "figure",
    "figcaption",
    "p",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "blockquote",
    "pre",
    "ul",
    "ol",
    "li",
    "table",
    "thead",
    "tbody",
    "tfoot",
    "tr",
    "td",
    "th",
    "hr",
)

private val NATIVE_HTML_SPECIAL_BLOCK_TAGS = setOf(
    "img",
    "button",
)

private val NATIVE_HTML_VOID_TAGS = setOf(
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
)

private val NATIVE_HTML_SKIP_TAGS = setOf(
    "head",
    "style",
    "script",
    "meta",
    "link",
)

private val NATIVE_HTML_NAMED_COLORS = mapOf(
    "black" to Color.Black,
    "white" to Color.White,
    "red" to Color(0xFFFF0000),
    "green" to Color(0xFF008000),
    "blue" to Color(0xFF0000FF),
    "yellow" to Color(0xFFFFFF00),
    "orange" to Color(0xFFFFA500),
    "purple" to Color(0xFF800080),
    "pink" to Color(0xFFFFC0CB),
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
    "silver" to Color(0xFFC0C0C0),
    "brown" to Color(0xFFA52A2A),
    "cyan" to Color(0xFF00FFFF),
    "magenta" to Color(0xFFFF00FF),
)

private class NativeHtmlClickableSpan(
    private val link: String,
    private val linkColor: Int,
    private val onLinkClick: (String) -> Unit,
) : ClickableSpan() {
    override fun onClick(widget: View) {
        onLinkClick(link)
    }

    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.color = linkColor
        ds.isUnderlineText = true
    }
}

@Composable
private fun NativeMarkdownBlockView(
    text: String,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actionMessageState = rememberUpdatedState(onActionMessage)
    val longPressState = rememberUpdatedState(onLongPress)
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val textColor = remember(onSurfaceColor) { onSurfaceColor.toArgb() }
    val linkColor = remember(primaryColor) { primaryColor.toArgb() }
    val normalizedText = remember(text) {
        normalizeNativeMarkdownInput(text)
    }
    val markwon = remember(context, textColor, linkColor) {
        createNativeMarkwon(
            context = context,
            textColor = textColor,
            linkColor = linkColor,
            onActionMessage = { rawText ->
                actionMessageState.value?.invoke(buildActionMessagePayload(rawText))
            },
        )
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { viewContext ->
            TextView(viewContext).apply {
                setPadding(0, 0, 0, 0)
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                includeFontPadding = false
                isLongClickable = longPressState.value != null
                setOnLongClickListener {
                    longPressState.value?.invoke()
                    longPressState.value != null
                }
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.isLongClickable = longPressState.value != null
            textView.setOnLongClickListener {
                longPressState.value?.invoke()
                longPressState.value != null
            }
            applyTextStyle(textView, bodyStyle)
            markwon.setMarkdown(textView, normalizedText)
        },
    )
}

private fun createNativeMarkwon(
    context: android.content.Context,
    textColor: Int,
    linkColor: Int,
    onActionMessage: (String) -> Unit,
): Markwon =
    Markwon.builder(context)
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(TablePlugin.create(context))
        .usePlugin(ImagesPlugin.create())
        .usePlugin(HtmlPlugin.create())
        .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
        .usePlugin(
            object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(
                        object : LinkResolver {
                            override fun resolve(view: android.view.View, link: String) {
                                val uri = runCatching { Uri.parse(link) }.getOrNull()
                                if (uri?.scheme == VCP_NATIVE_ACTION_SCHEME) {
                                    val actionText = uri.getQueryParameter(VCP_NATIVE_ACTION_QUERY)
                                        ?.trim()
                                        .orEmpty()
                                    if (actionText.isNotBlank()) {
                                        onActionMessage(actionText)
                                    }
                                    return
                                }

                                val intentUri = uri ?: return
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, intentUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        },
                    )
                }
            },
        )
        .build()

private fun applyTextStyle(
    textView: TextView,
    style: TextStyle,
) {
    if (style.fontSize.isSpecified) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
    }
}

private fun normalizeNativeMarkdownInput(rawText: String): String {
    if (rawText.isBlank() || '<' !in rawText) {
        return rawText
    }

    val protectedBlocks = mutableListOf<String>()
    var normalized = Regex("""(?s)```.*?```""").replace(rawText) { match ->
        val token = "__VCP_NATIVE_CODE_BLOCK_${protectedBlocks.size}__"
        protectedBlocks += match.value
        token
    }

    normalized = extractHtmlBodyContent(normalized)
    normalized = normalized
        .replace(Regex("""(?is)<!DOCTYPE[^>]*>"""), "")
        .replace(Regex("""(?is)<head\b[^>]*>.*?</head>"""), "")
        .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), "")
        .replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), "")
        .replace(Regex("""(?is)</?(html|body)\b[^>]*>"""), "")
        .replace(Regex("""(?is)<br\s*/?>"""), "<br />\n")
        .replace(Regex("""(?is)</?(div|p|section|article|header|footer|nav|aside|main|figure|figcaption)\b[^>]*>"""), "\n")
        .replace(Regex("""(?is)</?span\b[^>]*>"""), "")

    normalized = NATIVE_HTML_BUTTON_REGEX.replace(normalized) { match ->
        buildNativeActionAnchor(
            attributes = match.groups[1]?.value.orEmpty(),
            rawLabel = match.groups[2]?.value.orEmpty(),
        )
    }

    normalized = normalized
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    protectedBlocks.forEachIndexed { index, block ->
        normalized = normalized.replace("__VCP_NATIVE_CODE_BLOCK_${index}__", block)
    }

    return normalized
}

internal fun shouldUseBrowserHtmlRenderer(content: String): Boolean {
    if ('<' !in content) {
        return false
    }

    return BROWSER_HTML_ADVANCED_TAGS_REGEX.containsMatchIn(content) ||
        BROWSER_HTML_EVENT_HANDLER_REGEX.containsMatchIn(content) ||
        BROWSER_HTML_CSS_FEATURES_REGEX.containsMatchIn(content) ||
        BROWSER_HTML_CSS_VAR_REGEX.containsMatchIn(content)
}

private fun shouldRenderMessageInSafeMode(content: String): Boolean {
    if (content.length > 120_000) {
        return true
    }

    if (content.lines().size > 6_000) {
        return true
    }

    val htmlTagCount = SAFE_MODE_HTML_TAG_REGEX.findAll(content).take(2_001).count()
    return htmlTagCount > 2_000
}

private fun buildSafeRenderPreview(content: String): String {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val maxChars = 8_000
    return if (normalized.length <= maxChars) {
        normalized
    } else {
        buildString {
            append(normalized.take(maxChars))
            append("\n\n[内容过长，已启用安全模式截断显示。长按气泡可复制完整原文。]")
        }
    }
}

private fun shouldFallbackToSafeHtmlRenderer(rawHtml: String): Boolean {
    if (rawHtml.isBlank()) {
        return false
    }

    val length = rawHtml.length
    if (length > 120_000) {
        return true
    }

    val tagCount = Regex("""</?[a-zA-Z][^>]*>""").findAll(rawHtml).take(2_001).count()
    if (tagCount > 2_000) {
        return true
    }

    val styleBytes = Regex("""(?is)<style\b[^>]*>(.*?)</style>""")
        .findAll(rawHtml)
        .map { it.groupValues.getOrElse(1) { "" }.length }
        .sum()
    if (styleBytes > 50_000) {
        return true
    }

    val suspiciousDepth = estimateNativeHtmlNestingDepth(rawHtml)
    return suspiciousDepth > 256
}

private fun isProbablyTruncatedBrowserHtml(rawHtml: String): Boolean {
    if (rawHtml.isBlank()) {
        return false
    }

    val trimmed = rawHtml.trimEnd()
    val lastTagStart = trimmed.lastIndexOf('<')
    val lastTagEnd = trimmed.lastIndexOf('>')
    return lastTagStart > lastTagEnd
}

private fun repairBrowserHtmlForRender(rawHtml: String): String {
    val trimmed = trimDanglingTrailingHtmlFragment(rawHtml)
    return appendMissingClosingTags(trimmed)
}

private fun trimDanglingTrailingHtmlFragment(rawHtml: String): String {
    val trimmed = rawHtml.trimEnd()
    val lastTagStart = trimmed.lastIndexOf('<')
    val lastTagEnd = trimmed.lastIndexOf('>')
    return if (lastTagStart > lastTagEnd) {
        trimmed.substring(0, lastTagStart).trimEnd()
    } else {
        trimmed
    }
}

private fun appendMissingClosingTags(rawHtml: String): String {
    if (rawHtml.isBlank()) {
        return rawHtml
    }

    val tokenRegex = Regex("""(?is)<!--[\s\S]*?-->|<!DOCTYPE[^>]*>|</?([a-zA-Z][\w:-]*)\b[^>]*?>""")
    val openTags = ArrayDeque<String>()

    tokenRegex.findAll(rawHtml).forEach { match ->
        val token = match.value
        val tagName = match.groups[1]?.value?.lowercase() ?: return@forEach
        when {
            token.startsWith("</") -> removeLastMatchingTag(openTags, tagName)
            token.endsWith("/>") || tagName in BROWSER_HTML_VOID_TAGS -> Unit
            else -> openTags.addLast(tagName)
        }
    }

    if (openTags.isEmpty()) {
        return rawHtml
    }

    return buildString(rawHtml.length + openTags.size * 8) {
        append(rawHtml)
        openTags.reversed().forEach { tagName ->
            append("</")
            append(tagName)
            append('>')
        }
    }
}

private fun removeLastMatchingTag(
    deque: ArrayDeque<String>,
    value: String,
) {
    if (deque.isEmpty()) {
        return
    }

    val buffer = mutableListOf<String>()
    var removed = false

    while (deque.isNotEmpty()) {
        val current = deque.removeLast()
        if (!removed && current == value) {
            removed = true
            break
        }
        buffer += current
    }

    for (index in buffer.indices.reversed()) {
        deque.addLast(buffer[index])
    }
}

/**
 * Estimate a reasonable initial height (in Dp) for browser HTML content so the
 * WebView does not flash as a tiny 32-48 dp box while waiting for the JS bridge
 * to report the real height.  The estimate does not need to be precise — it just
 * needs to be in the right ballpark so the LazyColumn item does not visually
 * collapse during recycling.
 */
private fun estimateBrowserHtmlHeightDp(html: String): androidx.compose.ui.unit.Dp {
    val len = html.length
    return when {
        len < 120 -> 80.dp
        len < 500 -> 160.dp
        len < 2000 -> 300.dp
        len < 6000 -> 420.dp
        else -> 560.dp
    }
}

private fun buildBrowserHtmlDocument(rawHtml: String): String {
    val headContent = extractHtmlHeadContent(rawHtml)
    val bodyContent = if (Regex("""(?is)<body\b""").containsMatchIn(rawHtml)) {
        extractHtmlBodyContent(rawHtml)
    } else {
        rawHtml
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta
                name="viewport"
                content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"
            />
            $headContent
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    background: transparent;
                    overflow-x: hidden;
                }
                * {
                    box-sizing: border-box;
                    max-width: 100%;
                }
                img, svg, canvas, iframe, video {
                    max-width: 100%;
                    height: auto;
                }
                button {
                    -webkit-tap-highlight-color: transparent;
                }
                .vcp-scroll-paused,
                .vcp-scroll-paused * {
                    animation-play-state: paused !important;
                    transition-duration: 0s !important;
                    transition-delay: 0s !important;
                    scroll-behavior: auto !important;
                }
            </style>
            <script>
                (function() {
                    window.input = function(text) {
                        try {
                            location.href = '${VCP_NATIVE_ACTION_SCHEME}://action?${VCP_NATIVE_ACTION_QUERY}=' + encodeURIComponent(text || '');
                        } catch (error) {}
                    };

                    var lastPostedHeight = 0;
                    var heightScheduled = false;

                    function readHeight() {
                        var root = document.documentElement;
                        var body = document.body;
                        return Math.ceil(Math.max(
                            body ? body.scrollHeight : 0,
                            body ? body.offsetHeight : 0,
                            root ? root.scrollHeight : 0,
                            root ? root.offsetHeight : 0
                        ));
                    }

                    function postHeightNow() {
                        try {
                            var pxHeight = readHeight();
                            if (!pxHeight || Math.abs(pxHeight - lastPostedHeight) < 2) {
                                return;
                            }
                            lastPostedHeight = pxHeight;
                            if (window.${BROWSER_HTML_BRIDGE_NAME} && window.${BROWSER_HTML_BRIDGE_NAME}.postHeight) {
                                window.${BROWSER_HTML_BRIDGE_NAME}.postHeight(String(pxHeight));
                            }
                        } catch (error) {}
                    }

                    function schedulePostHeight() {
                        if (heightScheduled) {
                            return;
                        }
                        heightScheduled = true;
                        var flush = function() {
                            heightScheduled = false;
                            postHeightNow();
                        };
                        if (window.requestAnimationFrame) {
                            window.requestAnimationFrame(flush);
                        } else {
                            setTimeout(flush, 16);
                        }
                    }

                    window.__vcpSetPaused = function(paused) {
                        try {
                            var root = document.documentElement;
                            if (root) {
                                root.classList.toggle('vcp-scroll-paused', !!paused);
                            }
                            if (document.getAnimations) {
                                document.getAnimations().forEach(function(animation) {
                                    try {
                                        if (paused) {
                                            animation.pause();
                                        } else {
                                            animation.play();
                                        }
                                    } catch (error) {}
                                });
                            }
                        } catch (error) {}
                    };

                    window.__vcpPostHeight = schedulePostHeight;
                    window.addEventListener('load', function() {
                        schedulePostHeight();
                        setTimeout(schedulePostHeight, 60);
                        setTimeout(schedulePostHeight, 240);
                        setTimeout(schedulePostHeight, 600);
                    });
                    window.addEventListener('resize', schedulePostHeight);
                    document.addEventListener('DOMContentLoaded', function() {
                        try {
                            var observerTarget = document.documentElement || document.body;
                            if (window.ResizeObserver && observerTarget) {
                                new ResizeObserver(schedulePostHeight).observe(observerTarget);
                            } else if (window.MutationObserver && observerTarget) {
                                new MutationObserver(schedulePostHeight).observe(observerTarget, {
                                    subtree: true,
                                    childList: true,
                                    characterData: true
                                });
                            }
                        } catch (error) {}
                        try {
                            Array.prototype.forEach.call(document.images || [], function(img) {
                                if (!img.complete) {
                                    img.addEventListener('load', schedulePostHeight);
                                    img.addEventListener('error', schedulePostHeight);
                                }
                            });
                        } catch (error) {}
                        schedulePostHeight();
                    });
                })();
            </script>
        </head>
        <body>
            $bodyContent
        </body>
        </html>
    """.trimIndent()
}

private fun extractHtmlHeadContent(text: String): String =
    Regex("""(?is)<head\b[^>]*>(.*)</head>""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?: ""

private fun handleBrowserHtmlNavigation(
    context: android.content.Context,
    link: String?,
    onActionMessage: ((String) -> Unit)?,
): Boolean {
    if (link.isNullOrBlank()) {
        return false
    }

    val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return false
    if (uri.scheme == VCP_NATIVE_ACTION_SCHEME) {
        val actionText = uri.getQueryParameter(VCP_NATIVE_ACTION_QUERY)
            ?.trim()
            .orEmpty()
        if (actionText.isNotBlank()) {
            onActionMessage?.invoke(buildActionMessagePayload(actionText))
        }
        return true
    }

    if (uri.scheme == "http" || uri.scheme == "https") {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return true
    }

    return false
}

private const val BROWSER_HTML_BASE_URL = "https://vcpnative.invalid/"
private val BROWSER_HTML_ADVANCED_TAGS_REGEX = Regex("""(?is)<(style|svg|filter|pattern|mask|canvas|iframe|video|audio)\b""")
private val BROWSER_HTML_EVENT_HANDLER_REGEX = Regex("""(?is)\bon(?:click|mouseover|mouseout|mouseenter|mouseleave|load|error)\s*=""")
private val BROWSER_HTML_CSS_FEATURES_REGEX = Regex("""(?is)(@keyframes|animation\s*:|backdrop-filter\s*:|filter\s*:|perspective\s*:|grid-template-columns\s*:|grid-template-rows\s*:|display\s*:\s*grid|display\s*:\s*flex|position\s*:\s*fixed|position\s*:\s*sticky|transform\s*:|clip-path\s*:|radial-gradient\s*\(|linear-gradient\s*\()""")
private val BROWSER_HTML_CSS_VAR_REGEX = Regex("""(?is)var\s*\(--""")
private val SAFE_MODE_HTML_TAG_REGEX = Regex("""</?[a-zA-Z][^>]*>""")
private const val BROWSER_HTML_BRIDGE_NAME = "VcpNativeBridge"
private const val BROWSER_HTML_HEIGHT_JS =
    "(function(){if(window.__vcpPostHeight){window.__vcpPostHeight();}return true;})();"
private const val BROWSER_HTML_PAUSE_JS =
    "(function(){if(window.__vcpSetPaused){window.__vcpSetPaused(true);}return true;})();"
private const val BROWSER_HTML_RESUME_JS =
    "(function(){if(window.__vcpSetPaused){window.__vcpSetPaused(false);}return true;})();"
private const val BROWSER_HTML_UPDATE_DEBOUNCE_MS = 180L
private const val BROWSER_HTML_PAUSE_TAG_KEY = 0x7F0B0211
private const val CHAT_RENDER_DOCUMENT_CACHE_MAX_CHARS = 1_500_000
private const val NATIVE_HTML_DOCUMENT_CACHE_MAX_CHARS = 1_000_000
private val BROWSER_HTML_VOID_TAGS = setOf(
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
)

private class BrowserHtmlBridge(
    private val onHeightChanged: (Float) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postHeight(heightPx: String?) {
        val parsed = heightPx?.toFloatOrNull() ?: return
        if (parsed > 0f) {
            mainHandler.post {
                onHeightChanged(parsed)
            }
        }
    }
}

private fun estimateNativeHtmlNestingDepth(rawHtml: String): Int {
    val tokenRegex = Regex("""(?is)</?[a-zA-Z][\w:-]*(?:\s+[^<>]*?)?/?>""")
    var depth = 0
    var maxDepth = 0
    tokenRegex.findAll(rawHtml).forEach { match ->
        val token = match.value
        val tagName = extractNativeHtmlTagName(token) ?: return@forEach
        when {
            token.startsWith("</") -> {
                depth = (depth - 1).coerceAtLeast(0)
            }

            isNativeHtmlSelfClosing(tagName, token) -> Unit
            else -> {
                depth += 1
                if (depth > maxDepth) {
                    maxDepth = depth
                }
            }
        }
    }
    return maxDepth
}

private fun extractHtmlBodyContent(text: String): String =
    Regex("""(?is)<body\b[^>]*>(.*)</body>""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?: text

private fun buildNativeActionAnchor(
    attributes: String,
    rawLabel: String,
): String {
    val sendText = extractNativeHtmlAttribute(attributes, "data-send")
        ?.trim()
        ?.ifBlank { null }
        ?: extractNativeOnclickInputText(extractNativeHtmlAttribute(attributes, "onclick"))
        ?: stripNativeHtmlTags(rawLabel).trim()
    val label = stripNativeHtmlTags(rawLabel).trim().ifBlank {
        sendText.ifBlank { "按钮" }
    }
    val href = Uri.Builder()
        .scheme(VCP_NATIVE_ACTION_SCHEME)
        .authority("action")
        .appendQueryParameter(VCP_NATIVE_ACTION_QUERY, sendText)
        .build()
        .toString()

    return """<a href="$href">${escapeHtmlText(label)}</a>"""
}

private fun escapeHtmlText(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private const val VCP_NATIVE_ACTION_SCHEME = "vcpnative-action"
private const val VCP_NATIVE_ACTION_QUERY = "text"
private val NATIVE_HTML_BUTTON_REGEX = Regex("""(?is)<button\b([^>]*)>(.*?)</button>""")

private fun extractNativeHtmlAttribute(
    attributes: String,
    name: String,
): String? {
    val regex = Regex("""(?is)\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""")
    return regex.find(attributes)
        ?.groupValues
        ?.getOrNull(2)
        ?.let(::decodeNativeHtml)
}

private fun extractNativeOnclickInputText(onclick: String?): String? =
    onclick
        ?.let {
            Regex("""(?is)\binput\s*\(\s*(['"])(.*?)\1\s*\)""")
                .find(it)
                ?.groupValues
                ?.getOrNull(2)
        }
        ?.let(::decodeNativeHtml)
        ?.trim()
        ?.ifBlank { null }

private fun stripNativeHtmlTags(value: String): String =
    decodeNativeHtml(value.replace(Regex("""<[^>]+>"""), " "))
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun decodeNativeHtml(value: String): String =
    if (!value.contains('&')) {
        value
    } else {
        runCatching {
            HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        }.getOrElse {
            value
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }
    }

@Composable
private fun MermaidDiagramView(block: ChatRenderBlock.MermaidDiagram) {
    CollapsibleCard(
        stableKey = "mermaid:${block.diagramType}:${block.code.hashCode()}",
        title = "Mermaid 图",
        subtitle = "Android 端暂不执行 Mermaid，先按源码展示",
        collapsedByDefault = true,
    ) {
        CodeFenceView(
            block = ChatRenderBlock.CodeFence(
                language = block.diagramType,
                code = block.code,
            ),
        )
    }
}

@Composable
private fun QuoteBlockView(
    block: ChatRenderBlock.Quote,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .background(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = RoundedCornerShape(99.dp),
                )
                .height(24.dp),
        )
        ChatRenderBlocksView(
            blocks = block.blocks,
            mode = mode,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            pauseDynamicContent = pauseDynamicContent,
            modifier = Modifier.weight(1f),
            nested = true,
        )
    }
}

@Composable
private fun MarkdownListView(
    block: ChatRenderBlock.MarkdownList,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        block.items.forEachIndexed { index, itemBlocks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (block.ordered) {
                        "${(block.startNumber ?: 1) + index}."
                    } else {
                        "•"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(top = 1.dp),
                )
                ChatRenderBlocksView(
                    blocks = itemBlocks,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                    modifier = Modifier.weight(1f),
                    nested = true,
                )
            }
        }
    }
}

@Composable
private fun ToolUseView(block: ChatRenderBlock.ToolUse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VCP-ToolUse:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = block.toolName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        CodeFenceView(
            block = ChatRenderBlock.CodeFence(
                language = "vcp",
                code = block.rawContent,
            ),
        )
    }
}

@Composable
private fun ToolResultView(
    block: ChatRenderBlock.ToolResult,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean,
) {
    CollapsibleCard(
        stableKey = "tool-result:${block.toolName}:${block.status}:${block.details.hashCode()}",
        title = "VCP-ToolResult",
        subtitle = "${block.toolName} · ${block.status}",
        collapsedByDefault = true,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            block.details.forEach { detail ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = detail.key,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.isRichText) {
                        ChatRenderBlocksView(
                            blocks = detail.richBlocks,
                            mode = mode,
                            onActionMessage = onActionMessage,
                            onLongPress = onLongPress,
                            pauseDynamicContent = pauseDynamicContent,
                            nested = true,
                        )
                    } else if (detail.imageUrl != null) {
                        RemoteImageView(
                            block = ChatRenderBlock.RemoteImage(
                                url = detail.imageUrl,
                                alt = detail.key,
                            ),
                        )
                    } else {
                        RichTextBlock(
                            text = detail.value,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (block.footerBlocks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                ChatRenderBlocksView(
                    blocks = block.footerBlocks,
                    mode = mode,
                    onActionMessage = onActionMessage,
                    onLongPress = onLongPress,
                    pauseDynamicContent = pauseDynamicContent,
                    nested = true,
                )
            }
        }
    }
}

@Composable
private fun ThoughtView(
    block: ChatRenderBlock.Thought,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean,
) {
    CollapsibleCard(
        stableKey = "thought:${block.title}:${block.blocks.hashCode()}",
        title = block.title,
        subtitle = null,
        collapsedByDefault = true,
    ) {
        ChatRenderBlocksView(
            blocks = block.blocks,
            mode = mode,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            pauseDynamicContent = pauseDynamicContent,
            nested = true,
        )
    }
}

@Composable
private fun DesktopPushView(block: ChatRenderBlock.DesktopPush) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (block.streaming) "正在向桌面推送..." else "已推送到桌面画布",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        CodeFenceView(
            block = ChatRenderBlock.CodeFence(
                language = null,
                code = block.preview,
            ),
        )
    }
}

@Composable
private fun DailyNoteView(
    block: ChatRenderBlock.DailyNote,
    mode: ChatRenderMode,
    onActionMessage: ((String) -> Unit)?,
    onLongPress: (() -> Unit)?,
    pauseDynamicContent: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Maid's Diary",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            block.date?.let { date ->
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
        block.maid?.let { maid ->
            Text(
                text = "Maid: $maid",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        ChatRenderBlocksView(
            blocks = block.blocks,
            mode = mode,
            onActionMessage = onActionMessage,
            onLongPress = onLongPress,
            pauseDynamicContent = pauseDynamicContent,
            nested = true,
        )
    }
}

@Composable
private fun RoleDividerView(block: ChatRenderBlock.RoleDivider) {
    val roleLabel = when (block.role) {
        "system" -> "System"
        "assistant" -> "Assistant"
        "user" -> "User"
        else -> block.role
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "角色分界: $roleLabel [${if (block.isEnd) "结束" else "起始"}]",
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(99.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CanvasPlaceholderView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Canvas协同中...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CollapsibleCard(
    stableKey: String,
    title: String,
    subtitle: String?,
    collapsedByDefault: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val corner = RoundedCornerShape(16.dp)
    var expanded by rememberSaveable(stableKey) {
        androidx.compose.runtime.mutableStateOf(!collapsedByDefault)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = corner,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = corner,
            )
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RichTextBlock(
    text: String,
    style: TextStyle,
) {
    val inlineCodeBackground = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    val tagColor = MaterialTheme.colorScheme.secondary
    val alertTagColor = MaterialTheme.colorScheme.error
    val quoteColor = MaterialTheme.colorScheme.tertiary
    val protectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(
        text,
        inlineCodeBackground,
        linkColor,
        tagColor,
        alertTagColor,
        quoteColor,
        protectedColor,
    ) {
        buildRichAnnotatedString(
            text = text,
            inlineCodeBackground = inlineCodeBackground,
            linkColor = linkColor,
            tagColor = tagColor,
            alertTagColor = alertTagColor,
            quoteColor = quoteColor,
            protectedColor = protectedColor,
        )
    }

    SelectionContainer {
        Text(
            text = annotated,
            style = style,
        )
    }
}

private fun buildRichAnnotatedString(
    text: String,
    inlineCodeBackground: Color,
    linkColor: Color,
    tagColor: Color,
    alertTagColor: Color,
    quoteColor: Color,
    protectedColor: Color,
): AnnotatedString = buildAnnotatedString {
    appendInlineContent(
        target = this,
        text = text,
        inlineCodeBackground = inlineCodeBackground,
        linkColor = linkColor,
        tagColor = tagColor,
        alertTagColor = alertTagColor,
        quoteColor = quoteColor,
        protectedColor = protectedColor,
    )
}

private fun appendInlineContent(
    target: AnnotatedString.Builder,
    text: String,
    inlineCodeBackground: Color,
    linkColor: Color,
    tagColor: Color,
    alertTagColor: Color,
    quoteColor: Color,
    protectedColor: Color,
) {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("「始」", index) -> {
                val endIndex = text.indexOf("「末」", index + 2)
                val protectedText = if (endIndex == -1) {
                    text.substring(index)
                } else {
                    text.substring(index, endIndex + 2)
                }
                target.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = protectedColor,
                        background = inlineCodeBackground.copy(alpha = 0.6f),
                    ),
                ) {
                    append(protectedText)
                }
                index = if (endIndex == -1) text.length else endIndex + 2
            }

            text.startsWith("![", index) -> {
                val closeBracket = text.indexOf(']', index + 2)
                val openParen = closeBracket + 1
                val closeParen = if (closeBracket != -1 && openParen < text.length && text[openParen] == '(') {
                    text.indexOf(')', openParen + 1)
                } else {
                    -1
                }

                if (closeBracket != -1 && closeParen != -1) {
                    val alt = text.substring(index + 2, closeBracket).ifBlank { "image" }
                    val url = text.substring(openParen + 1, closeParen)
                    target.withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append("[图片] $alt")
                    }
                    target.append(" ")
                    target.append(url)
                    index = closeParen + 1
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end != -1) {
                    target.withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        appendInlineContent(
                            target = target,
                            text = text.substring(index + 2, end),
                            inlineCodeBackground = inlineCodeBackground,
                            linkColor = linkColor,
                            tagColor = tagColor,
                            alertTagColor = alertTagColor,
                            quoteColor = quoteColor,
                            protectedColor = protectedColor,
                        )
                    }
                    index = end + 2
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end != -1) {
                    target.withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = inlineCodeBackground.copy(alpha = 0.9f),
                        ),
                    ) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            text[index] == '[' -> {
                val closeBracket = text.indexOf(']', index + 1)
                val openParen = closeBracket + 1
                val closeParen = if (closeBracket != -1 && openParen < text.length && text[openParen] == '(') {
                    text.indexOf(')', openParen + 1)
                } else {
                    -1
                }

                if (closeBracket != -1 && closeParen != -1) {
                    val label = text.substring(index + 1, closeBracket)
                    target.withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(label)
                    }
                    index = closeParen + 1
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            text.startsWith("http://", index) || text.startsWith("https://", index) -> {
                val end = findUrlEnd(text, index)
                target.withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(text.substring(index, end))
                }
                index = end
            }

            text.startsWith("@!", index) && canStartInlineMarker(text, index) -> {
                val end = findTagEnd(text, index + 2)
                if (end > index + 2) {
                    target.withStyle(
                        SpanStyle(
                            color = alertTagColor,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        append(text.substring(index, end))
                    }
                    index = end
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            text[index] == '@' && canStartInlineMarker(text, index) -> {
                val end = findTagEnd(text, index + 1)
                if (end > index + 1) {
                    target.withStyle(
                        SpanStyle(
                            color = tagColor,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(text.substring(index, end))
                    }
                    index = end
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            text[index] == '"' || text[index] == '“' -> {
                val closing = if (text[index] == '“') '”' else '"'
                val end = text.indexOf(closing, index + 1)
                if (end != -1) {
                    target.withStyle(
                        SpanStyle(
                            color = quoteColor,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(text.substring(index, end + 1))
                    }
                    index = end + 1
                } else {
                    target.append(text[index])
                    index += 1
                }
            }

            else -> {
                val nextIndex = findNextInlineMarker(text, index)
                target.append(text.substring(index, nextIndex))
                index = nextIndex
            }
        }
    }
}

private fun findNextInlineMarker(
    text: String,
    startIndex: Int,
): Int {
    var index = startIndex
    while (index < text.length) {
        if (text.startsWith("![", index) ||
            text.startsWith("**", index) ||
            text[index] == '`' ||
            text[index] == '[' ||
            text.startsWith("http://", index) ||
            text.startsWith("https://", index) ||
            text.startsWith("@!", index) ||
            text[index] == '@' ||
            text[index] == '"' ||
            text[index] == '“' ||
            text.startsWith("「始」", index)
        ) {
            return index
        }
        index += 1
    }
    return text.length
}

private fun findUrlEnd(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length && !text[index].isWhitespace()) {
        index += 1
    }
    return index
}

private fun canStartInlineMarker(text: String, index: Int): Boolean =
    index == 0 || !text[index - 1].isLetterOrDigit()

private fun findTagEnd(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length) {
        val char = text[index]
        val valid = char == '_' || char.isLetterOrDigit() || char.code in 0x4e00..0x9fa5
        if (!valid) {
            break
        }
        index += 1
    }
    return index
}
