package com.vcpnative.app.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.room.AgentEntity
import kotlinx.coroutines.launch

@Composable
fun AgentsRoute(
    appContainer: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenAgentEditor: (agentId: String) -> Unit,
    onOpenTopics: (agentId: String) -> Unit,
) {
    val agents by appContainer.workspaceRepository
        .observeAgents()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    AgentsScreen(
        agents = agents,
        onOpenSettings = onOpenSettings,
        onOpenAgentEditor = onOpenAgentEditor,
        onOpenTopics = onOpenTopics,
        onCreatePlaceholder = {
            scope.launch {
                val agent = appContainer.workspaceRepository.createPlaceholderAgent()
                onOpenTopics(agent.id)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsScreen(
    agents: List<AgentEntity>,
    onOpenSettings: () -> Unit,
    onOpenAgentEditor: (agentId: String) -> Unit,
    onOpenTopics: (agentId: String) -> Unit,
    onCreatePlaceholder: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Agent 列表") },
                actions = {
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
                    contentDescription = "create_placeholder_agent"
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "创建占位 Agent",
                )
            }
        },
    ) { innerPadding ->
        if (agents.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有 Agent 数据。",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "先创建一个占位 Agent，把 Agent -> Topic -> Chat 这条骨架工作流跑通。后续再接真实导入和迁移逻辑。",
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
                items(agents, key = { it.id }) { agent ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTopics(agent.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = agent.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = agent.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onOpenAgentEditor(agent.id) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "编辑 Agent",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
