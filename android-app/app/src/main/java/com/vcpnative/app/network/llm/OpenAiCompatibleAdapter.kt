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
 * OpenAI-compatible adapter — works with OpenAI, DeepSeek, Groq, OpenRouter,
 * SiliconFlow, Ollama, and any OpenAI-format endpoint.
 *
 * Inspired by aio-hub's openai-compatible adapter.
 */
class OpenAiCompatibleAdapter(
    private val httpClient: OkHttpClient,
    override val providerId: String = "openai-compatible",
) : LlmAdapter {

    override fun chat(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
    ): Flow<LlmStreamEvent> = flow {
        emit(LlmStreamEvent.Started)

        val baseUrl = profile.baseUrl.trimEnd('/')
        val endpoint = "$baseUrl/chat/completions"
        val apiKey = profile.apiKeys.firstOrNull() ?: ""

        val body = JSONObject().apply {
            put("model", options.model)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
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
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        profile.customHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        val request = requestBuilder.build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    emit(LlmStreamEvent.Error("HTTP ${response.code}: $errorBody"))
                    return@flow
                }

                if (!options.stream) {
                    val json = JSONObject(response.body.string())
                    val text = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "") ?: ""
                    val usage = json.optJSONObject("usage")
                    emit(LlmStreamEvent.Completed(
                        fullText = text,
                        inputTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                        outputTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                    ))
                    return@flow
                }

                // SSE streaming
                val source = response.body.source()
                val accumulated = StringBuilder()

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break

                        try {
                            val chunk = JSONObject(data)
                            val delta = chunk.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                            val content = delta?.optString("content")
                            if (!content.isNullOrEmpty()) {
                                accumulated.append(content)
                                emit(LlmStreamEvent.Delta(content))
                            }
                        } catch (_: Exception) {
                            // skip malformed chunks
                        }
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
