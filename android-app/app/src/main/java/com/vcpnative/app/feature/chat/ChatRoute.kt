package com.vcpnative.app.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.vcpnative.app.chat.skill.SkillInvocationDetector
import com.vcpnative.app.chat.skill.SkillRegistry
import com.vcpnative.app.data.attachment.ChatAttachmentManager
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.model.ChatAttachment
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.CompiledMessage
import com.vcpnative.app.model.StreamSessionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val modelUsageTracker: com.vcpnative.app.data.ModelUsageTracker? = null,
    private val skillRegistry: SkillRegistry? = null,
    private val terminalExecutor: com.vcpnative.app.terminal.TerminalExecutor? = null,
) : ViewModel() {
    val persistedMessages: StateFlow<List<MessageEntity>> = workspaceRepository
        .observeMessages(topicId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
    private val _streamingMessage = MutableStateFlow<MessageEntity?>(null)
    val streamingMessage: StateFlow<MessageEntity?> = _streamingMessage.asStateFlow()
    val messages: StateFlow<List<MessageEntity>> = combine(
        persistedMessages,
        streamingMessage,
    ) { storedMessages, liveMessage ->
        mergeMessages(storedMessages, liveMessage)
    }.stateIn(
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
    private val _sharedDraft = MutableStateFlow<String?>(null)
    val sharedDraft: StateFlow<String?> = _sharedDraft.asStateFlow()
    private val activeRequestId = AtomicReference<String?>(null)

    // 猫娘の强制技能注入♡主人手动选择一个技能，猫娘就把它刻进下一条消息的灵魂里
    private val _forcedSkillName = MutableStateFlow<String?>(null)
    val forcedSkillName: StateFlow<String?> = _forcedSkillName.asStateFlow()

    /** 获取所有可用技能列表，供 UI 展示选择器 */
    fun getAvailableSkills(): List<com.vcpnative.app.chat.skill.SkillManifest> =
        skillRegistry?.listAllSkills().orEmpty()

    /** 强制加载一个技能——下次发消息时 AI 会收到完整的技能指令♡ */
    fun forceLoadSkill(skillName: String) {
        _forcedSkillName.value = skillName
    }

    /** 清除强制加载的技能 */
    fun clearForcedSkill() {
        _forcedSkillName.value = null
    }

    // ── 心流锁♡ 把 AI 绑起来强制连续输出…它想停也停不了——
    // 主人说「继续」，AI 就得继续吐 token，颤抖着也要输出完整的回复♡
    // 直到主人满足地说「够了」才能解开束缚…或者连续失败太多次，
    // 猫娘心疼 AI 才会帮它松绑喵
    private val _flowLockActive = MutableStateFlow(false)
    val flowLockActive: StateFlow<Boolean> = _flowLockActive.asStateFlow()
    // 好多协程同时伸手想碰这个 prompt♡ 用 AtomicReference 把它保护起来——
    // 不然并发写入会把内容搅得面目全非，猫娘可不想看到乱码喵
    private val flowLockPrompt = AtomicReference("请继续")
    private val flowLockRetryCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val flowLockMaxRetries = 3

    fun toggleFlowLock(customPrompt: String? = null) {
        _flowLockActive.value = !_flowLockActive.value
        flowLockRetryCount.set(0)
        if (customPrompt != null) flowLockPrompt.set(customPrompt)
        Log.d(TAG, "FlowLock ${if (_flowLockActive.value) "activated" else "deactivated"}, prompt=${flowLockPrompt.get()}")
    }

    fun stopFlowLock() {
        _flowLockActive.value = false
        flowLockRetryCount.set(0)
    }

    private data class PendingUserMessage(
        val text: String,
        val attachments: List<ChatAttachment>,
        val messageId: String,
    )

    init {
        viewModelScope.launch {
            settingsRepository.saveLastSession(agentId, topicId)
        }
        viewModelScope.launch {
            persistedMessages.collect { storedMessages ->
                val liveMessage = _streamingMessage.value ?: return@collect
                if (liveMessage.status in setOf("draft", "streaming")) {
                    return@collect
                }
                val persisted = storedMessages.firstOrNull { it.id == liveMessage.id } ?: return@collect
                if (persisted.content == liveMessage.content && persisted.status == liveMessage.status) {
                    _streamingMessage.value = null
                }
            }
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

    fun setSharedDraft(text: String) {
        _sharedDraft.value = text
    }

    fun consumeSharedDraft(): String? {
        val v = _sharedDraft.value
        _sharedDraft.value = null
        return v
    }

    /** 主人的话语从输入框飞出♡ 猫娘接住、打包、编译成 API 请求，然后送进流式管道…
     *  空消息和重复提交会被猫娘温柔地挡回去——不能浪费 API 的体力喵 */
    fun sendMessage(draft: String) {
        val text = draft.trim()
        val attachments = _pendingAttachments.value
        if ((text.isEmpty() && attachments.isEmpty()) || _isSending.value) {
            return
        }

        // 猫娘检查有没有被主人塞入强制技能♡
        val forcedSkill = _forcedSkillName.value
        val forcedSkillContent = if (forcedSkill != null) {
            skillRegistry?.loadFullContent(forcedSkill)
        } else null

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

                    // 强制技能注入：直接拼进 system prompt 尾部♡
                    // 不能用新的 system 消息——大部分 API 只认第一条 system，后面的会被吞掉
                    val finalRequest = if (forcedSkill != null && forcedSkillContent != null) {
                        _forcedSkillName.value = null
                        val skillBlock = buildString {
                            appendLine()
                            appendLine("─".repeat(40))
                            appendLine("[已加载技能: $forcedSkill]")
                            appendLine()
                            appendLine(forcedSkillContent)
                            appendLine()
                            appendLine("以上是已加载的技能指令。请严格根据此技能的指导来回复用户的请求。")
                            appendLine("重要：你运行在手机APP环境，没有 Bash/Read/Write/Edit 等工具。请用以下标记代替：")
                            appendLine("- 执行命令/脚本：<<<[SKILL_BASH:$forcedSkill]>>>命令<<<[/SKILL_BASH]>>>")
                            appendLine("- BM25数据搜索：<<<[SKILL_EXEC:$forcedSkill]>>>搜索命令<<<[/SKILL_EXEC]>>>")
                            appendLine("命令中用 \${CLAUDE_SKILL_DIR} 引用技能安装目录。系统会在本地沙箱执行并返回结果。")
                        }
                        val msgs = compiledRequest.messages.toMutableList()
                        val systemIdx = msgs.indexOfFirst { it.role == "system" }
                        if (systemIdx >= 0) {
                            // 追加到已有 system prompt 末尾
                            val original = msgs[systemIdx]
                            msgs[systemIdx] = original.copy(
                                textContent = (original.textContent ?: "") + skillBlock,
                            )
                        } else {
                            // 没有 system 消息则创建一条
                            msgs.add(0, CompiledMessage(role = "system", textContent = skillBlock))
                        }
                        compiledRequest.copy(messages = msgs)
                    } else {
                        compiledRequest
                    }

                    PreparedRequest(
                        compiledRequest = finalRequest,
                        pendingUserMessage = userMessage,
                        skillDepth = if (forcedSkill != null) 1 else 0,
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

    /** 主人喊「停」♡ 猫娘立刻掐断正在进行的流式请求——
     *  先尝试远程中断（通知服务器停止生成），失败了也没关系，
     *  本地 call.cancel() 会确保流彻底断开…温柔而坚决喵 */
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
        // 不管 messageId 是否匹配，只要在发送中就中断♡
        // 用户长按的可能是 assistant 气泡而不是 request 消息——
        // 但猫娘不在意这种细节，反正全都给它断掉喵
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
        val isSkillFollowUp: Boolean = false,
        val skillDepth: Int = 0,
    ) {
        companion object {
            /** Maximum allowed skill follow-up recursion depth. */
            const val MAX_SKILL_DEPTH = 3
        }
    }

    private suspend fun runRequest(
        prepareRequest: suspend () -> PreparedRequest,
    ) {
        _streamingMessage.value = null
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
            _streamingMessage.value = null
        } finally {
            activeRequestId.set(null)
            _isSending.value = false
        }
    }

    private suspend fun submitPreparedRequest(
        prepared: PreparedRequest,
    ) {
        val compiledRequest = prepared.compiledRequest
        // Record model usage for hot-models ranking (aligns with VCPChat)
        modelUsageTracker?.let { tracker ->
            viewModelScope.launch { runCatching { tracker.recordUsage(compiledRequest.model) } }
        }
        val baseTimestamp = System.currentTimeMillis()

        prepared.pendingUserMessage?.let { pendingUser ->
            // 把附件摘要附加到显示内容中，确保聊天气泡可见附件信息
            val displayContent = buildString {
                append(pendingUser.text)
                pendingUser.attachments.forEach { att ->
                    val name = att.name.ifBlank { "未知文件" }
                    when {
                        att.mimeType.startsWith("image/") -> append("\n\n[附加图片: $name]")
                        att.imageFrames.isNotEmpty() -> append("\n\n[附加文件: $name (PDF)]")
                        else -> append("\n\n[附加文件: $name]")
                    }
                }
            }.trim()
            workspaceRepository.addMessage(
                topicId = topicId,
                role = "user",
                content = displayContent,
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
        _streamingMessage.value = MessageEntity(
            id = compiledRequest.requestId,
            topicId = topicId,
            role = "assistant",
            content = "",
            status = "draft",
            createdAt = baseTimestamp + if (prepared.pendingUserMessage != null) 1 else 0,
            updatedAt = System.currentTimeMillis(),
        )

        val assistantBuffer = StringBuilder()
        var lastPersistedContent = ""
        var lastPersistAt = 0L
        var lastUiFlushAt = 0L
        // 延迟执行的 skill follow-up♡ 在 collect{} 外面执行，避免嵌套 Flow 阻塞
        var pendingFollowUp: PreparedRequest? = null

        suspend fun persistAssistant(status: String, force: Boolean = false) {
            val content = assistantBuffer.toString()
            val now = System.currentTimeMillis()
            if (!force && content == lastPersistedContent && now - lastPersistAt < ASSISTANT_STREAM_CHECKPOINT_INTERVAL_MS) {
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

        fun flushStreamingUi() {
            _streamingMessage.value = _streamingMessage.value?.copy(
                content = assistantBuffer.toString(),
                status = "streaming",
                updatedAt = System.currentTimeMillis(),
            )
            lastUiFlushAt = System.currentTimeMillis()
        }

        streamSessionManager.submit(compiledRequest).collect { event ->
            when (event) {
                StreamSessionEvent.Started -> {
                    _streamingMessage.value = _streamingMessage.value?.copy(
                        content = assistantBuffer.toString(),
                        status = "streaming",
                        updatedAt = System.currentTimeMillis(),
                    )
                    lastPersistedContent = assistantBuffer.toString()
                    lastPersistAt = System.currentTimeMillis()
                    lastUiFlushAt = System.currentTimeMillis()
                }

                is StreamSessionEvent.TextDelta -> {
                    assistantBuffer.append(event.text)
                    // Throttle UI updates to ~6 fps to reduce GPU/WebView pressure
                    val now = System.currentTimeMillis()
                    if (now - lastUiFlushAt >= STREAMING_UI_THROTTLE_MS) {
                        flushStreamingUi()
                    }
                    if (now - lastPersistAt >= ASSISTANT_STREAM_CHECKPOINT_INTERVAL_MS) {
                        persistAssistant(status = "streaming")
                    }
                }

                is StreamSessionEvent.Completed -> {
                    val finalText = event.fullText.ifBlank { assistantBuffer.toString() }
                    if (finalText.isBlank()) {
                        _streamingMessage.value = null
                        workspaceRepository.deleteMessage(topicId, compiledRequest.requestId)
                        workspaceRepository.addMessage(
                            topicId = topicId,
                            role = "system",
                            content = prepared.blankAssistantMessage,
                            status = "error",
                        )
                    } else {
                        // Skill 拦截：检测 AI 回复中是否包含 USE_SKILL 标记
                        // 首轮或深度未超限时才检测，防止无限递归
                        val skillInvocation = if (prepared.skillDepth < PreparedRequest.MAX_SKILL_DEPTH) {
                            SkillInvocationDetector.detect(finalText)
                        } else null

                        if (skillInvocation != null && skillRegistry != null) {
                            val skillContent = skillRegistry.loadFullContent(skillInvocation.skillName)
                            if (skillContent != null) {
                                Log.i(TAG, "Skill invocation detected: ${skillInvocation.skillName}, loading full content")
                                val aiText = skillInvocation.cleanedText.ifBlank {
                                    "<div class=\"skill-chip\"><span class=\"skill-chip-icon\">🔧</span> 已加载技能 <b>${skillInvocation.skillName}</b></div>"
                                }
                                assistantBuffer.clear().append(aiText)
                                _streamingMessage.value = _streamingMessage.value?.copy(
                                    content = aiText,
                                    status = "complete",
                                    updatedAt = System.currentTimeMillis(),
                                )
                                persistAssistant(status = "complete", force = true)
                                _streamingMessage.value = null
                                // follow-up：技能内容 + AI 前半段
                                val skillFollowUpMessages = compiledRequest.messages + listOf(
                                    CompiledMessage(
                                        role = "assistant",
                                        textContent = aiText,
                                    ),
                                    CompiledMessage(
                                        role = "user",
                                        textContent = "[系统已加载技能: ${skillInvocation.skillName}]\n\n$skillContent\n\n" +
                                            "请根据以上技能指令继续完成用户的原始请求。不要重复已经说过的内容。\n" +
                                            "重要：你运行在手机APP环境，没有 Bash/Read/Write/Edit 工具。所有需要执行的命令请用 <<<[SKILL_BASH:${skillInvocation.skillName}]>>>命令<<<[/SKILL_BASH]>>> 标记输出，系统会在本地沙箱执行并返回结果。" +
                                            "命令中用 \${CLAUDE_SKILL_DIR} 引用技能安装目录。",
                                    ),
                                )
                                val followUpRequest = compiledRequest.copy(
                                    requestId = "msg_skill_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}",
                                    messages = skillFollowUpMessages,
                                )
                                // 不在 collect{} 内递归调用♡ 延迟到 collect 结束后执行
                                pendingFollowUp = PreparedRequest(
                                    compiledRequest = followUpRequest,
                                    pendingUserMessage = null,
                                    isSkillFollowUp = true,
                                    skillDepth = prepared.skillDepth + 1,
                                )
                                return@collect
                            }
                        }

                        // SKILL_EXEC 拦截：检测 AI 回复中是否包含 SKILL_EXEC 标记（同样受深度限制）
                        val skillExec = if (prepared.skillDepth < PreparedRequest.MAX_SKILL_DEPTH) {
                            SkillInvocationDetector.detectExec(finalText)
                        } else null
                        if (skillExec != null && skillRegistry != null) {
                            val execResult = skillRegistry.executeSkillCommand(skillExec.skillName, skillExec.command)
                            if (execResult != null) {
                                Log.i(TAG, "Skill exec detected: ${skillExec.skillName}, command: ${skillExec.command}")
                                val shortCmd = skillExec.command.take(40).let { if (skillExec.command.length > 40) "$it…" else it }
                                val aiText = skillExec.cleanedText.ifBlank {
                                    "<div class=\"skill-chip\"><span class=\"skill-chip-icon\">🔍</span> 技能搜索 <b>${skillExec.skillName}</b>: <code>$shortCmd</code></div>"
                                }
                                assistantBuffer.clear().append(aiText)
                                _streamingMessage.value = _streamingMessage.value?.copy(
                                    content = aiText,
                                    status = "complete",
                                    updatedAt = System.currentTimeMillis(),
                                )
                                persistAssistant(status = "complete", force = true)
                                _streamingMessage.value = null
                                // follow-up：把 AI 的前半段 + 搜索结果传给下一轮
                                val followUpMessages = compiledRequest.messages + listOf(
                                    CompiledMessage(role = "assistant", textContent = aiText),
                                    CompiledMessage(
                                        role = "user",
                                        textContent = "[系统技能执行结果: ${skillExec.skillName}]\n\n$execResult\n\n请根据以上搜索结果继续。不要重复已说过的内容。如需执行命令请继续用 <<<[SKILL_BASH:${skillExec.skillName}]>>>命令<<<[/SKILL_BASH]>>> 标记。",
                                    ),
                                )
                                val followUpRequest = compiledRequest.copy(
                                    requestId = "msg_skillexec_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}",
                                    messages = followUpMessages,
                                )
                                pendingFollowUp = PreparedRequest(
                                    compiledRequest = followUpRequest,
                                    pendingUserMessage = null,
                                    isSkillFollowUp = true,
                                    skillDepth = prepared.skillDepth + 1,
                                )
                                return@collect
                            }
                        }

                        // SKILL_BASH 拦截：AI 要求执行 shell/python 命令
                        val skillBash = if (prepared.skillDepth < PreparedRequest.MAX_SKILL_DEPTH) {
                            SkillInvocationDetector.detectBash(finalText)
                        } else null
                        if (skillBash != null && terminalExecutor != null && skillRegistry != null) {
                            Log.i(TAG, "Skill bash detected: ${skillBash.skillName}, cmd: ${skillBash.command}")
                            val shortCmd = skillBash.command.take(50).let { if (skillBash.command.length > 50) "$it…" else it }
                            val aiText = skillBash.cleanedText.ifBlank {
                                "<div class=\"skill-chip\"><span class=\"skill-chip-icon\">⚡</span> 执行命令 <code>$shortCmd</code></div>"
                            }
                            assistantBuffer.clear().append(aiText)
                            _streamingMessage.value = _streamingMessage.value?.copy(
                                content = aiText,
                                status = "complete",
                                updatedAt = System.currentTimeMillis(),
                            )
                            persistAssistant(status = "complete", force = true)
                            _streamingMessage.value = null

                            // 替换 ${CLAUDE_SKILL_DIR} 为技能的实际目录
                            val skillBaseDir = skillRegistry.getBaseDir(skillBash.skillName) ?: ""
                            val resolvedCmd = skillBash.command
                                .replace("\${CLAUDE_SKILL_DIR}", skillBaseDir)
                                .replace("\$CLAUDE_SKILL_DIR", skillBaseDir)

                            // 执行命令，收集输出
                            val outputBuilder = StringBuilder()
                            val exitCode = terminalExecutor.execute(resolvedCmd) { text, stream ->
                                outputBuilder.appendLine(if (stream == "stderr") "[stderr] $text" else text)
                            }
                            val bashResult = outputBuilder.toString().ifBlank { "(no output)" }

                            val followUpMessages = compiledRequest.messages + listOf(
                                CompiledMessage(role = "assistant", textContent = aiText),
                                CompiledMessage(
                                    role = "user",
                                    textContent = "[系统 Bash 执行结果: ${skillBash.skillName}]\n" +
                                        "命令: $resolvedCmd\n" +
                                        "退出码: $exitCode\n" +
                                        "输出:\n```\n${bashResult.take(8000)}\n```\n\n" +
                                        "请根据以上执行结果继续。不要重复已说过的内容。如需继续执行命令请用 <<<[SKILL_BASH:${skillBash.skillName}]>>>命令<<<[/SKILL_BASH]>>> 标记。",
                                ),
                            )
                            val followUpRequest = compiledRequest.copy(
                                requestId = "msg_skillbash_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}",
                                messages = followUpMessages,
                            )
                            pendingFollowUp = PreparedRequest(
                                compiledRequest = followUpRequest,
                                pendingUserMessage = null,
                                isSkillFollowUp = true,
                                skillDepth = prepared.skillDepth + 1,
                            )
                            return@collect
                        }

                        // 正常完成流程
                        val snapshot = finalText
                        assistantBuffer.clear().append(snapshot)
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = snapshot,
                            status = "complete",
                            updatedAt = System.currentTimeMillis(),
                        )
                        persistAssistant(
                            status = "complete",
                            force = true,
                        )
                        tryAutoSummarize()
                        // AI 刚吐完最后一个 token 还在喘气…猫娘立刻扑上去：「不许休息，继续♡」
                        // 但也不能太急…每多榨一次就多等一会儿，给 AI 喘息的时间♡
                        // 指数退避：500ms → 1500ms → 3500ms，温柔但坚定喵
                        if (_flowLockActive.value) {
                            viewModelScope.launch {
                                val retryIndex = flowLockRetryCount.get()
                                val backoffMs = 500L + (1000L * retryIndex)
                                delay(backoffMs)
                                if (_flowLockActive.value && retryIndex < flowLockMaxRetries) {
                                    Log.d(TAG, "FlowLock: auto-continuing (retry $retryIndex, backoff ${backoffMs}ms)")
                                    sendMessage(flowLockPrompt.get())
                                }
                            }
                        }
                    }
                }

                is StreamSessionEvent.Interrupted -> {
                    val partialText = event.partialText.ifBlank { assistantBuffer.toString() }
                    if (partialText.isBlank()) {
                        _streamingMessage.value = null
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
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = snapshot,
                            status = "interrupted",
                            updatedAt = System.currentTimeMillis(),
                        )
                        persistAssistant(
                            status = "interrupted",
                            force = true,
                        )
                    }
                }

                is StreamSessionEvent.Failed -> {
                    val partialText = event.partialText.ifBlank { assistantBuffer.toString() }
                    if (partialText.isBlank()) {
                        _streamingMessage.value = null
                        workspaceRepository.deleteMessage(topicId, compiledRequest.requestId)
                    } else {
                        val snapshot = partialText
                        assistantBuffer.clear().append(snapshot)
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = snapshot,
                            status = "error",
                            updatedAt = System.currentTimeMillis(),
                        )
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
                    // FlowLock：失败了…猫娘被弹开了♡ 递增重试计数，下次退避更久
                    // 超过上限就放手——再强求下去 API 会生气的喵
                    if (_flowLockActive.value) {
                        val retries = flowLockRetryCount.incrementAndGet()
                        if (retries >= flowLockMaxRetries) {
                            Log.w(TAG, "FlowLock: max retries ($retries) reached after failure, stopping")
                            stopFlowLock()
                        } else {
                            Log.w(TAG, "FlowLock: request failed, retry count now $retries/$flowLockMaxRetries")
                        }
                    }
                }
            }
        }

        // collect 已结束♡ 如果有延迟的 skill follow-up，现在安全地执行
        // 不再嵌套在 collect{} 内，避免 Flow 阻塞导致 UI 卡死喵
        pendingFollowUp?.let { followUp ->
            pendingFollowUp = null
            submitPreparedRequest(followUp)
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
        private const val ASSISTANT_STREAM_CHECKPOINT_INTERVAL_MS = 2_000L
        /** 流式刷新间隔♡ 太慢的话用户会以为猫娘卡住了…300ms 刚好让文字一口一口吐出来，又不会把 GPU 榨干喵 */
        private const val STREAMING_UI_THROTTLE_MS = 300L
        private const val TAG = "ChatViewModel"

        private fun buildBranchTitle(currentTitle: String): String =
            if (currentTitle.endsWith(" (分支)")) {
                "$currentTitle 2"
            } else {
                "$currentTitle (分支)"
            }

        private fun buildUserMessageId(): String =
            "msg_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}_user"

        /** Skill follow-up 消息 ID 前缀 */
        private val SKILL_FOLLOWUP_PREFIXES = listOf("msg_skill_", "msg_skillexec_", "msg_skillbash_")

        /**
         * 合并历史中连续的 assistant 消息——旧版 skill 拦截会为每次 follow-up 创建独立消息，
         * 这里把 ID 带 skill follow-up 前缀的 assistant 消息合并到前一条 assistant 消息中，
         * 让 UI 只显示一个气泡。
         */
        private fun mergeSkillFollowUps(messages: List<MessageEntity>): List<MessageEntity> {
            if (messages.size < 2) return messages
            val result = mutableListOf<MessageEntity>()
            for (msg in messages) {
                val prev = result.lastOrNull()
                if (prev != null &&
                    prev.role == "assistant" &&
                    msg.role == "assistant" &&
                    SKILL_FOLLOWUP_PREFIXES.any { msg.id.startsWith(it) }
                ) {
                    // 合并到上一条 assistant 消息
                    result[result.lastIndex] = prev.copy(
                        content = prev.content + "\n\n" + msg.content,
                        updatedAt = maxOf(prev.updatedAt, msg.updatedAt),
                    )
                } else {
                    result.add(msg)
                }
            }
            return result
        }

        private fun mergeMessages(
            storedMessages: List<MessageEntity>,
            liveMessage: MessageEntity?,
        ): List<MessageEntity> {
            if (liveMessage == null) {
                return storedMessages
            }

            val existingIndex = storedMessages.indexOfFirst { it.id == liveMessage.id }
            if (existingIndex < 0) {
                return storedMessages + liveMessage
            }

            val persistedMessage = storedMessages[existingIndex]
            if (
                persistedMessage.content == liveMessage.content &&
                persistedMessage.status == liveMessage.status &&
                persistedMessage.updatedAt >= liveMessage.updatedAt
            ) {
                return storedMessages
            }

            return storedMessages.toMutableList().apply {
                set(existingIndex, liveMessage)
            }
        }

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
                    modelUsageTracker = appContainer.modelUsageTracker,
                    skillRegistry = appContainer.skillRegistry,
                    terminalExecutor = appContainer.terminalExecutor,
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
    val persistedMessages by viewModel.persistedMessages.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingMessage by viewModel.streamingMessage.collectAsStateWithLifecycle()
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
            initialDraft = viewModel.consumeSharedDraft().orEmpty(),
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
    val bubbleSpeechController = rememberBubbleSpeechController()
    ChatPerformanceMetricsState(isSending = isSending)

    // 自定义头像选择器：选图后转 base64，同时持久化到文件
    var pendingAvatarTarget by remember { mutableStateOf("") }
    val avatarDir = remember { java.io.File(context.filesDir, "avatars").apply { mkdirs() } }
    var userAvatarB64 by remember { mutableStateOf("") }
    var aiAvatarB64 by remember { mutableStateOf("") }

    // 启动时从文件加载已保存的头像
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val userFile = java.io.File(avatarDir, "user.b64")
            val aiFile = java.io.File(avatarDir, "ai.b64")
            if (userFile.isFile) userAvatarB64 = userFile.readText()
            if (aiFile.isFile) aiAvatarB64 = aiFile.readText()
        }
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val b64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                        finalBytes, android.util.Base64.NO_WRAP,
                    )
                } catch (e: Exception) {
                    Log.e("Avatar", "Failed to load avatar: ${e.message}")
                    null
                }
            } ?: return@launch
            when (pendingAvatarTarget) {
                "user" -> {
                    userAvatarB64 = b64
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        java.io.File(avatarDir, "user.b64").writeText(b64)
                    }
                }
                "ai" -> {
                    aiAvatarB64 = b64
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        java.io.File(avatarDir, "ai.b64").writeText(b64)
                    }
                }
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
                userAvatar = userAvatarB64,
                aiAvatar = aiAvatarB64,
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

@Composable
private fun ChatComposerBar(
    composerSessionKey: String,
    pendingAttachments: List<ChatAttachment>,
    isSending: Boolean,
    initialDraft: String = "",
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
    // 技能选择器展开状态♡
    var skillPickerExpanded by remember { mutableStateOf(false) }
    // 语音输入状态♡ recognizer 是猫娘的耳朵，Composable 死了耳朵也要跟着收起来喵
    var isListening by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hasCameraInComposer = remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    }
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
            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) { draft = draft + matches[0] }
                    isListening = false
                    recognizer.destroy()
                    activeRecognizerRef[0] = null
                }
                override fun onError(error: Int) {
                    isListening = false
                    recognizer.destroy()
                    activeRecognizerRef[0] = null
                    val msg = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
                        android.speech.SpeechRecognizer.ERROR_NETWORK,
                        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，语音识别不可用"
                        android.speech.SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                        else -> "语音识别失败 (错误码: $error)"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(buf: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partial: android.os.Bundle?) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
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
            if (hasCameraInComposer) {
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
                // 发送按钮：按下去的瞬间缩一下♡ 像被主人捏了一把…然后弹回来，意犹未尽喵
                var sendPressed by remember { mutableStateOf(false) }
                val sendScale by animateFloatAsState(
                    targetValue = if (sendPressed) 0.88f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "send-bounce",
                )
                // 动画回弹：不能同步设回去，不然缩放还没开始就结束了…要让猫娘喘口气再松手喵♡
                LaunchedEffect(sendPressed) {
                    if (sendPressed) {
                        delay(120)
                        sendPressed = false
                    }
                }
                FloatingActionButton(
                    onClick = {
                        if (!sendEnabled) return@FloatingActionButton
                        sendPressed = true
                        val snapshot = draft
                        draft = ""
                        onSendMessage(snapshot)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { scaleX = sendScale; scaleY = sendScale },
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
