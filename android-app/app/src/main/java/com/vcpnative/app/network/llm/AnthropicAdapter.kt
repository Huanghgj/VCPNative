package com.vcpnative.app.network.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Claude adapter — uses Claude's native Messages API.
 * Handles Claude-specific features: thinking/reasoning, tool use.
 *
 * Inspired by aio-hub's anthropic adapter.
 */
class AnthropicAdapter(
    private val httpClient: OkHttpClient,
) : LlmAdapter {
    override val providerId = "anthropic"

    override fun chat(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
    ): Flow<LlmStreamEvent> = flow {
        emit(LlmStreamEvent.Started)

        val baseUrl = profile.baseUrl.trimEnd('/')
        val endpoint = "$baseUrl/messages"
        val apiKey = profile.apiKeys.firstOrNull() ?: ""

        // Claude API: system prompt is separate, not in messages array
        val systemPrompt = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val chatMessages = messages.filter { it.role != "system" }

        val body = JSONObject().apply {
            put("model", options.model)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", JSONArray().apply {
                chatMessages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
            put("max_tokens", options.maxTokens ?: 4096)
            put("temperature", options.temperature)
            put("stream", options.stream)
            options.topP?.let { put("top_p", it) }
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .apply { profile.customHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(LlmStreamEvent.Error("HTTP ${response.code}: ${response.body.string()}"))
                    return@flow
                }

                if (!options.stream) {
                    val json = JSONObject(response.body.string())
                    val text = json.optJSONArray("content")
                        ?.let { arr ->
                            (0 until arr.length())
                                .mapNotNull { arr.optJSONObject(it) }
                                .filter { it.optString("type") == "text" }
                                .joinToString("") { it.optString("text", "") }
                        } ?: ""
                    val usage = json.optJSONObject("usage")
                    emit(LlmStreamEvent.Completed(
                        fullText = text,
                        inputTokens = usage?.optInt("input_tokens", 0) ?: 0,
                        outputTokens = usage?.optInt("output_tokens", 0) ?: 0,
                    ))
                    return@flow
                }

                // SSE streaming — Claude events: content_block_delta, message_stop
                val source = response.body.source()
                val accumulated = StringBuilder()

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        try {
                            val event = JSONObject(data)
                            val type = event.optString("type")
                            when (type) {
                                "content_block_delta" -> {
                                    val delta = event.optJSONObject("delta")
                                    val text = delta?.optString("text")
                                    if (!text.isNullOrEmpty()) {
                                        accumulated.append(text)
                                        emit(LlmStreamEvent.Delta(text))
                                    }
                                }
                                "message_stop" -> break
                            }
                        } catch (_: Exception) {}
                    }
                }

                emit(LlmStreamEvent.Completed(fullText = accumulated.toString()))
            }
        } catch (e: Exception) {
            emit(LlmStreamEvent.Error(e.message ?: "请求失败"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
