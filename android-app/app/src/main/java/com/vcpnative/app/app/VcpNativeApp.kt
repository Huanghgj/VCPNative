package com.vcpnative.app.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vcpnative.app.VcpNativeApplication
import com.vcpnative.app.feature.agents.AgentsRoute
import com.vcpnative.app.feature.agenteditor.AgentEditorRoute
import com.vcpnative.app.feature.attachment.AttachmentViewerScreen
import com.vcpnative.app.feature.bootstrap.BootstrapRoute
import com.vcpnative.app.feature.bootstrap.SetupGateScreen
import com.vcpnative.app.feature.settings.SettingsRoute
import com.vcpnative.app.feature.chat.ChatRoute
import com.vcpnative.app.feature.topics.TopicsRoute

private object VcpRoutes {
    const val BOOTSTRAP = "bootstrap"
    const val SETUP_GATE = "setup-gate"
    const val SETTINGS_SETUP = "settings/setup"
    const val SETTINGS_STANDALONE = "settings/standalone"
    const val AGENTS = "workspace/agents"
    const val AGENT_EDITOR_PATTERN = "workspace/agent/{agentId}"
    const val TOPICS_PATTERN = "workspace/topics/{agentId}"
    const val CHAT_PATTERN = "workspace/chat/{agentId}/{topicId}"
    const val ATTACHMENT_PATTERN = "attachment/{attachmentId}"

    fun agentEditor(agentId: String): String = "workspace/agent/$agentId"

    fun topics(agentId: String): String = "workspace/topics/$agentId"

    fun chat(agentId: String, topicId: String): String = "workspace/chat/$agentId/$topicId"

    fun attachment(attachmentId: String): String = "attachment/$attachmentId"
}

@Composable
fun VcpNativeApp(
    appContainer: AppContainer = rememberAppContainer(),
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = VcpRoutes.BOOTSTRAP,
    ) {
        composable(VcpRoutes.BOOTSTRAP) {
            BootstrapRoute(
                appContainer = appContainer,
                onOpenSetupGate = {
                    navController.navigate(VcpRoutes.SETUP_GATE) {
                        popUpTo(VcpRoutes.BOOTSTRAP) { inclusive = true }
                    }
                },
                onOpenAgents = {
                    navController.navigate(VcpRoutes.AGENTS) {
                        popUpTo(VcpRoutes.BOOTSTRAP) { inclusive = true }
                    }
                },
                onRestoreChat = { agentId, topicId ->
                    navController.navigate(VcpRoutes.chat(agentId, topicId)) {
                        popUpTo(VcpRoutes.BOOTSTRAP) { inclusive = true }
                    }
                },
            )
        }

        composable(VcpRoutes.SETUP_GATE) {
            SetupGateScreen(
                onOpenSettings = {
                    navController.navigate(VcpRoutes.SETTINGS_SETUP)
                },
            )
        }

        composable(VcpRoutes.SETTINGS_SETUP) {
            SettingsRoute(
                appContainer = appContainer,
                isSetup = true,
                onNavigateBack = { navController.navigateUp() },
                onSaved = {
                    navController.navigate(VcpRoutes.AGENTS) {
                        popUpTo(VcpRoutes.SETUP_GATE) { inclusive = true }
                    }
                },
            )
        }

        composable(VcpRoutes.SETTINGS_STANDALONE) {
            SettingsRoute(
                appContainer = appContainer,
                isSetup = false,
                onNavigateBack = { navController.navigateUp() },
                onSaved = { navController.navigateUp() },
            )
        }

        composable(VcpRoutes.AGENTS) {
            AgentsRoute(
                appContainer = appContainer,
                onOpenSettings = { navController.navigate(VcpRoutes.SETTINGS_STANDALONE) },
                onOpenAgentEditor = { agentId ->
                    navController.navigate(VcpRoutes.agentEditor(agentId))
                },
                onOpenTopics = { agentId ->
                    navController.navigate(VcpRoutes.topics(agentId))
                },
            )
        }

        composable(
            route = VcpRoutes.AGENT_EDITOR_PATTERN,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            AgentEditorRoute(
                appContainer = appContainer,
                agentId = backStackEntry.arguments?.getString("agentId").orEmpty(),
                onNavigateBack = { navController.navigateUp() },
            )
        }

        composable(
            route = VcpRoutes.TOPICS_PATTERN,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId").orEmpty()
            TopicsRoute(
                appContainer = appContainer,
                agentId = agentId,
                onNavigateBack = { navController.navigateUp() },
                onOpenAgentEditor = { navController.navigate(VcpRoutes.agentEditor(agentId)) },
                onOpenSettings = { navController.navigate(VcpRoutes.SETTINGS_STANDALONE) },
                onOpenChat = { topicId ->
                    navController.navigate(VcpRoutes.chat(agentId, topicId))
                },
            )
        }

        composable(
            route = VcpRoutes.CHAT_PATTERN,
            arguments = listOf(
                navArgument("agentId") { type = NavType.StringType },
                navArgument("topicId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId").orEmpty()
            val topicId = backStackEntry.arguments?.getString("topicId").orEmpty()
            ChatRoute(
                appContainer = appContainer,
                agentId = agentId,
                topicId = topicId,
                onNavigateBack = { navController.navigateUp() },
                onOpenTopics = { navController.navigate(VcpRoutes.topics(agentId)) },
                onOpenTopic = { nextTopicId ->
                    navController.navigate(VcpRoutes.chat(agentId, nextTopicId))
                },
                onOpenAgentEditor = { navController.navigate(VcpRoutes.agentEditor(agentId)) },
                onOpenSettings = { navController.navigate(VcpRoutes.SETTINGS_STANDALONE) },
                onOpenAttachment = { attachmentId ->
                    navController.navigate(VcpRoutes.attachment(attachmentId))
                },
            )
        }

        composable(
            route = VcpRoutes.ATTACHMENT_PATTERN,
            arguments = listOf(navArgument("attachmentId") { type = NavType.StringType }),
        ) { backStackEntry ->
            AttachmentViewerScreen(
                appContainer = appContainer,
                attachmentId = backStackEntry.arguments?.getString("attachmentId").orEmpty(),
                onNavigateBack = { navController.navigateUp() },
            )
        }
    }
}

@Composable
private fun rememberAppContainer(): AppContainer {
    val application = LocalContext.current.applicationContext as VcpNativeApplication
    return remember(application) { application.appContainer }
}
