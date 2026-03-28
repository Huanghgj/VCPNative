package com.vcpnative.app.chat.session

import android.util.Log
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.StreamInterruptResult
import com.vcpnative.app.model.StreamSessionEvent
import com.vcpnative.app.network.sse.SseEventParser
import com.vcpnative.app.network.vcp.VcpServiceConfig
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

interface StreamSessionManager {
    fun submit(compiledRequest: CompiledChatRequest): Flow<StreamSessionEvent>

    suspend fun interrupt(requestId: String): StreamInterruptResult
}

class VcpToolBoxStreamSessionManager(
    private val okHttpClient: OkHttpClient,
    private val boundedHttpClient: OkHttpClient = okHttpClient,
) : StreamSessionManager {
    private val activeRequests = ConcurrentHashMap<String, ActiveStreamRequest>()

    override fun submit(compiledRequest: CompiledChatRequest): Flow<StreamSessionEvent> = flow {
        emit(StreamSessionEvent.Started)

        val serviceConfig = VcpServiceConfig(
            baseUrl = compiledRequest.apiBaseUrl ?: compiledRequest.endpoint,
            apiKey = compiledRequest.apiKey,
        )
        val request = Request.Builder()
            .url(compiledRequest.endpoint)
            .post(buildRequestBody(compiledRequest).toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${compiledRequest.apiKey}")
            .build()
        val call = okHttpClient.newCall(request)
        val activeRequest = ActiveStreamRequest(
            serviceConfig = serviceConfig,
            call = call,
        )
        activeRequests[compiledRequest.requestId] = activeRequest

        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }

        val partialTextBuilder = StringBuilder()
        var hasTerminalEvent = false

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    hasTerminalEvent = true
                    emit(
                        StreamSessionEvent.Failed(
                            partialText = partialTextBuilder.toString(),
                            message = extractErrorMessage(response.code, response.body.string()),
                        ),
                    )
                    return@flow
                }

                val body = response.body

                val contentType = response.header("Content-Type").orEmpty()
                if (!compiledRequest.stream || !contentType.contains("text/event-stream", ignoreCase = true)) {
                    hasTerminalEvent = true
                    emit(
                        StreamSessionEvent.Completed(
                            fullText = extractResponseText(body.string()),
                        ),
                    )
                    return@flow
                }

                val parser = SseEventParser()
                val source = body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val frame = parser.consumeLine(line) ?: continue
                    val chunkResult = parseStreamFrame(frame.data)
                    when {
                        frame.isDone -> {
                            hasTerminalEvent = true
                            emit(StreamSessionEvent.Completed(fullText = partialTextBuilder.toString()))
                            return@flow
                        }

                        chunkResult.errorMessage != null -> {
                            hasTerminalEvent = true
                            emit(
                                StreamSessionEvent.Failed(
                                    partialText = partialTextBuilder.toString(),
                                    message = chunkResult.errorMessage,
                                ),
                            )
                            return@flow
                        }

                        !chunkResult.deltaText.isNullOrEmpty() -> {
                            partialTextBuilder.append(chunkResult.deltaText)
                            emit(StreamSessionEvent.TextDelta(chunkResult.deltaText))
                        }
                    }
                }

                parser.flush()?.let { frame ->
                    if (frame.isDone) {
                        hasTerminalEvent = true
                        emit(StreamSessionEvent.Completed(fullText = partialTextBuilder.toString()))
                        return@flow
                    }

                    val chunkResult = parseStreamFrame(frame.data)
                    when {
                        chunkResult.errorMessage != null -> {
                            hasTerminalEvent = true
                            emit(
                                StreamSessionEvent.Failed(
                                    partialText = partialTextBuilder.toString(),
                                    message = chunkResult.errorMessage,
                                ),
                            )
                            return@flow
                        }

                        !chunkResult.deltaText.isNullOrEmpty() -> {
                            partialTextBuilder.append(chunkResult.deltaText)
                            emit(StreamSessionEvent.TextDelta(chunkResult.deltaText))
                        }
                    }
                }
            }

            if (!hasTerminalEvent) {
                emit(StreamSessionEvent.Completed(fullText = partialTextBuilder.toString()))
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }

            val interrupted = activeRequest.interrupted.get() ||
                (error is IOException && error.message?.contains("Canceled", ignoreCase = true) == true)
            val partialText = partialTextBuilder.toString()
            emit(
                if (interrupted) {
                    StreamSessionEvent.Interrupted(partialText = partialText)
                } else {
                    StreamSessionEvent.Failed(
                        partialText = partialText,
                        message = error.message ?: "VCP 请求失败",
                    )
                },
            )
        } finally {
            activeRequests.remove(compiledRequest.requestId)
            runCatching { call.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun interrupt(requestId: String): StreamInterruptResult {
        val activeRequest = activeRequests[requestId]
            ?: return StreamInterruptResult(
                success = false,
                message = "未找到活跃请求 $requestId",
            )

        // Mark as interrupted first so the stream loop recognises the cancel
        activeRequest.interrupted.set(true)

        // Send remote interrupt signal to backend BEFORE cancelling the local
        // connection — matches VCPChat behaviour where the backend is notified
        // first so it can stop generation.
        val interruptRequest = Request.Builder()
            .url(activeRequest.serviceConfig.interruptUrl)
            .post(
                JSONObject()
                    .put("requestId", requestId)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${activeRequest.serviceConfig.apiKey}")
            .build()

        val remoteResult = try {
            // Use bounded client (short timeout) for the interrupt call
            boundedHttpClient.newCall(interruptRequest).execute().use { response ->
                if (response.isSuccessful) {
                    StreamInterruptResult(
                        success = true,
                        message = "请求 $requestId 已中止",
                    )
                } else {
                    StreamInterruptResult(
                        success = false,
                        message = extractErrorMessage(response.code, response.body.string()),
                    )
                }
            }
        } catch (error: Throwable) {
            StreamInterruptResult(
                success = false,
                message = error.message ?: "中断请求失败",
            )
        }

        // Always cancel the local call regardless of remote result — ensures
        // the stream stops even if the backend interrupt endpoint is unreachable.
        activeRequest.call.cancel()

        return remoteResult
    }

    private fun buildRequestBody(compiledRequest: CompiledChatRequest): String {
        val json = JSONObject()
            .put("messages", JSONArray().apply {
                compiledRequest.messages.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", serializeMessageContent(message)),
                    )
                }
            })
            .put("model", compiledRequest.model)
            .put("temperature", compiledRequest.temperature)
            .put("stream", compiledRequest.stream)
            .put("requestId", compiledRequest.requestId)

        compiledRequest.maxTokens?.let { json.put("max_tokens", it) }
        compiledRequest.contextTokenLimit?.let { json.put("contextTokenLimit", it) }
        compiledRequest.topP?.let { json.put("top_p", it) }
        compiledRequest.topK?.let { json.put("top_k", it) }

        return json.toString()
    }

    private fun serializeMessageContent(message: com.vcpnative.app.model.CompiledMessage): Any =
        if (message.contentParts.isEmpty()) {
            message.textContent.orEmpty()
        } else {
            JSONArray().apply {
                message.contentParts.forEach { part ->
                    put(
                        when (part.type) {
                            "text" -> JSONObject()
                                .put("type", "text")
                                .put("text", part.text.orEmpty())

                            else -> JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", part.dataUrl.orEmpty()),
                                )
                        },
                    )
                }
            }
        }

    private fun parseStreamFrame(payload: String?): ParsedStreamChunk {
        if (payload.isNullOrBlank()) {
            return ParsedStreamChunk()
        }

        return try {
            val json = JSONObject(payload)
            val errorMessage = extractJsonError(json)
            if (errorMessage != null) {
                ParsedStreamChunk(errorMessage = errorMessage)
            } else {
                ParsedStreamChunk(deltaText = extractJsonText(json))
            }
        } catch (error: JSONException) {
            Log.w(TAG, "Ignoring non-JSON SSE chunk: $payload")
            ParsedStreamChunk()
        }
    }

    private fun extractResponseText(body: String): String {
        if (body.isBlank()) {
            return ""
        }

        return try {
            val json = JSONObject(body)
            extractJsonError(json)?.let { return it }
            extractJsonText(json).orEmpty()
        } catch (_: JSONException) {
            body
        }
    }

    private fun extractJsonError(json: JSONObject): String? {
        // Check for explicit "error" field first (string or object)
        val errorValue = json.opt("error")
        when (errorValue) {
            is String -> if (errorValue.isNotBlank()) {
                val directMessage = json.optString("message").takeIf { it.isNotBlank() }
                return if (directMessage != null && directMessage != errorValue) {
                    "$errorValue: $directMessage"
                } else {
                    errorValue
                }
            }
            is JSONObject -> {
                val errorMessage = errorValue.optString("message").takeIf { it.isNotBlank() }
                return errorMessage ?: errorValue.toString()
            }
        }
        // Do NOT treat a standalone "message" field as an error — it may be a normal response field
        return null
    }

    private fun extractJsonText(json: JSONObject): String? {
        json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?.let { delta ->
                extractContentText(delta.opt("content"))?.let { return it }
            }

        json.optJSONObject("delta")
            ?.let { delta ->
                extractContentText(delta.opt("content"))?.let { return it }
            }

        extractContentText(json.opt("content"))?.let { return it }

        json.optJSONObject("message")
            ?.let { message ->
                extractContentText(message.opt("content"))?.let { return it }
            }

        json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.let { message ->
                extractContentText(message.opt("content"))?.let { return it }
            }

        return null
    }

    private fun extractContentText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is JSONObject -> {
            value.optString("text").takeIf { it.isNotBlank() }
                ?: extractContentText(value.opt("content"))
        }

        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val text = extractContentText(value.opt(index))
                if (!text.isNullOrBlank()) {
                    append(text)
                }
            }
        }.takeIf { it.isNotBlank() }

        else -> value.toString()
    }

    private fun extractErrorMessage(code: Int, body: String): String {
        val message = if (body.isBlank()) {
            "服务器返回状态 $code"
        } else {
            extractResponseText(body)
        }
        return "VCP 请求失败: $code - $message"
    }

    private data class ActiveStreamRequest(
        val serviceConfig: VcpServiceConfig,
        val call: Call,
        val interrupted: AtomicBoolean = AtomicBoolean(false),
    )

    private data class ParsedStreamChunk(
        val deltaText: String? = null,
        val errorMessage: String? = null,
    )

    companion object {
        private const val TAG = "VcpStreamSession"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
