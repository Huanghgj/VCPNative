package com.vcpnative.app.app

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vcpnative.app.VcpNativeApplication
import com.vcpnative.app.feature.agents.AgentsRoute
import com.vcpnative.app.feature.agenteditor.AgentEditorRoute
import com.vcpnative.app.feature.attachment.AttachmentViewerScreen
import com.vcpnative.app.feature.bootstrap.BootstrapRoute
import com.vcpnative.app.feature.bootstrap.SetupGateScreen
import com.vcpnative.app.feature.chat.ChatRoute
import com.vcpnative.app.feature.debug.DebugLogRoute
import com.vcpnative.app.feature.imageviewer.ImageViewerScreen
import com.vcpnative.app.feature.modules.VcpModuleRoute
import com.vcpnative.app.feature.modules.VcpModules
import com.vcpnative.app.feature.notification.VcpLogSidebarPanel
import com.vcpnative.app.feature.notification.VcpLogToastOverlay
import com.vcpnative.app.feature.settings.SettingsRoute
import com.vcpnative.app.feature.tools.ToolsRoute
import com.vcpnative.app.feature.topics.TopicsRoute
import com.vcpnative.app.network.vcplog.VcpLogConnectionStatus
import com.vcpnative.app.network.vcplog.VcpLogMessage
import com.vcpnative.app.ui.navigation.BottomTab
import com.vcpnative.app.ui.navigation.VcpBottomNavBar

// ── Route constants ────────────────────────────────────────────────

private object R {
    const val ARG_AGENT_ID = "agentId"
    const val ARG_TOPIC_ID = "topicId"
    const val ARG_ATTACHMENT_ID = "attachmentId"
    const val ARG_IMAGE_URL = "imageUrl"
    const val ARG_IMAGE_ALT = "imageAlt"
    const val ARG_MODULE_ID = "moduleId"

    const val BOOTSTRAP = "bootstrap"
    const val SETUP_GATE = "setup-gate"
    const val SETTINGS_SETUP = "settings/setup"

    // Tabs (bottom bar visible)
    const val TAB_CHAT = "tab/chat"
    const val TAB_TOOLS = "tab/tools"
    const val TAB_SETTINGS = "tab/settings"

    // Child screens (bottom bar hidden)
    const val TOPICS_PATTERN = "workspace/topics/{$ARG_AGENT_ID}"
    const val CHAT_PATTERN = "workspace/chat/{$ARG_AGENT_ID}/{$ARG_TOPIC_ID}"
    const val AGENT_EDITOR_PATTERN = "workspace/agent/{$ARG_AGENT_ID}"
    const val ATTACHMENT_PATTERN = "attachment/{$ARG_ATTACHMENT_ID}"
    const val IMAGE_VIEWER_PATTERN = "image-viewer?url={$ARG_IMAGE_URL}&alt={$ARG_IMAGE_ALT}"
    const val MODULE_PATTERN = "module/{$ARG_MODULE_ID}"
    const val DEBUG_LOG = "debug/log"

    fun topics(agentId: String) = "workspace/topics/$agentId"
    fun chat(agentId: String, topicId: String) = "workspace/chat/$agentId/$topicId"
    fun agentEditor(agentId: String) = "workspace/agent/$agentId"
    fun attachment(attachmentId: String) = "attachment/$attachmentId"
    fun module(moduleId: String) = "module/$moduleId"
    fun imageViewer(imageUrl: String, alt: String?): String {
        val encodedUrl = Uri.encode(imageUrl)
        val encodedAlt = Uri.encode(alt ?: "")
        return "image-viewer?url=$encodedUrl&alt=$encodedAlt"
    }
}

private val TAB_ROUTES = setOf(R.TAB_CHAT, R.TAB_TOOLS, R.TAB_SETTINGS)

// ── VCPLog notification state ──────────────────────────────────────

