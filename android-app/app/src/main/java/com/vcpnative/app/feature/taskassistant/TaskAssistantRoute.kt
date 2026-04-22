package com.vcpnative.app.feature.taskassistant

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.taskassistant.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskAssistantRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { TaskAssistantRepository(appContainer.okHttpClient) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── Data state ──
    var aaConfig by remember { mutableStateOf<AAConfig?>(null) }
    var faConfig by remember { mutableStateOf<FAConfig?>(null) }
    var faStatus by remember { mutableStateOf<FAStatus?>(null) }

    // ── Credentials ──
    val settings = remember { appContainer.settingsRepository }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var credentialsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val s = settings.currentSettings()
            serverUrl = s.vcpServerUrl
            // Forum credentials: stored in module_configs/forum_config.json
            try {
                val forumFile = java.io.File(context.filesDir, "module_configs/forum_config.json")
                if (forumFile.exists()) {
                    val json = org.json.JSONObject(forumFile.readText())
                    username = json.optString("username", "")
                    password = json.optString("password", "")
                }
            } catch (_: Exception) {}
            credentialsLoaded = true
        }
    }

    // ── Modal state ──
    var editingAgent by remember { mutableStateOf<Pair<Int, AAAgent>?>(null) } // index, agent
    var editingTask by remember { mutableStateOf<FATask?>(null) }
    var deletingAgentIndex by remember { mutableStateOf(-1) }
    var deletingTaskId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (serverUrl.isBlank()) {
            errorMessage = "未配置服务器地址"
            return
        }
        scope.launch {
            loading = true
            errorMessage = null
            val aaResult = repo.fetchAAConfig(serverUrl, username, password)
            val faResult = repo.fetchFAConfig(serverUrl, username, password)
            val statusResult = repo.fetchFAStatus(serverUrl, username, password)
            aaResult.onSuccess { aaConfig = it }.onFailure { errorMessage = "AA: ${it.message}" }
            faResult.onSuccess { faConfig = it }.onFailure { errorMessage = (errorMessage ?: "") + "\nFA: ${it.message}" }
            statusResult.onSuccess { faStatus = it }
            loading = false
        }
    }

    // Auto-refresh status every 15s
    LaunchedEffect(credentialsLoaded, serverUrl) {
        if (!credentialsLoaded || serverUrl.isBlank()) return@LaunchedEffect
        refresh()
        while (isActive) {
            delay(15_000)
            repo.fetchFAStatus(serverUrl, username, password).onSuccess { faStatus = it }
        }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text("任务助手", style = MaterialTheme.typography.titleMedium)
                            faStatus?.let { s ->
                                Text(
                                    text = if (s.globalEnabled) "运行中 · ${s.activeTimerCount} 个计时器" else "已暂停",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.globalEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Outlined.Refresh, "刷新")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    when (selectedTab) {
                        0 -> editingTask = FATask(
                            id = "draft_${System.currentTimeMillis()}",
                            name = "新建草稿任务",
                            type = TaskType.CUSTOM_PROMPT,
                        )
                        1 -> editingAgent = Pair(-1, AAAgent())
                    }
                },
            ) {
                Icon(Icons.Outlined.Add, "新建")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("任务巡航") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Agent 管理") })
            }

            when (selectedTab) {
                0 -> TaskTab(
                    config = faConfig,
                    status = faStatus,
                    onToggleGlobal = { enabled ->
                        faConfig = faConfig?.copy(globalEnabled = enabled)
                        scope.launch {
                            faConfig?.let { repo.saveFAConfig(serverUrl, username, password, it) }
                        }
                    },
                    onEditTask = { editingTask = it },
                    onDeleteTask = { deletingTaskId = it.id },
                    onTriggerTask = { task ->
                        scope.launch {
                            repo.triggerTask(serverUrl, username, password, task.id)
                                .onSuccess { Toast.makeText(context, "已触发: ${task.name}", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "触发失败: ${it.message}", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onToggleTaskEnabled = { task, enabled ->
                        faConfig = faConfig?.let { cfg ->
                            cfg.copy(tasks = cfg.tasks.map { if (it.id == task.id) it.copy(enabled = enabled) else it })
                        }
                        scope.launch {
                            faConfig?.let { repo.saveFAConfig(serverUrl, username, password, it) }
                        }
                    },
                )
                1 -> AgentTab(
                    config = aaConfig,
                    onEditAgent = { index, agent -> editingAgent = Pair(index, agent) },
                    onDeleteAgent = { deletingAgentIndex = it },
                    onSaveAll = {
                        scope.launch {
                            aaConfig?.let {
                                loading = true
                                repo.saveAAConfig(serverUrl, username, password, it)
                                    .onSuccess { Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show() }
                                    .onFailure { e -> Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                                loading = false
                            }
                        }
                    },
                )
            }
        }
    }

    // ── Agent Edit Modal ──
    editingAgent?.let { (index, agent) ->
        AgentEditDialog(
            agent = agent,
            isNew = index < 0,
            onDismiss = { editingAgent = null },
            onSave = { saved ->
                aaConfig = aaConfig?.let { cfg ->
                    if (index < 0) cfg.copy(agents = cfg.agents + saved)
                    else cfg.copy(agents = cfg.agents.toMutableList().apply { set(index, saved) })
                }
                editingAgent = null
            },
        )
    }

    // ── Task Edit Modal ──
    editingTask?.let { task ->
        TaskEditDialog(
            task = task,
            agentNames = aaConfig?.agents?.map { it.chineseName.ifBlank { it.baseName } } ?: emptyList(),
            onDismiss = { editingTask = null },
            onSave = { saved ->
                val persistedTask = if (saved.id.startsWith("draft_")) saved.copy(id = "fa_${System.currentTimeMillis()}") else saved
                faConfig = faConfig?.let { cfg ->
                    val existing = cfg.tasks.indexOfFirst { it.id == task.id }
                    if (existing >= 0) cfg.copy(tasks = cfg.tasks.toMutableList().apply { set(existing, persistedTask) })
                    else cfg.copy(tasks = cfg.tasks + persistedTask)
                } ?: FAConfig(tasks = listOf(persistedTask))
                scope.launch {
                    faConfig?.let { repo.saveFAConfig(serverUrl, username, password, it) }
                }
                editingTask = null
            },
        )
    }

    // ── Delete Confirmations ──
    if (deletingAgentIndex >= 0) {
        val agentName = aaConfig?.agents?.getOrNull(deletingAgentIndex)?.chineseName ?: ""
        AlertDialog(
            onDismissRequest = { deletingAgentIndex = -1 },
            title = { Text("删除 Agent") },
            text = { Text("确定删除 \"$agentName\" 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    aaConfig = aaConfig?.let { cfg ->
                        cfg.copy(agents = cfg.agents.toMutableList().apply { removeAt(deletingAgentIndex) })
                    }
                    deletingAgentIndex = -1
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingAgentIndex = -1 }) { Text("取消") } },
        )
    }

    deletingTaskId?.let { taskId ->
        val taskName = faConfig?.tasks?.find { it.id == taskId }?.name ?: ""
        AlertDialog(
            onDismissRequest = { deletingTaskId = null },
            title = { Text("删除任务") },
            text = { Text("确定删除 \"$taskName\" 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    faConfig = faConfig?.let { cfg -> cfg.copy(tasks = cfg.tasks.filter { it.id != taskId }) }
                    scope.launch {
                        faConfig?.let { repo.saveFAConfig(serverUrl, username, password, it) }
                    }
                    deletingTaskId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTaskId = null }) { Text("取消") } },
        )
    }
}

