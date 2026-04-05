package com.vcpnative.app.feature.overlay

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentPurple = Color(0xFF6C5CE7)
private val AccentGreen = Color(0xFF00B894)
private val DarkBg = Color(0xFF1E1E2E)
private val DarkHeader = Color(0xFF2D2D44)
private val DarkInput = Color(0xFF3D3D55)
private val MutedText = Color(0xFFBBBBCC)
private val PlaceholderText = Color(0xFF777788)

@Composable
fun OverlayBubble(
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(AccentPurple)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = "AI 悬浮助手",
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
fun OverlayChatPanel(
    messages: List<OverlayMessage>,
    isStreaming: Boolean,
    pendingScreenshot: Bitmap?,
    isAgentMode: Boolean,
    agentStatus: String?,
    onSend: (String) -> Unit,
    onScreenshot: () -> Unit,
    onClearHistory: () -> Unit,
    onCollapse: () -> Unit,
    onClearScreenshot: () -> Unit,
    onToggleAgentMode: () -> Unit,
    onStopAgent: () -> Unit,
    onToggleDebug: (() -> Unit)? = null,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .size(width = 320.dp, height = 480.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(DarkBg),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkHeader)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = if (isAgentMode) AccentGreen else AccentPurple,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAgentMode) "Agent 模式" else "AI 助手",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )

            // Agent mode toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isAgentMode) AccentGreen.copy(alpha = 0.2f) else DarkInput)
                    .clickable(onClick = onToggleAgentMode)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (isAgentMode) "Agent" else "Chat",
                    color = if (isAgentMode) AccentGreen else MutedText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))
            if (onToggleDebug != null) {
                IconButton(onClick = onToggleDebug, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = "Debug",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onScreenshot, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "截屏",
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onClearHistory, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "清除记录",
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "折叠",
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Agent status bar
        AnimatedVisibility(visible = agentStatus != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentGreen.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = agentStatus ?: "",
                    color = AccentGreen,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onStopAgent, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }

        // Screenshot preview
        AnimatedVisibility(
            visible = pendingScreenshot != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            pendingScreenshot?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkHeader)
                        .padding(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "截图预览",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "已截图",
                            color = MutedText,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearScreenshot, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "移除截图",
                                tint = Color(0xFF999999),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // Input area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkHeader)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkInput)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (isAgentMode) "输入任务指令..." else "输入消息...",
                        color = PlaceholderText,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(AccentPurple),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank() || pendingScreenshot != null) {
                        onSend(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank() || pendingScreenshot != null) {
                            if (isAgentMode) AccentGreen else AccentPurple
                        } else {
                            DarkInput
                        },
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun OverlayDebugPanel(
    debugLog: List<String>,
    onCopyAll: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(debugLog.size) {
        if (debugLog.isNotEmpty()) {
            listState.animateScrollToItem(debugLog.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .size(width = 320.dp, height = 480.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(DarkBg),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkHeader)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = null,
                tint = Color(0xFFFF6B6B),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Debug Log",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onCopyAll(debugLog.joinToString("\n")) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制全部",
                    tint = AccentPurple,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "清除",
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Log content
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(debugLog.size) { index ->
                    Text(
                        text = debugLog[index],
                        color = Color(0xFF00FF88),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: OverlayMessage) {
    val isUser = message.role == "user" && !message.isToolResult

    // Tool results and agent actions use special styling
    val bgColor = when {
        message.isToolResult -> Color(0xFF1A2A1A) // Dark green tint
        message.isAgentAction -> Color(0xFF1A1A2A) // Dark blue tint
        isUser -> AccentPurple
        else -> DarkHeader
    }

    val textColor = when {
        message.isToolResult -> AccentGreen
        message.isAgentAction -> Color(0xFF74B9FF)
        else -> Color.White
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when {
            message.isToolResult || message.isAgentAction -> Alignment.Start
            isUser -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp,
                    ),
                )
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .then(
                    if (isUser) Modifier else Modifier.fillMaxWidth(0.9f),
                ),
        ) {
            Text(
                text = message.content.ifBlank { if (!isUser) "..." else "" },
                color = textColor,
                fontSize = if (message.isToolResult) 11.sp else 13.sp,
                lineHeight = if (message.isToolResult) 15.sp else 18.sp,
            )
        }
    }
}
