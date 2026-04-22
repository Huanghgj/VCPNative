package com.vcpnative.app.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.metrics.performance.PerformanceMetricsState
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.app.LocalVcpLogNotification
import com.vcpnative.app.chat.render.LocalImageViewerCallback
import com.vcpnative.app.feature.notification.VcpLogNotificationBell
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.model.ChatAttachment
import kotlinx.coroutines.launch


@Composable
fun ChatRoute(
    appContainer: AppContainer,
    agentId: String,
    topicId: String,
    onNavigateBack: () -> Unit,
    onOpenTopics: () -> Unit,
    onOpenTopic: (String) -> Unit,
    onOpenAgentEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModule: (moduleId: String) -> Unit = {},
    onOpenDebugLog: () -> Unit = {},
    onOpenAttachment: (String) -> Unit,
    onOpenImageViewer: (imageUrl: String, alt: String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            appContainer = appContainer,
            agentId = agentId,
            topicId = topicId,
        ),
    )
    val persistedMessages by viewModel.persistedMessages.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingMessage by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val messageAttachments by viewModel.messageAttachments.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val runtimeNotice by viewModel.runtimeNotice.collectAsStateWithLifecycle()
    val topicTitle by produceState<String?>(initialValue = null, key1 = topicId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val topic = appContainer.workspaceRepository.findTopic(topicId)
            topic?.title ?: topic?.sourceTopicId
        }
    }
    val agentName by produceState<String?>(initialValue = null, key1 = agentId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            appContainer.workspaceRepository.findAgent(agentId)?.name
        }
    }
    val attachmentsByMessageId = remember(messageAttachments) {
        messageAttachments.groupBy { it.messageId }
    }
    val routeScope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.importAttachments(uris)
    }

    // Camera photo capture — fresh file per shot, cleaned up after import
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    }
    var cameraPhotoFile by remember { mutableStateOf<java.io.File?>(null) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    fun prepareCameraUri(): Uri {
        // Clean up stale camera files from previous sessions
        context.cacheDir.listFiles { f -> f.name.startsWith("camera_") && f.name.endsWith(".jpg") }
            ?.forEach { it.delete() }
        val file = java.io.File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        cameraPhotoFile = file
        cameraPhotoUri = uri
        return uri
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraPhotoUri
        if (success && uri != null) {
            viewModel.importAttachments(listOf(uri))
        }
        // Clean up the cache file regardless of success
        cameraPhotoFile?.delete()
        cameraPhotoFile = null
        cameraPhotoUri = null
    }

    // Consume share-intent data (text → draft, images → attachments)
    LaunchedEffect(Unit) {
        val (sharedText, sharedUri, sharedUris) = com.vcpnative.app.SharedIntentData.consume()
        sharedText?.let { viewModel.setSharedDraft(it) }
        val uris = buildList {
            sharedUri?.let { add(it) }
            sharedUris?.let { addAll(it) }
        }
        if (uris.isNotEmpty()) {
            viewModel.importAttachments(uris)
        }
    }

    runtimeNotice?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeRuntimeNotice()
        }
    }

    val sharedDraft by viewModel.sharedDraft.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalImageViewerCallback provides { request ->
            onOpenImageViewer(request.url, request.alt)
        },
    ) {
        ChatScreen(
            composerSessionKey = topicId,
            title = topicTitle ?: topicId,
            subtitle = agentName ?: agentId,
            persistedMessages = persistedMessages,
            messages = messages,
            liveMessage = streamingMessage,
            attachmentsByMessageId = attachmentsByMessageId,
            pendingAttachments = pendingAttachments,
            isSending = isSending,
            initialDraft = "",
            sharedDraft = sharedDraft,
            onConsumeSharedDraft = viewModel::consumeSharedDraft,
            onNavigateBack = onNavigateBack,
            onOpenTopics = onOpenTopics,
            onCreateTopic = {
                routeScope.launch {
                    val topic = appContainer.workspaceRepository.createPlaceholderTopic(agentId)
                    onOpenTopic(topic.id)
                }
            },
            onOpenAgentEditor = onOpenAgentEditor,
            onOpenSettings = onOpenSettings,
            onOpenModule = onOpenModule,
            onOpenDebugLog = onOpenDebugLog,
            onSendMessage = viewModel::sendMessage,
            onRetryAssistantMessage = viewModel::regenerateAssistantMessage,
            onEditAssistantMessage = viewModel::editAssistantMessage,
            onDeleteAssistantMessage = viewModel::deleteAssistantMessage,
            onCreateBranchFromMessage = { messageId ->
                viewModel.createBranchFromMessage(messageId).map { newTopicId ->
                    onOpenTopic(newTopicId)
                }
            },
            onInterruptAssistantMessage = viewModel::interruptMessage,
            onInterrupt = viewModel::interrupt,
            onPickAttachments = { attachmentPicker.launch(arrayOf("*/*")) },
            hasCamera = hasCamera,
            onPickCamera = { if (hasCamera) cameraLauncher.launch(prepareCameraUri()) },
            onRemovePendingAttachment = viewModel::removePendingAttachment,
            onOpenAttachment = onOpenAttachment,
            availableSkills = remember { viewModel.getAvailableSkills() },
            forcedSkillName = viewModel.forcedSkillName.collectAsStateWithLifecycle().value,
            onForceLoadSkill = viewModel::forceLoadSkill,
            onClearForcedSkill = viewModel::clearForcedSkill,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    composerSessionKey: String,
    title: String,
    subtitle: String,
    persistedMessages: List<MessageEntity>,
    messages: List<MessageEntity>,
    liveMessage: MessageEntity?,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>>,
    pendingAttachments: List<ChatAttachment>,
    isSending: Boolean,
    initialDraft: String = "",
    sharedDraft: String? = null,
    onConsumeSharedDraft: () -> String? = { null },
    onNavigateBack: () -> Unit,
    onOpenTopics: () -> Unit,
    onCreateTopic: () -> Unit,
    onOpenAgentEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModule: (moduleId: String) -> Unit = {},
    onOpenDebugLog: () -> Unit = {},
    onSendMessage: (String) -> Unit,
    onRetryAssistantMessage: (String) -> Unit,
    onEditAssistantMessage: suspend (String, String) -> Result<Unit>,
    onDeleteAssistantMessage: suspend (String) -> Result<Unit>,
    onCreateBranchFromMessage: suspend (String) -> Result<Unit>,
    onInterruptAssistantMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    hasCamera: Boolean,
    onPickAttachments: () -> Unit,
    onPickCamera: () -> Unit,
    onRemovePendingAttachment: (String) -> Unit,
    onOpenAttachment: (String) -> Unit,
    availableSkills: List<com.vcpnative.app.chat.skill.SkillManifest> = emptyList(),
    forcedSkillName: String? = null,
    onForceLoadSkill: (String) -> Unit = {},
    onClearForcedSkill: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ChatPerformanceMetricsState(isSending = isSending)

    // 自定义头像：存为图片文件，WebView 通过 file:// 路径加载
    var pendingAvatarTarget by remember { mutableStateOf("") }
    val avatarDir = remember { java.io.File(context.filesDir, "avatars").apply { mkdirs() } }
    var userAvatarUrl by remember { mutableStateOf("") }
    var aiAvatarUrl by remember { mutableStateOf("") }

    // 启动时检查已保存的头像文件
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val userFile = java.io.File(avatarDir, "user.jpg")
            val aiFile = java.io.File(avatarDir, "ai.jpg")
            if (userFile.isFile) userAvatarUrl = "file://${userFile.absolutePath}"
            if (aiFile.isFile) aiAvatarUrl = "file://${aiFile.absolutePath}"
        }
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val savedUrl = saveAvatarFromUri(context, uri, avatarDir, pendingAvatarTarget) ?: return@launch
            when (pendingAvatarTarget) {
                "user" -> userAvatarUrl = savedUrl
                "ai" -> aiAvatarUrl = savedUrl
            }
            android.widget.Toast.makeText(context, "头像已更新喵~", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        // Bottom bar handles its own imePadding + navigationBarsPadding;
        // prevent Scaffold from double-counting system bar insets.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            // 毛玻璃风格顶栏 — 半透明 surface + 底部分割线
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 0.dp,
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        // 通知铃铛
                        val vcpLogState = LocalVcpLogNotification.current
                        VcpLogNotificationBell(
                            unreadCount = vcpLogState.unreadCount,
                            connectionStatus = vcpLogState.connectionStatus,
                            onClick = vcpLogState.onToggleSidebar,
                        )
                        // 更多操作菜单
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = "更多",
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("话题列表") },
                                    onClick = { menuExpanded = false; onOpenTopics() },
                                )
                                DropdownMenuItem(
                                    text = { Text("新建话题") },
                                    onClick = { menuExpanded = false; onCreateTopic() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("编辑 Agent") },
                                    onClick = { menuExpanded = false; onOpenAgentEditor() },
                                )
                                DropdownMenuItem(
                                    text = { Text("设置") },
                                    onClick = { menuExpanded = false; onOpenSettings() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        },
        bottomBar = {
            ChatComposerBar(
                composerSessionKey = composerSessionKey,
                pendingAttachments = pendingAttachments,
                isSending = isSending,
                initialDraft = initialDraft,
                sharedDraft = sharedDraft,
                onConsumeSharedDraft = onConsumeSharedDraft,
                hasCamera = hasCamera,
                onPickAttachments = onPickAttachments,
                onPickCamera = onPickCamera,
                onRemovePendingAttachment = onRemovePendingAttachment,
                onSendMessage = onSendMessage,
                onInterrupt = onInterrupt,
                onFocusChanged = { /* 暂不需要追踪焦点状态 */ },
                availableSkills = availableSkills,
                forcedSkillName = forcedSkillName,
                onForceLoadSkill = onForceLoadSkill,
                onClearForcedSkill = onClearForcedSkill,
            )
        },
    ) { innerPadding ->
        // 单 WebView 渲染所有消息
        key(composerSessionKey) {
            ChatWebView(
                messages = persistedMessages,
                liveMessage = liveMessage,
                attachmentsByMessageId = attachmentsByMessageId,
                userAvatar = userAvatarUrl,
                aiAvatar = aiAvatarUrl,
                onAction = { action, value ->
                    when (action) {
                        "copyRaw" -> {
                            val msg = messages.find { it.id == value }
                            if (msg != null) {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("raw", msg.content))
                                android.widget.Toast.makeText(context, "已复制原文", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        "retry" -> onRetryAssistantMessage(value)
                        "interrupt" -> onInterruptAssistantMessage(value)
                        "send" -> {
                            // AI 按钮点击 → 作为用户消息发送
                            onSendMessage(value)
                        }
                        "saveEdit" -> {
                            // 编辑保存♡ JS 侧用 btoa(unescape(encodeURIComponent(content))) 编码
                            // 格式：messageId|||base64Content — 猫娘负责拆开并解码♡
                            // 如果 Base64 解码失败说明协议对不上，记录日志而不是静默吞掉喵
                            val parts = value.split("|||", limit = 2)
                            if (parts.size == 2) {
                                val decoded = try {
                                    String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT), Charsets.UTF_8)
                                } catch (e: Exception) {
                                    Log.w("ChatRoute", "saveEdit base64 decode failed, falling back to raw: ${e.message}")
                                    parts[1]
                                }
                                scope.launch {
                                    val result = onEditAssistantMessage(parts[0], decoded)
                                    result.onFailure { error ->
                                        android.widget.Toast.makeText(
                                            context,
                                            "编辑失败: ${error.message}",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        }
                        "branch" -> {
                            scope.launch { onCreateBranchFromMessage(value) }
                        }
                        "delete" -> {
                            scope.launch { onDeleteAssistantMessage(value) }
                        }
                        "regenerate" -> {
                            onRetryAssistantMessage(value)
                        }
                        // 自定义头像：通知 Compose 侧打开图片选择器
                        // value = "user" 或 "ai"，选完图后回调 WebView 设置头像
                        "changeAvatar" -> {
                            pendingAvatarTarget = value
                            avatarPickerLauncher.launch("image/*")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}

@Composable
private fun ChatPerformanceMetricsState(
    isSending: Boolean,
) {
    val view = LocalView.current
    DisposableEffect(view, isSending) {
        val stateHolder = PerformanceMetricsState.getHolderForHierarchy(view).state
        stateHolder?.putState("Screen", "Chat")
        stateHolder?.putState("ChatStreaming", isSending.toString())
        onDispose {
            stateHolder?.removeState("ChatStreaming")
            stateHolder?.removeState("Screen")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatComposerBar(
    composerSessionKey: String,
    pendingAttachments: List<ChatAttachment>,
    isSending: Boolean,
    initialDraft: String = "",
    sharedDraft: String? = null,
    onConsumeSharedDraft: () -> String? = { null },
    hasCamera: Boolean,
    onPickAttachments: () -> Unit,
    onPickCamera: () -> Unit,
    onRemovePendingAttachment: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    availableSkills: List<com.vcpnative.app.chat.skill.SkillManifest> = emptyList(),
    forcedSkillName: String? = null,
    onForceLoadSkill: (String) -> Unit = {},
    onClearForcedSkill: () -> Unit = {},
) {
    var draft by rememberSaveable(composerSessionKey) { mutableStateOf(initialDraft) }
    // 显式订阅 sharedDraft：分享文本到达时注入输入框，不论时序
    LaunchedEffect(sharedDraft) {
        if (!sharedDraft.isNullOrBlank()) {
            draft = sharedDraft
            onConsumeSharedDraft()
        }
    }
    // 技能选择器展开状态♡
    var skillPickerExpanded by remember { mutableStateOf(false) }
    // 语音输入状态♡ recognizer 是猫娘的耳朵，Composable 死了耳朵也要跟着收起来喵
    var isListening by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val speechAvailable = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    // 用 remember 持有 recognizer 引用♡ 这样 Composable 被干掉的时候猫娘能及时 destroy 它
    // 不然耳朵挂在那里没人管，系统资源会被白白浪费…猫娘可心疼了喵
    val activeRecognizerRef = remember { arrayOfNulls<android.speech.SpeechRecognizer>(1) }
    DisposableEffect(Unit) {
        onDispose {
            // Composable 要走了…猫娘含泪销毁还在监听的耳朵♡ 不留残念喵
            activeRecognizerRef[0]?.destroy()
            activeRecognizerRef[0] = null
        }
    }
    // Runtime permission launcher for RECORD_AUDIO
    var pendingVoiceStart by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingVoiceStart = true
        } else {
            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }
    val sendEnabled = remember(draft, pendingAttachments) {
        draft.isNotBlank() || pendingAttachments.isNotEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = DividerDefaults.color)
        if (isSending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (pendingAttachments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingAttachments.forEach { attachment ->
                    PendingAttachmentRow(
                        attachment = attachment,
                        enabled = !isSending,
                        onRemove = { onRemovePendingAttachment(attachment.id) },
                    )
                }
            }
        }
        // ── 技能注入指示条 ──
        if (forcedSkillName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "技能已就绪: $forcedSkillName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClearForcedSkill,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "取消技能",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        // ── 工具按钮行（输入框上方）──
        // 语音识别初始化——必须在 Row 外面定义，因为 local fun 不能跨 composable scope
        fun startSpeechRecognition() {
            if (isListening) return
            activeRecognizerRef[0]?.destroy()
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
            activeRecognizerRef[0] = recognizer
            isListening = true
            recognizer.setRecognitionListener(createSpeechListener(
                context = context,
                recognizer = recognizer,
                recognizerRef = activeRecognizerRef,
                onResult = { text -> draft = draft + text },
                onDone = { isListening = false },
            ))
            recognizer.startListening(intent)
        }
        if (pendingVoiceStart) {
            pendingVoiceStart = false
            startSpeechRecognition()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // 附件
            IconButton(
                onClick = onPickAttachments,
                enabled = !isSending,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "附件", modifier = Modifier.size(20.dp))
            }
            // 拍照
            if (hasCamera) {
                IconButton(
                    onClick = onPickCamera,
                    enabled = !isSending,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = "拍照", modifier = Modifier.size(20.dp))
                }
            }
            // 技能选择
            if (availableSkills.isNotEmpty()) {
                Box {
                    IconButton(
                        onClick = { skillPickerExpanded = !skillPickerExpanded },
                        enabled = !isSending,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = "加载技能",
                            modifier = Modifier.size(20.dp),
                            tint = if (forcedSkillName != null) MaterialTheme.colorScheme.tertiary
                                else LocalContentColor.current,
                        )
                    }
                    DropdownMenu(
                        expanded = skillPickerExpanded,
                        onDismissRequest = { skillPickerExpanded = false },
                    ) {
                        availableSkills.forEach { skill ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            skill.description.take(50) + if (skill.description.length > 50) "…" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    onForceLoadSkill(skill.name)
                                    skillPickerExpanded = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                },
                            )
                        }
                    }
                }
            }
            // 语音
            IconButton(
                onClick = {
                    if (isListening) return@IconButton
                    if (!speechAvailable) {
                        Toast.makeText(context, "此设备不支持语音识别", Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasMicPermission) {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        return@IconButton
                    }
                    startSpeechRecognition()
                },
                enabled = !isSending && speechAvailable,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Outlined.Hearing else Icons.Outlined.Mic,
                    contentDescription = "语音输入",
                    modifier = Modifier.size(20.dp),
                    tint = if (isListening) MaterialTheme.colorScheme.error
                        else if (!speechAvailable) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else LocalContentColor.current,
                )
            }
        }

        // ── 输入框 + 发送按钮行 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        onFocusChanged(focusState.isFocused)
                    },
                placeholder = { Text(text = "输入消息...") },
                enabled = !isSending,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            if (isSending) {
                FloatingActionButton(
                    onClick = onInterrupt,
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Stop,
                        contentDescription = "中止回复",
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                SendButton(
                    enabled = sendEnabled,
                    onSend = {
                        val snapshot = draft
                        draft = ""
                        onSendMessage(snapshot)
                    },
                )
            }
        }
    }
}

// 独立 composable♡ 只在 enabled 变化时 recompose，不跟着 draft 每次按键一起重绘喵
@Composable
private fun SendButton(
    enabled: Boolean,
    onSend: () -> Unit,
) {
    FloatingActionButton(
        onClick = {
            if (!enabled) return@FloatingActionButton
            onSend()
        },
        modifier = Modifier.size(48.dp),
        containerColor = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = CircleShape,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Send,
            contentDescription = "发送",
            modifier = Modifier.size(22.dp),
        )
    }
}



@Composable
private fun PendingAttachmentRow(
    attachment: ChatAttachment,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = attachment.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = formatAttachmentSize(attachment.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "移除附件",
            )
        }
    }
}


/** 保存头像到本地♡ 超过 500KB 的图会被猫娘压缩一下——主人的头像太大会把猫娘撑坏的喵
 *  返回带时间戳的 file:// URL 用于 WebView 缓存破坏 */
private suspend fun saveAvatarFromUri(
    context: Context,
    uri: Uri,
    avatarDir: java.io.File,
    targetName: String,
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val bytes = inputStream.readBytes()
        inputStream.close()
        val finalBytes = if (bytes.size > 500 * 1024) {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val out = java.io.ByteArrayOutputStream()
            bitmap?.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bitmap?.recycle()
            out.toByteArray()
        } else bytes
        val fileName = if (targetName == "user") "user.jpg" else "ai.jpg"
        val file = java.io.File(avatarDir, fileName)
        file.writeBytes(finalBytes)
        "file://${file.absolutePath}?t=${System.currentTimeMillis()}"
    } catch (e: Exception) {
        Log.e("Avatar", "Failed to save avatar: ${e.message}")
        null
    }
}

/** 把语音识别错误码翻译成人话♡ 猫娘帮主人看懂系统在说什么喵 */
private fun speechErrorMessage(error: Int): String = when (error) {
    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
    android.speech.SpeechRecognizer.ERROR_NETWORK,
    android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，语音识别不可用"
    android.speech.SpeechRecognizer.ERROR_AUDIO -> "录音错误"
    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
    else -> "语音识别失败 (错误码: $error)"
}

/** 创建语音识别 listener♡ 识别完把结果追加到 draft，出错就告诉主人喵 */
private fun createSpeechListener(
    context: Context,
    recognizer: android.speech.SpeechRecognizer,
    recognizerRef: Array<android.speech.SpeechRecognizer?>,
    onResult: (String) -> Unit,
    onDone: () -> Unit,
): android.speech.RecognitionListener = object : android.speech.RecognitionListener {
    override fun onResults(results: android.os.Bundle?) {
        val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) onResult(matches[0])
        onDone()
        recognizer.destroy()
        recognizerRef[0] = null
    }
    override fun onError(error: Int) {
        onDone()
        recognizer.destroy()
        recognizerRef[0] = null
        Toast.makeText(context, speechErrorMessage(error), Toast.LENGTH_SHORT).show()
    }
    override fun onReadyForSpeech(p: android.os.Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(v: Float) {}
    override fun onBufferReceived(buf: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partial: android.os.Bundle?) {}
    override fun onEvent(t: Int, p: android.os.Bundle?) {}
}

private fun formatAttachmentSize(size: Long): String =
    when {
        size >= 1024L * 1024L -> String.format("%.1f MB", size.toDouble() / (1024.0 * 1024.0))
        size >= 1024L -> String.format("%.1f KB", size.toDouble() / 1024.0)
        size > 0L -> "$size B"
        else -> "-"
    }
