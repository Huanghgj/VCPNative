package com.vcpnative.app.feature.agenteditor

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.chat.compiler.AgentPromptResolver
import com.vcpnative.app.data.prompt.DEFAULT_PRESET_PROMPT_PATH
import com.vcpnative.app.data.prompt.PromptPresetCatalog
import com.vcpnative.app.data.prompt.PromptPresetInfo
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.model.VcpModelInfo
import com.vcpnative.app.network.vcp.MODEL_CATALOG_LOADING_TEXT
import com.vcpnative.app.network.vcp.VcpModelCatalog
import com.vcpnative.app.network.vcp.buildModelFetchFailureText
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentEditorUiState(
    val agentId: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val name: String = "",
    val promptMode: String = "original",
    val originalSystemPrompt: String = "",
    val advancedSystemPromptJson: String = "",
    val presetSystemPrompt: String = "",
    val presetPromptPath: String = DEFAULT_PRESET_PROMPT_PATH,
    val selectedPreset: String = "",
    val resolvedPresetPath: String = "",
    val activeSystemPromptPreview: String = "",
    val model: String = "gemini-pro",
    val temperature: String = "0.7",
    val contextTokenLimit: String = "",
    val maxOutputTokens: String = "",
    val topP: String = "",
    val topK: String = "",
    val streamOutput: Boolean = true,
    val isRefreshingModels: Boolean = false,
    val availableModels: List<VcpModelInfo> = emptyList(),
    val modelStatus: String? = null,
    val isRefreshingPresets: Boolean = false,
    val availablePresets: List<PromptPresetInfo> = emptyList(),
    val presetStatus: String? = null,
    val saveStatus: String? = null,
    val loadError: String? = null,
) {
    val canSave: Boolean
        get() = !isLoading && !isSaving && loadError == null
}

