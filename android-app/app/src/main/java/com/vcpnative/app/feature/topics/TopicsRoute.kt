package com.vcpnative.app.feature.topics

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    Toast.makeText(context, "标题更新成功！✨", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, e.message ?: "重命名失败惹...", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDeleteTopic = { topicId ->
            scope.launch {
                runCatching {
                    appContainer.workspaceRepository.deleteTopic(topicId)
                }.onSuccess {
                    Toast.makeText(context, "记忆已抹除～", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, e.message ?: "删除失败了哦", Toast.LENGTH_SHORT).show()
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
            ) {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                text = "话题列表",
                                fontWeight = FontWeight.Black
                            )
                        },
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
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary,
                            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Text(
                        text = "正在与 $agentName 聊天中~",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePlaceholder,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "还没有对话记录哦~",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "点击下方的加号，\n开始聊天吧~",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(topics, key = { it.id }) { topic ->
                    Box {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .combinedClickable(
                                    onClick = { onOpenChat(topic.id) },
                                    onLongClick = { menuTopic = topic },
                                ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ChatBubbleOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = topic.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "ID: ${topic.id.takeLast(8)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = menuTopic?.id == topic.id,
                            onDismissRequest = { menuTopic = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重命名话题") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    renameTarget = topic
                                    renameText = topic.title
                                    menuTopic = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除记录", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { 
                                    Icon(
                                        imageVector = Icons.Outlined.Forum, 
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    ) 
                                },
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
            title = { Text("重命名话题", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("新标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
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
                    Text("确定更新", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("先不改了")
                }
            },
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { topic ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("要删除这段记忆吗？", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除「${topic.title}」吗？一旦删除，你们之间的所有聊天记录都将消失在虚空之中哦。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTopic(topic.id)
                        deleteTarget = null
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("留着吧")
                }
            },
        )
    }
}
