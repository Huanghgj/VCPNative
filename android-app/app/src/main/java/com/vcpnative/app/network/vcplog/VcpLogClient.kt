package com.vcpnative.app.network.vcplog

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class VcpLogConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Error,
}

data class VcpLogMessage(
    val type: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isApprovalRequest: Boolean = false,
    val requestId: String? = null,
    val toolName: String? = null,
    val maidName: String? = null,
)

class VcpLogClient(
    okHttpClient: OkHttpClient,
) {
    // WebSocket 专用客户端：加心跳 ping + 无限 read timeout（长连接不能超时）
    private val wsClient = okHttpClient.newBuilder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(VcpLogConnectionStatus.Disconnected)
    val status: StateFlow<VcpLogConnectionStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<VcpLogMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<VcpLogMessage> = _messages.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var currentKey: String? = null
    private var shouldReconnect = false
    private var reconnectAttempt = 0

    fun connect(wsUrl: String, wsKey: String) {
        disconnect()
        if (wsUrl.isBlank() || wsKey.isBlank()) {
            return
        }
        currentUrl = wsUrl
        currentKey = wsKey
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(wsUrl, wsKey)
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _status.value = VcpLogConnectionStatus.Disconnected
    }

    fun sendApprovalResponse(requestId: String, approved: Boolean) {
        val json = JSONObject().apply {
            put("type", "tool_approval_response")
            put("requestId", requestId)
            put("approved", approved)
        }
        webSocket?.send(json.toString())
    }

    private fun doConnect(wsUrl: String, wsKey: String) {
        _status.value = VcpLogConnectionStatus.Connecting

        val url = buildString {
            // 自动将 http(s):// 转为 ws(s):// — 用户可能配置的是 HTTP URL
            var base = wsUrl.trimEnd('/')
            if (base.startsWith("http://")) base = "ws://" + base.removePrefix("http://")
            else if (base.startsWith("https://")) base = "wss://" + base.removePrefix("https://")
            else if (!base.startsWith("ws://") && !base.startsWith("wss://")) base = "ws://$base"
            append(base)
            append("/VCPlog/VCP_Key=")
            append(wsKey)
        }
        Log.d(TAG, "VCPLog connecting to: $url")

        val request = Request.Builder().url(url).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = VcpLogConnectionStatus.Connected
                reconnectAttempt = 0
                Log.d(TAG, "VCPLog WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "VCPLog message received (${text.length} chars): ${text.take(200)}")
                parseAndEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "VCPLog WebSocket closed: $code $reason")
                _status.value = VcpLogConnectionStatus.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "VCPLog WebSocket failure", t)
                _status.value = VcpLogConnectionStatus.Error
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = currentUrl ?: return
        val key = currentKey ?: return
        reconnectAttempt++
        // Exponential backoff: 3s, 6s, 12s, 24s, max 60s
        val delayMs = minOf(
            RECONNECT_BASE_DELAY_MS * (1L shl minOf(reconnectAttempt - 1, 4)),
            RECONNECT_MAX_DELAY_MS,
        )
        Log.d(TAG, "VCPLog reconnect #$reconnectAttempt in ${delayMs}ms")
        _status.value = VcpLogConnectionStatus.Connecting
        scope.launch {
            delay(delayMs)
            if (shouldReconnect) {
                doConnect(url, key)
            }
        }
    }

    private fun parseAndEmit(raw: String) {
        runCatching {
            val json = JSONObject(raw)
            val type = json.optString("type", "unknown")

            // Skip connection ack
            if (type == "connection_ack") return

            val message = when (type) {
                "vcp_log" -> parseVcpLog(json)
                "tool_approval_request" -> parseApprovalRequest(json)
                "daily_note_created" -> parseDailyNote(json)
                "video_generation_status" -> parseVideoStatus(json)
                else -> parseGeneric(json, type)
            }

            if (message != null) {
                val emitted = _messages.tryEmit(message)
                Log.d(TAG, "VCPLog emit ${message.type}/${message.title} (success=$emitted, subscribers=${_messages.subscriptionCount.value})")
            }
        }.onFailure {
            Log.w(TAG, "Failed to parse VCPLog message: $raw", it)
        }
    }

    private fun parseVcpLog(json: JSONObject): VcpLogMessage? {
        val data = json.optJSONObject("data") ?: return null
        val toolName = data.optString("tool_name", "")
        val status = data.optString("status", "")
        val content = data.opt("content")?.toString() ?: ""
        val maidName = data.optString("MaidName", "").ifBlank { null }

        val title = buildString {
            if (toolName.isNotBlank()) append(toolName)
            if (status.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append(status)
            }
            if (maidName != null) {
                if (isNotBlank()) append(" · ")
                append(maidName)
            }
        }.ifBlank { "VCP Log" }

        val displayContent = extractDisplayContent(content)

        return VcpLogMessage(
            type = "vcp_log",
            title = title,
            content = displayContent,
            toolName = toolName.ifBlank { null },
            maidName = maidName,
        )
    }

    private fun parseApprovalRequest(json: JSONObject): VcpLogMessage? {
        val data = json.optJSONObject("data") ?: return null
        val requestId = data.optString("requestId", "")
        val toolName = data.optString("toolName", "")
        val maid = data.optString("maid", "")
        val args = data.opt("args")?.toString() ?: ""

        return VcpLogMessage(
            type = "tool_approval_request",
            title = "Tool 审批请求",
            content = buildString {
                if (maid.isNotBlank()) append("Agent: $maid\n")
                if (toolName.isNotBlank()) append("Tool: $toolName\n")
                if (args.isNotBlank()) append("Args: $args")
            }.trim(),
            isApprovalRequest = true,
            requestId = requestId.ifBlank { null },
            toolName = toolName.ifBlank { null },
            maidName = maid.ifBlank { null },
        )
    }

    private fun parseDailyNote(json: JSONObject): VcpLogMessage {
        val data = json.optJSONObject("data")
        val maidName = data?.optString("maidName", "") ?: ""
        val dateString = data?.optString("dateString", "") ?: ""
        val status = data?.optString("status", "") ?: ""
        val message = data?.optString("message", "") ?: ""

        return VcpLogMessage(
            type = "daily_note_created",
            title = "Daily Note · $status",
            content = buildString {
                if (maidName.isNotBlank()) append("$maidName ")
                if (dateString.isNotBlank()) append("($dateString)")
                if (message.isNotBlank()) {
                    if (isNotBlank()) append("\n")
                    append(message)
                }
            }.trim().ifBlank { "日记已创建" },
            maidName = maidName.ifBlank { null },
        )
    }

    private fun parseVideoStatus(json: JSONObject): VcpLogMessage {
        val data = json.optJSONObject("data")
        val output = data?.optJSONObject("original_plugin_output")
        val message = output?.optString("message", "") ?: data?.optString("message", "") ?: ""

        return VcpLogMessage(
            type = "video_generation_status",
            title = "Video Generation",
            content = message.ifBlank { "视频生成状态更新" },
        )
    }

    private fun parseGeneric(json: JSONObject, type: String): VcpLogMessage {
        val message = json.optString("message", "")
        val data = json.opt("data")?.toString() ?: ""

        return VcpLogMessage(
            type = type,
            title = type,
            content = message.ifBlank { data }.ifBlank { type },
        )
    }

    private fun extractDisplayContent(raw: String): String {
        // Try to extract plugin_error from JSON-wrapped error content
        if (raw.startsWith("{")) {
            runCatching {
                val obj = JSONObject(raw)
                obj.optString("plugin_error").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("message").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("content").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return raw
    }

    companion object {
        private const val TAG = "VcpLogClient"
        private const val RECONNECT_BASE_DELAY_MS = 3000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
    }
}
