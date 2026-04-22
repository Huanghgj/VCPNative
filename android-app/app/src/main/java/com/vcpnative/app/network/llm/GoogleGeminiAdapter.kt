package com.vcpnative.app.network.llm

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini adapter — uses Gemini's native generateContent API.
 */
class GoogleGeminiAdapter(
    httpClient: OkHttpClient,
) : BaseSseAdapter(httpClient) {
    override val providerId = "google"
    override val tag = "GoogleGeminiAdapter"

    override fun buildRequest(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
        apiKey: String,
    ): Request {
        val baseUrl = profile.baseUrl.trimEnd('/')
        val action = if (options.stream) "streamGenerateContent?alt=sse&" else "generateContent?"
        val endpoint = "$baseUrl/models/${options.model}:${action}key=$apiKey"

        val systemParts = messages.filter { it.role == "system" }
        val chatMessages = messages.filter { it.role != "system" }

        val body = JSONObject().apply {
            if (systemParts.isNotEmpty()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().apply {
                    systemParts.forEach { put(JSONObject().put("text", it.content)) }
                }))
            }
            put("contents", JSONArray().apply {
                chatMessages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", serializeGeminiParts(msg))
                    })
                }
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", options.temperature)
                options.maxTokens?.let { put("maxOutputTokens", it) }
                options.topP?.let { put("topP", it) }
            })
        }

        return Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(LlmAdapterConstants.JSON_MEDIA))
            .header("Content-Type", "application/json")
            .apply { profile.customHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
    }

    override fun parseNonStreamingResponse(json: JSONObject): LlmStreamEvent.Completed {
        val text = extractGeminiText(json)
        return LlmStreamEvent.Completed(fullText = text)
    }

    override fun processStreamChunk(json: JSONObject): StreamResult {
        val text = extractGeminiText(json)
        return if (text.isNotEmpty()) StreamResult.TextDelta(text)
        else StreamResult.Skip
    }

    private fun extractGeminiText(json: JSONObject): String {
        return json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                (0 until parts.length())
                    .mapNotNull { parts.optJSONObject(it)?.optString("text") }
                    .joinToString("")
            } ?: ""
    }

    companion object {
        private val DATA_URI_REGEX = LlmAdapterConstants.DATA_URI_REGEX

        private fun serializeGeminiParts(msg: LlmMessage): JSONArray {
            if (msg.contentParts.isEmpty()) {
                return JSONArray().put(JSONObject().put("text", msg.content))
            }
            return JSONArray().apply {
                msg.contentParts.forEach { part ->
                    when (part.type) {
                        "text" -> put(JSONObject().put("text", part.text.orEmpty()))
                        "image_url" -> {
                            val url = part.imageUrl.orEmpty()
                            val match = DATA_URI_REGEX.find(url)
                            if (match != null) {
                                put(JSONObject().put("inlineData", JSONObject()
                                    .put("mimeType", match.groupValues[1])
                                    .put("data", match.groupValues[2])))
                            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                                val mimeType = guessImageMimeType(url)
                                put(JSONObject().put("fileData", JSONObject()
                                    .put("mimeType", mimeType)
                                    .put("fileUri", url)))
                            } else {
                                Log.w("GoogleGeminiAdapter", "Unsupported image source for Gemini, skipping: ${url.take(80)}")
                                put(JSONObject().put("text", "[不支持的图片来源]"))
                            }
                        }
                        else -> {
                            Log.w("GoogleGeminiAdapter", "Unknown content part type: ${part.type}")
                            put(JSONObject().put("text", part.text.orEmpty()))
                        }
                    }
                }
            }
        }

        private fun guessImageMimeType(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".png") -> "image/png"
                path.endsWith(".gif") -> "image/gif"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".svg") -> "image/svg+xml"
                else -> "image/jpeg"
            }
        }
    }
}