class AgentEditorViewModel(
    private val agentId: String,
    private val workspaceRepository: WorkspaceRepository,
    private val modelCatalog: VcpModelCatalog,
    private val promptPresetCatalog: PromptPresetCatalog,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentEditorUiState(agentId = agentId))
    val uiState: StateFlow<AgentEditorUiState> = _uiState.asStateFlow()

    private var sourceAgent: AgentEntity? = null

    init {
        viewModelScope.launch {
            loadAgent()
            refreshModels(forceRefresh = false)
            refreshPresets()
        }
    }

    fun updateName(value: String) = updateDraft { copy(name = value, saveStatus = null) }

    fun updatePromptMode(value: String) =
        updateDraft { copy(promptMode = normalizePromptMode(value), saveStatus = null) }

    fun updateOriginalSystemPrompt(value: String) =
        updateDraft { copy(originalSystemPrompt = value, saveStatus = null) }

    fun updateAdvancedSystemPromptJson(value: String) =
        updateDraft { copy(advancedSystemPromptJson = value, saveStatus = null) }

    fun updatePresetSystemPrompt(value: String) =
        updateDraft {
            copy(
                presetSystemPrompt = value,
                selectedPreset = "",
                saveStatus = null,
                presetStatus = if (value.isBlank()) null else "已切换为自定义预设内容。",
            )
        }

    fun updatePresetPromptPath(value: String) =
        updateDraft {
            copy(
                presetPromptPath = value,
                resolvedPresetPath = "",
                availablePresets = emptyList(),
                presetStatus = null,
                saveStatus = null,
            )
        }

    fun updateModel(value: String) = updateDraft { copy(model = value, saveStatus = null) }

    fun updateTemperature(value: String) = updateDraft { copy(temperature = value, saveStatus = null) }

    fun updateContextTokenLimit(value: String) =
        updateDraft { copy(contextTokenLimit = value, saveStatus = null) }

    fun updateMaxOutputTokens(value: String) =
        updateDraft { copy(maxOutputTokens = value, saveStatus = null) }

    fun updateTopP(value: String) = updateDraft { copy(topP = value, saveStatus = null) }

    fun updateTopK(value: String) = updateDraft { copy(topK = value, saveStatus = null) }

    fun updateStreamOutput(value: Boolean) =
        updateDraft { copy(streamOutput = value, saveStatus = null) }

    suspend fun refreshModels(forceRefresh: Boolean = true) {
        val snapshot = _uiState.value
        if (snapshot.isRefreshingModels) {
            return
        }

        _uiState.value = snapshot.copy(
            isRefreshingModels = true,
            modelStatus = MODEL_CATALOG_LOADING_TEXT,
        )
        runCatching {
            modelCatalog.fetchAvailableModels(forceRefresh = forceRefresh)
        }.onSuccess { models ->
            _uiState.value = _uiState.value.copy(
                isRefreshingModels = false,
                availableModels = models,
                modelStatus = if (models.isEmpty()) {
                    "当前连接没有返回可用模型。"
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
                    fallbackHint = "。这不影响聊天，可直接手填模型 ID。",
                ),
            )
        }
    }

    suspend fun refreshPresets() {
        val snapshot = _uiState.value
        if (snapshot.isRefreshingPresets) {
            return
        }

        _uiState.value = snapshot.copy(
            isRefreshingPresets = true,
            presetStatus = "正在读取预设列表…",
        )
        runCatching {
            promptPresetCatalog.listPresets(snapshot.presetPromptPath)
        }.onSuccess { listing ->
            _uiState.value = _uiState.value.copy(
                isRefreshingPresets = false,
                presetPromptPath = listing.requestedPath,
                resolvedPresetPath = listing.resolvedPath,
                availablePresets = listing.presets,
                presetStatus = listing.message ?: "已读取 ${listing.presets.size} 个预设。",
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                isRefreshingPresets = false,
                availablePresets = emptyList(),
                resolvedPresetPath = "",
                presetStatus = error.message ?: "预设列表读取失败",
            )
        }
    }

    suspend fun applyPreset(presetPath: String) {
        runCatching {
            promptPresetCatalog.loadPresetContent(presetPath)
        }.onSuccess { content ->
            _uiState.value = _uiState.value.copy(
                presetSystemPrompt = content.trim(),
                selectedPreset = presetPath,
                presetStatus = "已应用预设: ${File(presetPath).nameWithoutExtension}",
                saveStatus = null,
            ).withResolvedPrompt()
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                presetStatus = error.message ?: "预设读取失败",
            )
        }
    }

    suspend fun save(): Boolean {
        val baseAgent = sourceAgent ?: return false
        val snapshot = _uiState.value
        if (!snapshot.canSave) {
            return false
        }

        _uiState.value = snapshot.copy(
            isSaving = true,
            saveStatus = "正在保存 Agent 配置…",
        )

        return runCatching {
            val normalizedMode = normalizePromptMode(snapshot.promptMode)
            val originalPrompt = snapshot.originalSystemPrompt.trim()
            val advancedPrompt = snapshot.advancedSystemPromptJson.trim()
            val presetPrompt = snapshot.presetSystemPrompt.trim()
            val presetPromptPath = snapshot.presetPromptPath.trim()
                .ifBlank { DEFAULT_PRESET_PROMPT_PATH }
            val selectedPreset = snapshot.selectedPreset.trim()
            val activePrompt = AgentPromptResolver.resolveModeContent(
                promptMode = normalizedMode,
                originalSystemPrompt = originalPrompt,
                advancedSystemPromptJson = advancedPrompt,
                presetSystemPrompt = presetPrompt,
            )

            workspaceRepository.saveAgent(
                baseAgent.copy(
                    name = snapshot.name.trim().ifBlank { baseAgent.name },
                    systemPrompt = activePrompt,
                    promptMode = normalizedMode,
                    originalSystemPrompt = originalPrompt,
                    advancedSystemPromptJson = advancedPrompt,
                    presetSystemPrompt = presetPrompt,
                    presetPromptPath = presetPromptPath,
                    selectedPreset = selectedPreset,
                    model = snapshot.model.trim().ifBlank { "gemini-pro" },
                    temperature = snapshot.temperature.toDoubleOrNull()
                        ?.coerceIn(0.0, 2.0)
                        ?: 0.7,
                    contextTokenLimit = snapshot.contextTokenLimit.toIntOrNull()
                        ?.takeIf { it > 0 },
                    maxOutputTokens = snapshot.maxOutputTokens.toIntOrNull()
                        ?.takeIf { it > 0 },
                    topP = snapshot.topP.toDoubleOrNull()
                        ?.takeIf { it in 0.0..1.0 },
                    topK = snapshot.topK.toIntOrNull()
                        ?.takeIf { it > 0 },
                    streamOutput = snapshot.streamOutput,
                ),
            )
        }.onSuccess { savedAgent ->
            sourceAgent = savedAgent
            _uiState.value = buildUiState(
                agent = savedAgent,
                previous = _uiState.value,
                saveStatus = "Agent 配置已保存。",
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                saveStatus = error.message ?: "Agent 配置保存失败",
            )
        }.isSuccess
    }

    private suspend fun loadAgent() {
        val agent = workspaceRepository.findAgent(agentId)
        if (agent == null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadError = "未找到 Agent：$agentId",
            )
            return
        }

        sourceAgent = agent
        _uiState.value = buildUiState(
            agent = agent,
            previous = _uiState.value,
        )
    }

    private fun updateDraft(transform: AgentEditorUiState.() -> AgentEditorUiState) {
        _uiState.value = _uiState.value.transform().withResolvedPrompt()
    }

    private fun buildUiState(
        agent: AgentEntity,
        previous: AgentEditorUiState,
        saveStatus: String? = previous.saveStatus,
    ): AgentEditorUiState {
        val promptMode = normalizePromptMode(agent.promptMode)
        return previous.copy(
            agentId = agent.id,
            isLoading = false,
            isSaving = false,
            name = agent.name,
            promptMode = promptMode,
            originalSystemPrompt = agent.originalSystemPrompt.ifBlank {
                agent.systemPrompt.takeIf { promptMode == "original" }.orEmpty()
            },
            advancedSystemPromptJson = agent.advancedSystemPromptJson.ifBlank {
                agent.systemPrompt.takeIf { promptMode == "modular" }.orEmpty()
            },
            presetSystemPrompt = agent.presetSystemPrompt.ifBlank {
                agent.systemPrompt.takeIf { promptMode == "preset" }.orEmpty()
            },
            presetPromptPath = agent.presetPromptPath.ifBlank { DEFAULT_PRESET_PROMPT_PATH },
            selectedPreset = agent.selectedPreset,
            model = agent.model,
            temperature = agent.temperature.toString(),
            contextTokenLimit = agent.contextTokenLimit?.toString().orEmpty(),
            maxOutputTokens = agent.maxOutputTokens?.toString().orEmpty(),
            topP = agent.topP?.toString().orEmpty(),
            topK = agent.topK?.toString().orEmpty(),
            streamOutput = agent.streamOutput,
            saveStatus = saveStatus,
            loadError = null,
        ).withResolvedPrompt()
    }

    companion object {
        fun factory(
            appContainer: AppContainer,
            agentId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AgentEditorViewModel(
                    agentId = agentId,
                    workspaceRepository = appContainer.workspaceRepository,
                    modelCatalog = appContainer.modelCatalog,
                    promptPresetCatalog = appContainer.promptPresetCatalog,
                )
            }
        }
    }
}

