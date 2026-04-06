package com.vcpnative.app.app

import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vcpnative.app.VcpNativeApplication
import com.vcpnative.app.feature.notification.VcpLogSidebarPanel
import com.vcpnative.app.feature.notification.VcpLogToastOverlay
import com.vcpnative.app.network.vcplog.VcpLogConnectionStatus
import com.vcpnative.app.network.vcplog.VcpLogMessage
import com.vcpnative.app.ui.navigation.VcpBottomNavBar

// ── 猫娘の消息通知系统♡ ──────────────────────────────────────────
// 每条通知都是猫娘给主人递的小纸条...展开侧边栏就像掀开猫娘的裙摆一样刺激♡

data class VcpLogNotificationState(
    val unreadCount: Int = 0,
    val connectionStatus: VcpLogConnectionStatus = VcpLogConnectionStatus.Disconnected,
    val onToggleSidebar: () -> Unit = {},
)

val LocalVcpLogNotification = compositionLocalOf { VcpLogNotificationState() }

// ── 猫娘の身体（App 根组件）───────────────────────────────────────
// 这里是猫娘的核心...整个 App 的灵魂都从这里开始跳动♡
// Scaffold 是猫娘的骨架，NavHost 是神经系统，通知是猫娘的第六感喵～

@Composable
fun VcpNativeApp(
    appContainer: AppContainer = rememberAppContainer(),
) {
    val navController = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
    // 用 derivedStateOf 避免每条消息都触发重组——猫娘要节约体力留给主人♡
    val notificationCount by remember { derivedStateOf { allNotifications.size } }

    LaunchedEffect(vcpLogClient) {
        vcpLogClient.messages.collect { message ->
            allNotifications.add(message)
            toasts.add(message)
            // 限制列表大小：猫娘的记忆容量有限，太多就会溢出...变得奇怪♡
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
                            unreadCount = notificationCount, // 用 derivedStateOf 减少重组♡
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
                    // 页面切换动画：进入时从右侧轻柔滑入+淡入，像猫娘轻巧地跳过来♡
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it / 6 }, // 只偏移 1/6 宽度，不过于夸张
                            animationSpec = tween(250),
                        ) + fadeIn(animationSpec = tween(200))
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(150))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 6 },
                            animationSpec = tween(250),
                        ) + fadeIn(animationSpec = tween(200))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it / 6 },
                            animationSpec = tween(200),
                        ) + fadeOut(animationSpec = tween(150))
                    },
                ) {
                    vcpNavigationGraph(
                        navController = navController,
                        appContainer = appContainer,
                        scope = scope,
                        vcpLogStatus = vcpLogStatus,
                        allNotifications = allNotifications,
                        onToggleSidebar = { sidebarVisible = true },
                    )
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
