package com.vcpnative.app.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
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
    const val ARG_AGENT_ID = "agentId"
    const val ARG_TOPIC_ID = "topicId"
    const val ARG_ATTACHMENT_ID = "attachmentId"

    const val BOOTSTRAP = "bootstrap"
    const val SETUP_GATE = "setup-gate"
    const val SETTINGS_SETUP = "settings/setup"
    const val SETTINGS_STANDALONE = "settings/standalone"
    const val AGENTS = "workspace/agents"
    const val AGENT_EDITOR_PATTERN = "workspace/agent/{$ARG_AGENT_ID}"
    const val TOPICS_PATTERN = "workspace/topics/{$ARG_AGENT_ID}"
    const val CHAT_PATTERN = "workspace/chat/{$ARG_AGENT_ID}/{$ARG_TOPIC_ID}"
    const val ATTACHMENT_PATTERN = "attachment/{$ARG_ATTACHMENT_ID}"

    fun agentEditor(agentId: String): String = "workspace/agent/$agentId"

    fun topics(agentId: String): String = "workspace/topics/$agentId"

    fun chat(agentId: String, topicId: String): String = "workspace/chat/$agentId/$topicId"

    fun attachment(attachmentId: String): String = "attachment/$attachmentId"
}

private fun navStringArgument(name: String) = navArgument(name) { type = NavType.StringType }

private fun NavBackStackEntry.requireStringArg(name: String): String {
    return checkNotNull(arguments?.getString(name)) {
        "Missing navigation argument: $name"
    }
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
            arguments = listOf(navStringArgument(VcpRoutes.ARG_AGENT_ID)),
        ) { backStackEntry ->
            AgentEditorRoute(
                appContainer = appContainer,
                agentId = backStackEntry.requireStringArg(VcpRoutes.ARG_AGENT_ID),
                onNavigateBack = { navController.navigateUp() },
            )
        }

        composable(
            route = VcpRoutes.TOPICS_PATTERN,
            arguments = listOf(navStringArgument(VcpRoutes.ARG_AGENT_ID)),
        ) { backStackEntry ->
            val agentId = backStackEntry.requireStringArg(VcpRoutes.ARG_AGENT_ID)
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
                navStringArgument(VcpRoutes.ARG_AGENT_ID),
                navStringArgument(VcpRoutes.ARG_TOPIC_ID),
            ),
        ) { backStackEntry ->
            val agentId = backStackEntry.requireStringArg(VcpRoutes.ARG_AGENT_ID)
            val topicId = backStackEntry.requireStringArg(VcpRoutes.ARG_TOPIC_ID)
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
            arguments = listOf(navStringArgument(VcpRoutes.ARG_ATTACHMENT_ID)),
        ) { backStackEntry ->
            AttachmentViewerScreen(
                appContainer = appContainer,
                attachmentId = backStackEntry.requireStringArg(VcpRoutes.ARG_ATTACHMENT_ID),
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
