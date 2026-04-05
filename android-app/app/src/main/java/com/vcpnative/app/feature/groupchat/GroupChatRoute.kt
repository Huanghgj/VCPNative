package com.vcpnative.app.feature.groupchat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.groupchat.AgentGroup
import com.vcpnative.app.data.groupchat.GroupStreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GroupChatRoute"

data class GroupMessage(
    val id: String,
    val role: String,
    val name: String,
    val content: String,
    val agentId: String? = null,
    val isStreaming: Boolean = false,
)

@Composable
fun GroupChatRoute(
    appContainer: AppContainer,
    groupId: String,
    topicId: String,
    onNavigateBack: () -> Unit,
) {
    var group by remember { mutableStateOf<AgentGroup?>(null) }
    val messages = remember { mutableStateListOf<GroupMessage>() }
    var isSending by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sendJob by remember { mutableStateOf<Job?>(null) }

    // 加载历史
    LaunchedEffect(groupId, topicId) {
        group = appContainer.groupChatRepository.getGroupConfig(groupId)
        val history = appContainer.groupChatRepository.loadHistory(groupId, topicId)
        messages.clear()
        for (i in 0 until history.length()) {
            val msg = history.optJSONObject(i) ?: continue
            messages.add(
                GroupMessage(
                    id = msg.optString("id", "msg_$i"),
                    role = msg.optString("role"),
                    name = msg.optString("name", msg.optString("role")),
                    content = msg.optString("content"),
                    agentId = msg.optString("agentId").takeIf { it.isNotBlank() },
                )
            )
        }
        // 滚到底部
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group?.name ?: "群聊",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group?.members?.size ?: 0} 位成员",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                HorizontalDivider(color = DividerDefaults.color)
                if (isSending) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息...") },
                        enabled = !isSending,
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                    )
                    if (isSending) {
                        FloatingActionButton(
                            onClick = { sendJob?.cancel() },
                            modifier = Modifier.size(48.dp),
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Outlined.Stop, "中止", modifier = Modifier.size(24.dp))
                        }
                    } else {
                        FloatingActionButton(
                            onClick = {
                                val text = draft.trim()
                                if (text.isBlank()) return@FloatingActionButton
                                draft = ""
                                isSending = true

                                sendJob = scope.launch {
                                    appContainer.groupChatEngine.sendMessage(
                                        groupId = groupId,
                                        topicId = topicId,
                                        userText = text,
                                    ).collect { event ->
                                        when (event) {
                                            is GroupStreamEvent.UserMessageSaved -> {
                                                messages.add(
                                                    GroupMessage(
                                                        id = event.messageId,
                                                        role = "user",
                                                        name = "用户",
                                                        content = text,
                                                    )
                                                )
                                                listState.animateScrollToItem(messages.lastIndex)
                                            }
                                            is GroupStreamEvent.AgentThinking -> {
                                                messages.add(
                                                    GroupMessage(
                                                        id = event.messageId,
                                                        role = "assistant",
                                                        name = event.agentName,
                                                        content = "",
                                                        agentId = event.agentId,
                                                        isStreaming = true,
                                                    )
                                                )
                                                listState.animateScrollToItem(messages.lastIndex)
                                            }
                                            is GroupStreamEvent.AgentDelta -> {
                                                val idx = messages.indexOfLast { it.id == event.messageId }
                                                if (idx >= 0) {
                                                    messages[idx] = messages[idx].copy(
                                                        content = messages[idx].content + event.text,
                                                    )
                                                }
                                            }
                                            is GroupStreamEvent.AgentCompleted -> {
                                                val idx = messages.indexOfLast { it.id == event.messageId }
                                                if (idx >= 0) {
                                                    messages[idx] = messages[idx].copy(
                                                        content = event.fullText,
                                                        isStreaming = false,
                                                    )
                                                }
                                                listState.animateScrollToItem(messages.lastIndex)
                                            }
                                            is GroupStreamEvent.AgentError -> {
                                                val idx = messages.indexOfLast { it.id == event.messageId }
                                                if (idx >= 0) {
                                                    messages[idx] = messages[idx].copy(
                                                        content = "❌ ${event.error}",
                                                        isStreaming = false,
                                                    )
                                                } else {
                                                    messages.add(
                                                        GroupMessage(
                                                            id = event.messageId,
                                                            role = "system",
                                                            name = event.agentName,
                                                            content = "❌ ${event.error}",
                                                        )
                                                    )
                                                }
                                            }
                                            is GroupStreamEvent.AllCompleted -> {
                                                isSending = false
                                            }
                                            is GroupStreamEvent.NoResponse -> {
                                                messages.add(
                                                    GroupMessage(
                                                        id = "sys_${System.currentTimeMillis()}",
                                                        role = "system",
                                                        name = "系统",
                                                        content = "没有成员响应（invite_only 模式下需手动邀请）",
                                                    )
                                                )
                                                isSending = false
                                            }
                                            is GroupStreamEvent.Error -> {
                                                messages.add(
                                                    GroupMessage(
                                                        id = "err_${System.currentTimeMillis()}",
                                                        role = "system",
                                                        name = "系统",
                                                        content = "❌ ${event.message}",
                                                    )
                                                )
                                                isSending = false
                                            }
                                        }
                                    }
                                    isSending = false
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            containerColor = if (draft.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Send, "发送", modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            items(messages, key = { it.id }) { msg ->
                GroupMessageBubble(msg)
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun GroupMessageBubble(msg: GroupMessage) {
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"

    if (isSystem) {
        // 系统消息居中
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            // Agent 头像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = msg.name.take(1),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (!isUser) {
                Text(
                    text = msg.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            Card(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 18.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                if (msg.isStreaming && msg.content.isBlank()) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(16.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = msg.content.ifBlank { "..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
        }
    }
}
