package com.vcpnative.app.feature.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.chat.compiler.ChatRequestCompiler
import com.vcpnative.app.chat.session.StreamSessionManager
import com.vcpnative.app.chat.skill.SkillInvocationDetector
import com.vcpnative.app.chat.skill.SkillRegistry
import com.vcpnative.app.chat.summary.TopicSummarizer
import com.vcpnative.app.data.attachment.ChatAttachmentManager
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.repository.toChatAttachment
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.model.ChatAttachment
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.CompiledMessage
import com.vcpnative.app.model.StreamSessionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
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
        .observeRecentMessages(topicId, limit = 80)
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

    @Suppress("OPT_IN_USAGE")
    val messageAttachments: StateFlow<List<MessageAttachmentEntity>> = persistedMessages
        .flatMapLatest { msgs ->
            val ids = msgs.map { it.id }
            workspaceRepository.observeMessageAttachmentsByIds(ids)
        }
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
                // 流式/草稿状态绝不清除——防止竞态截断正在流式的消息喵
                if (liveMessage.status in setOf("draft", "streaming")) {
                    return@collect
                }
                // 只在发送中标志已关闭时才考虑清除
                if (_isSending.value) return@collect
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

        // 从仓库读取完整历史，不依赖 UI 窗口的 80 条限制
        val fullHistory = workspaceRepository.loadMessages(topicId)
        val targetIndex = fullHistory.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) {
            error("找不到要创建分支的消息。")
        }

        val targetMessage = fullHistory[targetIndex]
        if (targetMessage.role != "assistant") {
            error("当前仅支持从 AI 回复创建分支。")
        }
        if (targetMessage.status in setOf("draft", "streaming")) {
            error("请在回复完成后再创建分支。")
        }

        val branchMessages = fullHistory.take(targetIndex + 1)
        if (branchMessages.isEmpty()) {
            error("没有可用于创建分支的消息。")
        }

        val currentTopic = workspaceRepository.findTopic(topicId)
        val branchTitle = buildBranchTitle(currentTopic?.title ?: topicId)
        val newTopic = workspaceRepository.createTopic(
            agentId = agentId,
            title = branchTitle,
        )

        // 从仓库直接加载完整话题附件♡ 不能用 UI 层的 messageAttachments.value——
        // 那个只观察 persistedMessages 窗口（80条），分支包含更早消息时附件会丢失喵
        val branchMessageIds = branchMessages.map { it.id }.toSet()
        val allAttachments = workspaceRepository.loadMessageAttachmentsByTopic(topicId)
        val attachmentsByMessageId = allAttachments
            .filter { it.messageId in branchMessageIds }
            .groupBy { it.messageId }
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
            val isStreaming = status in setOf("draft", "streaming")
            workspaceRepository.updateMessage(
                topicId = topicId,
                messageId = compiledRequest.requestId,
                content = content,
                status = status,
                syncCompatHistory = !isStreaming,
                // 流式 checkpoint 不 touch topic，避免每 2s 搅动话题列表排序
                touchTopic = !isStreaming,
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
                    // Throttle UI updates to ~20 fps to reduce GPU/WebView pressure
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
                        // Skill 拦截：检测 AI 回复中的技能标记并构建 follow-up
                        val followUp = if (prepared.skillDepth < PreparedRequest.MAX_SKILL_DEPTH) {
                            tryBuildSkillFollowUp(
                                finalText = finalText,
                                compiledRequest = compiledRequest,
                                prepared = prepared,
                                assistantBuffer = assistantBuffer,
                                persistAssistant = { status, force -> persistAssistant(status, force) },
                            )
                        } else null

                        if (followUp != null) {
                            pendingFollowUp = followUp
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
                            // 成功完成♡ 重置重试计数，给下一次 auto-continue 全新的重试预算喵
                            flowLockRetryCount.set(0)
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

    /**
     * Detect skill invocations in AI output and build follow-up request.
     * Consolidates USE_SKILL / SKILL_EXEC / SKILL_BASH detection into one method.
     */
    private suspend fun tryBuildSkillFollowUp(
        finalText: String,
        compiledRequest: CompiledChatRequest,
        prepared: PreparedRequest,
        assistantBuffer: StringBuilder,
        persistAssistant: suspend (status: String, force: Boolean) -> Unit,
    ): PreparedRequest? {
        if (skillRegistry == null) return null

        // --- USE_SKILL ---
        SkillInvocationDetector.detect(finalText)?.let { invocation ->
            val skillContent = skillRegistry.loadFullContent(invocation.skillName) ?: return@let
            Log.i(TAG, "Skill invocation detected: ${invocation.skillName}")
            val displayText = invocation.cleanedText.ifBlank {
                "<div class=\"skill-chip\"><span class=\"skill-chip-icon\">\uD83D\uDD27</span> 已加载技能 <b>${invocation.skillName}</b></div>"
            }
            // API 侧用纯文本，避免 HTML 污染对话历史
            val apiText = invocation.cleanedText.ifBlank { "[已加载技能: ${invocation.skillName}]" }
            return commitSkillFollowUp(
                displayText = displayText,
                apiText = apiText,
                assistantBuffer = assistantBuffer,
                persistAssistant = persistAssistant,
                compiledRequest = compiledRequest,
                prepared = prepared,
                idPrefix = "msg_skill",
                userFollowUpText = "[系统已加载技能: ${invocation.skillName}]\n\n$skillContent\n\n" +
                    "请根据以上技能指令继续完成用户的原始请求。不要重复已经说过的内容。\n" +
                    "重要：你运行在手机APP环境，没有 Bash/Read/Write/Edit 工具。所有需要执行的命令请用 <<<[SKILL_BASH:${invocation.skillName}]>>>命令<<<[/SKILL_BASH]>>> 标记输出，系统会在本地沙箱执行并返回结果。" +
                    "命令中用 \${CLAUDE_SKILL_DIR} 引用技能安装目录。",
            )
        }

        // --- SKILL_EXEC ---
        SkillInvocationDetector.detectExec(finalText)?.let { exec ->
            val execResult = skillRegistry.executeSkillCommand(exec.skillName, exec.command) ?: return@let
            Log.i(TAG, "Skill exec detected: ${exec.skillName}, command: ${exec.command}")
            val shortCmd = exec.command.take(40).let { if (exec.command.length > 40) "$it…" else it }
            val displayText = exec.cleanedText.ifBlank {
                "<div class=\"skill-chip\"><span class=\"skill-chip-icon\">\uD83D\uDD0D</span> 技能搜索 <b>${exec.skillName}</b>: <code>$shortCmd</code></div>"
            }
            val apiText = exec.cleanedText.ifBlank { "[技能搜索: ${exec.skillName} → $shortCmd]" }
            return commitSkillFollowUp(
                displayText = displayText,
                apiText = apiText,
                assistantBuffer = assistantBuffer,
                persistAssistant = persistAssistant,
                compiledRequest = compiledRequest,
                prepared = prepared,
                idPrefix = "msg_skillexec",
                userFollowUpText = "[系统技能执行结果: ${exec.skillName}]\n\n$execResult\n\n请根据以上搜索结果继续。不要重复已说过的内容。如需执行命令请继续用 <<<[SKILL_BASH:${exec.skillName}]>>>命令<<<[/SKILL_BASH]>>> 标记。",
            )
        }

        // --- SKILL_BASH ---
        if (terminalExecutor == null) return null
        SkillInvocationDetector.detectBash(finalText)?.let { bash ->
            Log.i(TAG, "Skill bash detected: ${bash.skillName}, cmd: ${bash.command}")
            val shortCmd = bash.command.take(50).let { if (bash.command.length > 50) "$it…" else it }
            val displayText = bash.cleanedText.ifBlank {
                "<div class=\"skill-chip\"><span class=\"skill-chip-icon\">⚡</span> 执行命令 <code>$shortCmd</code></div>"
            }
            val apiText = bash.cleanedText.ifBlank { "[执行命令: $shortCmd]" }
            val skillBaseDir = skillRegistry.getBaseDir(bash.skillName) ?: ""
            val resolvedCmd = bash.command
                .replace("\${CLAUDE_SKILL_DIR}", skillBaseDir)
                .replace("\$CLAUDE_SKILL_DIR", skillBaseDir)
            val outputBuilder = StringBuilder()
            val exitCode = terminalExecutor.execute(resolvedCmd) { text, stream ->
                outputBuilder.appendLine(if (stream == "stderr") "[stderr] $text" else text)
            }
            val bashResult = outputBuilder.toString().ifBlank { "(no output)" }
            return commitSkillFollowUp(
                displayText = displayText,
                apiText = apiText,
                assistantBuffer = assistantBuffer,
                persistAssistant = persistAssistant,
                compiledRequest = compiledRequest,
                prepared = prepared,
                idPrefix = "msg_skillbash",
                userFollowUpText = "[系统 Bash 执行结果: ${bash.skillName}]\n" +
                    "命令: $resolvedCmd\n退出码: $exitCode\n输出:\n```\n${bashResult.take(8000)}\n```\n\n" +
                    "请根据以上执行结果继续。不要重复已说过的内容。如需继续执行命令请用 <<<[SKILL_BASH:${bash.skillName}]>>>命令<<<[/SKILL_BASH]>>> 标记。",
            )
        }

        return null
    }

    /** Shared logic: persist current AI text as complete, then build a follow-up request. */
    private suspend fun commitSkillFollowUp(
        displayText: String,
        apiText: String,
        assistantBuffer: StringBuilder,
        persistAssistant: suspend (status: String, force: Boolean) -> Unit,
        compiledRequest: CompiledChatRequest,
        prepared: PreparedRequest,
        idPrefix: String,
        userFollowUpText: String,
    ): PreparedRequest {
        // displayText → 持久化 + WebView 显示（含 chip HTML）
        // apiText → 发给 API 的对话历史（纯文本，不含 HTML）
        assistantBuffer.clear().append(displayText)
        _streamingMessage.value = _streamingMessage.value?.copy(
            content = displayText,
            status = "complete",
            updatedAt = System.currentTimeMillis(),
        )
        persistAssistant("complete", true)
        _streamingMessage.value = null
        val followUpMessages = compiledRequest.messages + listOf(
            CompiledMessage(role = "assistant", textContent = apiText),
            CompiledMessage(role = "user", textContent = userFollowUpText),
        )
        val followUpRequest = compiledRequest.copy(
            requestId = "${idPrefix}_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}",
            messages = followUpMessages,
        )
        return PreparedRequest(
            compiledRequest = followUpRequest,
            pendingUserMessage = null,
            isSkillFollowUp = true,
            skillDepth = prepared.skillDepth + 1,
        )
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
        /** 流式刷新间隔♡ 50ms ≈ 20fps，让文字像打字机一样一点一点冒出来——
         *  太快会榨干 GPU/WebView，太慢会像一坨一坨蹦…50ms 是猫娘精心调教的甜蜜点喵 */
        private const val STREAMING_UI_THROTTLE_MS = 50L
        private const val TAG = "ChatViewModel"

        private fun buildBranchTitle(currentTitle: String): String =
            if (currentTitle.endsWith(" (分支)")) {
                "$currentTitle 2"
            } else {
                "$currentTitle (分支)"
            }

        private fun buildUserMessageId(): String =
            "msg_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${UUID.randomUUID().toString().substring(0, 8)}_user"

        /** 合并消息♡ 复用 package-level mergeMessagesForRender，
         *  额外增加 updatedAt 新鲜度检查——如果 Room 已经有更新的版本就不覆盖喵 */
        private fun mergeMessages(
            storedMessages: List<MessageEntity>,
            liveMessage: MessageEntity?,
        ): List<MessageEntity> {
            if (liveMessage == null) return storedMessages

            val existingIndex = storedMessages.indexOfFirst { it.id == liveMessage.id }
            if (existingIndex >= 0) {
                val persisted = storedMessages[existingIndex]
                if (
                    persisted.content == liveMessage.content &&
                    persisted.status == liveMessage.status &&
                    persisted.updatedAt >= liveMessage.updatedAt
                ) {
                    return storedMessages
                }
            }

            return mergeMessagesForRender(storedMessages, liveMessage)
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
