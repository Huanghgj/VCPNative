package com.vcpnative.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.room.MessageEntity
import kotlinx.coroutines.launch

@Composable
fun SearchRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onOpenChat: (agentId: String, topicId: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 缓存 topicId -> agentId 映射
    var topicAgentMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索消息内容...") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (query.length >= 2) {
                                scope.launch {
                                    results = appContainer.workspaceRepository.searchMessages(query, 50)
                                    // 批量查询 topicId -> agentId
                                    val topicIds = results.map { it.topicId }.distinct()
                                    val map = mutableMapOf<String, String>()
                                    for (tid in topicIds) {
                                        appContainer.workspaceRepository.findTopic(tid)?.let { map[tid] = it.agentId }
                                    }
                                    topicAgentMap = map
                                    searched = true
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Search, "搜索")
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
        }

        // 结果列表
        if (searched && results.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("没有找到匹配的消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results) { message ->
                    val agentId = topicAgentMap[message.topicId] ?: ""
                    SearchResultCard(
                        message = message,
                        query = query,
                        onClick = {
                            if (agentId.isNotBlank()) onOpenChat(agentId, message.topicId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    message: MessageEntity,
    query: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.role,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (message.role) {
                        "user" -> MaterialTheme.colorScheme.primary
                        "assistant" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.topicId.take(12),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            // 显示匹配上下文
            val content = message.content
            val idx = content.lowercase().indexOf(query.lowercase())
            val preview = if (idx >= 0) {
                val start = maxOf(0, idx - 40)
                val end = minOf(content.length, idx + query.length + 80)
                (if (start > 0) "..." else "") + content.substring(start, end) + (if (end < content.length) "..." else "")
            } else {
                content.take(150)
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
