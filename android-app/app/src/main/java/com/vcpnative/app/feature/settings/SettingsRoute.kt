package com.vcpnative.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.exporter.AppDataExportManager
import com.vcpnative.app.data.exporter.AppDataExportResult
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.model.VcpModelInfo
import com.vcpnative.app.network.vcp.MODEL_CATALOG_LOADING_TEXT
import com.vcpnative.app.network.vcp.VcpModelCatalog
import com.vcpnative.app.network.vcp.VcpServiceConfig
import com.vcpnative.app.network.vcp.buildModelFetchFailureText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val vcpLogUrl: String = "",
    val vcpLogKey: String = "",
    val enableVcpToolInjection: Boolean = false,
    val enableAgentBubbleTheme: Boolean = false,
    val enableThoughtChainInjection: Boolean = false,
    val enableContextSanitizer: Boolean = true,
    val contextSanitizerDepth: String = "2",
    val enableContextFolding: Boolean = true,
    val contextFoldingKeepRecentMessages: String = "12",
    val contextFoldingTriggerMessageCount: String = "24",
    val contextFoldingTriggerCharCount: String = "24000",
    val contextFoldingExcerptCharLimit: String = "160",
    val contextFoldingMaxSummaryEntries: String = "40",
    val topicSummaryModel: String = "gemini-2.5-flash",
    val rootDir: String = "",
    val exportsDir: String = "",
    val isSaving: Boolean = false,
    val isExporting: Boolean = false,
    val isRefreshingModels: Boolean = false,
    val availableModels: List<VcpModelInfo> = emptyList(),
    val modelStatus: String? = null,
    val exportStatus: String? = null,
    val lastExportZipPath: String? = null,
) {
    val canSave: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank() && !isSaving
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appDataExportManager: AppDataExportManager,
    private val modelCatalog: VcpModelCatalog,
    fileStore: AppFileStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            rootDir = fileStore.rootDir.absolutePath,
            exportsDir = fileStore.exportsDir.absolutePath,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            _uiState.value = _uiState.value.copy(
                serverUrl = settings.vcpServerUrl,
                apiKey = settings.vcpApiKey,
                vcpLogUrl = settings.vcpLogUrl,
                vcpLogKey = settings.vcpLogKey,
                enableVcpToolInjection = settings.enableVcpToolInjection,
                enableAgentBubbleTheme = settings.enableAgentBubbleTheme,
                enableThoughtChainInjection = settings.enableThoughtChainInjection,
                enableContextSanitizer = settings.enableContextSanitizer,
                contextSanitizerDepth = settings.contextSanitizerDepth.toString(),
                enableContextFolding = settings.enableContextFolding,
                contextFoldingKeepRecentMessages = settings.contextFoldingKeepRecentMessages.toString(),
                contextFoldingTriggerMessageCount = settings.contextFoldingTriggerMessageCount.toString(),
                contextFoldingTriggerCharCount = settings.contextFoldingTriggerCharCount.toString(),
                contextFoldingExcerptCharLimit = settings.contextFoldingExcerptCharLimit.toString(),
                contextFoldingMaxSummaryEntries = settings.contextFoldingMaxSummaryEntries.toString(),
                topicSummaryModel = settings.topicSummaryModel,
            )
            if (settings.isConfigured) {
                refreshModels(forceRefresh = false)
            }
        }
    }

    fun updateServerUrl(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value)
    }

    fun updateApiKey(value: String) {
        _uiState.value = _uiState.value.copy(apiKey = value)
    }

    fun updateVcpLogUrl(value: String) {
        _uiState.value = _uiState.value.copy(vcpLogUrl = value)
    }

    fun updateVcpLogKey(value: String) {
        _uiState.value = _uiState.value.copy(vcpLogKey = value)
    }

    fun updateEnableVcpToolInjection(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableVcpToolInjection = value)
    }

    fun updateEnableAgentBubbleTheme(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableAgentBubbleTheme = value)
    }

    fun updateEnableThoughtChainInjection(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableThoughtChainInjection = value)
    }

    fun updateEnableContextSanitizer(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableContextSanitizer = value)
    }

    fun updateContextSanitizerDepth(value: String) {
        _uiState.value = _uiState.value.copy(contextSanitizerDepth = value)
    }

    fun updateTopicSummaryModel(value: String) {
        _uiState.value = _uiState.value.copy(topicSummaryModel = value)
    }

    fun updateEnableContextFolding(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableContextFolding = value)
    }

    fun updateContextFoldingKeepRecentMessages(value: String) {
        _uiState.value = _uiState.value.copy(contextFoldingKeepRecentMessages = value)
    }

    fun updateContextFoldingTriggerMessageCount(value: String) {
        _uiState.value = _uiState.value.copy(contextFoldingTriggerMessageCount = value)
    }

    fun updateContextFoldingTriggerCharCount(value: String) {
        _uiState.value = _uiState.value.copy(contextFoldingTriggerCharCount = value)
    }

    fun updateContextFoldingExcerptCharLimit(value: String) {
        _uiState.value = _uiState.value.copy(contextFoldingExcerptCharLimit = value)
    }

    fun updateContextFoldingMaxSummaryEntries(value: String) {
        _uiState.value = _uiState.value.copy(contextFoldingMaxSummaryEntries = value)
    }

    fun updateExportStatus(value: String) {
        _uiState.value = _uiState.value.copy(exportStatus = value)
    }

    suspend fun refreshModels(forceRefresh: Boolean = true) {
        val snapshot = _uiState.value
        if (snapshot.isRefreshingModels) {
            return
        }
        if (snapshot.serverUrl.isBlank() || snapshot.apiKey.isBlank()) {
            _uiState.value = snapshot.copy(
                availableModels = emptyList(),
                modelStatus = "先填写可用的 Server URL 和 API Key，再获取模型列表。",
            )
            return
        }

        _uiState.value = snapshot.copy(
            isRefreshingModels = true,
            modelStatus = MODEL_CATALOG_LOADING_TEXT,
        )
        runCatching {
            modelCatalog.fetchAvailableModels(
                forceRefresh = forceRefresh,
                serviceConfigOverride = VcpServiceConfig(
                    baseUrl = snapshot.serverUrl.trim(),
                    apiKey = snapshot.apiKey.trim(),
                ),
            )
        }.onSuccess { models ->
            _uiState.value = _uiState.value.copy(
                isRefreshingModels = false,
                availableModels = models,
                modelStatus = if (models.isEmpty()) {
                    "模型接口可达，但没有返回可用模型。"
                } else {
                    "已获取 ${models.size} 个模型。"
                },
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                isRefreshingModels = false,
                availableModels = emptyList(),
                modelStatus = buildModelFetchFailureText(
                    error = error,
                    fallbackHint = "。这不影响聊天，可先在 Agent 配置里手填模型 ID。",
                ),
            )
        }
    }

    suspend fun save(): Boolean {
        val snapshot = _uiState.value
        if (!snapshot.canSave) {
            return false
        }

        _uiState.value = snapshot.copy(isSaving = true)
        try {
            val sanitizerDepth = snapshot.contextSanitizerDepth.toIntOrNull()?.coerceAtLeast(0) ?: 2
            val keepRecent = snapshot.contextFoldingKeepRecentMessages.toIntOrNull()?.coerceAtLeast(4) ?: 12
            val triggerMessageCount = snapshot.contextFoldingTriggerMessageCount.toIntOrNull()?.coerceAtLeast(8) ?: 24
            val triggerCharCount = snapshot.contextFoldingTriggerCharCount.toIntOrNull()?.coerceAtLeast(4_000) ?: 24_000
            val excerptCharLimit = snapshot.contextFoldingExcerptCharLimit.toIntOrNull()?.coerceAtLeast(40) ?: 160
            val maxSummaryEntries = snapshot.contextFoldingMaxSummaryEntries.toIntOrNull()?.coerceAtLeast(8) ?: 40
            val summaryModel = snapshot.topicSummaryModel.trim().ifBlank { "gemini-2.5-flash" }
            settingsRepository.saveConnection(
                serverUrl = snapshot.serverUrl,
                apiKey = snapshot.apiKey,
                vcpLogUrl = snapshot.vcpLogUrl,
                vcpLogKey = snapshot.vcpLogKey,
            )
            settingsRepository.saveCompilerOptions(
                enableVcpToolInjection = snapshot.enableVcpToolInjection,
                enableAgentBubbleTheme = snapshot.enableAgentBubbleTheme,
                enableThoughtChainInjection = snapshot.enableThoughtChainInjection,
                enableContextSanitizer = snapshot.enableContextSanitizer,
                contextSanitizerDepth = sanitizerDepth,
                enableContextFolding = snapshot.enableContextFolding,
                contextFoldingKeepRecentMessages = keepRecent,
                contextFoldingTriggerMessageCount = triggerMessageCount,
                contextFoldingTriggerCharCount = triggerCharCount,
                contextFoldingExcerptCharLimit = excerptCharLimit,
                contextFoldingMaxSummaryEntries = maxSummaryEntries,
                topicSummaryModel = summaryModel,
            )
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                contextSanitizerDepth = sanitizerDepth.toString(),
                contextFoldingKeepRecentMessages = keepRecent.toString(),
                contextFoldingTriggerMessageCount = triggerMessageCount.toString(),
                contextFoldingTriggerCharCount = triggerCharCount.toString(),
                contextFoldingExcerptCharLimit = excerptCharLimit.toString(),
                contextFoldingMaxSummaryEntries = maxSummaryEntries.toString(),
                topicSummaryModel = summaryModel,
            )
            refreshModels(forceRefresh = true)
            return true
        } catch (error: Throwable) {
            _uiState.value = _uiState.value.copy(isSaving = false)
            throw error
        }
    }

    suspend fun exportAppData() {
        val snapshot = _uiState.value
        if (snapshot.isExporting) {
            return
        }

        _uiState.value = snapshot.copy(
            isExporting = true,
            exportStatus = "正在导出 AppData…",
        )
        val result = appDataExportManager.exportCurrentSnapshot()
        _uiState.value = _uiState.value.copy(
            isExporting = false,
            exportStatus = result.toStatusText(),
            lastExportZipPath = (result as? AppDataExportResult.Exported)?.zipPath,
        )
    }

    companion object {
        fun factory(appContainer: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = appContainer.settingsRepository,
                    appDataExportManager = appContainer.appDataExportManager,
                    modelCatalog = appContainer.modelCatalog,
                    fileStore = appContainer.fileStore,
                )
            }
        }
    }
}

