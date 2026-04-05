package com.vcpnative.app.feature.overlay

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.feature.overlay.agent.AgentToolExecutor
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.CompiledMessage
import com.vcpnative.app.model.CompiledMessagePart
import com.vcpnative.app.model.StreamSessionEvent
import com.vcpnative.app.network.vcp.VcpServiceConfig
import com.vcpnative.app.network.vcp.toServiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OverlayMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val screenshotDataUrl: String? = null,
    val isToolResult: Boolean = false,
    val isAgentAction: Boolean = false,
)

class OverlayChatManager(
    private val appContainer: AppContainer,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "OverlayChatManager"
        private const val MAX_AGENT_ROUNDS = 15
        private const val OPERATION_DELAY_MS = 600L
        private const val MAX_HISTORY = 30
        /** Only keep the N most recent screenshots in the request to avoid 413 */
        private const val MAX_SCREENSHOTS_IN_REQUEST = 2
    }

    val messages = mutableStateListOf<OverlayMessage>()
    val debugLog = mutableStateListOf<String>()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    private val _agentStatus = MutableStateFlow<String?>(null)
    val agentStatus = _agentStatus.asStateFlow()

    var isAgentMode = mutableStateOf(false)

    var toolExecutor: AgentToolExecutor? = null

    private var activeJob: Job? = null

    // Callback for confirmation; set by UI
    var onConfirmationRequired: (suspend (String) -> Boolean)? = null

    fun sendMessage(text: String, screenshotDataUrl: String? = null) {
        if (text.isBlank() && screenshotDataUrl == null) return

        val userMessage = OverlayMessage(
            role = "user",
            content = text,
            screenshotDataUrl = screenshotDataUrl,
        )
        messages.add(userMessage)
        trimHistory()

        if (isAgentMode.value && toolExecutor != null) {
            startAgentLoop(text)
        } else {
            startSimpleChat(text, screenshotDataUrl)
        }
    }

    fun stopAgent() {
        activeJob?.cancel()
        _isStreaming.value = false
        _agentStatus.value = null
    }

    fun clearHistory() {
        activeJob?.cancel()
        messages.clear()
        _isStreaming.value = false
        _agentStatus.value = null
    }

    fun clearDebugLog() { debugLog.clear() }

    private fun logDebug(entry: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        debugLog.add("[$ts] $entry")
        while (debugLog.size > 100) debugLog.removeAt(0)
    }

    private fun debugSerializeRequest(request: CompiledChatRequest): String {
        val json = JSONObject()
        val messagesArr = JSONArray()
        request.messages.forEach { msg ->
            val content: Any = if (msg.contentParts.isEmpty()) {
                msg.textContent.orEmpty()
            } else {
                JSONArray().apply {
                    msg.contentParts.forEach { part ->
                        put(
                            when (part.type) {
                                "text" -> JSONObject()
                                    .put("type", "text")
                                    .put("text", part.text.orEmpty())
                                else -> {
                                    val url = part.dataUrl.orEmpty()
                                    val display = if (url.length > 80) {
                                        "${url.take(40)}...[${url.length} chars total]"
                                    } else {
                                        url
                                    }
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", display))
                                }
                            },
                        )
                    }
                }
            }
            messagesArr.put(JSONObject().put("role", msg.role).put("content", content))
        }
        json.put("messages", messagesArr)
        json.put("model", request.model)
        json.put("temperature", request.temperature)
        json.put("stream", request.stream)
        json.put("requestId", request.requestId)
        request.maxTokens?.let { json.put("max_tokens", it) }
        request.contextTokenLimit?.let { json.put("contextTokenLimit", it) }
        request.topP?.let { json.put("top_p", it) }
        request.topK?.let { json.put("top_k", it) }
        if (request.thinking == true) {
            json.put("thinking", JSONObject().put("type", "enabled"))
        }
        return json.toString(2)
    }

    // ── Simple chat (existing behavior) ──

    private fun startSimpleChat(text: String, screenshotDataUrl: String?) {
        val assistantMessage = OverlayMessage(role = "assistant", content = "")
        messages.add(assistantMessage)
        val assistantIndex = messages.lastIndex

        activeJob = scope.launch {
            _isStreaming.value = true
            try {
                val fullText = StringBuilder()
                val request = buildChatRequest(screenshotDataUrl = screenshotDataUrl)
                logDebug("── 请求 ──")
                logDebug("URL: ${request.endpoint}")
                logDebug("Key: ${request.apiKey.take(8)}...${request.apiKey.takeLast(4)}")
                logDebug("Body:\n${debugSerializeRequest(request)}")
                appContainer.streamSessionManager.submit(request).collect { event ->
                    handleStreamEvent(event, assistantIndex, fullText)
                    when (event) {
                        is StreamSessionEvent.Completed ->
                            logDebug("✅ 完成 (${event.fullText.length} chars)")
                        is StreamSessionEvent.Failed ->
                            logDebug("❌ 失败: ${event.message}")
                        is StreamSessionEvent.Interrupted ->
                            logDebug("⚠️ 中断")
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logDebug("❌ 异常: ${e.javaClass.simpleName}: ${e.message}")
                messages[assistantIndex] = messages[assistantIndex].copy(
                    content = "出错了: ${e.message}",
                )
            } finally {
                _isStreaming.value = false
            }
        }
    }

    // ── Agent loop ──

    private fun startAgentLoop(userTask: String) {
        activeJob = scope.launch {
            _isStreaming.value = true
            val executor = toolExecutor ?: return@launch
            var rounds = 0

            try {
                // Auto read screen at start — use "user" role so the message
                // list keeps strict user/assistant alternation (Claude API
                // requires the last message to be "user").
                _agentStatus.value = "正在读取屏幕..."
                val initialRead = executor.execute(
                    AgentToolExecutor.ToolCall("read_screen", org.json.JSONObject()),
                )
                messages.add(OverlayMessage(
                    role = "user",
                    content = "[屏幕状态]\n${initialRead.result}",
                    isToolResult = true,
                ))
                trimHistory()

                while (rounds < MAX_AGENT_ROUNDS) {
                    rounds++
                    _agentStatus.value = "AI 思考中... ($rounds/$MAX_AGENT_ROUNDS)"

                    // Build request and get LLM response
                    val assistantMessage = OverlayMessage(role = "assistant", content = "")
                    messages.add(assistantMessage)
                    val assistantIndex = messages.lastIndex

                    val fullText = StringBuilder()
                    val request = buildAgentRequest()
                    logDebug("── Agent 轮次 $rounds ──")
                    logDebug("URL: ${request.endpoint}")
                    logDebug("Body:\n${debugSerializeRequest(request)}")
                    appContainer.streamSessionManager.submit(request).collect { event ->
                        handleStreamEvent(event, assistantIndex, fullText)
                        when (event) {
                            is StreamSessionEvent.Failed ->
                                logDebug("❌ Agent 轮次 $rounds 失败: ${event.message}")
                            is StreamSessionEvent.Completed ->
                                logDebug("✅ Agent 轮次 $rounds 完成")
                            else -> {}
                        }
                    }

                    val responseText = fullText.toString()
                    logDebug("LLM 回复 (${responseText.length} chars): ${responseText.take(200)}")
                    if (responseText.isBlank()) {
                        logDebug("⚠️ 空回复，终止循环")
                        break
                    }

                    // Try to parse tool call
                    val toolCall = executor.parseToolCall(responseText)
                    if (toolCall == null) {
                        logDebug("⚠️ 未解析到 tool call，终止循环")
                        break
                    }
                    logDebug("🔧 Tool: ${toolCall.tool} args: ${toolCall.args}")

                    if (toolCall.tool == "finish") {
                        val result = executor.execute(toolCall)
                        logDebug("🏁 Finish: ${result.result}")
                        messages.add(OverlayMessage(
                            role = "assistant",
                            content = "✅ ${result.result}",
                            isAgentAction = true,
                        ))
                        break
                    }

                    // Check for sensitive operations
                    val confirmMsg = executor.requiresConfirmation(toolCall)
                    if (confirmMsg != null) {
                        val confirmed = onConfirmationRequired?.invoke(confirmMsg) ?: false
                        if (!confirmed) {
                            messages.add(OverlayMessage(
                                role = "assistant",
                                content = "⚠️ 用户取消了操作: $confirmMsg",
                                isAgentAction = true,
                            ))
                            break
                        }
                    }

                    // Execute the tool
                    _agentStatus.value = "执行: ${toolCall.tool}..."
                    val result = executor.execute(toolCall)
                    logDebug("${if (result.success) "✅" else "❌"} ${toolCall.tool} → ${result.result}")

                    // Add tool result to chat
                    val resultContent = if (result.success) "✅ ${result.result}" else "❌ ${result.result}"
                    messages.add(OverlayMessage(
                        role = "user", // Tool results go as "user" messages for the LLM
                        content = "[工具结果] $resultContent",
                        screenshotDataUrl = result.screenshotDataUrl,
                        isToolResult = true,
                    ))
                    trimHistory()

                    // Wait for UI to settle after action
                    delay(OPERATION_DELAY_MS)

                    // Auto read screen after action (except for read_screen itself and screenshot)
                    if (toolCall.tool != "read_screen" && toolCall.tool != "screenshot") {
                        _agentStatus.value = "读取屏幕变化..."
                        val screenResult = executor.execute(
                            AgentToolExecutor.ToolCall("read_screen", org.json.JSONObject()),
                        )
                        messages.add(OverlayMessage(
                            role = "user",
                            content = "[屏幕更新]\n${screenResult.result}",
                            isToolResult = true,
                        ))
                        trimHistory()
                    }
                }

                if (rounds >= MAX_AGENT_ROUNDS) {
                    messages.add(OverlayMessage(
                        role = "assistant",
                        content = "⚠️ 已达到最大操作轮次 ($MAX_AGENT_ROUNDS)，自动停止。",
                        isAgentAction = true,
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error", e)
                messages.add(OverlayMessage(
                    role = "assistant",
                    content = "Agent 出错: ${e.message}",
                ))
            } finally {
                _isStreaming.value = false
                _agentStatus.value = null
            }
        }
    }

    // ── Request building ──

    private suspend fun buildChatRequest(screenshotDataUrl: String? = null): CompiledChatRequest {
        val settings = appContainer.settingsRepository.currentSettings()

        val systemPrompt = if (isAgentMode.value) {
            AgentToolExecutor.SYSTEM_PROMPT
        } else {
            AgentToolExecutor.CHAT_SYSTEM_PROMPT
        }

        // Use overlay-specific config if set, otherwise fall back to global
        val hasOverlayConfig = settings.overlayApiUrl.isNotBlank() && settings.overlayApiKey.isNotBlank()
        val serviceConfig = if (hasOverlayConfig) {
            VcpServiceConfig(
                baseUrl = settings.overlayApiUrl.trim(),
                apiKey = settings.overlayApiKey.trim(),
            )
        } else {
            // Use the exact same config path as main chat
            settings.toServiceConfig()
        }
        val model = settings.overlayModel.ifBlank {
            "gemini-2.5-flash"
        }

        // Count screenshots from newest to oldest so we only keep the most recent ones,
        // preventing the request body from growing unboundedly and hitting 413.
        val screenshotMessages = messages.indices
            .filter { messages[it].screenshotDataUrl != null }
        val allowedScreenshotIds = screenshotMessages
            .takeLast(MAX_SCREENSHOTS_IN_REQUEST)
            .map { messages[it].id }
            .toSet()

        // Build raw message list, then merge consecutive same-role messages
        // so upstream APIs that require strict user/assistant alternation don't 400.
        val rawMessages = buildList {
            add(CompiledMessage(role = "system", textContent = systemPrompt))
            for (msg in messages) {
                if (msg.role == "user") {
                    val keepImage = msg.screenshotDataUrl != null && msg.id in allowedScreenshotIds
                    if (keepImage) {
                        val parts = buildList {
                            if (msg.content.isNotBlank()) {
                                add(CompiledMessagePart(type = "text", text = msg.content))
                            }
                            add(CompiledMessagePart(type = "image_url", dataUrl = msg.screenshotDataUrl!!))
                        }
                        add(CompiledMessage(role = "user", contentParts = parts))
                    } else if (msg.content.isNotBlank()) {
                        add(CompiledMessage(role = "user", textContent = msg.content))
                    }
                } else if (msg.role == "assistant" && msg.content.isNotBlank()) {
                    add(CompiledMessage(role = "assistant", textContent = msg.content))
                }
            }
        }

        // Merge consecutive same-role messages into one so upstream APIs
        // that require strict user/assistant alternation don't reject with 400.
        val compiledMessages = mutableListOf<CompiledMessage>()
        for (msg in rawMessages) {
            val prev = compiledMessages.lastOrNull()
            if (prev != null && prev.role == msg.role && prev.contentParts.isEmpty() && msg.contentParts.isEmpty()) {
                compiledMessages[compiledMessages.lastIndex] = CompiledMessage(
                    role = prev.role,
                    textContent = "${prev.textContent.orEmpty()}\n${msg.textContent.orEmpty()}",
                )
            } else {
                compiledMessages.add(msg)
            }
        }

        // Match main chat's requestId format exactly
        val requestId = "msg_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}"

        return CompiledChatRequest(
            agentId = "_overlay",
            topicId = "_overlay",
            requestId = requestId,
            endpoint = serviceConfig.chatUrl(settings.enableVcpToolInjection),
            apiBaseUrl = serviceConfig.apiRootUrl,
            apiKey = serviceConfig.apiKey,
            model = model,
            messages = compiledMessages,
        )
    }

    private suspend fun buildAgentRequest(): CompiledChatRequest {
        return buildChatRequest()
    }

    private fun handleStreamEvent(
        event: StreamSessionEvent,
        assistantIndex: Int,
        fullText: StringBuilder,
    ) {
        when (event) {
            is StreamSessionEvent.TextDelta -> {
                fullText.append(event.text)
                messages[assistantIndex] = messages[assistantIndex].copy(
                    content = fullText.toString(),
                )
            }
            is StreamSessionEvent.Completed -> {
                fullText.clear()
                fullText.append(event.fullText)
                messages[assistantIndex] = messages[assistantIndex].copy(
                    content = event.fullText,
                )
            }
            is StreamSessionEvent.Failed -> {
                messages[assistantIndex] = messages[assistantIndex].copy(
                    content = event.partialText.ifBlank { "请求失败: ${event.message}" },
                )
            }
            is StreamSessionEvent.Interrupted -> {
                fullText.clear()
                fullText.append(event.partialText)
                messages[assistantIndex] = messages[assistantIndex].copy(
                    content = event.partialText,
                )
            }
            is StreamSessionEvent.Started -> {}
        }
    }

    private fun trimHistory() {
        while (messages.size > MAX_HISTORY) {
            messages.removeAt(0)
        }
    }
}
