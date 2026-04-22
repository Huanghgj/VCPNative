package com.vcpnative.app.network.vcplog

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
    /** 原始 WebSocket JSON — 灵视中心需要完整结构化数据 */
    val rawJson: String? = null,
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

    private val _messages = MutableSharedFlow<VcpLogMessage>(extraBufferCapacity = 1024)
    val messages: SharedFlow<VcpLogMessage> = _messages.asSharedFlow()
    private val pendingMessages = Channel<VcpLogMessage>(capacity = 256)

    // 三条 WebSocket 连接
    private var logSocket: WebSocket? = null     // /VCPlog/
    private var infoSocket: WebSocket? = null    // /vcpinfo/
    private var mobileSocket: WebSocket? = null  // /vcp-mobile/

    private var currentUrl: String? = null
    private var currentKey: String? = null
    @Volatile private var shouldReconnect = false
    @Volatile private var reconnectAttempt = 0
    @Volatile private var hasEverConnected = false
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var logConnected = false
    @Volatile private var infoConnected = false
    @Volatile private var mobileConnected = false
    // Guards connect/disconnect/reconnect state transitions — WebSocket callbacks arrive on OkHttp threads
    private val stateLock = Any()

    init {
        scope.launch {
            for (message in pendingMessages) {
                _messages.emit(message)
            }
        }
    }

    fun connect(wsUrl: String, wsKey: String) {
        disconnect()
        if (wsUrl.isBlank() || wsKey.isBlank()) return
        synchronized(stateLock) {
            currentUrl = wsUrl
            currentKey = wsKey
            shouldReconnect = true
            reconnectAttempt = 0
            hasEverConnected = false
        }
        doConnect(wsUrl, wsKey)
    }

    fun disconnect() {
        synchronized(stateLock) {
            shouldReconnect = false
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            logSocket?.close(1000, "Client disconnect")
            infoSocket?.close(1000, "Client disconnect")
            mobileSocket?.close(1000, "Client disconnect")
            logSocket = null
            infoSocket = null
            mobileSocket = null
            logConnected = false
            infoConnected = false
            mobileConnected = false
        }
        _status.value = VcpLogConnectionStatus.Disconnected
    }

    fun sendApprovalResponse(requestId: String, approved: Boolean) {
        val json = JSONObject().apply {
            put("type", "tool_approval_response")
            put("data", JSONObject().apply {
                put("requestId", requestId)
                put("approved", approved)
            })
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
            logConnected || infoConnected || mobileConnected -> VcpLogConnectionStatus.Connected
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

        // ── 通道 3: Mobile（离线消息队列、心跳、移动端专属广播） ──
        val mobileUrl = "$wsBase/vcp-mobile/VCP_Key=$wsKey"
        Log.d(TAG, "Connecting VCP-Mobile: $mobileUrl")
        mobileSocket = wsClient.newWebSocket(
            Request.Builder().url(mobileUrl).build(),
            createMobileListener(),
        )
    }

    private fun createListener(tag: String, isLogChannel: Boolean) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "$tag WebSocket connected")
            if (isLogChannel) logConnected = true else infoConnected = true
            hasEverConnected = true
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
            if (!logConnected && !infoConnected) scheduleReconnect(t)
        }
    }

    private fun createMobileListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "VCP-Mobile WebSocket connected")
            mobileConnected = true
            hasEverConnected = true
            reconnectAttempt = 0
            updateStatus()
            // Start heartbeat to keep mobile channel alive
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    runCatching {
                        webSocket.send(JSONObject().put("type", "heartbeat").toString())
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "VCP-Mobile message (${text.length} chars): ${text.take(300)}")
            parseAndEmit(text, "VCP-Mobile")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "VCP-Mobile WebSocket closed: $code $reason")
            mobileConnected = false
            heartbeatJob?.cancel()
            updateStatus()
            if (!logConnected && !infoConnected && !mobileConnected) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "VCP-Mobile WebSocket failure: ${t.message}")
            mobileConnected = false
            heartbeatJob?.cancel()
            updateStatus()
            if (!logConnected && !infoConnected && !mobileConnected) scheduleReconnect(t)
        }
    }

    private fun scheduleReconnect(cause: Throwable? = null) {
        synchronized(stateLock) {
            if (!shouldReconnect) return
            // 从未成功连接过 + 连接被拒/不可达 → 服务器没开，不值得重连喵
            if (!hasEverConnected && cause != null && isUnreachableError(cause)) {
                Log.w(TAG, "Server unreachable (${cause.message}), not retrying")
                _status.value = VcpLogConnectionStatus.Error
                return
            }
            val url = currentUrl ?: return
            val key = currentKey ?: return
            reconnectAttempt++
            if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
                _status.value = VcpLogConnectionStatus.Error
                return
            }
            val delayMs = minOf(
                RECONNECT_BASE_DELAY_MS * (1L shl minOf(reconnectAttempt - 1, 4)),
                RECONNECT_MAX_DELAY_MS,
            )
            Log.d(TAG, "Reconnect #$reconnectAttempt in ${delayMs}ms")
            _status.value = VcpLogConnectionStatus.Connecting
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                if (shouldReconnect) doConnect(url, key)
            }
        }
    }

    private fun isUnreachableError(t: Throwable): Boolean {
        return t is java.net.ConnectException ||
            t is java.net.NoRouteToHostException ||
            t is java.net.UnknownHostException
    }

    // ── 消息解析（两条通道共用） ────────────────────

    private fun parseAndEmit(raw: String, source: String) {
        runCatching {
            val json = JSONObject(raw)
            val type = json.optString("type", "unknown")

            if (type in SILENT_CONTROL_MESSAGE_TYPES) {
                Log.d(TAG, "$source control message ignored: $type")
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
                // 保存原始 JSON，灵视中心需要完整结构
                val withRaw = message.copy(rawJson = raw)
                if (shouldSuppressNotification(json, withRaw)) {
                    Log.d(TAG, "$source notification suppressed: ${withRaw.type}/${withRaw.title}")
                    return
                }
                val enqueued = pendingMessages.trySend(withRaw)
                if (enqueued.isFailure) {
                    Log.e(TAG, "$source failed to enqueue ${withRaw.type}/${withRaw.title}: ${enqueued.exceptionOrNull()?.message}")
                }
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

    private fun shouldSuppressNotification(
        json: JSONObject,
        message: VcpLogMessage,
    ): Boolean {
        val mergedText = buildString {
            append(message.title)
            append('\n')
            append(message.content)
        }.lowercase()

        if (
            mergedText.contains("heartbeat") ||
            mergedText.contains("ping") ||
            mergedText.contains("pong")
        ) {
            return true
        }

        val data = json.optJSONObject("data")
        val source = data?.optString("source", "").orEmpty()
        if (
            source == "DistPluginManager" &&
            (
                mergedText.contains("heartbeat") ||
                mergedText.contains("checking server status")
            )
        ) {
            return true
        }

        return false
    }

    companion object {
        private const val TAG = "VcpLogClient"
        private const val RECONNECT_BASE_DELAY_MS = 3000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 20
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private val SILENT_CONTROL_MESSAGE_TYPES = setOf(
            "connection_ack",
            "heartbeat_ack",
            "heartbeat",
            "ping",
            "pong",
        )
    }
}
