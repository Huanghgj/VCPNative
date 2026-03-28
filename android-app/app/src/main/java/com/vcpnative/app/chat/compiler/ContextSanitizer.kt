package com.vcpnative.app.chat.compiler

import android.os.Build
import android.text.Html

/**
 * Converts HTML content in older assistant messages to plain text / lightweight markdown,
 * reducing token waste while preserving essential structure.
 *
 * Strategy:
 * - If content has no HTML tags, return as-is (fast path).
 * - Preserve VCP special blocks (TOOL_REQUEST, DailyNoteStart) verbatim.
 * - Preserve media tags (<img>, <audio>, <video>) verbatim.
 * - Convert remaining HTML to plain text via Android's Html.fromHtml.
 * - Clean up excessive whitespace.
 *
 * Reference: /root/VCPChat/modules/contextSanitizer.js
 */
object ContextSanitizer {

    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    private val VCP_SPECIAL_BLOCK_REGEX = Regex(
        """<<<\[TOOL_REQUEST]>>>[\s\S]*?<<<\[/TOOL_REQUEST]>>>|<<<DailyNoteStart>>>[\s\S]*?<<<DailyNoteEnd>>>""",
    )

    private val MEDIA_TAG_REGEX = Regex(
        """<(?:img|audio|video)\b[^>]*>(?:</(?:audio|video)>)?""",
        RegexOption.IGNORE_CASE,
    )

    private val EXCESSIVE_NEWLINES_REGEX = Regex("""\n{3,}""")

    fun sanitize(content: String): String {
        if (content.isBlank()) return content
        if (!containsHtml(content)) return content

        val specialBlocks = mutableListOf<Pair<String, String>>()
        var working = content

        // 1. Extract and preserve VCP special blocks
        var blockIndex = 0
        working = VCP_SPECIAL_BLOCK_REGEX.replace(working) { match ->
            val placeholder = "\uFFFC\u200BVCP_BLK_${blockIndex++}\u200B\uFFFC"
            specialBlocks.add(placeholder to match.value)
            placeholder
        }

        // 2. Extract and preserve media tags
        working = MEDIA_TAG_REGEX.replace(working) { match ->
            val placeholder = "\uFFFC\u200BVCP_BLK_${blockIndex++}\u200B\uFFFC"
            specialBlocks.add(placeholder to match.value)
            placeholder
        }

        // 3. Convert HTML to plain text
        @Suppress("DEPRECATION")
        val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(working, Html.FROM_HTML_MODE_COMPACT)
        } else {
            Html.fromHtml(working)
        }
        working = spanned.toString()

        // 4. Restore preserved blocks
        specialBlocks.forEach { (placeholder, original) ->
            working = working.replace(placeholder, original)
        }

        // 5. Clean excessive whitespace
        working = working.replace(EXCESSIVE_NEWLINES_REGEX, "\n\n").trim()

        return working
    }

    fun containsHtml(content: String): Boolean =
        HTML_TAG_REGEX.containsMatchIn(content)
}
