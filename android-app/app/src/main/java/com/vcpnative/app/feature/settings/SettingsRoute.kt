package com.vcpnative.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
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
    // 模块配置 (Forum/Memo 共享凭据)
    val userName: String = "用户",
    val forumUsername: String = "",
    val forumPassword: String = "",
    val forumReplyName: String = "",
    val forumRememberCredentials: Boolean = true,
    // 悬浮窗独立 API 配置 (留空则跟随全局)
    val overlayApiUrl: String = "",
    val overlayApiKey: String = "",
    val overlayModel: String = "",
) {
    val canSave: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank() && !isSaving
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appDataExportManager: AppDataExportManager,
    private val modelCatalog: VcpModelCatalog,
    private val fileStore: AppFileStore,
    private val appContext: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            rootDir = fileStore.rootDir.absolutePath,
            exportsDir = fileStore.exportsDir.absolutePath,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // 与 IpcHandlers.kt 中 configDir 路径保持一致：context.filesDir/module_configs/
    private val forumConfigFile: File get() = File(appContext.filesDir, "module_configs/forum_config.json")

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
                overlayApiUrl = settings.overlayApiUrl,
                overlayApiKey = settings.overlayApiKey,
                overlayModel = settings.overlayModel,
            )
            // 加载 Forum 凭据
            loadForumConfig()
            if (settings.isConfigured) {
                refreshModels(forceRefresh = false)
            }
        }
    }

    private fun loadForumConfig() {
        try {
            if (forumConfigFile.exists()) {
                val json = org.json.JSONObject(forumConfigFile.readText())
                _uiState.value = _uiState.value.copy(
                    forumUsername = json.optString("username", ""),
                    forumPassword = json.optString("password", ""),
                    forumReplyName = json.optString("replyUsername", ""),
                    forumRememberCredentials = json.optBoolean("rememberCredentials", true),
                )
            }
        } catch (_: Exception) {}
    }

    fun updateForumUsername(v: String) { _uiState.value = _uiState.value.copy(forumUsername = v) }
    fun updateForumPassword(v: String) { _uiState.value = _uiState.value.copy(forumPassword = v) }
    fun updateForumReplyName(v: String) { _uiState.value = _uiState.value.copy(forumReplyName = v) }
    fun updateForumRememberCredentials(v: Boolean) { _uiState.value = _uiState.value.copy(forumRememberCredentials = v) }
    fun updateUserName(v: String) { _uiState.value = _uiState.value.copy(userName = v) }

    fun updateOverlayApiUrl(value: String) { _uiState.value = _uiState.value.copy(overlayApiUrl = value) }
    fun updateOverlayApiKey(value: String) { _uiState.value = _uiState.value.copy(overlayApiKey = value) }
    fun updateOverlayModel(value: String) { _uiState.value = _uiState.value.copy(overlayModel = value) }

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
            settingsRepository.saveOverlayApiConfig(
                apiUrl = snapshot.overlayApiUrl,
                apiKey = snapshot.overlayApiKey,
                model = snapshot.overlayModel,
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
            // 保存 Forum/Memo 共享凭据
            withContext(Dispatchers.IO) {
                forumConfigFile.parentFile?.mkdirs()
                val forumJson = org.json.JSONObject().apply {
                    put("username", snapshot.forumUsername.trim())
                    put("password", if (snapshot.forumRememberCredentials) snapshot.forumPassword else "")
                    put("replyUsername", snapshot.forumReplyName.trim())
                    put("rememberCredentials", snapshot.forumRememberCredentials)
                }
                forumConfigFile.writeText(forumJson.toString())
            }

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
        fun factory(appContainer: AppContainer, context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = appContainer.settingsRepository,
                    appDataExportManager = appContainer.appDataExportManager,
                    modelCatalog = appContainer.modelCatalog,
                    fileStore = appContainer.fileStore,
                    appContext = context.applicationContext,
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
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(appContainer, context))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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
        onForumUsernameChange = viewModel::updateForumUsername,
        onForumPasswordChange = viewModel::updateForumPassword,
        onForumReplyNameChange = viewModel::updateForumReplyName,
        onForumRememberCredentialsChange = viewModel::updateForumRememberCredentials,
        onOverlayApiUrlChange = viewModel::updateOverlayApiUrl,
        onOverlayApiKeyChange = viewModel::updateOverlayApiKey,
        onOverlayModelChange = viewModel::updateOverlayModel,
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
    onForumUsernameChange: (String) -> Unit = {},
    onForumPasswordChange: (String) -> Unit = {},
    onForumReplyNameChange: (String) -> Unit = {},
    onForumRememberCredentialsChange: (Boolean) -> Unit = {},
    onOverlayApiUrlChange: (String) -> Unit = {},
    onOverlayApiKeyChange: (String) -> Unit = {},
    onOverlayModelChange: (String) -> Unit = {},
) {
    // ── Staggered entrance animation state ──
    val cardCount = if (isSetup) 8 else 9
    val animProgress = remember { List(cardCount) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        animProgress.forEachIndexed { index, anim ->
            delay(index * 60L)
            anim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
    }

    // Expandable state for "上下文折叠" section
    var contextFoldingExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
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
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Card index tracker
            var ci = 0

            // ── 1. Core Connection ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.Hub,
                accentColors = listOf(Color(0xFF007AFF), Color(0xFF5856D6)),
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

            // ── 2. Broadcast ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.CellTower,
                accentColors = listOf(Color(0xFF34C759), Color(0xFF30D158)),
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

            // ── 3. Compiler Options ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.Build,
                accentColors = listOf(Color(0xFFFF9500), Color(0xFFFF3B30)),
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

            // ── 4. Context Folding (Collapsible) ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.LayersClear,
                accentColors = listOf(Color(0xFF5856D6), Color(0xFFAF52DE)),
                title = "上下文折叠",
                subtitle = "直接参考 VCPChat `contextFolder.js` 默认值和语义，让伙伴的记忆更长久～",
                trailing = {
                    Icon(
                        imageVector = if (contextFoldingExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (contextFoldingExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onHeaderClick = { contextFoldingExpanded = !contextFoldingExpanded },
            ) {
                AnimatedVisibility(
                    visible = contextFoldingExpanded,
                    enter = expandVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ) + fadeIn(),
                    exit = shrinkVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ) + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                }
            }

            // ── 5. Available Models ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.SmartToy,
                accentColors = listOf(Color(0xFF0A84FF), Color(0xFF64D2FF)),
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

            // ── 6. Module Config ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.Forum,
                accentColors = listOf(Color(0xFFFF2D55), Color(0xFFFF6482)),
                title = "模块配置",
                subtitle = "Forum / Memo / 日记 等模块共享此凭据连接 VCP 服务器。"
            ) {
                OutlinedTextField(
                    value = uiState.forumUsername,
                    onValueChange = onForumUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Forum 用户名") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = uiState.forumPassword,
                    onValueChange = onForumPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Forum 密码") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = uiState.forumReplyName,
                    onValueChange = onForumReplyNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("回帖署名（留空则用用户名）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                SettingsToggleRow(
                    title = "记住登录信息",
                    subtitle = "关闭则不保存密码到本地。",
                    checked = uiState.forumRememberCredentials,
                    onCheckedChange = onForumRememberCredentialsChange,
                )
            }

            // ── 7. Data Directory ──
            AnimatedSettingsCard(
                progress = animProgress[ci++],
                icon = Icons.Outlined.FolderOpen,
                accentColors = listOf(Color(0xFF8E8E93), Color(0xFFAEAEB2)),
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

            // ── 8. Data Backup ──
            if (!isSetup) {
                AnimatedSettingsCard(
                    progress = animProgress[ci],
                    icon = Icons.Outlined.Backup,
                    accentColors = listOf(Color(0xFF34C759), Color(0xFF00C7BE)),
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

            // ── AI Floating Window ──
            if (!isSetup) {
                val context = LocalContext.current
                var isOverlayRunning by remember {
                    mutableStateOf(com.vcpnative.app.feature.overlay.AiOverlayService.isRunning())
                }
                AnimatedSettingsCard(
                    progress = animProgress[ci - 1],
                    icon = Icons.Outlined.SmartToy,
                    accentColors = listOf(Color(0xFF6C5CE7), Color(0xFFA29BFE)),
                    title = "AI 悬浮助手",
                    subtitle = "在任意应用上方显示悬浮窗，支持截屏识图和 AI 对话。"
                ) {
                    SettingsToggleRow(
                        title = "启用悬浮助手",
                        subtitle = "需要在系统无障碍设置中开启本应用的无障碍服务。",
                        checked = isOverlayRunning,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // Open system accessibility settings
                                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } else {
                                // Disable by opening accessibility settings (user must toggle off)
                                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                    )
                    Text(
                        text = if (isOverlayRunning) "服务运行中" else "请在无障碍设置中启用 VCPNative",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverlayRunning) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "悬浮窗独立 API 配置（留空则跟随全局设置）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.overlayApiUrl,
                        onValueChange = onOverlayApiUrlChange,
                        label = { Text("API 地址") },
                        placeholder = { Text("留空跟随全局") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = uiState.overlayApiKey,
                        onValueChange = onOverlayApiKeyChange,
                        label = { Text("API Key") },
                        placeholder = { Text("留空跟随全局") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = uiState.overlayModel,
                        onValueChange = onOverlayModelChange,
                        label = { Text("模型") },
                        placeholder = { Text("默认 gemini-2.5-flash") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Save CTA with gradient ──
            val saveBtnAlpha = animProgress.last()
            Button(
                onClick = onSave,
                enabled = uiState.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(56.dp)
                    .graphicsLayer {
                        alpha = saveBtnAlpha.value
                        translationY = (1f - saveBtnAlpha.value) * 40f
                    }
                    .semantics { contentDescription = "save_settings_button" },
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                ),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (uiState.canSave) {
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    )
                                )
                            } else {
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    )
                                )
                            },
                            shape = MaterialTheme.shapes.large,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Save,
                            contentDescription = null,
                            tint = if (uiState.canSave) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (uiState.isSaving) "保存中…" else "保存并继续",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (uiState.canSave) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AnimatedSettingsCard(
    progress: Animatable<Float, *>,
    icon: ImageVector,
    accentColors: List<Color>,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * 60f
                scaleX = 0.92f + 0.08f * progress.value
                scaleY = 0.92f + 0.08f * progress.value
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Gradient accent stripe
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        brush = Brush.verticalGradient(colors = accentColors)
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header row: icon + title + optional trailing
                val headerModifier = if (onHeaderClick != null) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onHeaderClick)
                } else {
                    Modifier.fillMaxWidth()
                }
                Row(
                    modifier = headerModifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Section icon with tinted background
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = accentColors.first().copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.small,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColors.first(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (trailing != null) {
                        trailing()
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}