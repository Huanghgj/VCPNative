package com.vcpnative.app.network.vcp

import android.util.Log
import com.vcpnative.app.network.vcplog.VcpLogClient
import com.vcpnative.app.network.vcplog.VcpLogMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * VCP Tool Bridge — mirrors aio-hub's VcpBridgeFactory.
 *
 * Connects to VCPToolBox backend via VcpLogClient WebSocket and:
 * 1. Receives tool execution logs and approval requests
 * 2. Displays available distributed server tools
 * 3. Sends approval responses
 *
 * The actual tool invocation happens through the chat API (chatvcp/completions),
 * this bridge handles the monitoring and approval side.
 */
class VcpToolBridge(
    private val vcpLogClient: VcpLogClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 工具执行日志
    private val _toolLogs = MutableStateFlow<List<ToolLogEntry>>(emptyList())
    val toolLogs: StateFlow<List<ToolLogEntry>> = _toolLogs.asStateFlow()

    // 待审批的工具请求
    private val _pendingApprovals = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ToolApprovalRequest>> = _pendingApprovals.asStateFlow()

    init {
        // 监听 VcpLog 消息，提取工具相关事件
        scope.launch {
            vcpLogClient.messages.collect { message ->
                when (message.type) {
                    "vcp_log" -> {
                        val entry = ToolLogEntry(
                            toolName = message.toolName ?: "unknown",
                            maidName = message.maidName,
                            title = message.title,
                            content = message.content,
                            timestamp = message.timestamp,
                        )
                        _toolLogs.value = (_toolLogs.value + entry).takeLast(100)
                    }
                    "tool_approval_request" -> {
                        val request = ToolApprovalRequest(
                            requestId = message.requestId ?: return@collect,
                            toolName = message.toolName ?: "unknown",
                            maidName = message.maidName,
                            content = message.content,
                            timestamp = message.timestamp,
                        )
                        _pendingApprovals.value = _pendingApprovals.value + request
                    }
                }
            }
        }
    }

    /** Approve a pending tool execution request. */
    fun approve(requestId: String) {
        vcpLogClient.sendApprovalResponse(requestId, approved = true)
        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.requestId == requestId }
    }

    /** Reject a pending tool execution request. */
    fun reject(requestId: String) {
        vcpLogClient.sendApprovalResponse(requestId, approved = false)
        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.requestId == requestId }
    }

    fun clearLogs() {
        _toolLogs.value = emptyList()
    }

    companion object {
        private const val TAG = "VcpToolBridge"
    }
}

data class ToolLogEntry(
    val toolName: String,
    val maidName: String?,
    val title: String,
    val content: String,
    val timestamp: Long,
)

data class ToolApprovalRequest(
    val requestId: String,
    val toolName: String,
    val maidName: String?,
    val content: String,
    val timestamp: Long,
)
