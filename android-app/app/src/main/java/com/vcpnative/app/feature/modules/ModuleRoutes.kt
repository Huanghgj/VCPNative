package com.vcpnative.app.feature.modules

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.bridge.IpcDispatcher
import com.vcpnative.app.bridge.VcpModuleHost
import com.vcpnative.app.bridge.createIpcDispatcher

/**
 * Registry of all VCPChat HTML modules that can be hosted in WebView.
 */
object VcpModules {
    val Notes = ModuleDef("notes/notes.html", "Notes")
    val Memo = ModuleDef("memo/memo.html", "Memo")
    val Forum = ModuleDef("forum/forum.html", "Forum")
    val Canvas = ModuleDef("canvas/canvas.html", "Canvas")
    val Translator = ModuleDef("translator/translator.html", "Translator")
    val Dice = ModuleDef("dice/dice.html", "Dice")
    val VoiceChat = ModuleDef("voicechat/voicechat.html", "Voice Chat")
    val Themes = ModuleDef("themes/themes.html", "Themes")

    val all = listOf(Notes, Memo, Forum, Canvas, Translator, Dice, VoiceChat, Themes)
}

data class ModuleDef(
    val assetPath: String,
    val title: String,
) {
    /** Navigation route name derived from asset path. */
    val routeName: String = "module/${assetPath.substringBefore("/")}"
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
