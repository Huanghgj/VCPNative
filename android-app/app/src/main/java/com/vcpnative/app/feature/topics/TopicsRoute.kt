package com.vcpnative.app.feature.topics

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicsScreen(
    agentName: String,
    topics: List<TopicEntity>,
    onNavigateBack: () -> Unit,
    onOpenAgentEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: (topicId: String) -> Unit,
    onCreatePlaceholder: () -> Unit,
) {
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
                            .clickable { onOpenChat(topic.id) },
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
                    }
                }
            }
        }
    }
}
