// 导航图从 VcpNativeApp.kt 拆分出来，避免 500+ 行的单体 Composable。
// 每个 composable() 块对应一个屏幕，路由常量集中在 R 对象中。

package com.vcpnative.app.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
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
import com.vcpnative.app.feature.settings.SettingsRoute
import com.vcpnative.app.feature.tools.ToolsRoute
import com.vcpnative.app.feature.topics.TopicsRoute
import com.vcpnative.app.network.vcplog.VcpLogConnectionStatus
import com.vcpnative.app.network.vcplog.VcpLogMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ── Route constants ────────────────────────────────────────────────

internal object R {
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
    const val TAB_HOME = "tab/home"
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
    const val SEARCH = "search"
    const val MODELS = "models"
    const val BRIDGE = "bridge"
    const val GROUP_CHAT_LIST = "groupchat/list"
    const val GROUP_TOPICS_PATTERN = "groupchat/topics/{$ARG_AGENT_ID}"
    const val GROUP_CHAT_PATTERN = "groupchat/chat/{$ARG_AGENT_ID}/{$ARG_TOPIC_ID}"
    const val GROUP_SETTINGS_PATTERN = "groupchat/settings/{$ARG_AGENT_ID}"

    fun groupSettings(groupId: String) = "groupchat/settings/$groupId"

    fun groupTopics(groupId: String) = "groupchat/topics/$groupId"
    fun groupChat(groupId: String, topicId: String) = "groupchat/chat/$groupId/$topicId"

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

internal val TAB_ROUTES = setOf(R.TAB_HOME, R.TAB_CHAT, R.TAB_TOOLS, R.TAB_SETTINGS)

// ── Helpers ────────────────────────────────────────────────────────

internal fun navStringArgument(name: String) = navArgument(name) { type = NavType.StringType }

internal fun NavBackStackEntry.requireStringArg(name: String): String =
    checkNotNull(arguments?.getString(name)) { "Missing navigation argument: $name" }

// ── Navigation graph ──────────────────────────────────────────────

internal fun NavGraphBuilder.vcpNavigationGraph(
    navController: NavHostController,
    appContainer: AppContainer,
    scope: CoroutineScope,
    vcpLogStatus: VcpLogConnectionStatus,
    allNotifications: List<VcpLogMessage>,
    onToggleSidebar: () -> Unit,
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
                navController.navigate(R.TAB_HOME) {
                    popUpTo(R.BOOTSTRAP) { inclusive = true }
                }
            },
            onRestoreChat = { agentId, topicId ->
                navController.navigate(R.TAB_HOME) {
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
                navController.navigate(R.TAB_HOME) {
                    popUpTo(R.SETUP_GATE) { inclusive = true }
                }
            },
        )
    }

    // ── Tabs ──
    composable(R.TAB_HOME) {
        com.vcpnative.app.feature.home.HomeRoute(
            appContainer = appContainer,
            onOpenChat = { agentId, topicId ->
                navController.navigate(R.chat(agentId, topicId))
            },
            onOpenTopics = { agentId ->
                navController.navigate(R.topics(agentId))
            },
            onOpenModule = { moduleId ->
                navController.navigate(R.module(moduleId))
            },
            onOpenSettings = {
                navController.navigate(R.TAB_SETTINGS)
            },
            onOpenSearch = {
                navController.navigate(R.SEARCH)
            },
            onOpenGroupChat = {
                navController.navigate(R.GROUP_CHAT_LIST)
            },
            onCreateAgent = {
                scope.launch {
                    val agent = appContainer.workspaceRepository.createPlaceholderAgent()
                    navController.navigate(R.topics(agent.id))
                }
            },
            onOpenModels = { navController.navigate(R.MODELS) },
            onOpenBridge = { navController.navigate(R.BRIDGE) },
            onDeleteAgent = { agentId ->
                scope.launch { appContainer.workspaceRepository.deleteAgent(agentId) }
            },
        )
    }

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
            onOpenVcpLog = { onToggleSidebar() },
            onOpenDebugLog = { navController.navigate(R.DEBUG_LOG) },
            onOpenModels = { navController.navigate(R.MODELS) },
            onOpenBridge = { navController.navigate(R.BRIDGE) },
            onOpenSearch = { navController.navigate(R.SEARCH) },
            onOpenGroupChat = { navController.navigate(R.GROUP_CHAT_LIST) },
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

    composable(R.MODELS) {
        com.vcpnative.app.feature.models.ModelsRoute(
            appContainer = appContainer,
            onNavigateBack = { navController.navigateUp() },
        )
    }

    composable(R.BRIDGE) {
        com.vcpnative.app.feature.bridge.BridgeRoute(
            appContainer = appContainer,
            onNavigateBack = { navController.navigateUp() },
        )
    }

    composable(R.SEARCH) {
        com.vcpnative.app.feature.search.SearchRoute(
            appContainer = appContainer,
            onNavigateBack = { navController.navigateUp() },
            onOpenChat = { agentId, topicId ->
                if (agentId.isNotBlank() && topicId.isNotBlank()) {
                    navController.navigate(R.chat(agentId, topicId))
                }
            },
        )
    }

    composable(
        route = R.GROUP_TOPICS_PATTERN,
        arguments = listOf(navStringArgument(R.ARG_AGENT_ID)),
    ) { backStackEntry ->
        val gId = backStackEntry.requireStringArg(R.ARG_AGENT_ID)
        com.vcpnative.app.feature.groupchat.GroupTopicsRoute(
            appContainer = appContainer,
            groupId = gId,
            onNavigateBack = { navController.navigateUp() },
            onOpenChat = { tId -> navController.navigate(R.groupChat(gId, tId)) },
            onOpenSettings = { navController.navigate(R.groupSettings(gId)) },
        )
    }

    composable(
        route = R.GROUP_CHAT_PATTERN,
        arguments = listOf(
            navStringArgument(R.ARG_AGENT_ID),
            navStringArgument(R.ARG_TOPIC_ID),
        ),
    ) { backStackEntry ->
        com.vcpnative.app.feature.groupchat.GroupChatRoute(
            appContainer = appContainer,
            groupId = backStackEntry.requireStringArg(R.ARG_AGENT_ID),
            topicId = backStackEntry.requireStringArg(R.ARG_TOPIC_ID),
            onNavigateBack = { navController.navigateUp() },
        )
    }

    composable(
        route = R.GROUP_SETTINGS_PATTERN,
        arguments = listOf(navStringArgument(R.ARG_AGENT_ID)),
    ) { backStackEntry ->
        com.vcpnative.app.feature.groupchat.GroupSettingsRoute(
            appContainer = appContainer,
            groupId = backStackEntry.requireStringArg(R.ARG_AGENT_ID),
            onNavigateBack = { navController.navigateUp() },
        )
    }

    composable(R.GROUP_CHAT_LIST) {
        com.vcpnative.app.feature.groupchat.GroupChatListRoute(
            appContainer = appContainer,
            onNavigateBack = { navController.navigateUp() },
            onOpenGroupTopics = { groupId ->
                navController.navigate(R.groupTopics(groupId))
            },
        )
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
}
