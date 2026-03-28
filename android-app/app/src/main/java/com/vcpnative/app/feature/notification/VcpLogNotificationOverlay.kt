package com.vcpnative.app.feature.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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

            // 通知列表
            if (notifications.isEmpty()) {
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        items = notifications.asReversed(),
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
 * 单条通知 — 紧凑行布局（类似 iOS 通知列表）。
 */
@Composable
private fun NotificationRow(
    message: VcpLogMessage,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        // 第一行：标题 + 时间
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
                // 类型指示
                val dotColor = when {
                    message.isApprovalRequest -> Color(0xFFFF9500)
                    message.type == "vcp_log" -> MaterialTheme.colorScheme.primary
                    message.type == "daily_note_created" -> Color(0xFF34C759)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = message.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                message.maidName?.let { agent ->
                    Text(
                        text = agent,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        // 内容 — 紧凑，最多 4 行
        if (message.content.isNotBlank()) {
            Text(
                text = message.content.take(300),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
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
                    onClick = { onApprove(message.requestId) },
                    modifier = Modifier.weight(1f).height(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("允许", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { onReject(message.requestId) },
                    modifier = Modifier.weight(1f).height(30.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("拒绝", fontSize = 12.sp) }
            }
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
