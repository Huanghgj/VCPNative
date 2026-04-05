package com.vcpnative.app.feature.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.network.llm.LlmMessage
import com.vcpnative.app.network.llm.LlmProfile
import com.vcpnative.app.network.llm.LlmRequestOptions
import com.vcpnative.app.network.llm.LlmStreamEvent
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

private data class ProviderDef(
    val id: String,
    val name: String,
    val defaultBaseUrl: String,
    val color: Color,
)

private val PROVIDERS = listOf(
    ProviderDef("openai", "OpenAI", "https://api.openai.com/v1", Color(0xFF10A37F)),
    ProviderDef("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", Color(0xFFD4A574)),
    ProviderDef("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", Color(0xFF4285F4)),
    ProviderDef("deepseek", "DeepSeek", "https://api.deepseek.com/v1", Color(0xFF536DFE)),
    ProviderDef("groq", "Groq", "https://api.groq.com/openai/v1", Color(0xFFF55036)),
    ProviderDef("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", Color(0xFF6366F1)),
    ProviderDef("ollama", "Ollama (本地)", "http://localhost:11434/v1", Color(0xFF333333)),
    ProviderDef("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", Color(0xFF00BCD4)),
    ProviderDef("openai-compatible", "自定义 (OpenAI 兼容)", "", Color(0xFF9E9E9E)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
) {
    val store = appContainer.llmProfileStore
    val profiles by store.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var testingProfileId by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val toolBridge = appContainer.vcpToolBridge
    val pendingApprovals by toolBridge.pendingApprovals.collectAsState()
    val toolLogs by toolBridge.toolLogs.collectAsState()

    LaunchedEffect(Unit) { store.load() }

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("LLM 桥接器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${profiles.size} 个服务配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, shape = CircleShape) {
                Icon(Icons.Outlined.Add, "添加服务商")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (profiles.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Cloud, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f))
                            Spacer(Modifier.height(12.dp))
                            Text("还没有配置服务商", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("点击右下角 + 添加一个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                        }
                    }
                }
            }

            items(profiles, key = { it.id }) { profile ->
                val providerDef = PROVIDERS.find { it.id == profile.provider }
                val color = providerDef?.color ?: Color.Gray
                val isTesting = testingProfileId == profile.id

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(color.copy(0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp), tint = color)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${profile.provider} · ${profile.apiKeys.size} key · ${profile.baseUrl.take(40)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 测试按钮
                            IconButton(
                                onClick = {
                                    testingProfileId = profile.id
                                    testResult = null
                                    scope.launch {
                                        val adapter = appContainer.llmAdapterRegistry.getAdapter(profile.provider)
                                        if (adapter == null) {
                                            testResult = "❌ 不支持的 provider: ${profile.provider}"
                                            testingProfileId = null
                                            return@launch
                                        }
                                        val key = appContainer.llmKeyManager.selectKey(profile) ?: ""
                                        val testProfile = profile.copy(apiKeys = listOf(key))
                                        try {
                                            adapter.chat(
                                                testProfile,
                                                listOf(LlmMessage("user", "Hi, respond with just 'OK'")),
                                                LlmRequestOptions(model = "gpt-4o-mini", stream = false, maxTokens = 10),
                                            ).catch { e ->
                                                testResult = "❌ ${e.message}"
                                            }.collect { event ->
                                                when (event) {
                                                    is LlmStreamEvent.Completed -> {
                                                        testResult = "✅ 连接成功: ${event.fullText.take(50)}"
                                                        appContainer.llmKeyManager.reportSuccess(key)
                                                    }
                                                    is LlmStreamEvent.Error -> {
                                                        testResult = "❌ ${event.message.take(100)}"
                                                        if (key.isNotBlank()) appContainer.llmKeyManager.reportError(key)
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        } catch (e: Exception) {
                                            testResult = "❌ ${e.message}"
                                        }
                                        testingProfileId = null
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                if (isTesting) {
                                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.PlayArrow, "测试", Modifier.size(20.dp))
                                }
                            }
                            // 删除按钮
                            IconButton(
                                onClick = { scope.launch { store.deleteProfile(profile.id) } },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (testResult != null && (testingProfileId == null || testingProfileId == profile.id)) {
                            Text(
                                testResult!!,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                                color = if (testResult!!.startsWith("✅")) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // 工具审批区

            if (pendingApprovals.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠️ 待审批 (${pendingApprovals.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF9500))
                }
                items(pendingApprovals) { req ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9500).copy(0.08f)),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${req.toolName}${req.maidName?.let { " · $it" } ?: ""}", fontWeight = FontWeight.SemiBold)
                            Text(req.content.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { toolBridge.approve(req.requestId) },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp),
                                ) { Text("✓ 允许") }
                                OutlinedButton(
                                    onClick = { toolBridge.reject(req.requestId) },
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp),
                                ) { Text("✕ 拒绝") }
                            }
                        }
                    }
                }
            }

            if (toolLogs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("📋 工具日志 (${toolLogs.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(toolLogs.takeLast(15).reversed()) { log ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(log.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(log.content.take(100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    // 添加服务商对话框
    if (showAddDialog) {
        AddProfileDialog(
            onDismiss = { showAddDialog = false },
            onSave = { profile ->
                scope.launch {
                    store.addProfile(profile)
                    showAddDialog = false
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProfileDialog(
    onDismiss: () -> Unit,
    onSave: (LlmProfile) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("openai") }
    var baseUrl by remember { mutableStateOf(PROVIDERS[0].defaultBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("如：我的 OpenAI") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = PROVIDERS.find { it.id == provider }?.name ?: provider,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        PROVIDERS.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    provider = p.id
                                    if (p.defaultBaseUrl.isNotBlank()) baseUrl = p.defaultBaseUrl
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        LlmProfile(
                            id = UUID.randomUUID().toString().take(8),
                            name = name.trim().ifBlank { PROVIDERS.find { it.id == provider }?.name ?: provider },
                            provider = provider,
                            baseUrl = baseUrl.trim(),
                            apiKeys = listOf(apiKey.trim()).filter { it.isNotBlank() },
                        )
                    )
                },
                enabled = baseUrl.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