@Composable
fun SettingsRoute(
    appContainer: AppContainer,
    isSetup: Boolean,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(appContainer))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportZipSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        val zipPath = uiState.lastExportZipPath ?: return@rememberLauncherForActivityResult
        scope.launch {
            val status = saveExportZipToUri(
                context = context,
                zipPath = zipPath,
                uri = uri,
            )
            viewModel.updateExportStatus(status)
        }
    }

    SettingsScreen(
        uiState = uiState,
        isSetup = isSetup,
        onNavigateBack = onNavigateBack,
        onServerUrlChange = viewModel::updateServerUrl,
        onApiKeyChange = viewModel::updateApiKey,
        onVcpLogUrlChange = viewModel::updateVcpLogUrl,
        onVcpLogKeyChange = viewModel::updateVcpLogKey,
        onEnableVcpToolInjectionChange = viewModel::updateEnableVcpToolInjection,
        onEnableAgentBubbleThemeChange = viewModel::updateEnableAgentBubbleTheme,
        onEnableThoughtChainInjectionChange = viewModel::updateEnableThoughtChainInjection,
        onEnableContextSanitizerChange = viewModel::updateEnableContextSanitizer,
        onContextSanitizerDepthChange = viewModel::updateContextSanitizerDepth,
        onEnableContextFoldingChange = viewModel::updateEnableContextFolding,
        onContextFoldingKeepRecentMessagesChange = viewModel::updateContextFoldingKeepRecentMessages,
        onContextFoldingTriggerMessageCountChange = viewModel::updateContextFoldingTriggerMessageCount,
        onContextFoldingTriggerCharCountChange = viewModel::updateContextFoldingTriggerCharCount,
        onContextFoldingExcerptCharLimitChange = viewModel::updateContextFoldingExcerptCharLimit,
        onContextFoldingMaxSummaryEntriesChange = viewModel::updateContextFoldingMaxSummaryEntries,
        onTopicSummaryModelChange = viewModel::updateTopicSummaryModel,
        onSave = {
            scope.launch {
                if (viewModel.save()) {
                    onSaved()
                }
            }
        },
        onRefreshModels = {
            scope.launch {
                viewModel.refreshModels(forceRefresh = true)
            }
        },
        onExport = {
            scope.launch {
                viewModel.exportAppData()
            }
        },
        onShareLatestExport = {
            uiState.lastExportZipPath?.let { zipPath ->
                shareExportZip(context, zipPath)
            }
        },
        onSaveLatestExportCopy = {
            val suggestedName = uiState.lastExportZipPath
                ?.let(::File)
                ?.name
                ?: "vcpnative-export.zip"
            exportZipSaver.launch(suggestedName)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    uiState: SettingsUiState,
    isSetup: Boolean,
    onNavigateBack: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onVcpLogUrlChange: (String) -> Unit,
    onVcpLogKeyChange: (String) -> Unit,
    onEnableVcpToolInjectionChange: (Boolean) -> Unit,
    onEnableAgentBubbleThemeChange: (Boolean) -> Unit,
    onEnableThoughtChainInjectionChange: (Boolean) -> Unit,
    onEnableContextSanitizerChange: (Boolean) -> Unit,
    onContextSanitizerDepthChange: (String) -> Unit,
    onEnableContextFoldingChange: (Boolean) -> Unit,
    onContextFoldingKeepRecentMessagesChange: (String) -> Unit,
    onContextFoldingTriggerMessageCountChange: (String) -> Unit,
    onContextFoldingTriggerCharCountChange: (String) -> Unit,
    onContextFoldingExcerptCharLimitChange: (String) -> Unit,
    onContextFoldingMaxSummaryEntriesChange: (String) -> Unit,
    onTopicSummaryModelChange: (String) -> Unit,
    onSave: () -> Unit,
    onRefreshModels: () -> Unit,
    onExport: () -> Unit,
    onShareLatestExport: () -> Unit,
    onSaveLatestExportCopy: () -> Unit,
) {
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
                                text = if (isSetup) "初始设置" else "系统设置",
                                fontWeight = FontWeight.Black
                            )
                        },
                        navigationIcon = {
                            if (!isSetup) {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "返回",
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Text(
                        text = if (isSetup) "建立精神连接，唤醒你的数字伙伴！" else "调整参数，让魔法流转更顺畅～",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            
            AnimeSettingsCard(
                title = "核心连接",
                subtitle = "先把灵魂连接参数固定下来，之后 Bootstrap 才能恢复到 Agent -> Topic -> Chat 主工作流哦～"
            ) {
                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "settings_server_url_field" },
                    label = { Text(text = "VCP Server URL") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "settings_api_key_field" },
                    label = { Text(text = "VCP API Key") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            AnimeSettingsCard(
                title = "信息广播",
                subtitle = "通过 WebSocket 接收 VCP 服务器的实时通知和工具执行日志。"
            ) {
                OutlinedTextField(
                    value = uiState.vcpLogUrl,
                    onValueChange = onVcpLogUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "VCP WebSocket URL") },
                    placeholder = { Text(text = "ws://127.0.0.1:5890") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.vcpLogKey,
                    onValueChange = onVcpLogKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "VCP WebSocket Key") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            AnimeSettingsCard(
                title = "编译选项",
                subtitle = "调整底层咒语，适配各种奇妙的运行环境。"
            ) {
                SettingsToggleRow(
                    title = "VCP Tool Injection",
                    subtitle = "把聊天请求路径切到 /v1/chatvcp/completions。",
                    checked = uiState.enableVcpToolInjection,
                    onCheckedChange = onEnableVcpToolInjectionChange,
                )
                SettingsToggleRow(
                    title = "Agent Bubble Theme",
                    subtitle = "在 system prompt 里追加 {{VarDivRender}} 输出规范。",
                    checked = uiState.enableAgentBubbleTheme,
                    onCheckedChange = onEnableAgentBubbleThemeChange,
                )
                SettingsToggleRow(
                    title = "Thought Chain Injection",
                    subtitle = "开启后保留思维链在上下文中，关闭则在发送前剥离 <think>/<thinking> 和 VCP 元思考链。",
                    checked = uiState.enableThoughtChainInjection,
                    onCheckedChange = onEnableThoughtChainInjectionChange,
                )
                SettingsToggleRow(
                    title = "Context Sanitizer",
                    subtitle = "将历史中较早的 AI 消息的 HTML 净化为纯文本，减少 token 开销。",
                    checked = uiState.enableContextSanitizer,
                    onCheckedChange = onEnableContextSanitizerChange,
                )
                OutlinedTextField(
                    value = uiState.contextSanitizerDepth,
                    onValueChange = onContextSanitizerDepthChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Sanitizer 跳过最近 AI 消息数") },
                    enabled = uiState.enableContextSanitizer,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                SettingsToggleRow(
                    title = "Context Folding",
                    subtitle = "按 VCPChat 的 contextFolder 规则压缩更早历史，保留最近消息原文。",
                    checked = uiState.enableContextFolding,
                    onCheckedChange = onEnableContextFoldingChange,
                )
            }

            AnimeSettingsCard(
                title = "上下文折叠",
                subtitle = "直接参考 VCPChat `contextFolder.js` 默认值和语义，让伙伴的记忆更长久～"
            ) {
                OutlinedTextField(
                    value = uiState.contextFoldingKeepRecentMessages,
                    onValueChange = onContextFoldingKeepRecentMessagesChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "保留最近消息数") },
                    enabled = uiState.enableContextFolding,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.contextFoldingTriggerMessageCount,
                    onValueChange = onContextFoldingTriggerMessageCountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "触发消息数阈值") },
                    enabled = uiState.enableContextFolding,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.contextFoldingTriggerCharCount,
                    onValueChange = onContextFoldingTriggerCharCountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "触发字符数阈值") },
                    enabled = uiState.enableContextFolding,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.contextFoldingExcerptCharLimit,
                    onValueChange = onContextFoldingExcerptCharLimitChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "摘要摘录长度") },
                    enabled = uiState.enableContextFolding,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.contextFoldingMaxSummaryEntries,
                    onValueChange = onContextFoldingMaxSummaryEntriesChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "最大摘要条目数") },
                    enabled = uiState.enableContextFolding,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = uiState.topicSummaryModel,
                    onValueChange = onTopicSummaryModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "话题自动总结模型") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            AnimeSettingsCard(
                title = "可用模型",
                subtitle = "按 VCPChat 的方式从 `${uiState.serverUrl.ifBlank { "(未配置)" }}` 对应的 `/v1/models` 获取。"
            ) {
                Button(
                    onClick = onRefreshModels,
                    enabled = !uiState.isRefreshingModels && uiState.serverUrl.isNotBlank() && uiState.apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = if (uiState.isRefreshingModels) "雷达扫描中…" else "探测可用模型",
                        fontWeight = FontWeight.Bold
                    )
                }
                uiState.modelStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (uiState.availableModels.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        uiState.availableModels.forEach { model ->
                            Text(
                                text = buildString {
                                    append(model.id)
                                    model.ownedBy?.let {
                                        append(" · ")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            AnimeSettingsCard(
                title = "数据目录",
                subtitle = "运行时真相来源固定为 DataStore + Room + private files。"
            ) {
                Text(
                    text = uiState.rootDir,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "导出目录: ${uiState.exportsDir}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isSetup) {
                AnimeSettingsCard(
                    title = "数据备份",
                    subtitle = "从当前运行时真相和 compat view 重建桌面风格 AppData，并补回 passthrough 空位。"
                ) {
                    Button(
                        onClick = onExport,
                        enabled = !uiState.isExporting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = if (uiState.isExporting) "导出中…" else "导出当前 AppData",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onShareLatestExport,
                        enabled = uiState.lastExportZipPath?.let { File(it).isFile } == true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = "分享最近导出 ZIP",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onSaveLatestExportCopy,
                        enabled = uiState.lastExportZipPath?.let { File(it).isFile } == true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = "另存最近导出 ZIP",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    uiState.exportStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = onSave,
                enabled = uiState.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(56.dp)
                    .semantics { contentDescription = "save_settings_button" },
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = if (uiState.isSaving) "保存中…" else "保存并继续",
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun AnimeSettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                content()
            }
        }
    }
}

private fun AppDataExportResult.toStatusText(): String = when (this) {
    is AppDataExportResult.Exported -> buildString {
        append("已导出到 ")
        append(zipPath)
        if (warnings.isNotEmpty()) {
            append(" · Warnings ")
            append(warnings.size)
        }
    }

    is AppDataExportResult.Failed -> buildString {
        append("导出失败 · ")
        append(message)
        reportPath?.takeIf { it.isNotBlank() }?.let { path ->
            append(" · Report ")
            append(path)
        }
    }
}

private fun shareExportZip(
    context: Context,
    zipPath: String,
) {
    val zipFile = File(zipPath).takeIf(File::isFile) ?: return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        zipFile,
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "分享导出 ZIP").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

private suspend fun saveExportZipToUri(
    context: Context,
    zipPath: String,
    uri: Uri,
): String = withContext(Dispatchers.IO) {
    val zipFile = File(zipPath).takeIf(File::isFile)
        ?: return@withContext "外部保存失败 · ZIP 不存在"

    return@withContext runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            zipFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("无法打开目标输出流")
        "已另存导出 ZIP"
    }.getOrElse { error ->
        "外部保存失败 · ${error.message ?: "未知错误"}"
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}