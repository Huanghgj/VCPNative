package com.vcpnative.app.feature.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.importer.AppDataImportManager
import com.vcpnative.app.data.importer.AppDataImportResult
import com.vcpnative.app.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BootstrapDestination {
    data object SetupGate : BootstrapDestination
    data object Agents : BootstrapDestination
    data class Chat(val agentId: String, val topicId: String) : BootstrapDestination
}

data class BootstrapUiState(
    val isLoading: Boolean = true,
    val isConfigured: Boolean = false,
    val rootDir: String = "",
    val attachmentsDir: String = "",
    val importsDir: String = "",
    val importStatus: String? = null,
    val destination: BootstrapDestination? = null,
)

class BootstrapViewModel(
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val appDataImportManager: AppDataImportManager,
    fileStore: AppFileStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        BootstrapUiState(
            rootDir = fileStore.rootDir.absolutePath,
            attachmentsDir = fileStore.attachmentsDir.absolutePath,
            importsDir = fileStore.importsDir.absolutePath,
        ),
    )
    val uiState: StateFlow<BootstrapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                importStatus = "正在扫描导入目录…",
            )
            val importResult = appDataImportManager.importPending()
            val settings = settingsRepository.currentSettings()
            val destination = when {
                !settings.isConfigured -> BootstrapDestination.SetupGate
                settings.lastAgentId != null &&
                    settings.lastTopicId != null &&
                    workspaceRepository.findAgent(settings.lastAgentId) != null &&
                    workspaceRepository.findTopic(settings.lastTopicId) != null ->
                    BootstrapDestination.Chat(settings.lastAgentId, settings.lastTopicId)

                else -> BootstrapDestination.Agents
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isConfigured = settings.isConfigured,
                importStatus = importResult.toStatusText(),
                destination = destination,
            )
        }
    }

    companion object {
        fun factory(appContainer: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BootstrapViewModel(
                    settingsRepository = appContainer.settingsRepository,
                    workspaceRepository = appContainer.workspaceRepository,
                    appDataImportManager = appContainer.appDataImportManager,
                    fileStore = appContainer.fileStore,
                )
            }
        }
    }
}

@Composable
fun BootstrapRoute(
    appContainer: AppContainer,
    onOpenSetupGate: () -> Unit,
    onOpenAgents: () -> Unit,
    onRestoreChat: (agentId: String, topicId: String) -> Unit,
) {
    val viewModel: BootstrapViewModel = viewModel(factory = BootstrapViewModel.factory(appContainer))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.destination) {
        when (val destination = uiState.destination) {
            BootstrapDestination.Agents -> onOpenAgents()
            is BootstrapDestination.Chat -> onRestoreChat(destination.agentId, destination.topicId)
            BootstrapDestination.SetupGate -> onOpenSetupGate()
            null -> Unit
        }
    }

    BootstrapScreen(uiState = uiState)
}

@Composable
private fun BootstrapScreen(
    uiState: BootstrapUiState,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "VCPNative Bootstrap",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (uiState.isConfigured) {
                    "连接设置已存在，正在恢复工作区。"
                } else {
                    "首次启动，正在检查必需配置。"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(24.dp))
            BootstrapPathCard(title = "私有根目录", value = uiState.rootDir)
            BootstrapPathCard(title = "附件目录", value = uiState.attachmentsDir)
            BootstrapPathCard(title = "导入目录", value = uiState.importsDir)
            uiState.importStatus?.let { status ->
                BootstrapPathCard(title = "导入状态", value = status)
            }
        }
    }
}

@Composable
private fun BootstrapPathCard(
    title: String,
    value: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun AppDataImportResult.toStatusText(): String = when (this) {
    AppDataImportResult.NoPendingImport -> "未发现待导入 AppData。"
    is AppDataImportResult.Imported -> buildString {
        append("已导入 ")
        append(sourceName)
        append(" · Agents ")
        append(agents)
        append(" · Topics ")
        append(topics)
        append(" · Messages ")
        append(messages)
        if (warnings.isNotEmpty()) {
            append(" · Warnings ")
            append(warnings.size)
        }
    }

    is AppDataImportResult.Failed -> buildString {
        append("导入失败")
        sourceName?.let {
            append(" · ")
            append(it)
        }
        append(" · ")
        append(message)
    }
}

@Composable
fun SetupGateScreen(
    onOpenSettings: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Setup Gate",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "当前还没有可用的 `vcpServerUrl` 和 `vcpApiKey`。先完成基础连接配置，再进入 Agent -> Topic -> Chat 工作流。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))
            androidx.compose.material3.Button(onClick = onOpenSettings) {
                Text(text = "进入设置")
            }
        }
    }
}
