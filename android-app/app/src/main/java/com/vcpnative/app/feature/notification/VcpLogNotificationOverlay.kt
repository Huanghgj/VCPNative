package com.vcpnative.app.feature.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcpnative.app.network.vcplog.VcpLogConnectionStatus
import com.vcpnative.app.network.vcplog.VcpLogMessage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Toast 通知：右上角弹出，自动消失。
 */
@Composable
fun VcpLogToastOverlay(
    toasts: List<VcpLogMessage>,
    onDismiss: (VcpLogMessage) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            toasts.takeLast(MAX_VISIBLE_TOASTS).forEach { message ->
                ToastCard(
                    message = message,
                    onDismiss = { onDismiss(message) },
                    onApprove = onApprove,
                    onReject = onReject,
                )
            }
        }
    }
}

@Composable
private fun ToastCard(
    message: VcpLogMessage,
    onDismiss: () -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(true) }

    if (!message.isApprovalRequest) {
        LaunchedEffect(message) {
            delay(TOAST_DURATION_MS)
            visible = false
            delay(300)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .clickable {
                    if (!message.isApprovalRequest) {
                        visible = false
                        onDismiss()
                    }
                }
                .padding(12.dp),
        ) {
            Column {
                // 标题行：工具名 · Agent · 时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 状态圆点
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (message.isApprovalRequest) Color(0xFFFF9500)
                                    else MaterialTheme.colorScheme.primary
                                ),
                        )
                        Text(
                            text = message.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 内容（紧凑）
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content.take(200),
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 16.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // 审批按钮
                if (message.isApprovalRequest && message.requestId != null) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                onApprove(message.requestId)
                                visible = false; onDismiss()
                            },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("允许", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = {
                                onReject(message.requestId)
                                visible = false; onDismiss()
                            },
                            modifier = Modifier.weight(1f).height(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("拒绝", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

/**
 * 全屏通知面板 — Apple 通知中心风格。
 * 从右侧滑入，半透明背景，紧凑的通知卡片列表。
 */
@Composable
fun VcpLogSidebarPanel(
    visible: Boolean,
    connectionStatus: VcpLogConnectionStatus,
    notifications: List<VcpLogMessage>,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    if (!visible) return

    // 遮罩
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onDismiss() },
    )

    // 面板
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.88f)
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "信息广播",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    ConnectionDot(status = connectionStatus)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onClearAll) {
                        Icon(Icons.Outlined.DeleteSweep, "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "关闭")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // 分栏筛选
            var selectedTab by remember { mutableStateOf("observer") }
            val tabs = listOf(
                "observer" to "灵视中心",
                "all" to "全部",
                "rag" to "RAG 召回",
                "tool" to "工具调用",
                "approval" to "审批",
                "note" to "日记",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEach { (id, label) ->
                    val selected = selectedTab == id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { selectedTab = id }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            // 用 derivedStateOf 避免每次 notifications.size 变化都重建列表
            val filtered by remember(selectedTab) {
                derivedStateOf {
                    when (selectedTab) {
                        "observer" -> emptyList() // observer tab 不用 Compose 列表
                        "rag" -> notifications.filter {
                            it.type == "RAG_RETRIEVAL_DETAILS" || it.type == "DailyNote"
                        }
                        "tool" -> notifications.filter {
                            it.type == "vcp_log" && it.toolName != null
                        }
                        "approval" -> notifications.filter { it.isApprovalRequest }
                        "note" -> notifications.filter {
                            it.type == "daily_note_created" || it.type == "DailyNote"
                        }
                        else -> notifications.toList()
                    }
                }
            }

            // 灵视中心 tab → 嵌入 RAG Observer WebView（完整复用 VCPChat 桌面端渲染）
            if (selectedTab == "observer") {
                RagObserverWebView(
                    notifications = notifications,
                    modifier = Modifier.fillMaxSize(),
                )
            } else
            // 通知列表
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无通知",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = filtered.asReversed(),
                        key = { it.timestamp },
                    ) { msg ->
                        NotificationRow(
                            message = msg,
                            onApprove = onApprove,
                            onReject = onReject,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条通知 — 按类型差异化渲染。
 */
@Composable
private fun NotificationRow(
    message: VcpLogMessage,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    val cardMod = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surface)
        .padding(12.dp)

    when (message.type) {
        "tool_approval_request" -> ApprovalCard(message, onApprove, onReject, cardMod)
        "daily_note_created" -> DailyNoteCard(message, cardMod)
        "DailyNote" -> DailyNoteCard(message, cardMod)
        "RAG_RETRIEVAL_DETAILS" -> GenericCard(message, cardMod, icon = "🔍", accentColor = Color(0xFF5856D6))
        "video_generation_status" -> GenericCard(message, cardMod, icon = "🎬", accentColor = Color(0xFFFF9500))
        "warning" -> GenericCard(message, cardMod, icon = "⚠️", accentColor = Color(0xFFFF9500))
        "error" -> GenericCard(message, cardMod, icon = "❌", accentColor = MaterialTheme.colorScheme.error)
        "success" -> GenericCard(message, cardMod, icon = "✅", accentColor = Color(0xFF34C759))
        "info" -> GenericCard(message, cardMod, icon = "ℹ️", accentColor = MaterialTheme.colorScheme.primary)
        else -> ToolLogCard(message, cardMod)
    }
}

/** 工具执行日志（最常见类型）— 显示工具名、状态、Agent、内容摘要 */
@Composable
private fun ToolLogCard(message: VcpLogMessage, modifier: Modifier) {
    Column(modifier = modifier) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态点：成功=绿、失败=红、其他=蓝
                val dotColor = when {
                    message.title.contains("error", ignoreCase = true) -> MaterialTheme.colorScheme.error
                    message.title.contains("success", ignoreCase = true) -> Color(0xFF34C759)
                    else -> MaterialTheme.colorScheme.primary
                }
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))

                // 工具名
                message.toolName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Agent 名
                message.maidName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        // 状态标签（如果标题里有状态信息且不是纯工具名）
        if (message.toolName != null && message.title != message.toolName) {
            val statusText = message.title
                .replace(message.toolName ?: "", "")
                .replace(message.maidName ?: "", "")
                .replace("·", "").trim()
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (statusText.contains("error", true)) MaterialTheme.colorScheme.error
                            else Color(0xFF34C759),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // 内容摘要
        if (message.content.isNotBlank()) {
            Text(
                text = message.content.take(300),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 🛠️ 工具审批请求 — 醒目的橙色边框 + 审批按钮 */
@Composable
private fun ApprovalCard(
    message: VcpLogMessage,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(Modifier.padding(1.dp)) // border trick
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFFFF9500).copy(alpha = 0.06f))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("🛠️", fontSize = 14.sp)
            Text(
                text = "审核请求",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9500),
            )
            message.toolName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        // 详情
        if (message.content.isNotBlank()) {
            Text(
                text = message.content.take(400),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 审批按钮
        if (message.requestId != null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onApprove(message.requestId) },
                    modifier = Modifier.weight(1f).height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("✓ 允许", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { onReject(message.requestId) },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("✕ 拒绝", fontSize = 13.sp) }
            }
        }
    }
}

/** ✒️ 日记创建通知 — 温暖的样式 */
@Composable
private fun DailyNoteCard(message: VcpLogMessage, modifier: Modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF34C759).copy(alpha = 0.06f))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("✒️", fontSize = 14.sp)
            Text(
                text = "日记",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF34C759),
            )
            message.maidName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 通用类型卡片（视频生成等） */
@Composable
private fun GenericCard(
    message: VcpLogMessage,
    modifier: Modifier,
    icon: String = "📋",
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(icon, fontSize = 14.sp)
            Text(
                text = message.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        if (message.content.isNotBlank()) {
            Text(
                text = message.content.take(300),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConnectionDot(status: VcpLogConnectionStatus) {
    val (text, color) = when (status) {
        VcpLogConnectionStatus.Connected -> "已连接" to Color(0xFF34C759)
        VcpLogConnectionStatus.Connecting -> "连接中..." to Color(0xFFFF9500)
        VcpLogConnectionStatus.Error -> "连接失败" to MaterialTheme.colorScheme.error
        VcpLogConnectionStatus.Disconnected -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * 通知铃铛按钮。
 */
@Composable
fun VcpLogNotificationBell(
    unreadCount: Int,
    connectionStatus: VcpLogConnectionStatus,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                        Text(
                            if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else if (connectionStatus == VcpLogConnectionStatus.Connected) {
                    Badge(
                        containerColor = Color(0xFF34C759),
                        modifier = Modifier.size(7.dp),
                    )
                }
            },
        ) {
            Icon(Icons.Outlined.Notifications, "通知")
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private const val TOAST_DURATION_MS = 5000L
private const val MAX_VISIBLE_TOASTS = 3
private const val RAG_OBSERVER_URL = "file:///android_asset/vcpchat/modules/ragobserver/RAG_Observer.html"

/**
 * 嵌入式 RAG Observer WebView — 直接复用 VCPChat 桌面端的"灵视中心"。
 * 通过 URL 查询参数传入 vcpLogUrl 和 vcpLogKey，让它自己建立 WebSocket 连接。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RagObserverWebView(
    notifications: List<VcpLogMessage>,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    var ready by remember { mutableStateOf(false) }
    val pushedCount = remember { intArrayOf(0) }

    // 从 AppContainer 读取设置，传给 RAG Observer 的 URL 查询参数
    val app = context.applicationContext as com.vcpnative.app.VcpNativeApplication
    val appSettings by app.appContainer.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val vcpLogUrl = appSettings?.vcpLogUrl ?: ""
    val vcpLogKey = appSettings?.vcpLogKey ?: ""

    val shimJs = remember {
        try {
            context.assets.open("vcpchat/bridge-shim.js").bufferedReader().readText()
        } catch (_: Exception) { "" }
    }

    // WebView 生命周期跟随面板可见性
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef[0]?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
                webViewRef[0] = null
                ready = false
                pushedCount[0] = 0
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                overScrollMode = View.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        android.util.Log.d("RagObserver", "[${msg.lineNumber()}] ${msg.message()}")
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (url.endsWith(".html") && url.startsWith("file:///android_asset/")) {
                            val assetPath = url.removePrefix("file:///android_asset/")
                            try {
                                val html = viewContext.assets.open(assetPath).bufferedReader().readText()
                                val mobileAdapt = """
<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'/>
<script>
$shimJs
window.__vcpPlatform='android';
</script>
<style id='mobile-adapt'>
/* 隐藏桌面标题栏和窗口控件 */
#custom-title-bar,.window-controls,.window-controls-mac,.window-controls-win,
#spectrum-canvas,.title-toolbar{display:none!important}
/* 主容器适配手机 */
.main-wrapper{max-width:100%!important;padding-top:0!important}
.header-section{padding:8px 12px!important}
/* 卡片适配 */
.card-base{padding:14px!important;margin-bottom:12px!important;border-radius:12px!important}
.card-base h3{font-size:0.95rem!important;gap:6px!important}
.meta-info{gap:6px!important;font-size:0.75rem!important}
.meta-pill{padding:1px 6px!important}
/* 滚动容器 */
#infoContainer{padding:0 10px 10px!important}
/* 筛选栏适配 */
.filter-bar{margin-bottom:8px!important;padding:3px!important;overflow-x:auto;flex-wrap:nowrap}
.filter-btn{padding:5px 10px!important;font-size:0.78rem!important;white-space:nowrap}
/* RAG 子项 */
.rag-sub-item{padding:8px!important;margin-top:6px!important}
.score-badge{font-size:0.72rem!important;padding:2px 6px!important}
/* 文本内容 */
.text-content{font-size:0.88rem!important;line-height:1.5!important}
/* 操作按钮 - 更大的触摸目标 */
.icon-btn{padding:8px!important;min-width:36px;min-height:36px}
.approval-btn{padding:8px 16px!important;min-height:40px}
.approval-actions{gap:8px!important}
/* 核心标签 */
.core-tag-pill{font-size:0.72rem!important;padding:1px 6px!important}
.core-tags-container{gap:4px!important;margin-bottom:6px!important}
/* 分数色标 */
.score-high{background:#4caf50!important}.score-med{background:#ff9800!important}.score-low{background:#f44336!important}
.score-badge{border-radius:4px;color:#fff;font-weight:600}
/* 确保内容不溢出 */
*{max-width:100%;box-sizing:border-box}
img,svg,canvas{max-width:100%!important;height:auto}
pre{overflow-x:auto;-webkit-overflow-scrolling:touch;font-size:0.8rem!important}
/* 去掉桌面悬停效果（手机没有hover） */
.card-base:hover{transform:none!important;box-shadow:var(--glass-shadow)!important}
</style>
"""
                                val injected = if (html.contains("<head>", true)) {
                                    html.replaceFirst(
                                        Regex("<head>", RegexOption.IGNORE_CASE),
                                        "<head>\n$mobileAdapt",
                                    )
                                } else html
                                return android.webkit.WebResourceResponse(
                                    "text/html", "utf-8",
                                    java.io.ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
                                )
                            } catch (_: Exception) {}
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        ready = true
                        // Push any existing notifications
                        pushPendingMessages(view, notifications, pushedCount)
                    }
                }

                // 把 vcpLogUrl 和 vcpLogKey 通过 URL 查询参数传入
                // RAG Observer 的 rag-observer-config.js 会从 URL params 读取
                val wsUrl = if (vcpLogUrl.startsWith("http")) {
                    vcpLogUrl.replace("http://", "ws://").replace("https://", "wss://")
                } else if (vcpLogUrl.startsWith("ws")) vcpLogUrl
                else "ws://$vcpLogUrl"
                val encodedUrl = android.net.Uri.encode(wsUrl.trimEnd('/'))
                val encodedKey = android.net.Uri.encode(vcpLogKey)
                val fullUrl = "$RAG_OBSERVER_URL?vcpLogUrl=$encodedUrl&vcpLogKey=$encodedKey"
                android.util.Log.d("RagObserver", "Loading: $fullUrl")
                loadUrl(fullUrl)
                webViewRef[0] = this
            }
        },
    )

    // Push new messages as they arrive
    LaunchedEffect(notifications.size, ready) {
        if (!ready) return@LaunchedEffect
        val wv = webViewRef[0] ?: return@LaunchedEffect
        pushPendingMessages(wv, notifications, pushedCount)
    }
}

private fun pushPendingMessages(
    webView: WebView,
    notifications: List<VcpLogMessage>,
    pushedCount: IntArray,
) {
    val start = pushedCount[0]
    if (start >= notifications.size) return
    for (i in start until notifications.size) {
        val msg = notifications[i]
        // Reconstruct the original WebSocket message format that RAG Observer expects
        val json = org.json.JSONObject().apply {
            put("type", msg.type)
            if (msg.type == "vcp_log") {
                put("data", org.json.JSONObject().apply {
                    put("tool_name", msg.toolName ?: "")
                    put("content", msg.content)
                    put("MaidName", msg.maidName ?: "")
                })
            } else {
                put("message", msg.content)
                put("dbName", msg.toolName ?: "")
            }
        }
        val b64 = android.util.Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        // Call displayRagInfo (the main entry point of RAG Observer)
        webView.evaluateJavascript(
            "(function(){try{var d=JSON.parse(atob('$b64'));if(typeof displayRagInfo==='function')displayRagInfo(d);else if(window.__vcpBridge)window.__vcpBridge.emit('vcp-log-message',JSON.stringify(d));}catch(e){console.error('Push failed:',e);}})();",
            null,
        )
    }
    pushedCount[0] = notifications.size
}
