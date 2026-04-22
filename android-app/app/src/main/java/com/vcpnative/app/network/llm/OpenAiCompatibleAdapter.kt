package com.vcpnative.app.network.llm

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible adapter — works with OpenAI, DeepSeek, Groq, OpenRouter,
 * SiliconFlow, Ollama, and any OpenAI-format endpoint.
 */
class OpenAiCompatibleAdapter(
    httpClient: OkHttpClient,
    override val providerId: String = "openai-compatible",
) : BaseSseAdapter(httpClient) {
    override val tag = "OpenAiCompatibleAdapter"

    override fun requiresApiKey(profile: LlmProfile): Boolean =
        profile.provider != "ollama"

    override fun buildRequest(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
        apiKey: String,
    ): Request {
        val baseUrl = profile.baseUrl.trimEnd('/')
        val endpoint = "$baseUrl/chat/completions"

        val body = JSONObject().apply {
            put("model", options.model)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", serializeContent(msg))
                        msg.name?.let { put("name", it) }
                    })
                }
            })
            put("temperature", options.temperature)
            put("stream", options.stream)
            options.maxTokens?.let { put("max_tokens", it) }
            options.topP?.let { put("top_p", it) }
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(LlmAdapterConstants.JSON_MEDIA))
            .header("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        profile.customHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        return requestBuilder.build()
    }

    override fun parseNonStreamingResponse(json: JSONObject): LlmStreamEvent.Completed {
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "") ?: ""
        val usage = json.optJSONObject("usage")
        return LlmStreamEvent.Completed(
            fullText = text,
            inputTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            outputTokens = usage?.optInt("completion_tokens", 0) ?: 0,
        )
    }

    override fun isRawStreamTerminator(data: String): Boolean = data == "[DONE]"

    override fun processStreamChunk(json: JSONObject): StreamResult {
        val delta = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
        val content = delta?.optString("content")
        return if (!content.isNullOrEmpty()) StreamResult.TextDelta(content)
        else StreamResult.Skip
    }

    companion object {
        /** Serialize message content: multimodal parts array or plain string. */
        internal fun serializeContent(msg: LlmMessage): Any =
            if (msg.contentParts.isEmpty()) {
                msg.content
            } else {
                JSONArray().apply {
                    msg.contentParts.forEach { part ->
                        put(when (part.type) {
                            "text" -> JSONObject()
                                .put("type", "text")
                                .put("text", part.text.orEmpty())
                            "image_url" -> JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", part.imageUrl.orEmpty()))
                            else -> {
                                Log.w("OpenAiCompatibleAdapter", "Unknown content part type: ${part.type}, treating as text")
                                JSONObject()
                                    .put("type", "text")
                                    .put("text", part.text.orEmpty())
                            }
                        })
                    }
                }
            }
    }
}
