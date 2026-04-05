package com.vcpnative.app.chat.content

import com.vcpnative.app.data.cache.RegexCache

/**
 * Structured content block parser for AI responses.
 *
 * Ported from VCPMobile `content_parser.rs`.
 * Extracts tool requests, thought chains, daily notes, and other special
 * blocks from raw AI output, leaving the rest as Markdown.
 *
 * This runs on the Kotlin side to offload parsing from the WebView's JS thread,
 * enabling pre-processing before rendering.
 */
object ContentParser {

    sealed class ContentBlock {
        data class Markdown(val text: String) : ContentBlock()
        data class ToolUse(val toolName: String, val content: String) : ContentBlock()
        data class ToolResult(val summary: String, val details: List<Pair<String, String>>) : ContentBlock()
        data class ThoughtChain(val title: String?, val content: String) : ContentBlock()
        data class HtmlPreview(val html: String) : ContentBlock()
        data class DailyNote(val date: String, val folder: String, val content: String) : ContentBlock()
        data class ButtonClick(val label: String) : ContentBlock()
    }

    // Patterns (compiled once via RegexCache)
    private val TOOL_REQUEST_PATTERN = """<<<\[TOOL_REQUEST\]>>>([\s\S]*?)<<<\[END_TOOL_REQUEST\]>>>"""
    private val THOUGHT_CHAIN_PATTERN = """\[--- VCP元思考链(?::\s*"([^"]*)")?\s*---\]([\s\S]*?)\[--- 元思考链结束 ---\]"""
    private val DAILY_NOTE_PATTERN = """<<<DailyNoteStart>>>([\s\S]*?)<<<DailyNoteEnd>>>"""
    private val TOOL_RESULT_PATTERN = """\[\[VCP调用结果信息汇总:([\s\S]*?)VCP调用结果结束\]\]"""
    private val BUTTON_CLICK_PATTERN = """\[\[点击按钮:(.*?)\]\]"""

    /**
     * Parse raw AI output into a sequence of structured [ContentBlock]s.
     *
     * Algorithm: find the earliest special marker, extract it as a typed block,
     * treat everything before it as Markdown, then continue scanning the remainder.
     * Matches VCPMobile's "earliest marker priority" strategy.
     */
    fun parse(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            val earliest = findEarliestMatch(remaining)
            if (earliest == null) {
                // No more special blocks — rest is Markdown
                if (remaining.isNotBlank()) {
                    blocks.add(ContentBlock.Markdown(remaining))
                }
                break
            }

            // Everything before the match is Markdown
            val before = remaining.substring(0, earliest.range.first)
            if (before.isNotBlank()) {
                blocks.add(ContentBlock.Markdown(before))
            }

            // Extract the matched block
            blocks.add(earliest.block)

            // Continue after the match
            remaining = remaining.substring(earliest.range.last + 1)
        }

        return blocks
    }

    private data class MatchResult(
        val range: IntRange,
        val block: ContentBlock,
    )

    private fun findEarliestMatch(text: String): MatchResult? {
        val candidates = mutableListOf<MatchResult>()

        RegexCache.get(TOOL_REQUEST_PATTERN, setOf(RegexOption.DOT_MATCHES_ALL))
            .find(text)?.let { match ->
                candidates.add(MatchResult(
                    range = match.range,
                    block = parseToolRequest(match.groupValues[1]),
                ))
            }

        RegexCache.get(THOUGHT_CHAIN_PATTERN, setOf(RegexOption.DOT_MATCHES_ALL))
            .find(text)?.let { match ->
                candidates.add(MatchResult(
                    range = match.range,
                    block = ContentBlock.ThoughtChain(
                        title = match.groupValues[1].takeIf { it.isNotBlank() },
                        content = match.groupValues[2].trim(),
                    ),
                ))
            }

        RegexCache.get(DAILY_NOTE_PATTERN, setOf(RegexOption.DOT_MATCHES_ALL))
            .find(text)?.let { match ->
                candidates.add(MatchResult(
                    range = match.range,
                    block = parseDailyNote(match.groupValues[1]),
                ))
            }

        RegexCache.get(TOOL_RESULT_PATTERN, setOf(RegexOption.DOT_MATCHES_ALL))
            .find(text)?.let { match ->
                candidates.add(MatchResult(
                    range = match.range,
                    block = parseToolResult(match.groupValues[1]),
                ))
            }

        RegexCache.get(BUTTON_CLICK_PATTERN)
            .find(text)?.let { match ->
                candidates.add(MatchResult(
                    range = match.range,
                    block = ContentBlock.ButtonClick(match.groupValues[1].trim()),
                ))
            }

        return candidates.minByOrNull { it.range.first }
    }

    private fun parseToolRequest(content: String): ContentBlock.ToolUse {
        val toolNameMatch = Regex("""tool_name:「始」(.*?)「末」""").find(content)
        return ContentBlock.ToolUse(
            toolName = toolNameMatch?.groupValues?.get(1) ?: "unknown",
            content = content.trim(),
        )
    }

    private fun parseDailyNote(content: String): ContentBlock.DailyNote {
        val dateMatch = Regex("""Date:「始」(.*?)「末」""").find(content)
        val folderMatch = Regex("""maid:「始」(.*?)「末」""").find(content)
        val contentMatch = Regex("""Content:「始」([\s\S]*?)「末」""").find(content)
        return ContentBlock.DailyNote(
            date = dateMatch?.groupValues?.get(1) ?: "",
            folder = folderMatch?.groupValues?.get(1) ?: "",
            content = contentMatch?.groupValues?.get(1)?.trim() ?: content.trim(),
        )
    }

    private fun parseToolResult(content: String): ContentBlock.ToolResult {
        val details = mutableListOf<Pair<String, String>>()
        val lines = content.trim().lines()
        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                details.add(line.substring(0, colonIndex).trim() to line.substring(colonIndex + 1).trim())
            }
        }
        return ContentBlock.ToolResult(
            summary = lines.firstOrNull()?.trim() ?: "",
            details = details,
        )
    }
}
