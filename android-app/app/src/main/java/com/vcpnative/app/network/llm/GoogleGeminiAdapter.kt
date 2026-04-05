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
 * Google Gemini adapter — uses Gemini's native generateContent API.
 *
 * Inspired by aio-hub's google adapter.
 */
class GoogleGeminiAdapter(
    private val httpClient: OkHttpClient,
) : LlmAdapter {
    override val providerId = "google"

    override fun chat(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
    ): Flow<LlmStreamEvent> = flow {
        emit(LlmStreamEvent.Started)

        val apiKey = profile.apiKeys.firstOrNull() ?: ""
        val baseUrl = profile.baseUrl.trimEnd('/')
        val action = if (options.stream) "streamGenerateContent?alt=sse" else "generateContent"
        val endpoint = "$baseUrl/models/${options.model}:$action&key=$apiKey"

        // Build Gemini format: { contents: [{role, parts: [{text}]}], systemInstruction, generationConfig }
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
                        put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                    })
                }
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", options.temperature)
                options.maxTokens?.let { put("maxOutputTokens", it) }
                options.topP?.let { put("topP", it) }
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
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
                    val text = extractGeminiText(json)
                    emit(LlmStreamEvent.Completed(fullText = text))
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
                        try {
                            val chunk = JSONObject(data)
                            val text = extractGeminiText(chunk)
                            if (text.isNotEmpty()) {
                                accumulated.append(text)
                                emit(LlmStreamEvent.Delta(text))
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
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