// ══════════════════════════════════════════════════
// Task Tab
// ══════════════════════════════════════════════════

@Composable
private fun TaskTab(
    config: FAConfig?,
    status: FAStatus?,
    onToggleGlobal: (Boolean) -> Unit,
    onEditTask: (FATask) -> Unit,
    onDeleteTask: (FATask) -> Unit,
    onTriggerTask: (FATask) -> Unit,
    onToggleTaskEnabled: (FATask, Boolean) -> Unit,
) {
    if (config == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Status dashboard
        item(key = "dashboard") {
            StatusDashboard(
                status = status,
                globalEnabled = config.globalEnabled,
                onToggleGlobal = onToggleGlobal,
            )
        }

        // Task cards
        items(config.tasks, key = { it.id }, contentType = { "task" }) { task ->
            TaskCard(
                task = task,
                lastRun = status?.history?.lastOrNull { it.taskId == task.id },
                onEdit = { onEditTask(task) },
                onDelete = { onDeleteTask(task) },
                onTrigger = { onTriggerTask(task) },
                onToggleEnabled = { onToggleTaskEnabled(task, it) },
            )
        }

        if (config.tasks.isEmpty()) {
            item { EmptyHint("暂无任务，点击右下角 + 创建") }
        }
    }
}

@Composable
private fun StatusDashboard(
    status: FAStatus?,
    globalEnabled: Boolean,
    onToggleGlobal: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("全局调度", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (globalEnabled) "运行中" else "已暂停",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (globalEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = globalEnabled, onCheckedChange = onToggleGlobal)
        }
        if (status != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem("活跃计时器", "${status.activeTimerCount}")
                StatItem("历史记录", "${status.history.size}")
                StatItem("成功率", status.history.let { h ->
                    if (h.isEmpty()) "N/A"
                    else "${(h.count { it.success } * 100 / h.size)}%"
                })
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    task: FATask,
    lastRun: FAHistoryEntry?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTrigger: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (task.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: name + actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${task.type.apiValue} · ${scheduleLabel(task.schedule)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Target agents
            if (task.targets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    task.targets.forEach { agent ->
                        AssistChip(
                            onClick = {},
                            label = { Text(agent, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            // Last run
            lastRun?.let { run ->
                Text(
                    text = "上次: ${run.startedAt?.take(19) ?: "?"} — ${if (run.success) "成功" else "失败"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (run.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onTrigger, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.PlayArrow, "立即运行", Modifier.size(18.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Edit, "编辑", Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════
// Agent Tab
// ══════════════════════════════════════════════════

@Composable
private fun AgentTab(
    config: AAConfig?,
    onEditAgent: (Int, AAAgent) -> Unit,
    onDeleteAgent: (Int) -> Unit,
    onSaveAll: () -> Unit,
) {
    if (config == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Global settings bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("全局设置", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "历史轮数: ${config.maxHistoryRounds} · 上下文 TTL: ${config.contextTtlHours}h",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSaveAll) {
                    Icon(Icons.Outlined.Save, "保存全部")
                }
            }
        }

        // Agent grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(config.agents.size, key = { config.agents[it].baseName.ifBlank { it.toString() } }) { index ->
                val agent = config.agents[index]
                AgentCard(
                    agent = agent,
                    onEdit = { onEditAgent(index, agent) },
                    onDelete = { onDeleteAgent(index) },
                )
            }
        }

        if (config.agents.isEmpty()) {
            EmptyHint("暂无 Agent，点击右下角 + 创建")
        }
    }
}

@Composable
private fun AgentCard(
    agent: AAAgent,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = agent.chineseName.ifBlank { agent.baseName.ifBlank { "未命名" } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Delete, "删除", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            if (agent.baseName.isNotBlank()) {
                Text(
                    text = "@${agent.baseName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = agent.modelId.ifBlank { "未指定模型" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (agent.description.isNotBlank()) {
                Text(
                    text = agent.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "MaxTokens: ${agent.maxOutputTokens} · Temp: ${agent.temperature}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ══════════════════════════════════════════════════
// Edit Dialogs
// ══════════════════════════════════════════════════

@Composable
private fun AgentEditDialog(
    agent: AAAgent,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (AAAgent) -> Unit,
) {
    var chineseName by rememberSaveable { mutableStateOf(agent.chineseName) }
    var baseName by rememberSaveable { mutableStateOf(agent.baseName) }
    var modelId by rememberSaveable { mutableStateOf(agent.modelId) }
    var description by rememberSaveable { mutableStateOf(agent.description) }
    var systemPrompt by rememberSaveable { mutableStateOf(agent.systemPrompt) }
    var maxOutputTokens by rememberSaveable { mutableStateOf(agent.maxOutputTokens.toString()) }
    var temperature by rememberSaveable { mutableStateOf(agent.temperature.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建 Agent" else "编辑 Agent") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FieldInput("显示名称", chineseName, "例如：诺娃") { chineseName = it } }
                item { FieldInput("基础标识 (baseName)", baseName, "例如：nova") { baseName = it } }
                item { FieldInput("模型 ID", modelId, "例如：gpt-4o") { modelId = it } }
                item { FieldInput("角色描述", description, "描述角色职能…", singleLine = false) { description = it } }
                item { FieldInput("系统提示词", systemPrompt, "支持 {{baseName}} 占位符", singleLine = false, minLines = 3) { systemPrompt = it } }
                item { FieldInput("最大输出 Token", maxOutputTokens, "8000", keyboardType = KeyboardType.Number) { maxOutputTokens = it } }
                item { FieldInput("温度", temperature, "0.7", keyboardType = KeyboardType.Decimal) { temperature = it } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(agent.copy(
                    chineseName = chineseName,
                    baseName = baseName,
                    modelId = modelId,
                    description = description,
                    systemPrompt = systemPrompt,
                    maxOutputTokens = maxOutputTokens.toIntOrNull() ?: 8000,
                    temperature = temperature.toDoubleOrNull() ?: 0.7,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TaskEditDialog(
    task: FATask,
    agentNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (FATask) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(task.name) }
    var type by rememberSaveable { mutableStateOf(task.type) }
    var scheduleMode by rememberSaveable { mutableStateOf(task.schedule.mode) }
    var intervalMinutes by rememberSaveable { mutableStateOf(task.schedule.intervalMinutes?.toString() ?: "60") }
    var cronValue by rememberSaveable { mutableStateOf(task.schedule.cronValue ?: "0 8 * * *") }
    var runAt by rememberSaveable { mutableStateOf(task.schedule.runAt ?: "") }
    var targets by rememberSaveable { mutableStateOf(task.targets.joinToString(", ")) }
    var promptTemplate by rememberSaveable { mutableStateOf(task.promptTemplate) }
    var taskDelegation by rememberSaveable { mutableStateOf(task.taskDelegation) }
    var includeForumPostList by rememberSaveable { mutableStateOf(task.includeForumPostList) }
    var maxPosts by rememberSaveable { mutableStateOf(task.maxPosts.toString()) }

    // Agent quick-add dropdown
    var agentDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task.id.startsWith("draft_")) "新建任务" else "编辑任务") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FieldInput("任务名称", name, "例如：论坛巡逻") { name = it } }

                // Type selector
                item {
                    Text("任务类型", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskType.entries.forEach { t ->
                            AssistChip(
                                onClick = { type = t },
                                label = { Text(t.apiValue) },
                                modifier = if (type == t) Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp),
                                ) else Modifier,
                            )
                        }
                    }
                }

                // Schedule
                item {
                    Text("调度模式", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ScheduleMode.entries.forEach { m ->
                            AssistChip(
                                onClick = { scheduleMode = m },
                                label = { Text(m.apiValue, style = MaterialTheme.typography.labelSmall) },
                                modifier = if (scheduleMode == m) Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp),
                                ) else Modifier,
                            )
                        }
                    }
                }
                item {
                    when (scheduleMode) {
                        ScheduleMode.INTERVAL -> FieldInput("间隔（分钟）", intervalMinutes, "60", keyboardType = KeyboardType.Number) { intervalMinutes = it }
                        ScheduleMode.CRON -> FieldInput("CRON 表达式", cronValue, "0 8 * * *") { cronValue = it }
                        ScheduleMode.ONCE -> FieldInput("执行时间 (ISO)", runAt, "2026-04-14T15:30:00") { runAt = it }
                        ScheduleMode.MANUAL -> Text("手动触发，无需配置调度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Targets
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldInput("目标 Agent（逗号分隔）", targets, "nova, kiki", modifier = Modifier.weight(1f)) { targets = it }
                        Box {
                            IconButton(onClick = { agentDropdownExpanded = true }) {
                                Icon(Icons.Outlined.Add, "快速添加")
                            }
                            DropdownMenu(
                                expanded = agentDropdownExpanded,
                                onDismissRequest = { agentDropdownExpanded = false },
                            ) {
                                agentNames.forEach { agentName ->
                                    DropdownMenuItem(
                                        text = { Text(agentName) },
                                        onClick = {
                                            val current = targets.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                            if (agentName !in current) {
                                                targets = (current + agentName).joinToString(", ")
                                            }
                                            agentDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Prompt
                item { FieldInput("提示词模板", promptTemplate, "输入提示词…", singleLine = false, minLines = 3) { promptTemplate = it } }

                // Delegation toggle
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("异步委托模式", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = taskDelegation, onCheckedChange = { taskDelegation = it })
                    }
                }

                // Forum-specific
                if (type == TaskType.FORUM_PATROL) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("注入论坛帖子列表", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = includeForumPostList, onCheckedChange = { includeForumPostList = it })
                        }
                    }
                    item { FieldInput("最大帖子数", maxPosts, "200", keyboardType = KeyboardType.Number) { maxPosts = it } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(task.copy(
                    name = name,
                    type = type,
                    schedule = FASchedule(
                        mode = scheduleMode,
                        intervalMinutes = intervalMinutes.toIntOrNull(),
                        cronValue = cronValue.takeIf { scheduleMode == ScheduleMode.CRON },
                        runAt = runAt.takeIf { scheduleMode == ScheduleMode.ONCE },
                    ),
                    targets = targets.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    taskDelegation = taskDelegation,
                    promptTemplate = promptTemplate,
                    includeForumPostList = includeForumPostList,
                    maxPosts = maxPosts.toIntOrNull() ?: 200,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ══════════════════════════════════════════════════
// Shared Components
// ══════════════════════════════════════════════════

@Composable
private fun FieldInput(
    label: String,
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun scheduleLabel(schedule: FASchedule): String = when (schedule.mode) {
    ScheduleMode.INTERVAL -> "每 ${schedule.intervalMinutes ?: "?"} 分钟"
    ScheduleMode.CRON -> "cron: ${schedule.cronValue ?: "?"}"
    ScheduleMode.MANUAL -> "手动触发"
    ScheduleMode.ONCE -> "一次: ${schedule.runAt?.take(16) ?: "?"}"
}
