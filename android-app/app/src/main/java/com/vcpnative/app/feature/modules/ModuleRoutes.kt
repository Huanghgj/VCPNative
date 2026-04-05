package com.vcpnative.app.feature.modules

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.bridge.IpcDispatcher
import com.vcpnative.app.bridge.VcpModuleHost
import com.vcpnative.app.bridge.createIpcDispatcher

// ModuleDef 是模块的唯一定义源。HomeRoute 和 ToolsRoute 都应该从这里读取，
// 不要在各自的 UI 文件里重复硬编码模块名称和图标。

/**
 * Registry of all VCPChat HTML modules that can be hosted in WebView.
 */
object VcpModules {
    val Notes = ModuleDef("notes/notes.html", "Notes", "Notes", Icons.Outlined.Article)
    val Memo = ModuleDef("memo/memo.html", "Memo", "Memo", Icons.Outlined.NoteAlt)
    val Forum = ModuleDef("forum/forum.html", "Forum", "Forum", Icons.Outlined.Forum)
    val Canvas = ModuleDef("canvas/canvas.html", "Canvas", "Canvas", Icons.Outlined.Code)
    val Translator = ModuleDef("translator/translator.html", "Translator", "Translator", Icons.Outlined.Translate)
    val Dice = ModuleDef("dice/dice.html", "Dice", "Dice", Icons.Outlined.Casino)
    val VoiceChat = ModuleDef("voicechat/voicechat.html", "Voice Chat", "Voice", Icons.Outlined.Mic)
    val Themes = ModuleDef("themes/themes.html", "Themes", "Themes", Icons.Outlined.Brush)
    val RagObserver = ModuleDef("ragobserver/RAG_Observer.html", "灵视中心", "RAG", Icons.Outlined.Psychology)

    val all = listOf(Notes, Memo, Forum, Canvas, Translator, Dice, VoiceChat, Themes, RagObserver)
}

data class ModuleDef(
    val assetPath: String,
    val title: String,
    /** Short label shown on chips in HomeRoute. */
    val shortName: String,
    /** Icon shown on chips in HomeRoute and ToolsRoute. */
    val icon: ImageVector,
) {
    /** Navigation route name derived from asset path. */
    val routeName: String = "module/${assetPath.substringBefore("/")}"

    /** Folder-level module identifier used for navigation (e.g. "notes", "memo"). */
    val moduleId: String = assetPath.substringBefore("/")
}

/**
 * Generic route for hosting a VCPChat HTML module.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VcpModuleRoute(
    moduleDef: ModuleDef,
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val dispatcher = remember(moduleDef.assetPath) {
        createIpcDispatcher(
            context = context,
            settingsRepository = appContainer.settingsRepository,
            workspaceRepository = appContainer.workspaceRepository,
            modelUsageTracker = appContainer.modelUsageTracker,
            groupChatRepository = appContainer.groupChatRepository,
            eventBus = appContainer.eventBus,
            activeRequestTracker = appContainer.activeRequestTracker,
            streamingHttpClient = appContainer.streamingHttpClient,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(moduleDef.title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        VcpModuleHost(
            modulePath = moduleDef.assetPath,
            ipcDispatcher = dispatcher,
            onCloseRequest = onNavigateBack,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
