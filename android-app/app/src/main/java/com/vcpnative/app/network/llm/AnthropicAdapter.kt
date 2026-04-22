package com.vcpnative.app.network.llm

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Claude adapter — uses Claude's native Messages API.
 * Handles Claude-specific features: thinking/reasoning, tool use.
 */
class AnthropicAdapter(
    httpClient: OkHttpClient,
) : BaseSseAdapter(httpClient) {
    override val providerId = "anthropic"
    override val tag = "AnthropicAdapter"

    override fun buildRequest(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
        apiKey: String,
    ): Request {
        val baseUrl = profile.baseUrl.trimEnd('/')
        val endpoint = "$baseUrl/messages"

        val systemPrompt = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val chatMessages = messages.filter { it.role != "system" }

        val body = JSONObject().apply {
            put("model", options.model)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", JSONArray().apply {
                chatMessages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", serializeClaudeContent(msg))
                    })
                }
            })
            put("max_tokens", options.maxTokens ?: 4096)
            put("temperature", options.temperature)
            put("stream", options.stream)
            options.topP?.let { put("top_p", it) }
        }

        return Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(LlmAdapterConstants.JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .apply { profile.customHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
    }

    override fun parseNonStreamingResponse(json: JSONObject): LlmStreamEvent.Completed {
        val text = json.optJSONArray("content")
            ?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { it.optString("text", "") }
            } ?: ""
        val usage = json.optJSONObject("usage")
        return LlmStreamEvent.Completed(
            fullText = text,
            inputTokens = usage?.optInt("input_tokens", 0) ?: 0,
            outputTokens = usage?.optInt("output_tokens", 0) ?: 0,
        )
    }

    override fun processStreamChunk(json: JSONObject): StreamResult {
        return when (json.optString("type")) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return StreamResult.Skip
                when (delta.optString("type")) {
                    "thinking_delta" -> {
                        val thinking = delta.optString("thinking")
                        if (thinking.isNotEmpty()) StreamResult.ThinkingDelta(thinking)
                        else StreamResult.Skip
                    }
                    else -> {
                        val text = delta.optString("text")
                        if (text.isNotEmpty()) StreamResult.TextDelta(text)
                        else StreamResult.Skip
                    }
                }
            }
            "message_stop" -> StreamResult.EndOfStream
            else -> StreamResult.Skip
        }
    }

    companion object {
        private val DATA_URI_REGEX = LlmAdapterConstants.DATA_URI_REGEX

        private fun serializeClaudeContent(msg: LlmMessage): Any {
            if (msg.contentParts.isEmpty()) return msg.content
            return JSONArray().apply {
                msg.contentParts.forEach { part ->
                    put(when (part.type) {
                        "text" -> JSONObject()
                            .put("type", "text")
                            .put("text", part.text.orEmpty())
                        else -> {
                            val url = part.imageUrl.orEmpty()
                            val match = DATA_URI_REGEX.find(url)
                            if (match != null) {
                                JSONObject()
                                    .put("type", "image")
                                    .put("source", JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", match.groupValues[1])
                                        .put("data", match.groupValues[2]))
                            } else {
                                JSONObject()
                                    .put("type", "image")
                                    .put("source", JSONObject()
                                        .put("type", "url")
                                        .put("url", url))
                            }
                        }
                    })
                }
            }
        }
    }
}
