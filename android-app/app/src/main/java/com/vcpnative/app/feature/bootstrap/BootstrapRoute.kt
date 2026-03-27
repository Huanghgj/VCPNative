package com.vcpnative.app.feature.bootstrap

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RocketLaunch,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "VCPNative",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (uiState.isConfigured) {
                        "正在恢复工作区，请稍候… ✨"
                    } else {
                        "首次启动，正在唤醒系统服务… 🚀"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                )

                Spacer(modifier = Modifier.height(40.dp))

                BootstrapPathCard(
                    title = "私有根目录", 
                    value = uiState.rootDir,
                    color = MaterialTheme.colorScheme.primary
                )
                BootstrapPathCard(
                    title = "附件目录", 
                    value = uiState.attachmentsDir,
                    color = MaterialTheme.colorScheme.secondary
                )
                BootstrapPathCard(
                    title = "导入目录", 
                    value = uiState.importsDir,
                    color = MaterialTheme.colorScheme.tertiary
                )
                
                uiState.importStatus?.let { status ->
                    BootstrapPathCard(
                        title = "数据导入状态", 
                        value = status,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun BootstrapPathCard(
    title: String,
    value: String,
    color: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(color)
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = color
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title, 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
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
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = "传送门尚未开启",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "指挥官，我们还没能连接到核心服务器。\n请先完成基础设置，开启你的次元之旅吧！✨",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
                
                Spacer(modifier = Modifier.height(40.dp))
                
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Outlined.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "前往设置中心",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

