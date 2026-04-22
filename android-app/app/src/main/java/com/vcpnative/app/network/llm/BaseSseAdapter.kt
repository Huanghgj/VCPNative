package com.vcpnative.app.network.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Base class for SSE-based LLM adapters — owns the common
 * flow { Started → request → SSE loop → Completed }.flowOn(IO) skeleton.
 *
 * Subclasses only provide provider-specific logic:
 * - [buildRequest]: construct the OkHttp request
 * - [parseNonStreamingResponse]: extract text + tokens from a full JSON response
 * - [processStreamChunk]: extract delta text from one SSE JSON chunk
 * - [isRawStreamTerminator]: detect raw-string terminators like `[DONE]`
 *
 * Inspired by gpt_mobile's unified ChatRepository + per-provider transform pattern.
 */
abstract class BaseSseAdapter(
    protected val httpClient: OkHttpClient,
) : LlmAdapter {

    protected abstract val tag: String

    /** Build the provider-specific HTTP request. [apiKey] is already validated non-blank. */
    protected abstract fun buildRequest(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
        apiKey: String,
    ): Request

    /** Parse a non-streaming (complete) JSON response body into a Completed event. */
    protected abstract fun parseNonStreamingResponse(json: JSONObject): LlmStreamEvent.Completed

    /** Result of processing a single SSE JSON chunk. */
    protected sealed interface StreamResult {
        data class TextDelta(val text: String) : StreamResult
        data class ThinkingDelta(val text: String) : StreamResult
        data object EndOfStream : StreamResult
        data object Skip : StreamResult
    }

    /** Extract content from one SSE JSON chunk. Return [StreamResult]. */
    protected abstract fun processStreamChunk(json: JSONObject): StreamResult

    /** Check if a raw SSE data string (before JSON parsing) signals end of stream.
     *  Default: no raw terminator. Override for OpenAI's `[DONE]`. */
    protected open fun isRawStreamTerminator(data: String): Boolean = false

    /** Whether this provider requires a non-blank API key.
     *  Override to false for local providers like Ollama. */
    protected open fun requiresApiKey(profile: LlmProfile): Boolean = true

    /** Extract the API key from the profile. */
    protected open fun getApiKey(profile: LlmProfile): String =
        profile.apiKeys.firstOrNull() ?: ""

    override fun chat(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
    ): Flow<LlmStreamEvent> = flow {
        emit(LlmStreamEvent.Started)

        val apiKey = getApiKey(profile)
        if (apiKey.isBlank() && requiresApiKey(profile)) {
            emit(LlmStreamEvent.Error("API key not configured"))
            return@flow
        }

        val request = buildRequest(profile, messages, options, apiKey)

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    emit(LlmStreamEvent.Error("HTTP ${response.code}: $errorBody"))
                    return@flow
                }

                if (!options.stream) {
                    val bodyText = response.body.string()
                    val json = JSONObject(bodyText)
                    emit(parseNonStreamingResponse(json))
                    return@flow
                }

                // SSE streaming loop
                val source = response.body.source()
                val accumulated = StringBuilder()

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()

                        if (isRawStreamTerminator(data)) break

                        try {
                            val chunk = JSONObject(data)
                            when (val result = processStreamChunk(chunk)) {
                                is StreamResult.TextDelta -> {
                                    accumulated.append(result.text)
                                    emit(LlmStreamEvent.Delta(result.text))
                                }
                                is StreamResult.ThinkingDelta -> {
                                    emit(LlmStreamEvent.Thinking(result.text))
                                }
                                is StreamResult.EndOfStream -> break
                                is StreamResult.Skip -> { /* ignore */ }
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Malformed SSE chunk", e)
                        }
                    }
                }

                emit(LlmStreamEvent.Completed(fullText = accumulated.toString()))
            }
        } catch (e: Exception) {
            emit(LlmStreamEvent.Error(e.message ?: "请求失败"))
        }
    }.flowOn(Dispatchers.IO)
}
