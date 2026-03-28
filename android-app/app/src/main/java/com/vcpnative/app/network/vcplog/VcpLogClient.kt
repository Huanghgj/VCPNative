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
import java.util.concurrent.TimeUnit

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

/**
 * VCPLog + VCPInfo 双通道客户端。
 *
 * VCPToolBox 后端有两条 WebSocket 广播通道：
 * - /VCPlog/VCP_Key=   → 工具执行日志、审批请求、日记创建等
 * - /vcpinfo/VCP_Key=  → RAG 召回内容、思维链进度、Agent 委派状态等
 *
 * 两条通道的消息都合并到同一个 [messages] Flow 中。
 */
class VcpLogClient(
    okHttpClient: OkHttpClient,
) {
    private val wsClient = okHttpClient.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(VcpLogConnectionStatus.Disconnected)
    val status: StateFlow<VcpLogConnectionStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<VcpLogMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<VcpLogMessage> = _messages.asSharedFlow()

    // 两条 WebSocket 连接
    private var logSocket: WebSocket? = null     // /VCPlog/
    private var infoSocket: WebSocket? = null    // /vcpinfo/

    private var currentUrl: String? = null
    private var currentKey: String? = null
    private var shouldReconnect = false
    private var reconnectAttempt = 0
    private var logConnected = false
    private var infoConnected = false

    fun connect(wsUrl: String, wsKey: String) {
        disconnect()
        if (wsUrl.isBlank() || wsKey.isBlank()) return
        currentUrl = wsUrl
        currentKey = wsKey
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(wsUrl, wsKey)
    }

    fun disconnect() {
        shouldReconnect = false
        logSocket?.close(1000, "Client disconnect")
        infoSocket?.close(1000, "Client disconnect")
        logSocket = null
        infoSocket = null
        logConnected = false
        infoConnected = false
        _status.value = VcpLogConnectionStatus.Disconnected
    }

    fun sendApprovalResponse(requestId: String, approved: Boolean) {
        val json = JSONObject().apply {
            put("type", "tool_approval_response")
            put("requestId", requestId)
            put("approved", approved)
        }
        logSocket?.send(json.toString())
    }

    private fun toWsBase(rawUrl: String): String {
        var base = rawUrl.trimEnd('/')
        if (base.startsWith("http://")) base = "ws://" + base.removePrefix("http://")
        else if (base.startsWith("https://")) base = "wss://" + base.removePrefix("https://")
        else if (!base.startsWith("ws://") && !base.startsWith("wss://")) base = "ws://$base"
        return base
    }

    private fun updateStatus() {
        _status.value = when {
            logConnected || infoConnected -> VcpLogConnectionStatus.Connected
            else -> VcpLogConnectionStatus.Connecting
        }
    }

    private fun doConnect(wsUrl: String, wsKey: String) {
        _status.value = VcpLogConnectionStatus.Connecting
        val wsBase = toWsBase(wsUrl)

        // ── 通道 1: VCPLog（工具日志、审批） ──
        val logUrl = "$wsBase/VCPlog/VCP_Key=$wsKey"
        Log.d(TAG, "Connecting VCPLog: $logUrl")
        logSocket = wsClient.newWebSocket(
            Request.Builder().url(logUrl).build(),
            createListener("VCPLog", isLogChannel = true),
        )

        // ── 通道 2: VCPInfo（RAG 召回、思维链、委派） ──
        val infoUrl = "$wsBase/vcpinfo/VCP_Key=$wsKey"
        Log.d(TAG, "Connecting VCPInfo: $infoUrl")
        infoSocket = wsClient.newWebSocket(
            Request.Builder().url(infoUrl).build(),
            createListener("VCPInfo", isLogChannel = false),
        )
    }

    private fun createListener(tag: String, isLogChannel: Boolean) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "$tag WebSocket connected")
            if (isLogChannel) logConnected = true else infoConnected = true
            reconnectAttempt = 0
            updateStatus()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "$tag message (${text.length} chars): ${text.take(300)}")
            parseAndEmit(text, tag)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "$tag WebSocket closed: $code $reason")
            if (isLogChannel) logConnected = false else infoConnected = false
            updateStatus()
            if (!logConnected && !infoConnected) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "$tag WebSocket failure: ${t.message}")
            if (isLogChannel) logConnected = false else infoConnected = false
            updateStatus()
            if (!logConnected && !infoConnected) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = currentUrl ?: return
        val key = currentKey ?: return
        reconnectAttempt++
        val delayMs = minOf(
            RECONNECT_BASE_DELAY_MS * (1L shl minOf(reconnectAttempt - 1, 4)),
            RECONNECT_MAX_DELAY_MS,
        )
        Log.d(TAG, "Reconnect #$reconnectAttempt in ${delayMs}ms")
        _status.value = VcpLogConnectionStatus.Connecting
        scope.launch {
            delay(delayMs)
            if (shouldReconnect) doConnect(url, key)
        }
    }

    // ── 消息解析（两条通道共用） ────────────────────

    private fun parseAndEmit(raw: String, source: String) {
        runCatching {
            val json = JSONObject(raw)
            val type = json.optString("type", "unknown")

            if (type == "connection_ack") {
                Log.d(TAG, "$source connection acknowledged")
                return
            }

            val message = when (type) {
                "vcp_log" -> parseVcpLog(json)
                "tool_approval_request" -> parseApprovalRequest(json)
                "daily_note_created" -> parseDailyNote(json)
                "video_generation_status" -> parseVideoStatus(json)
                "RAG_RETRIEVAL_DETAILS" -> parseRagRetrieval(json)
                "DailyNote" -> parseDailyNoteAction(json)
                "warning", "info", "error", "success" -> parseStatusMessage(json, type)
                else -> parseGeneric(json, type)
            }

            if (message != null) {
                val emitted = _messages.tryEmit(message)
                Log.d(TAG, "$source emit ${message.type}/${message.title} (ok=$emitted, subs=${_messages.subscriptionCount.value})")
            }
        }.onFailure {
            Log.w(TAG, "$source parse failed: ${raw.take(200)}", it)
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

        return VcpLogMessage(
            type = "vcp_log",
            title = title,
            content = extractDisplayContent(content),
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

    /** RAG 召回详情（VCPInfo 通道） */
    private fun parseRagRetrieval(json: JSONObject): VcpLogMessage {
        val dbName = json.optString("dbName", "")
        val k = json.optInt("k", 0)
        val threshold = json.optDouble("threshold", 0.0)
        val results = json.optJSONArray("results")
        val resultCount = results?.length() ?: 0

        val content = buildString {
            append("数据库: $dbName\n")
            append("召回 $resultCount 条 (k=$k, 阈值=${"%.2f".format(threshold)})\n")
            if (results != null) {
                for (i in 0 until minOf(resultCount, 5)) {
                    val r = results.optJSONObject(i) ?: continue
                    val name = r.optString("name", "?")
                    val score = r.optDouble("score", 0.0)
                    val preview = r.optString("preview", "").take(80)
                    append("\n[${"%.2f".format(score)}] $name")
                    if (preview.isNotBlank()) append("\n  $preview")
                }
                if (resultCount > 5) append("\n... 还有 ${resultCount - 5} 条")
            }
        }

        return VcpLogMessage(
            type = "RAG_RETRIEVAL_DETAILS",
            title = "RAG 召回 · $dbName",
            content = content,
            toolName = "RAG",
        )
    }

    /** DailyNote 动作（VCPInfo 通道：FullTextRecall / DirectRecall 等） */
    private fun parseDailyNoteAction(json: JSONObject): VcpLogMessage {
        val action = json.optString("action", "")
        val dbName = json.optString("dbName", "")
        val message = json.optString("message", "")

        val title = when (action) {
            "FullTextRecall" -> "日记全文召回 · $dbName"
            "DirectRecall" -> "日记直接引入 · $dbName"
            else -> "日记 · $action · $dbName"
        }

        return VcpLogMessage(
            type = "DailyNote",
            title = title,
            content = message.ifBlank { "$action on $dbName" },
            toolName = "DailyNote",
        )
    }

    /** 状态消息（warning/info/error/success） */
    private fun parseStatusMessage(json: JSONObject, type: String): VcpLogMessage {
        val source = json.optString("source", "")
        val message = json.optString("message", "")

        return VcpLogMessage(
            type = type,
            title = buildString {
                when (type) {
                    "warning" -> append("⚠️ 警告")
                    "error" -> append("❌ 错误")
                    "success" -> append("✅ 成功")
                    else -> append("ℹ️ 信息")
                }
                if (source.isNotBlank()) append(" · $source")
            },
            content = message.ifBlank { json.toString() },
            toolName = source.ifBlank { null },
        )
    }

    private fun parseGeneric(json: JSONObject, type: String): VcpLogMessage {
        // VCPInfo 通道的消息可能没有标准 type，直接用整个 JSON
        val message = json.optString("message", "")
        val data = json.opt("data")?.toString() ?: ""
        val action = json.optString("action", "")
        val dbName = json.optString("dbName", "")

        val title = buildString {
            if (type != "unknown") append(type)
            if (action.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append(action)
            }
            if (dbName.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append(dbName)
            }
        }.ifBlank { "VCP Info" }

        return VcpLogMessage(
            type = type,
            title = title,
            content = message.ifBlank { data }.ifBlank { json.toString(2) },
        )
    }

    private fun extractDisplayContent(raw: String): String {
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
