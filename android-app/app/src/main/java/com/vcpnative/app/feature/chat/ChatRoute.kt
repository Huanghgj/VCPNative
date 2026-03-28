package com.vcpnative.app.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.HtmlCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.metrics.performance.PerformanceMetricsState
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.chat.compiler.ChatRequestCompiler
import com.vcpnative.app.chat.render.ChatMessageReaderContent
import com.vcpnative.app.chat.render.ChatMessageContent
import com.vcpnative.app.chat.render.ChatRenderMode
import com.vcpnative.app.app.LocalVcpLogNotification
import com.vcpnative.app.chat.render.LocalImageViewerCallback
import com.vcpnative.app.feature.notification.VcpLogNotificationBell
import com.vcpnative.app.chat.render.shouldUseBrowserHtmlRenderer
import com.vcpnative.app.chat.summary.TopicSummarizer
import com.vcpnative.app.chat.session.StreamSessionManager
import com.vcpnative.app.data.attachment.ChatAttachmentManager
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.model.ChatAttachment
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.StreamSessionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class ChatViewModel(
    private val agentId: String,
    private val topicId: String,
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val requestCompiler: ChatRequestCompiler,
    private val streamSessionManager: StreamSessionManager,
    private val chatAttachmentManager: ChatAttachmentManager,
    private val topicSummarizer: TopicSummarizer,
) : ViewModel() {
    val messages: StateFlow<List<MessageEntity>> = workspaceRepository
        .observeMessages(topicId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val messageAttachments: StateFlow<List<MessageAttachmentEntity>> = workspaceRepository
        .observeMessageAttachments(topicId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()
    private val _runtimeNotice = MutableStateFlow<String?>(null)
    val runtimeNotice: StateFlow<String?> = _runtimeNotice.asStateFlow()
    private val activeRequestId = AtomicReference<String?>(null)

    private data class PendingUserMessage(
        val text: String,
        val attachments: List<ChatAttachment>,
        val messageId: String,
    )

    init {
        viewModelScope.launch {
            settingsRepository.saveLastSession(agentId, topicId)
        }
    }

    fun importAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || _isSending.value) {
            return
        }

        viewModelScope.launch {
            val imported = mutableListOf<ChatAttachment>()
            uris.forEach { uri ->
                runCatching {
                    chatAttachmentManager.importAttachment(uri)
                }.onSuccess { attachment ->
                    imported += attachment
                }.onFailure { error ->
                    workspaceRepository.addMessage(
                        topicId = topicId,
                        role = "system",
                        content = error.message ?: "附件导入失败",
                        status = "error",
                    )
                }
            }
            if (imported.isNotEmpty()) {
                _pendingAttachments.value = _pendingAttachments.value + imported
            }
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == attachmentId }
    }

    fun consumeRuntimeNotice() {
        _runtimeNotice.value = null
    }

    fun sendMessage(draft: String) {
        val text = draft.trim()
        val attachments = _pendingAttachments.value
        if ((text.isEmpty() && attachments.isEmpty()) || _isSending.value) {
            return
        }

        viewModelScope.launch {
            runRequest(
                prepareRequest = {
                    settingsRepository.saveLastSession(agentId, topicId)
                    val userMessage = PendingUserMessage(
                        text = text,
                        attachments = attachments,
                        messageId = buildUserMessageId(),
                    )
                    val compiledRequest = requestCompiler.compile(
                        agentId = agentId,
                        topicId = topicId,
                        userDraft = text,
                        attachments = attachments,
                    )
                    PreparedRequest(
                        compiledRequest = compiledRequest,
                        pendingUserMessage = userMessage,
                    )
                },
            )
        }
    }

    fun regenerateAssistantMessage(messageId: String) {
        if (_isSending.value) {
            return
        }

        viewModelScope.launch {
            runRequest(
                prepareRequest = {
                    settingsRepository.saveLastSession(agentId, topicId)
                    val currentMessages = workspaceRepository.loadMessages(topicId)
                    val latestAssistant = currentMessages.lastOrNull {
                        it.role == "assistant" && it.status !in setOf("draft", "streaming")
                    }
                    val targetMessage = currentMessages.firstOrNull { it.id == messageId }

                    when {
                        targetMessage == null ->
                            throw IllegalStateException("找不到要重新回复的消息。")

                        targetMessage.role != "assistant" ->
                            throw IllegalStateException("当前只支持对 AI 回复执行重新回复。")

                        latestAssistant?.id != targetMessage.id ->
                            throw IllegalStateException("当前仅支持重新回复最后一条 AI 回复。")
                    }

                    currentMessages
                        .takeWhile { it.id != targetMessage.id }
                        .lastOrNull { it.role == "user" }
                        ?: throw IllegalStateException("找不到对应的上一条用户消息。")

                    workspaceRepository.deleteMessagesFrom(
                        topicId = topicId,
                        createdAt = targetMessage.createdAt,
                    )

                    val compiledRequest = requestCompiler.compileFromHistory(
                        agentId = agentId,
                        topicId = topicId,
                    )

                    PreparedRequest(
                        compiledRequest = compiledRequest,
                        pendingUserMessage = null,
                        emptyPendingAttachments = false,
                        failureMessage = "重新回复失败",
                        blankAssistantMessage = "模型未重新生成可显示内容。",
                        interruptedMessage = "重新回复已中止。",
                    )
                },
            )
        }
    }

    fun interrupt() {
        val requestId = activeRequestId.get() ?: return
        viewModelScope.launch {
            val result = streamSessionManager.interrupt(requestId)
            if (!result.success) {
                // Remote interrupt failed — log the error but don't block.
                // The local call.cancel() in StreamSessionManager already
                // ensures the stream will stop and emit Interrupted event.
                Log.w(TAG, "Remote interrupt failed for $requestId: ${result.message}")
            }
        }
    }

    fun interruptMessage(messageId: String) {
        // 不管 messageId 是否匹配，只要在发送中就中断
        // （用户长按的可能是 assistant 消息而不是 request 消息）
        if (_isSending.value) {
            interrupt()
        }
    }

    suspend fun editAssistantMessage(
        messageId: String,
        newContent: String,
    ): Result<Unit> = runCatching {
        if (_isSending.value) {
            error("当前正在生成回复，暂不支持编辑消息。")
        }

        val targetMessage = messages.value.firstOrNull { it.id == messageId }
            ?: error("找不到要编辑的消息。")

        if (targetMessage.role != "assistant") {
            error("当前仅支持编辑 AI 回复。")
        }
        if (targetMessage.status in setOf("draft", "streaming")) {
            error("请在回复完成后再编辑消息。")
        }

        val normalizedContent = newContent.trimEnd()
        if (normalizedContent.isBlank()) {
            error("消息内容不能为空。")
        }
        if (normalizedContent == targetMessage.content) {
            return@runCatching
        }

        workspaceRepository.updateMessage(
            topicId = topicId,
            messageId = messageId,
            content = normalizedContent,
            status = targetMessage.status,
        )
    }

    suspend fun deleteAssistantMessage(messageId: String): Result<Unit> = runCatching {
        val targetMessage = messages.value.firstOrNull { it.id == messageId }
            ?: error("找不到要删除的消息。")

        // 如果正在流式输出这条消息，先中断再删
        if (targetMessage.status in setOf("draft", "streaming") && _isSending.value) {
            interrupt()
            // 等一下让中断生效
            kotlinx.coroutines.delay(200)
        }

        workspaceRepository.deleteMessage(
            topicId = topicId,
            messageId = messageId,
        )
    }

    suspend fun createBranchFromMessage(messageId: String): Result<String> = runCatching {
        if (_isSending.value) {
            error("当前正在生成回复，暂不支持创建分支。")
        }

        val currentMessages = messages.value
        val targetIndex = currentMessages.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) {
            error("找不到要创建分支的消息。")
        }

        val targetMessage = currentMessages[targetIndex]
        if (targetMessage.role != "assistant") {
            error("当前仅支持从 AI 回复创建分支。")
        }
        if (targetMessage.status in setOf("draft", "streaming")) {
            error("请在回复完成后再创建分支。")
        }

        val branchMessages = currentMessages.take(targetIndex + 1)
        if (branchMessages.isEmpty()) {
            error("没有可用于创建分支的消息。")
        }

        val currentTopic = workspaceRepository.findTopic(topicId)
        val branchTitle = buildBranchTitle(currentTopic?.title ?: topicId)
        val newTopic = workspaceRepository.createTopic(
            agentId = agentId,
            title = branchTitle,
        )

        val attachmentsByMessageId = messageAttachments.value.groupBy { it.messageId }
        var previousCreatedAt = Long.MIN_VALUE
        branchMessages.forEach { message ->
            val nextCreatedAt = if (previousCreatedAt == Long.MIN_VALUE) {
                message.createdAt
            } else {
                maxOf(message.createdAt, previousCreatedAt + 1L)
            }
            previousCreatedAt = nextCreatedAt

            workspaceRepository.addMessage(
                topicId = newTopic.id,
                role = message.role,
                content = message.content,
                status = message.status,
                createdAt = nextCreatedAt,
                attachments = attachmentsByMessageId[message.id]
                    .orEmpty()
                    .map(MessageAttachmentEntity::toChatAttachment),
            )
        }

        newTopic.id
    }

    private data class PreparedRequest(
        val compiledRequest: CompiledChatRequest,
        val pendingUserMessage: PendingUserMessage?,
        val emptyPendingAttachments: Boolean = true,
        val failureMessage: String = "未知错误",
        val blankAssistantMessage: String = "模型未返回可显示内容。",
        val interruptedMessage: String? = null,
    )

    private suspend fun runRequest(
        prepareRequest: suspend () -> PreparedRequest,
    ) {
        _isSending.value = true
        var assistantDraftId: String? = null
        var failureMessage = "未知错误"
        try {
            val prepared = prepareRequest()
            failureMessage = prepared.failureMessage
            assistantDraftId = prepared.compiledRequest.requestId
            activeRequestId.set(prepared.compiledRequest.requestId)
            submitPreparedRequest(prepared)
        } catch (error: Throwable) {
            runCatching {
                assistantDraftId?.let { draftId ->
                    workspaceRepository.deleteMessage(topicId, draftId)
                }
            }.onFailure { cleanupError ->
                Log.e(TAG, "Failed to clean up assistant draft for topic=$topicId", cleanupError)
            }
            val failureText = error.message ?: failureMessage
            runCatching {
                workspaceRepository.addMessage(
                    topicId = topicId,
                    role = "system",
                    content = failureText,
                    status = "error",
                )
            }.onFailure { persistError ->
                Log.e(TAG, "Failed to persist request failure message for topic=$topicId", persistError)
                _runtimeNotice.value = failureText
            }
        } finally {
            activeRequestId.set(null)
            _isSending.value = false
        }
    }

    private suspend fun submitPreparedRequest(
        prepared: PreparedRequest,
    ) {
        val compiledRequest = prepared.compiledRequest
        val baseTimestamp = System.currentTimeMillis()

        prepared.pendingUserMessage?.let { pendingUser ->
            workspaceRepository.addMessage(
                topicId = topicId,
                role = "user",
                content = pendingUser.text,
                messageId = pendingUser.messageId,
                createdAt = baseTimestamp,
                attachments = pendingUser.attachments,
            )
            if (prepared.emptyPendingAttachments) {
                _pendingAttachments.value = emptyList()
            }
        }

        workspaceRepository.addMessage(
            topicId = topicId,
            role = "assistant",
            content = "",
            status = "draft",
            messageId = compiledRequest.requestId,
            createdAt = baseTimestamp + if (prepared.pendingUserMessage != null) 1 else 0,
        )

        val assistantBuffer = StringBuilder()
        var lastPersistedContent = ""
        var lastPersistAt = 0L

        suspend fun persistAssistant(status: String, force: Boolean = false) {
            val content = assistantBuffer.toString()
            val now = System.currentTimeMillis()
            if (!force && content == lastPersistedContent && now - lastPersistAt < ASSISTANT_STREAM_PERSIST_INTERVAL_MS) {
                return
            }
            workspaceRepository.updateMessage(
                topicId = topicId,
                messageId = compiledRequest.requestId,
                content = content,
                status = status,
                syncCompatHistory = status != "streaming",
            )
            lastPersistedContent = content
            lastPersistAt = now
        }

        streamSessionManager.submit(compiledRequest).collect { event ->
            when (event) {
                StreamSessionEvent.Started -> {
                    workspaceRepository.updateMessage(
                        topicId = topicId,
                        messageId = compiledRequest.requestId,
                        content = assistantBuffer.toString(),
                        status = "streaming",
                        syncCompatHistory = false,
                    )
                    lastPersistedContent = assistantBuffer.toString()
                    lastPersistAt = System.currentTimeMillis()
                }

                is StreamSessionEvent.TextDelta -> {
                    assistantBuffer.append(event.text)
                    val now = System.currentTimeMillis()
                    if (
                        assistantBuffer.length == event.text.length ||
                        now - lastPersistAt >= ASSISTANT_STREAM_PERSIST_INTERVAL_MS ||
                        '\n' in event.text
                    ) {
                        persistAssistant(status = "streaming")
                    }
                }

                is StreamSessionEvent.Completed -> {
                    val finalText = event.fullText.ifBlank { assistantBuffer.toString() }
                    if (finalText.isBlank()) {
                        workspaceRepository.deleteMessage(topicId, compiledRequest.requestId)
                        workspaceRepository.addMessage(
                            topicId = topicId,
                            role = "system",
                            content = prepared.blankAssistantMessage,
                            status = "error",
                        )
                    } else {
                        // Replace buffer content safely: keep old content until persist succeeds
                        val snapshot = finalText
                        assistantBuffer.clear().append(snapshot)
                        persistAssistant(
                            status = "complete",
                            force = true,
                        )
                        tryAutoSummarize()
                    }
                }

                is StreamSessionEvent.Interrupted -> {
                    val partialText = event.partialText.ifBlank { assistantBuffer.toString() }
                    if (partialText.isBlank()) {
                        workspaceRepository.deleteMessage(topicId, compiledRequest.requestId)
                        prepared.interruptedMessage?.let { message ->
                            workspaceRepository.addMessage(
                                topicId = topicId,
                                role = "system",
                                content = message,
                                status = "error",
                            )
                        }
                    } else {
                        val snapshot = partialText
                        assistantBuffer.clear().append(snapshot)
                        persistAssistant(
                            status = "interrupted",
                            force = true,
                        )
                    }
                }

                is StreamSessionEvent.Failed -> {
                    val partialText = event.partialText.ifBlank { assistantBuffer.toString() }
                    if (partialText.isBlank()) {
                        workspaceRepository.deleteMessage(topicId, compiledRequest.requestId)
                    } else {
                        val snapshot = partialText
                        assistantBuffer.clear().append(snapshot)
                        persistAssistant(
                            status = "error",
                            force = true,
                        )
                    }
                    workspaceRepository.addMessage(
                        topicId = topicId,
                        role = "system",
                        content = event.message,
                        status = "error",
                    )
                }
            }
        }
    }

    private fun tryAutoSummarize() {
        viewModelScope.launch {
            runCatching {
                val agentName = workspaceRepository.findAgent(agentId)?.name ?: agentId
                topicSummarizer.trySummarize(topicId, agentName)
            }
        }
    }

    companion object {
        private const val ASSISTANT_STREAM_PERSIST_INTERVAL_MS = 240L
        private const val TAG = "ChatViewModel"

        private fun buildBranchTitle(currentTitle: String): String =
            if (currentTitle.endsWith(" (分支)")) {
                "$currentTitle 2"
            } else {
                "$currentTitle (分支)"
            }

        private fun buildUserMessageId(): String =
            "msg_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}_user"

        fun factory(
            appContainer: AppContainer,
            agentId: String,
            topicId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    agentId = agentId,
                    topicId = topicId,
                    settingsRepository = appContainer.settingsRepository,
                    workspaceRepository = appContainer.workspaceRepository,
                    requestCompiler = appContainer.requestCompiler,
                    streamSessionManager = appContainer.streamSessionManager,
                    chatAttachmentManager = appContainer.chatAttachmentManager,
                    topicSummarizer = appContainer.topicSummarizer,
                )
            }
        }
    }
}

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
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageAttachments by viewModel.messageAttachments.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val runtimeNotice by viewModel.runtimeNotice.collectAsStateWithLifecycle()
    val topicTitle by produceState<String?>(initialValue = null, key1 = topicId) {
        val topic = appContainer.workspaceRepository.findTopic(topicId)
        value = topic?.title ?: topic?.sourceTopicId
    }
    val agentName by produceState<String?>(initialValue = null, key1 = agentId) {
        value = appContainer.workspaceRepository.findAgent(agentId)?.name
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

    runtimeNotice?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeRuntimeNotice()
        }
    }

    CompositionLocalProvider(
        LocalImageViewerCallback provides { request ->
            onOpenImageViewer(request.url, request.alt)
        },
    ) {
        ChatScreen(
            composerSessionKey = topicId,
            title = topicTitle ?: topicId,
            subtitle = agentName ?: agentId,
            messages = messages,
            attachmentsByMessageId = attachmentsByMessageId,
            pendingAttachments = pendingAttachments,
            isSending = isSending,
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
            onRemovePendingAttachment = viewModel::removePendingAttachment,
            onOpenAttachment = onOpenAttachment,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    composerSessionKey: String,
    title: String,
    subtitle: String,
    messages: List<MessageEntity>,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>>,
    pendingAttachments: List<ChatAttachment>,
    isSending: Boolean,
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
    onPickAttachments: () -> Unit,
    onRemovePendingAttachment: (String) -> Unit,
    onOpenAttachment: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bubbleSpeechController = rememberBubbleSpeechController()
    var composerFocused by remember { mutableStateOf(false) }
    ChatPerformanceMetricsState(isSending = isSending)

    Scaffold(
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
                onPickAttachments = onPickAttachments,
                onRemovePendingAttachment = onRemovePendingAttachment,
                onSendMessage = onSendMessage,
                onInterrupt = onInterrupt,
                onFocusChanged = { composerFocused = it },
            )
        },
    ) { innerPadding ->
        // 单 WebView 渲染所有消息
        ChatWebView(
            messages = messages,
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
                        // 编辑保存：value = "messageId|||base64Content"
                        val parts = value.split("|||", limit = 2)
                        if (parts.size == 2) {
                            val decoded = try {
                                String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT), Charsets.UTF_8)
                            } catch (_: Exception) { parts[1] }
                            scope.launch {
                                onEditAssistantMessage(parts[0], decoded)
                            }
                        }
                    }
                    "branch" -> {
                        scope.launch { onCreateBranchFromMessage(value) }
                    }
                    "delete" -> {
                        scope.launch { onDeleteAssistantMessage(value) }
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

@Composable
private fun ChatComposerBar(
    composerSessionKey: String,
    pendingAttachments: List<ChatAttachment>,
    isSending: Boolean,
    onPickAttachments: () -> Unit,
    onRemovePendingAttachment: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onInterrupt: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    var draft by rememberSaveable(composerSessionKey) { mutableStateOf("") }
    val sendEnabled = remember(draft, pendingAttachments) {
        draft.isNotBlank() || pendingAttachments.isNotEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(
                onClick = onPickAttachments,
                enabled = !isSending,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "添加附件",
                    modifier = Modifier.size(24.dp),
                )
            }
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
                FloatingActionButton(
                    onClick = {
                        if (!sendEnabled) return@FloatingActionButton
                        val snapshot = draft
                        draft = ""
                        onSendMessage(snapshot)
                    },
                    modifier = Modifier.size(48.dp),
                    containerColor = if (sendEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (sendEnabled) {
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
        }
    }
}


@Composable
private fun MessageBubble(
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity>,
    canRegenerateAssistant: Boolean,
    isSending: Boolean,
    pauseDynamicContent: Boolean,
    onSendMessage: (String) -> Unit,
    onRetryAssistantMessage: (String) -> Unit,
    onEditAssistantMessage: suspend (String, String) -> Result<Unit>,
    onDeleteAssistantMessage: suspend (String) -> Result<Unit>,
    onCreateBranchFromMessage: suspend (String) -> Result<Unit>,
    onSpeakAssistantMessage: (String) -> Result<Unit>,
    onInterruptAssistantMessage: (String) -> Unit,
    onOpenTopics: () -> Unit,
    onCreateTopic: () -> Unit,
    onOpenAttachment: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backgroundColor = when {
        message.status == "error" -> MaterialTheme.colorScheme.errorContainer
        message.status == "interrupted" -> MaterialTheme.colorScheme.tertiaryContainer
        message.role == "user" -> MaterialTheme.colorScheme.primary
        message.role == "assistant" -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        message.status == "error" -> MaterialTheme.colorScheme.onErrorContainer
        message.role == "user" -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val alignment = if (message.role == "user") Alignment.CenterEnd else Alignment.CenterStart
    val label = remember(message.role) {
        when (message.role) {
            "user" -> "我"
            "assistant" -> "助手"
            else -> "系统"
        }
    }
    val displayContent = remember(message.content, message.role, message.status) {
        when {
            message.content.isNotBlank() -> message.content
            message.role == "assistant" && message.status in setOf("draft", "streaming") -> "思考中..."
            message.status == "interrupted" -> "已中止"
            else -> ""
        }
    }
    val canLongPressCopy = message.role != "user" && displayContent.isNotBlank()
    val isBrowserHtmlMessage = remember(displayContent, message.role) {
        message.role != "user" && shouldUseBrowserHtmlRenderer(displayContent)
    }
    val bubbleFillFraction = when {
        message.role == "user" -> 0.78f
        isBrowserHtmlMessage -> 0.95f
        else -> 0.88f
    }
    val bubbleBorderColor = when {
        isBrowserHtmlMessage -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        else -> Color.Transparent
    }
    val bubbleBackgroundColor = if (isBrowserHtmlMessage) {
        MaterialTheme.colorScheme.surface
    } else {
        backgroundColor
    }
    val bubblePadding = if (isBrowserHtmlMessage) {
        PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    } else {
        PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    }
    // iMessage 风格：对方左下小圆角，自己右下小圆角
    val bubbleShape = when {
        message.role == "user" -> RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
        isBrowserHtmlMessage -> RoundedCornerShape(20.dp)
        else -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }
    // 只给 assistant 和 system 显示角色标签，用户消息不显示（像 iMessage）
    val showRoleLabel = message.role != "user" && !(message.role == "assistant" && isBrowserHtmlMessage)
    val isStreamingMessage = isStreamingMessage(message)
    val canShowAssistantMenu = message.role == "assistant" && displayContent.isNotBlank()
    val canEditAssistant = message.role == "assistant" && !isSending && !isStreamingMessage
    val canDeleteAssistant = message.role == "assistant" && !isSending && !isStreamingMessage
    val canCreateBranch = message.role == "assistant" && !isSending && !isStreamingMessage
    val plainTextContent = remember(message.content, displayContent) {
        extractPlainTextForActions(message.content.ifBlank { displayContent })
    }
    var menuExpanded by remember(message.id) { mutableStateOf(false) }
    var activeDialog by remember(message.id) { mutableStateOf<String?>(null) }
    val editDialogOpen = activeDialog == "edit"
    val deleteDialogOpen = activeDialog == "delete"
    val readerDialogOpen = activeDialog == "reader"
    var actionInProgress by remember(message.id) { mutableStateOf(false) }
    var editDraft by remember(message.id) { mutableStateOf(message.content) }
    var editError by remember(message.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(message.id, message.content, activeDialog) {
        if (activeDialog != "edit") {
            editDraft = message.content
            editError = null
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(bubbleFillFraction)
                .widthIn(max = 720.dp)
                .combinedClickable(
                    enabled = canLongPressCopy || canShowAssistantMenu,
                    onClick = {},
                    onLongClick = {
                        if (canShowAssistantMenu) {
                            menuExpanded = true
                        } else {
                            copyMessageContentToClipboard(
                                context = context,
                                role = message.role,
                                content = displayContent,
                            )
                        }
                    },
                )
                .background(
                    color = bubbleBackgroundColor,
                    shape = bubbleShape,
                )
                .border(
                    width = if (bubbleBorderColor == Color.Transparent) 0.dp else 1.dp,
                    color = bubbleBorderColor,
                    shape = bubbleShape,
                )
                .padding(bubblePadding),
        ) {
            if (showRoleLabel) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (displayContent.isNotBlank()) {
                if (showRoleLabel) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
                key(message.id) {
                    ChatMessageContent(
                        content = displayContent,
                        mode = if (isStreamingMessage) {
                            ChatRenderMode.Streaming
                        } else {
                            ChatRenderMode.Final
                        },
                        role = message.role,
                        onActionMessage = if (message.role == "assistant" || message.role == "system") {
                            onSendMessage
                        } else {
                            null
                        },
                        pauseDynamicContent = pauseDynamicContent,
                        onLongPress = {
                            if (canShowAssistantMenu) {
                                menuExpanded = true
                            } else if (canLongPressCopy) {
                                copyMessageContentToClipboard(
                                    context = context,
                                    role = message.role,
                                    content = plainTextContent,
                                )
                            }
                        },
                    )
                }
            }
            if (attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                AttachmentList(
                    attachments = attachments,
                    onOpenAttachment = onOpenAttachment,
                )
            }
        }
        if (canShowAssistantMenu) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (isStreamingMessage) {
                    DropdownMenuItem(
                        text = { Text("中止回复") },
                        onClick = {
                            onInterruptAssistantMessage(message.id)
                            menuExpanded = false
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("编辑消息") },
                        enabled = canEditAssistant && !actionInProgress,
                        onClick = {
                            editDraft = message.content
                            editError = null
                            activeDialog = "edit"
                            menuExpanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("复制文本") },
                    onClick = {
                        copyMessageContentToClipboard(
                            context = context,
                            role = message.role,
                            content = plainTextContent,
                        )
                        menuExpanded = false
                    },
                )
                if (!isStreamingMessage) {
                    DropdownMenuItem(
                        text = { Text("转发消息") },
                        onClick = {
                            shareMessageText(
                                context = context,
                                subject = "VCPNative 消息",
                                text = plainTextContent,
                            )
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("朗读气泡") },
                        onClick = {
                            val result = onSpeakAssistantMessage(plainTextContent)
                            result.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "朗读失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("阅读模式") },
                        onClick = {
                            activeDialog = "reader"
                            menuExpanded = false
                        },
                    )
                }
                if (!isStreamingMessage) {
                    DropdownMenuItem(
                        text = { Text("创建分支") },
                        enabled = canCreateBranch && !actionInProgress,
                        onClick = {
                            menuExpanded = false
                            scope.launch {
                                actionInProgress = true
                                val result = onCreateBranchFromMessage(message.id)
                                actionInProgress = false
                                result.onSuccess {
                                    Toast.makeText(context, "已创建分支", Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "创建分支失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                }
                if (canRegenerateAssistant) {
                    DropdownMenuItem(
                        text = { Text("重新回复") },
                        onClick = {
                            onRetryAssistantMessage(message.id)
                            menuExpanded = false
                        },
                    )
                }
                if (!isStreamingMessage) {
                    DropdownMenuItem(
                        text = { Text("删除消息") },
                        enabled = canDeleteAssistant && !actionInProgress,
                        onClick = {
                            activeDialog = "delete"
                            menuExpanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("话题列表") },
                    onClick = {
                        onOpenTopics()
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("新建话题") },
                    onClick = {
                        onCreateTopic()
                        menuExpanded = false
                    },
                )
            }
        }
    }

    if (editDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!actionInProgress) {
                    activeDialog = null
                }
            },
            title = { Text("编辑消息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editDraft,
                        onValueChange = {
                            editDraft = it
                            editError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 14,
                    )
                    editError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !actionInProgress,
                    onClick = {
                        scope.launch {
                            actionInProgress = true
                            val result = onEditAssistantMessage(message.id, editDraft)
                            actionInProgress = false
                            result.onSuccess {
                                activeDialog = null
                                Toast.makeText(context, "已保存消息", Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                editError = error.message ?: "保存失败"
                            }
                        }
                    },
                ) {
                    Text(if (actionInProgress) "保存中..." else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionInProgress,
                    onClick = {
                        activeDialog = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (deleteDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!actionInProgress) {
                    activeDialog = null
                }
            },
            title = { Text("删除消息") },
            text = {
                Text(
                    text = buildDeletePreviewText(message.content),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionInProgress,
                    onClick = {
                        scope.launch {
                            actionInProgress = true
                            val result = onDeleteAssistantMessage(message.id)
                            actionInProgress = false
                            result.onSuccess {
                                activeDialog = null
                                Toast.makeText(context, "已删除消息", Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "删除失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(if (actionInProgress) "删除中..." else "删除")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionInProgress,
                    onClick = {
                        activeDialog = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (readerDialogOpen) {
        MessageReaderDialog(
            title = "阅读模式",
            content = message.content.ifBlank { "[空消息]" },
            onActionMessage = if (message.role == "assistant" || message.role == "system") {
                onSendMessage
            } else {
                null
            },
            onDismiss = { activeDialog = null },
            onCopyRaw = {
                copyMessageContentToClipboard(
                    context = context,
                    role = "assistant_raw",
                    content = message.content.ifBlank { "[空消息]" },
                )
            },
        )
    }
}

@Composable
private fun MessageReaderDialog(
    title: String,
    content: String,
    onActionMessage: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    onCopyRaw: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onCopyRaw) {
                        Text("复制原文")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                        )
                    }
                }
                HorizontalDivider(color = DividerDefaults.color)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .verticalScroll(scrollState),
                ) {
                    ChatMessageReaderContent(
                        content = content,
                        onActionMessage = onActionMessage,
                    )
                }
            }
        }
    }
}

private fun copyMessageContentToClipboard(
    context: Context,
    role: String,
    content: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    val label = when (role) {
        "assistant" -> "assistant_message"
        "assistant_raw" -> "assistant_message_raw"
        "system" -> "system_message"
        else -> "message"
    }
    val clipboardContent = if (role == "assistant_raw") {
        content
    } else {
        extractPlainTextForActions(content)
    }
    clipboard.setPrimaryClip(
        ClipData.newPlainText(label, clipboardContent),
    )
    Toast.makeText(context, "已复制消息", Toast.LENGTH_SHORT).show()
}

private fun shareMessageText(
    context: Context,
    subject: String,
    text: String,
) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(shareIntent, "转发消息").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun extractPlainTextForActions(content: String): String {
    val normalized = content.trim()
    if (normalized.isBlank()) {
        return ""
    }

    val baseText = if (shouldUseBrowserHtmlRenderer(normalized)) {
        HtmlCompat.fromHtml(
            normalized
                .replace(SCRIPT_OR_STYLE_REGEX, " "),
            HtmlCompat.FROM_HTML_MODE_LEGACY,
        ).toString()
    } else {
        normalized
    }

    return baseText
        .replace(MARKDOWN_IMAGE_REGEX, "$1")
        .replace(MARKDOWN_LINK_REGEX, "$1")
        .replace(CODE_FENCE_REGEX, "$1")
        .replace(INLINE_CODE_REGEX, "$1")
        .replace(HEADING_PREFIX_REGEX, "")
        .replace(BULLET_PREFIX_REGEX, "")
        .replace(QUOTE_PREFIX_REGEX, "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .ifBlank { normalized }
}

private fun isStreamingMessage(message: MessageEntity): Boolean =
    message.role == "assistant" && message.status in setOf("draft", "streaming")

private class BubbleSpeechController(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var ready = false
    @Volatile private var textToSpeech: TextToSpeech? = null

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val result = textToSpeech?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ready = false
                }
            }
        }
    }

    fun speak(rawText: String): Result<Unit> = runCatching {
        if (!ready) {
            error("系统朗读暂不可用。")
        }

        val speakableText = extractPlainTextForActions(rawText)
        if (speakableText.isBlank()) {
            error("没有可朗读的文本。")
        }

        val result = textToSpeech?.speak(
            speakableText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "chat_bubble_${System.currentTimeMillis()}",
        ) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            error("系统朗读启动失败。")
        }
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
    }
}

@Composable
private fun rememberBubbleSpeechController(): BubbleSpeechController {
    val context = LocalContext.current
    val controller = remember(context) { BubbleSpeechController(context) }
    DisposableEffect(controller) {
        onDispose {
            controller.shutdown()
        }
    }
    return controller
}

private fun buildDeletePreviewText(content: String): String {
    val normalized = content.trim().replace(Regex("\\s+"), " ")
    val preview = normalized.take(120).ifBlank { "[空消息]" }
    val suffix = if (normalized.length > preview.length) "..." else ""
    return "确定要删除这条消息吗？\n\n$preview$suffix"
}

private fun MessageAttachmentEntity.toChatAttachment(): ChatAttachment =
    ChatAttachment(
        id = id,
        fileId = if (hash.isNotBlank()) {
            "attachment_$hash"
        } else {
            id
        },
        name = name,
        mimeType = mimeType,
        size = size,
        src = src,
        internalFileName = internalFileName,
        internalPath = internalPath,
        hash = hash,
        createdAt = createdAt,
        extractedText = extractedText,
        imageFrames = imageFramesJson
            ?.let(::parseImageFramesJson)
            .orEmpty(),
    )

private fun parseImageFramesJson(raw: String): List<String> =
    runCatching {
        org.json.JSONArray(raw).let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
    }.getOrDefault(emptyList())

private val SCRIPT_OR_STYLE_REGEX = Regex(
    "<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>",
    RegexOption.IGNORE_CASE,
)
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val CODE_FENCE_REGEX = Regex("```(?:[\\w#+.-]+)?\\n?([\\s\\S]*?)```")
private val INLINE_CODE_REGEX = Regex("`([^`]+)`")
private val HEADING_PREFIX_REGEX = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
private val BULLET_PREFIX_REGEX = Regex("(?m)^\\s*(?:[-*+]|\\d+\\.)\\s+")
private val QUOTE_PREFIX_REGEX = Regex("(?m)^\\s*>\\s?")

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

@Composable
private fun AttachmentList(
    attachments: List<MessageAttachmentEntity>,
    onOpenAttachment: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAttachment(attachment.id) }
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
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
            }
        }
    }
}

private fun formatAttachmentSize(size: Long): String =
    when {
        size >= 1024L * 1024L -> String.format("%.1f MB", size.toDouble() / (1024.0 * 1024.0))
        size >= 1024L -> String.format("%.1f KB", size.toDouble() / 1024.0)
        size > 0L -> "$size B"
        else -> "-"
    }
