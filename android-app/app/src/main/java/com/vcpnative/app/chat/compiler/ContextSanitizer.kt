package com.vcpnative.app.chat.compiler

/**
 * Converts HTML content in assistant messages to Markdown,
 * preserving document structure (headings, lists, code, links, bold/italic).
 *
 * Ported from VCPMobile context_sanitizer.rs html_to_vcp_markdown().
 *
 * Key difference from the old implementation:
 * - OLD: Used Android's Html.fromHtml() which strips ALL structure to plain text
 * - NEW: Tree-walk HTML → Markdown conversion preserving semantic structure
 *
 * This is critical because LLMs rely on formatting cues (headings, code blocks, lists)
 * to understand context. Stripping them causes "weird" or "missing" context behavior.
 */
object ContextSanitizer {

    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    private val VCP_SPECIAL_BLOCK_REGEX = Regex(
        """<<<\[TOOL_REQUEST]>>>[\s\S]*?<<<\[/TOOL_REQUEST]>>>|<<<\[TOOL_REQUEST\]>>>[\s\S]*?<<<\[END_TOOL_REQUEST\]>>>|<<<DailyNoteStart>>>[\s\S]*?<<<DailyNoteEnd>>>""",
    )

    private val MEDIA_TAG_REGEX = Regex(
        """<(?:img|audio|video)\b[^>]*>(?:</(?:audio|video)>)?""",
        RegexOption.IGNORE_CASE,
    )

    private val EXCESSIVE_NEWLINES_REGEX = Regex("""\n{3,}""")

    // Self-closing tags that shouldn't have children processed
    private val SELF_CLOSING_TAGS = setOf("img", "br", "hr", "input", "source")

    fun sanitize(content: String): String {
        if (content.isBlank()) return content
        if (!containsHtml(content)) return content

        val specialBlocks = mutableListOf<Pair<String, String>>()
        var working = content

        // 1. Extract and preserve VCP special blocks verbatim
        var blockIndex = 0
        working = VCP_SPECIAL_BLOCK_REGEX.replace(working) { match ->
            val placeholder = "\uFFFC\u200BVCP_BLK_${blockIndex++}\u200B\uFFFC"
            specialBlocks.add(placeholder to match.value)
            placeholder
        }

        // 2. Extract and preserve media tags verbatim
        working = MEDIA_TAG_REGEX.replace(working) { match ->
            val placeholder = "\uFFFC\u200BVCP_BLK_${blockIndex++}\u200B\uFFFC"
            specialBlocks.add(placeholder to match.value)
            placeholder
        }

        // 3. Convert HTML to Markdown (preserving structure!)
        working = htmlToMarkdown(working)

        // 4. Restore preserved blocks
        specialBlocks.forEach { (placeholder, original) ->
            working = working.replace(placeholder, original)
        }

        // 5. Clean excessive whitespace
        working = working.replace(EXCESSIVE_NEWLINES_REGEX, "\n\n").trim()

        return working
    }

    /**
     * Simple HTML → Markdown converter using regex-based tag parsing.
     *
     * This is a lightweight approach (no XML parser dependency) that handles
     * the most common HTML tags produced by AI models. Matches VCPMobile's
     * context_sanitizer.rs output for: headings, bold, italic, code, lists,
     * links, paragraphs, line breaks, and pre blocks.
     */
    private fun htmlToMarkdown(html: String): String {
        var md = html

        // Pre-process: extract <pre> blocks first to protect their content
        val preBlocks = mutableListOf<String>()
        md = Regex("""<pre\b[^>]*>([\s\S]*?)</pre>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            val innerText = stripTags(match.groupValues[1])
            val placeholder = "\uFFFC\u200BPRE_${preBlocks.size}\u200B\uFFFC"
            // Check if it contains VCP special markers — preserve verbatim if so
            if (innerText.contains("<<<[TOOL_REQUEST]>>>") || innerText.contains("<<<DailyNoteStart>>>")) {
                preBlocks.add(innerText)
            } else {
                preBlocks.add("\n```\n${innerText.trim()}\n```\n")
            }
            placeholder
        }

        // Headings: <h1>-<h6> → # - ######
        for (level in 1..6) {
            val hashes = "#".repeat(level)
            md = Regex("""<h$level\b[^>]*>([\s\S]*?)</h$level>""", RegexOption.IGNORE_CASE).replace(md) { match ->
                "\n$hashes ${stripTags(match.groupValues[1]).trim()}\n"
            }
        }

        // Bold: <strong> / <b>
        md = Regex("""<(?:strong|b)\b[^>]*>([\s\S]*?)</(?:strong|b)>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            "**${stripTags(match.groupValues[1])}**"
        }

        // Italic: <em> / <i>
        md = Regex("""<(?:em|i)\b[^>]*>([\s\S]*?)</(?:em|i)>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            "*${stripTags(match.groupValues[1])}*"
        }

        // Inline code: <code> (but NOT inside <pre>)
        md = Regex("""<code\b[^>]*>([\s\S]*?)</code>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            "`${stripTags(match.groupValues[1])}`"
        }

        // Links: <a href="url">text</a>
        md = Regex("""<a\b[^>]*href=["']([^"']*)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            "[${stripTags(match.groupValues[2])}](${match.groupValues[1]})"
        }

        // List items: <li> → "- " (simplified; works for both <ul> and <ol>)
        md = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            "- ${stripTags(match.groupValues[1]).trim()}\n"
        }

        // Paragraphs: <p> → newlines
        md = Regex("""<p\b[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE).replace(md) { match ->
            "\n${match.groupValues[1].trim()}\n"
        }

        // Line breaks
        md = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(md, "\n")

        // Horizontal rules
        md = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE).replace(md, "\n---\n")

        // Strip list containers (content already extracted via <li>)
        md = Regex("""</?(?:ul|ol|dl|dt|dd)\b[^>]*>""", RegexOption.IGNORE_CASE).replace(md, "\n")

        // Strip all remaining tags (div, span, table, etc.)
        md = Regex("""</?[^>]+>""").replace(md, "")

        // Restore pre blocks
        preBlocks.forEachIndexed { index, block ->
            md = md.replace("\uFFFC\u200BPRE_$index\u200B\uFFFC", block)
        }

        // Decode common HTML entities
        md = md.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

        return md
    }

    /** Strip HTML tags from a string, keeping only text content. */
    private fun stripTags(html: String): String =
        Regex("""<[^>]+>""").replace(html, "")

    fun containsHtml(content: String): Boolean =
        HTML_TAG_REGEX.containsMatchIn(content)
}
