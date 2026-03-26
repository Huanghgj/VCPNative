package com.vcpnative.app.chat.compiler

import com.vcpnative.app.data.room.AgentEntity
import org.json.JSONArray
import org.json.JSONObject

object AgentPromptResolver {
    fun resolveActiveSystemPrompt(agent: AgentEntity?): String {
        if (agent == null) {
            return ""
        }
        return resolveActiveSystemPrompt(
            promptMode = agent.promptMode,
            systemPrompt = agent.systemPrompt,
            originalSystemPrompt = agent.originalSystemPrompt,
            advancedSystemPromptJson = agent.advancedSystemPromptJson,
            presetSystemPrompt = agent.presetSystemPrompt,
        )
    }

    fun resolveActiveSystemPrompt(
        promptMode: String,
        systemPrompt: String = "",
        originalSystemPrompt: String = "",
        advancedSystemPromptJson: String = "",
        presetSystemPrompt: String = "",
    ): String {
        val normalizedMode = promptMode.ifBlank { "original" }
        return when (normalizedMode) {
            "original" -> originalSystemPrompt.ifBlank { systemPrompt }
            "modular" -> resolveModularPrompt(advancedSystemPromptJson).ifBlank { systemPrompt }
            "preset" -> presetSystemPrompt.ifBlank { systemPrompt }
            else -> systemPrompt
        }
    }

    fun resolveModeContent(
        promptMode: String,
        originalSystemPrompt: String = "",
        advancedSystemPromptJson: String = "",
        presetSystemPrompt: String = "",
    ): String {
        return when (promptMode.ifBlank { "original" }) {
            "original" -> originalSystemPrompt
            "modular" -> resolveModularPrompt(advancedSystemPromptJson)
            "preset" -> presetSystemPrompt
            else -> ""
        }
    }

    private fun resolveModularPrompt(rawJson: String): String {
        val advanced = rawJson.takeIf { it.isNotBlank() } ?: return ""
        return runCatching {
            when {
                advanced.trimStart().startsWith("{") -> {
                    val root = JSONObject(advanced)
                    val blocks = root.optJSONArray("blocks") ?: return@runCatching ""
                    buildString {
                        for (index in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(index) ?: continue
                            if (block.optBoolean("disabled")) {
                                continue
                            }

                            if (block.optString("type") == "newline") {
                                append('\n')
                                continue
                            }

                            var content = block.optString("content")
                            val variants = block.optJSONArray("variants")
                            if (variants != null && variants.length() > 0) {
                                val selectedIndex = block.optInt("selectedVariant", 0)
                                    .coerceIn(0, variants.length() - 1)
                                content = variants.contentFor(selectedIndex, content)
                            }
                            append(content)
                        }
                    }
                }

                else -> advanced
            }
        }.getOrDefault("")
    }
}

private fun JSONArray.contentFor(index: Int, fallback: String): String =
    when (val variant = opt(index)) {
        is JSONObject -> variant.optString("content").ifBlank { variant.toString() }
        else -> optString(index, fallback)
    }
