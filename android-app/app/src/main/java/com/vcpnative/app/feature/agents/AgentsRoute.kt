package com.vcpnative.app.feature.agents

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val context = LocalContext.current

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
        onDeleteAgent = { agentId ->
            scope.launch {
                runCatching {
                    appContainer.workspaceRepository.deleteAgent(agentId)
                }.onSuccess {
                    Toast.makeText(context, "Agent 已删除", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, e.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AgentsScreen(
    agents: List<AgentEntity>,
    onOpenSettings: () -> Unit,
    onOpenAgentEditor: (agentId: String) -> Unit,
    onOpenTopics: (agentId: String) -> Unit,
    onCreatePlaceholder: () -> Unit,
    onDeleteAgent: (agentId: String) -> Unit,
) {
    var menuAgent by remember { mutableStateOf<AgentEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<AgentEntity?>(null) }

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
                                text = "VCPNative", 
                                fontWeight = FontWeight.Black
                            ) 
                        },
                        actions = {
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
                            actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Text(
                        text = "欢迎回来~今天也一起加油吧!",
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
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "还没有小伙伴哦~",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "点击右下角的按钮，\n召唤你的第一个 Agent 吧~",
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
                items(agents, key = { it.id }) { agent ->
                    Box {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .combinedClickable(
                                    onClick = { onOpenTopics(agent.id) },
                                    onLongClick = { menuAgent = agent },
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
                                        Text(
                                            text = agent.name.firstOrNull()?.uppercase() ?: "A",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = agent.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
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
                                            tint = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = menuAgent?.id == agent.id,
                            onDismissRequest = { menuAgent = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑 Agent") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    onOpenAgentEditor(agent.id)
                                    menuAgent = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除 Agent", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    deleteTarget = agent
                                    menuAgent = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确定要删除这个 Agent 吗？", fontWeight = FontWeight.Bold) },
            text = {
                Text("将永久删除「${agent.name}」以及其下所有话题和聊天记录。此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAgent(agent.id)
                        deleteTarget = null
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
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