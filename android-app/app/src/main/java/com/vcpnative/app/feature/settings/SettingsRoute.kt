package com.vcpnative.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.vcpnative.app.network.vcp.VcpServiceConfig
import com.vcpnative.app.network.vcp.VcpModelCatalog
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
    val enableVcpToolInjection: Boolean = false,
    val enableAgentBubbleTheme: Boolean = false,
    val enableContextFolding: Boolean = true,
    val contextFoldingKeepRecentMessages: String = "12",
    val contextFoldingTriggerMessageCount: String = "24",
    val contextFoldingTriggerCharCount: String = "24000",
    val contextFoldingExcerptCharLimit: String = "160",
    val contextFoldingMaxSummaryEntries: String = "40",
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
                enableVcpToolInjection = settings.enableVcpToolInjection,
                enableAgentBubbleTheme = settings.enableAgentBubbleTheme,
                enableContextFolding = settings.enableContextFolding,
                contextFoldingKeepRecentMessages = settings.contextFoldingKeepRecentMessages.toString(),
                contextFoldingTriggerMessageCount = settings.contextFoldingTriggerMessageCount.toString(),
                contextFoldingTriggerCharCount = settings.contextFoldingTriggerCharCount.toString(),
                contextFoldingExcerptCharLimit = settings.contextFoldingExcerptCharLimit.toString(),
                contextFoldingMaxSummaryEntries = settings.contextFoldingMaxSummaryEntries.toString(),
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

    fun updateEnableVcpToolInjection(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableVcpToolInjection = value)
    }

    fun updateEnableAgentBubbleTheme(value: Boolean) {
        _uiState.value = _uiState.value.copy(enableAgentBubbleTheme = value)
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
        val keepRecent = snapshot.contextFoldingKeepRecentMessages.toIntOrNull()?.coerceAtLeast(4) ?: 12
        val triggerMessageCount = snapshot.contextFoldingTriggerMessageCount.toIntOrNull()?.coerceAtLeast(8) ?: 24
        val triggerCharCount = snapshot.contextFoldingTriggerCharCount.toIntOrNull()?.coerceAtLeast(4_000) ?: 24_000
        val excerptCharLimit = snapshot.contextFoldingExcerptCharLimit.toIntOrNull()?.coerceAtLeast(40) ?: 160
        val maxSummaryEntries = snapshot.contextFoldingMaxSummaryEntries.toIntOrNull()?.coerceAtLeast(8) ?: 40
        settingsRepository.saveConnection(
            serverUrl = snapshot.serverUrl,
            apiKey = snapshot.apiKey,
        )
        settingsRepository.saveCompilerOptions(
            enableVcpToolInjection = snapshot.enableVcpToolInjection,
            enableAgentBubbleTheme = snapshot.enableAgentBubbleTheme,
            enableContextFolding = snapshot.enableContextFolding,
            contextFoldingKeepRecentMessages = keepRecent,
            contextFoldingTriggerMessageCount = triggerMessageCount,
            contextFoldingTriggerCharCount = triggerCharCount,
            contextFoldingExcerptCharLimit = excerptCharLimit,
            contextFoldingMaxSummaryEntries = maxSummaryEntries,
        )
        _uiState.value = _uiState.value.copy(
            isSaving = false,
            contextFoldingKeepRecentMessages = keepRecent.toString(),
            contextFoldingTriggerMessageCount = triggerMessageCount.toString(),
            contextFoldingTriggerCharCount = triggerCharCount.toString(),
            contextFoldingExcerptCharLimit = excerptCharLimit.toString(),
            contextFoldingMaxSummaryEntries = maxSummaryEntries.toString(),
        )
        refreshModels(forceRefresh = true)
        return true
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
        onEnableVcpToolInjectionChange = viewModel::updateEnableVcpToolInjection,
        onEnableAgentBubbleThemeChange = viewModel::updateEnableAgentBubbleTheme,
        onEnableContextFoldingChange = viewModel::updateEnableContextFolding,
        onContextFoldingKeepRecentMessagesChange = viewModel::updateContextFoldingKeepRecentMessages,
        onContextFoldingTriggerMessageCountChange = viewModel::updateContextFoldingTriggerMessageCount,
        onContextFoldingTriggerCharCountChange = viewModel::updateContextFoldingTriggerCharCount,
        onContextFoldingExcerptCharLimitChange = viewModel::updateContextFoldingExcerptCharLimit,
        onContextFoldingMaxSummaryEntriesChange = viewModel::updateContextFoldingMaxSummaryEntries,
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
    onEnableVcpToolInjectionChange: (Boolean) -> Unit,
    onEnableAgentBubbleThemeChange: (Boolean) -> Unit,
    onEnableContextFoldingChange: (Boolean) -> Unit,
    onContextFoldingKeepRecentMessagesChange: (String) -> Unit,
    onContextFoldingTriggerMessageCountChange: (String) -> Unit,
    onContextFoldingTriggerCharCountChange: (String) -> Unit,
    onContextFoldingExcerptCharLimitChange: (String) -> Unit,
    onContextFoldingMaxSummaryEntriesChange: (String) -> Unit,
    onSave: () -> Unit,
    onRefreshModels: () -> Unit,
    onExport: () -> Unit,
    onShareLatestExport: () -> Unit,
    onSaveLatestExportCopy: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = if (isSetup) "初始化设置" else "设置")
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
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "先把连接参数固定下来，之后 Bootstrap 才能恢复到 Agent -> Topic -> Chat 主工作流。",
                style = MaterialTheme.typography.bodyLarge,
            )

            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "settings_server_url_field"
                    },
                label = { Text(text = "VCP Server URL") },
                singleLine = true,
            )

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "settings_api_key_field"
                    },
                label = { Text(text = "VCP API Key") },
                singleLine = true,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "请求编译兼容项",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
                        title = "Context Folding",
                        subtitle = "按 VCPChat 的 contextFolder 规则压缩更早历史，保留最近消息原文。",
                        checked = uiState.enableContextFolding,
                        onCheckedChange = onEnableContextFoldingChange,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "上下文折叠参数",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "直接参考 VCPChat `contextFolder.js` 默认值和语义。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = uiState.contextFoldingKeepRecentMessages,
                        onValueChange = onContextFoldingKeepRecentMessagesChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "保留最近消息数") },
                        enabled = uiState.enableContextFolding,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.contextFoldingTriggerMessageCount,
                        onValueChange = onContextFoldingTriggerMessageCountChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "触发消息数阈值") },
                        enabled = uiState.enableContextFolding,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.contextFoldingTriggerCharCount,
                        onValueChange = onContextFoldingTriggerCharCountChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "触发字符数阈值") },
                        enabled = uiState.enableContextFolding,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.contextFoldingExcerptCharLimit,
                        onValueChange = onContextFoldingExcerptCharLimitChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "摘要摘录长度") },
                        enabled = uiState.enableContextFolding,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.contextFoldingMaxSummaryEntries,
                        onValueChange = onContextFoldingMaxSummaryEntriesChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "最大摘要条目数") },
                        enabled = uiState.enableContextFolding,
                        singleLine = true,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "模型发现",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "按 VCPChat 的方式从 `${uiState.serverUrl.ifBlank { "(未配置)" }}` 对应的 `/v1/models` 获取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRefreshModels,
                        enabled = !uiState.isRefreshingModels && uiState.serverUrl.isNotBlank() && uiState.apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = if (uiState.isRefreshingModels) "刷新中…" else "刷新模型列表")
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
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "应用私有目录",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uiState.rootDir,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "运行时真相来源固定为 DataStore + Room + private files。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "导出目录: ${uiState.exportsDir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!isSetup) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "AppData 导出",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "从当前运行时真相和 compat view 重建桌面风格 AppData，并补回 passthrough 空位。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onExport,
                            enabled = !uiState.isExporting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = if (uiState.isExporting) "导出中…" else "导出当前 AppData")
                        }
                        Button(
                            onClick = onShareLatestExport,
                            enabled = uiState.lastExportZipPath?.let { File(it).isFile } == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "分享最近导出 ZIP")
                        }
                        Button(
                            onClick = onSaveLatestExportCopy,
                            enabled = uiState.lastExportZipPath?.let { File(it).isFile } == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = "另存最近导出 ZIP")
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
            }

            Button(
                onClick = onSave,
                enabled = uiState.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "save_settings_button"
                    },
            ) {
                Text(text = if (uiState.isSaving) "保存中…" else "保存并继续")
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
