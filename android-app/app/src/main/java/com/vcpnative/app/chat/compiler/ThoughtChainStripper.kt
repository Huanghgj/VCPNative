package com.vcpnative.app.chat.compiler

/**
 * Strips thought chain content from message text.
 *
 * Handles two formats:
 * 1. VCP thought chains: [--- VCP元思考链 ---]...[--- 元思考链结束 ---]
 * 2. Conventional XML tags: <think>...</think> and <thinking>...</thinking>
 *
 * Reference: /root/VCPChat/modules/contextSanitizer.js → stripThoughtChains()
 */
object ThoughtChainStripper {

    private val VCP_THOUGHT_CHAIN_REGEX =
        Regex("""\[--- VCP元思考链(?::\s*"[^"]*")?\s*---][\s\S]*?\[--- 元思考链结束 ---]""")

    private val CONVENTIONAL_THOUGHT_REGEX =
        Regex("""<think(?:ing)?>[\s\S]*?</think(?:ing)?>""", RegexOption.IGNORE_CASE)

    fun strip(content: String): String {
        if (content.isBlank()) return content
        return content
            .replace(VCP_THOUGHT_CHAIN_REGEX, "")
            .replace(CONVENTIONAL_THOUGHT_REGEX, "")
            .trim()
    }
}
