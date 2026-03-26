package com.vcpnative.app.feature.topics

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.room.TopicEntity
import kotlinx.coroutines.launch

@Composable
fun TopicsRoute(
    appContainer: AppContainer,
    agentId: String,
    onNavigateBack: () -> Unit,
    onOpenAgentEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: (topicId: String) -> Unit,
) {
    val topics by appContainer.workspaceRepository
        .observeTopics(agentId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val agentName by produceState<String?>(initialValue = null, key1 = agentId) {
        value = appContainer.workspaceRepository.findAgent(agentId)?.name
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    TopicsScreen(
        agentName = agentName ?: agentId,
        topics = topics,
        onNavigateBack = onNavigateBack,
        onOpenAgentEditor = onOpenAgentEditor,
        onOpenSettings = onOpenSettings,
        onOpenChat = onOpenChat,
        onCreatePlaceholder = {
            scope.launch {
                val topic = appContainer.workspaceRepository.createPlaceholderTopic(agentId)
                onOpenChat(topic.id)
            }
        },
        onRenameTopic = { topicId, newTitle ->
            scope.launch {
                runCatching {
                    appContainer.workspaceRepository.renameTopic(topicId, newTitle)
                }.onSuccess {
                    Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, e.message ?: "重命名失败", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDeleteTopic = { topicId ->
            scope.launch {
                runCatching {
                    appContainer.workspaceRepository.deleteTopic(topicId)
                }.onSuccess {
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, e.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TopicsScreen(
    agentName: String,
    topics: List<TopicEntity>,
    onNavigateBack: () -> Unit,
    onOpenAgentEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: (topicId: String) -> Unit,
    onCreatePlaceholder: () -> Unit,
    onRenameTopic: (topicId: String, newTitle: String) -> Unit,
    onDeleteTopic: (topicId: String) -> Unit,
) {
    var menuTopic by remember { mutableStateOf<TopicEntity?>(null) }
    var renameTarget by remember { mutableStateOf<TopicEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TopicEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Topic · $agentName") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAgentEditor) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "编辑 Agent",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePlaceholder,
                modifier = Modifier.semantics {
                    contentDescription = "create_placeholder_topic"
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "创建占位 Topic",
                )
            }
        },
    ) { innerPadding ->
        if (topics.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "这个 Agent 还没有 Topic。",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "先建一个占位 Topic，让单聊页面、历史流和发送区骨架先跑起来。",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(topics, key = { it.id }) { topic ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenChat(topic.id) },
                                onLongClick = { menuTopic = topic },
                            ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = topic.title,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = topic.sourceTopicId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        DropdownMenu(
                            expanded = menuTopic?.id == topic.id,
                            onDismissRequest = { menuTopic = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    renameTarget = topic
                                    renameText = topic.title
                                    menuTopic = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    deleteTarget = topic
                                    menuTopic = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    renameTarget?.let { topic ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名话题") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("新标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newTitle = renameText.trim()
                        if (newTitle.isNotEmpty()) {
                            onRenameTopic(topic.id, newTitle)
                        }
                        renameTarget = null
                    },
                    enabled = renameText.trim().isNotEmpty(),
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { topic ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除话题") },
            text = {
                Text("确定要删除「${topic.title}」吗？该话题下的所有消息也会被删除，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTopic(topic.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}