@Composable
fun AgentEditorRoute(
    appContainer: AppContainer,
    agentId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: AgentEditorViewModel = viewModel(
        factory = AgentEditorViewModel.factory(
            appContainer = appContainer,
            agentId = agentId,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    AgentEditorScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onNameChange = viewModel::updateName,
        onPromptModeChange = viewModel::updatePromptMode,
        onOriginalPromptChange = viewModel::updateOriginalSystemPrompt,
        onAdvancedPromptJsonChange = viewModel::updateAdvancedSystemPromptJson,
        onPresetPromptChange = viewModel::updatePresetSystemPrompt,
        onPresetPromptPathChange = viewModel::updatePresetPromptPath,
        onModelChange = viewModel::updateModel,
        onTemperatureChange = viewModel::updateTemperature,
        onContextTokenLimitChange = viewModel::updateContextTokenLimit,
        onMaxOutputTokensChange = viewModel::updateMaxOutputTokens,
        onTopPChange = viewModel::updateTopP,
        onTopKChange = viewModel::updateTopK,
        onStreamOutputChange = viewModel::updateStreamOutput,
        onRefreshModels = {
            scope.launch {
                viewModel.refreshModels(forceRefresh = true)
            }
        },
        onRefreshPresets = {
            scope.launch {
                viewModel.refreshPresets()
            }
        },
        onApplyPreset = { presetPath ->
            scope.launch {
                viewModel.applyPreset(presetPath)
            }
        },
        onSave = {
            scope.launch {
                if (viewModel.save()) {
                    onNavigateBack()
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentEditorScreen(
    uiState: AgentEditorUiState,
    onNavigateBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onPromptModeChange: (String) -> Unit,
    onOriginalPromptChange: (String) -> Unit,
    onAdvancedPromptJsonChange: (String) -> Unit,
    onPresetPromptChange: (String) -> Unit,
    onPresetPromptPathChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (String) -> Unit,
    onContextTokenLimitChange: (String) -> Unit,
    onMaxOutputTokensChange: (String) -> Unit,
    onTopPChange: (String) -> Unit,
    onTopKChange: (String) -> Unit,
    onStreamOutputChange: (Boolean) -> Unit,
    onRefreshModels: () -> Unit,
    onRefreshPresets: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Agent 配置")
                        if (uiState.agentId.isNotBlank()) {
                            Text(
                                text = uiState.agentId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                    TextButton(
                        onClick = onSave,
                        enabled = uiState.canSave,
                    ) {
                        Text(text = if (uiState.isSaving) "保存中" else "保存")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "正在加载 Agent 配置…")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.loadError?.let { loadError ->
                SectionCard(title = "加载失败") {
                    Text(
                        text = loadError,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }

            SectionCard(title = "基础配置") {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Agent 名称") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.model,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "模型 ID") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.modelStatus ?: "可从 /v1/models 拉取模型列表后直接点选。",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onRefreshModels,
                        enabled = !uiState.isRefreshingModels && !uiState.isSaving,
                    ) {
                        Text(text = if (uiState.isRefreshingModels) "刷新中" else "刷新模型")
                    }
                }
                if (uiState.availableModels.isNotEmpty()) {
                    Text(
                        text = "点击下方模型可直接填入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.availableModels.forEach { model ->
                            OutlinedButton(
                                onClick = { onModelChange(model.id) },
                                enabled = !uiState.isSaving,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(text = model.id)
                                    model.ownedBy?.takeIf { it.isNotBlank() }?.let { ownedBy ->
                                        Text(
                                            text = ownedBy,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = uiState.temperature,
                    onValueChange = onTemperatureChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Temperature") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "流式输出",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "关闭后会等待完整回复，再一次性渲染。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.streamOutput,
                        onCheckedChange = onStreamOutputChange,
                        enabled = !uiState.isSaving,
                    )
                }
            }

            SectionCard(title = "系统提示词") {
                Text(
                    text = "保存时会按当前 promptMode 解析活跃提示词，并同步回写兼容字段 systemPrompt。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PROMPT_MODE_OPTIONS.forEach { option ->
                    PromptModeOption(
                        label = option.label,
                        description = option.description,
                        selected = uiState.promptMode == option.id,
                        enabled = !uiState.isSaving,
                        onClick = { onPromptModeChange(option.id) },
                    )
                }

                when (uiState.promptMode) {
                    "modular" -> {
                        OutlinedTextField(
                            value = uiState.advancedSystemPromptJson,
                            onValueChange = onAdvancedPromptJsonChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "advancedSystemPrompt JSON / 原始文本") },
                            enabled = !uiState.isSaving,
                            minLines = 10,
                        )
                    }

                    "preset" -> {
                        Text(
                            text = "兼容 VCPChat 的 `presetPromptPath + selectedPreset + presetSystemPrompt` 语义。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = uiState.presetPromptPath,
                            onValueChange = onPresetPromptPathChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "presetPromptPath") },
                            enabled = !uiState.isSaving,
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = uiState.presetStatus ?: "相对路径 `./AppData/...` 会映射到已导入的 compat AppData。",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onRefreshPresets,
                                enabled = !uiState.isRefreshingPresets && !uiState.isSaving,
                            ) {
                                Text(text = if (uiState.isRefreshingPresets) "刷新中" else "刷新预设")
                            }
                        }
                        if (uiState.resolvedPresetPath.isNotBlank()) {
                            Text(
                                text = "解析目录: ${uiState.resolvedPresetPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (uiState.selectedPreset.isNotBlank()) {
                            Text(
                                text = "当前选择: ${uiState.selectedPreset}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (uiState.availablePresets.isNotEmpty()) {
                            Text(
                                text = "点击下方预设可直接载入内容。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.availablePresets.take(12).forEach { preset ->
                                    PresetPromptRow(
                                        preset = preset,
                                        selected = preset.path == uiState.selectedPreset,
                                        enabled = !uiState.isSaving,
                                        onApply = { onApplyPreset(preset.path) },
                                    )
                                }
                            }
                            if (uiState.availablePresets.size > 12) {
                                Text(
                                    text = "其余 ${uiState.availablePresets.size - 12} 个预设已省略显示。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = uiState.presetSystemPrompt,
                            onValueChange = onPresetPromptChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "presetSystemPrompt") },
                            enabled = !uiState.isSaving,
                            minLines = 8,
                        )
                    }

                    else -> {
                        OutlinedTextField(
                            value = uiState.originalSystemPrompt,
                            onValueChange = onOriginalPromptChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = "originalSystemPrompt") },
                            enabled = !uiState.isSaving,
                            minLines = 8,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "当前生效提示词预览",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = uiState.activeSystemPromptPreview.ifBlank { "当前模式下还没有可生效的系统提示词。" },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            SectionCard(title = "生成参数") {
                OutlinedTextField(
                    value = uiState.contextTokenLimit,
                    onValueChange = onContextTokenLimitChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "contextTokenLimit") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.maxOutputTokens,
                    onValueChange = onMaxOutputTokensChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "maxOutputTokens") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.topP,
                    onValueChange = onTopPChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "top_p") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.topK,
                    onValueChange = onTopKChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "top_k") },
                    enabled = !uiState.isSaving,
                    singleLine = true,
                )
            }

            uiState.saveStatus?.let { saveStatus ->
                Text(
                    text = saveStatus,
                    color = if (uiState.isSaving) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = onSave,
                enabled = uiState.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = if (uiState.isSaving) "保存中…" else "保存 Agent 配置")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun PromptModeOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = if (enabled) onClick else null,
            enabled = enabled,
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = label)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetPromptRow(
    preset: PromptPresetInfo,
    selected: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    OutlinedButton(
        onClick = onApply,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = preset.name,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = "${preset.extension} · ${formatPresetSize(preset.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = preset.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class PromptModeOptionSpec(
    val id: String,
    val label: String,
    val description: String,
)

private fun AgentEditorUiState.withResolvedPrompt(): AgentEditorUiState =
    copy(
        activeSystemPromptPreview = AgentPromptResolver.resolveModeContent(
            promptMode = promptMode,
            originalSystemPrompt = originalSystemPrompt.trim(),
            advancedSystemPromptJson = advancedSystemPromptJson.trim(),
            presetSystemPrompt = presetSystemPrompt.trim(),
        ),
    )

private fun normalizePromptMode(value: String): String =
    when (value) {
        "modular", "preset" -> value
        else -> "original"
    }

private fun formatPresetSize(size: Long): String =
    when {
        size >= 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        size >= 1024 -> "${size / 1024} KB"
        else -> "${size} B"
}

private val PROMPT_MODE_OPTIONS = listOf(
    PromptModeOptionSpec(
        id = "original",
        label = "original",
        description = "直接编辑原始系统提示词。",
    ),
    PromptModeOptionSpec(
        id = "modular",
        label = "modular",
        description = "保留 VCPChat 的 advancedSystemPrompt 结构或原始文本。",
    ),
    PromptModeOptionSpec(
        id = "preset",
        label = "preset",
        description = "使用 VCPChat 风格的预设提示词目录与内容。",
    ),
)