data class VcpLogNotificationState(
    val unreadCount: Int = 0,
    val connectionStatus: VcpLogConnectionStatus = VcpLogConnectionStatus.Disconnected,
    val onToggleSidebar: () -> Unit = {},
)

val LocalVcpLogNotification = compositionLocalOf { VcpLogNotificationState() }

// ── Helpers ────────────────────────────────────────────────────────

private fun navStringArgument(name: String) = navArgument(name) { type = NavType.StringType }

private fun NavBackStackEntry.requireStringArg(name: String): String =
    checkNotNull(arguments?.getString(name)) { "Missing navigation argument: $name" }

// ── App root ───────────────────────────────────────────────────────

@Composable
fun VcpNativeApp(
    appContainer: AppContainer = rememberAppContainer(),
) {
    val navController = rememberNavController()
    val vcpLogClient = remember { appContainer.vcpLogClient }
    val vcpLogStatus by vcpLogClient.status.collectAsStateWithLifecycle()
    val settings by appContainer.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = null,
    )

    // Auto-connect VCPLog
    LaunchedEffect(settings?.vcpLogUrl, settings?.vcpLogKey) {
        val s = settings ?: return@LaunchedEffect
        if (s.vcpLogUrl.isNotBlank() && s.vcpLogKey.isNotBlank()) {
            vcpLogClient.connect(s.vcpLogUrl, s.vcpLogKey)
        } else {
            vcpLogClient.disconnect()
        }
    }
    DisposableEffect(vcpLogClient) { onDispose { vcpLogClient.disconnect() } }

    // Notification state
    val toasts = remember { mutableStateListOf<VcpLogMessage>() }
    val allNotifications = remember { mutableStateListOf<VcpLogMessage>() }
    var sidebarVisible by remember { mutableStateOf(false) }
    // 用 derivedStateOf 避免每条消息都触发 bottom bar badge 重组
    val notificationCount by remember { derivedStateOf { allNotifications.size } }

    LaunchedEffect(vcpLogClient) {
        vcpLogClient.messages.collect { message ->
            allNotifications.add(message)
            toasts.add(message)
            // 限制列表大小：用 subList + clear 替代 removeAt(0) 的 O(n) 复制
            if (allNotifications.size > 200) {
                val excess = allNotifications.size - 200
                allNotifications.subList(0, excess).clear()
            }
        }
    }

    val notificationState = remember(vcpLogStatus, notificationCount, sidebarVisible) {
        VcpLogNotificationState(
            unreadCount = notificationCount,
            connectionStatus = vcpLogStatus,
            onToggleSidebar = { sidebarVisible = !sidebarVisible },
        )
    }

    // Bottom bar visibility: only on tab routes
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val showBottomBar by remember {
        derivedStateOf { navBackStackEntry?.destination?.route in TAB_ROUTES }
    }

    CompositionLocalProvider(LocalVcpLogNotification provides notificationState) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    AnimatedVisibility(
                        visible = showBottomBar,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    ) {
                        VcpBottomNavBar(
                            currentRoute = navBackStackEntry?.destination?.route,
                            unreadCount = allNotifications.size,
                            onTabSelected = { tab ->
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = R.BOOTSTRAP,
                    modifier = Modifier.padding(innerPadding),
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { fadeOut() },
                ) {
                    // ── Bootstrap ──
                    composable(R.BOOTSTRAP) {
                        BootstrapRoute(
                            appContainer = appContainer,
                            onOpenSetupGate = {
                                navController.navigate(R.SETUP_GATE) {
                                    popUpTo(R.BOOTSTRAP) { inclusive = true }
                                }
                            },
                            onOpenAgents = {
                                navController.navigate(R.TAB_CHAT) {
                                    popUpTo(R.BOOTSTRAP) { inclusive = true }
                                }
                            },
                            onRestoreChat = { agentId, topicId ->
                                // Navigate to tab first, then to chat
                                navController.navigate(R.TAB_CHAT) {
                                    popUpTo(R.BOOTSTRAP) { inclusive = true }
                                }
                                navController.navigate(R.chat(agentId, topicId))
                            },
                        )
                    }

                    composable(R.SETUP_GATE) {
                        SetupGateScreen(
                            onOpenSettings = { navController.navigate(R.SETTINGS_SETUP) },
                        )
                    }

                    composable(R.SETTINGS_SETUP) {
                        SettingsRoute(
                            appContainer = appContainer,
                            isSetup = true,
                            onNavigateBack = { navController.navigateUp() },
                            onSaved = {
                                navController.navigate(R.TAB_CHAT) {
                                    popUpTo(R.SETUP_GATE) { inclusive = true }
                                }
                            },
                        )
                    }

                    // ── Tabs ──
                    composable(R.TAB_CHAT) {
                        AgentsRoute(
                            appContainer = appContainer,
                            onOpenSettings = { navController.navigate(R.TAB_SETTINGS) },
                            onOpenAgentEditor = { agentId ->
                                navController.navigate(R.agentEditor(agentId))
                            },
                            onOpenTopics = { agentId ->
                                navController.navigate(R.topics(agentId))
                            },
                            onOpenModule = { moduleId ->
                                navController.navigate(R.module(moduleId))
                            },
                        )
                    }

                    composable(R.TAB_TOOLS) {
                        ToolsRoute(
                            vcpLogConnectionStatus = vcpLogStatus,
                            notificationCount = allNotifications.size,
                            onOpenModule = { moduleId ->
                                navController.navigate(R.module(moduleId))
                            },
                            onOpenVcpLog = { sidebarVisible = true },
                            onOpenDebugLog = { navController.navigate(R.DEBUG_LOG) },
                        )
                    }

                    composable(R.TAB_SETTINGS) {
                        SettingsRoute(
                            appContainer = appContainer,
                            isSetup = false,
                            onNavigateBack = { /* Tab — no back */ },
                            onSaved = { /* Already configured */ },
                        )
                    }

                    // ── Child screens ──
                    composable(
                        route = R.AGENT_EDITOR_PATTERN,
                        arguments = listOf(navStringArgument(R.ARG_AGENT_ID)),
                    ) { backStackEntry ->
                        AgentEditorRoute(
                            appContainer = appContainer,
                            agentId = backStackEntry.requireStringArg(R.ARG_AGENT_ID),
                            onNavigateBack = { navController.navigateUp() },
                        )
                    }

                    composable(
                        route = R.TOPICS_PATTERN,
                        arguments = listOf(navStringArgument(R.ARG_AGENT_ID)),
                    ) { backStackEntry ->
                        val agentId = backStackEntry.requireStringArg(R.ARG_AGENT_ID)
                        TopicsRoute(
                            appContainer = appContainer,
                            agentId = agentId,
                            onNavigateBack = { navController.navigateUp() },
                            onOpenAgentEditor = {
                                navController.navigate(R.agentEditor(agentId))
                            },
                            onOpenSettings = {
                                navController.navigate(R.TAB_SETTINGS)
                            },
                            onOpenChat = { topicId ->
                                navController.navigate(R.chat(agentId, topicId))
                            },
                        )
                    }

                    composable(
                        route = R.CHAT_PATTERN,
                        arguments = listOf(
                            navStringArgument(R.ARG_AGENT_ID),
                            navStringArgument(R.ARG_TOPIC_ID),
                        ),
                    ) { backStackEntry ->
                        val agentId = backStackEntry.requireStringArg(R.ARG_AGENT_ID)
                        val topicId = backStackEntry.requireStringArg(R.ARG_TOPIC_ID)
                        ChatRoute(
                            appContainer = appContainer,
                            agentId = agentId,
                            topicId = topicId,
                            onNavigateBack = { navController.navigateUp() },
                            onOpenTopics = { navController.navigate(R.topics(agentId)) },
                            onOpenTopic = { nextTopicId ->
                                navController.navigate(R.chat(agentId, nextTopicId)) {
                                    popUpTo(R.CHAT_PATTERN) { inclusive = true }
                                }
                            },
                            onOpenAgentEditor = { navController.navigate(R.agentEditor(agentId)) },
                            onOpenSettings = { navController.navigate(R.TAB_SETTINGS) },
                            onOpenModule = { moduleId -> navController.navigate(R.module(moduleId)) },
                            onOpenDebugLog = { navController.navigate(R.DEBUG_LOG) },
                            onOpenAttachment = { attachmentId ->
                                navController.navigate(R.attachment(attachmentId))
                            },
                            onOpenImageViewer = { imageUrl, alt ->
                                navController.navigate(R.imageViewer(imageUrl, alt))
                            },
                        )
                    }

                    composable(
                        route = R.ATTACHMENT_PATTERN,
                        arguments = listOf(navStringArgument(R.ARG_ATTACHMENT_ID)),
                    ) { backStackEntry ->
                        AttachmentViewerScreen(
                            appContainer = appContainer,
                            attachmentId = backStackEntry.requireStringArg(R.ARG_ATTACHMENT_ID),
                            onNavigateBack = { navController.navigateUp() },
                        )
                    }

                    composable(
                        route = R.IMAGE_VIEWER_PATTERN,
                        arguments = listOf(
                            navArgument(R.ARG_IMAGE_URL) { type = NavType.StringType; defaultValue = "" },
                            navArgument(R.ARG_IMAGE_ALT) { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { backStackEntry ->
                        ImageViewerScreen(
                            imageUrl = backStackEntry.arguments?.getString(R.ARG_IMAGE_URL).orEmpty(),
                            alt = backStackEntry.arguments?.getString(R.ARG_IMAGE_ALT)?.takeIf { it.isNotBlank() },
                            onNavigateBack = { navController.navigateUp() },
                        )
                    }

                    composable(R.DEBUG_LOG) {
                        DebugLogRoute(onNavigateBack = { navController.navigateUp() })
                    }

                    composable(
                        route = R.MODULE_PATTERN,
                        arguments = listOf(navStringArgument(R.ARG_MODULE_ID)),
                    ) { backStackEntry ->
                        val moduleId = backStackEntry.requireStringArg(R.ARG_MODULE_ID)
                        val moduleDef = VcpModules.all.find { it.routeName == "module/$moduleId" }
                        if (moduleDef != null) {
                            VcpModuleRoute(
                                moduleDef = moduleDef,
                                appContainer = appContainer,
                                onNavigateBack = { navController.navigateUp() },
                            )
                        }
                    }
                } // NavHost
            } // Scaffold

            // Sidebar overlay
            VcpLogSidebarPanel(
                visible = sidebarVisible,
                connectionStatus = vcpLogStatus,
                notifications = allNotifications,
                onDismiss = { sidebarVisible = false },
                onClearAll = { allNotifications.clear() },
                onApprove = { requestId ->
                    vcpLogClient.sendApprovalResponse(requestId, approved = true)
                },
                onReject = { requestId ->
                    vcpLogClient.sendApprovalResponse(requestId, approved = false)
                },
            )

            // Toast overlay
            VcpLogToastOverlay(
                toasts = toasts,
                onDismiss = { message -> toasts.remove(message) },
                onApprove = { requestId ->
                    vcpLogClient.sendApprovalResponse(requestId, approved = true)
                },
                onReject = { requestId ->
                    vcpLogClient.sendApprovalResponse(requestId, approved = false)
                },
            )
        } // Box
    } // CompositionLocalProvider
}

@Composable
private fun rememberAppContainer(): AppContainer {
    val application = LocalContext.current.applicationContext as VcpNativeApplication
    return remember { application.appContainer }
}
