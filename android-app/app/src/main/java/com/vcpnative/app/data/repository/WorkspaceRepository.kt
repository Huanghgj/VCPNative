package com.vcpnative.app.data.repository

import androidx.room.withTransaction
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.files.AtomicFileWriter
import com.vcpnative.app.data.room.AgentDao
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.data.room.AppDatabase
import com.vcpnative.app.data.room.MessageAttachmentDao
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageDao
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.data.room.RecentChatRow
import com.vcpnative.app.data.room.RegexRuleDao
import com.vcpnative.app.data.room.RegexRuleEntity
import com.vcpnative.app.data.room.TopicDao
import com.vcpnative.app.data.room.TopicEntity
import com.vcpnative.app.model.ChatAttachment
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

data class HomeRecentChat(
    val agent: AgentEntity,
    val topic: TopicEntity,
)

data class HomeOverview(
    val agents: List<AgentEntity>,
    val recentChats: List<HomeRecentChat>,
)

interface WorkspaceRepository {
    fun observeAgents(): Flow<List<AgentEntity>>

    fun observeHomeOverview(recentLimit: Int = 6): Flow<HomeOverview>

    fun observeTopics(agentId: String): Flow<List<TopicEntity>>

    fun observeMessages(topicId: String): Flow<List<MessageEntity>>

    /** 只观察最近 [limit] 条消息（UI 展示用，省内存省电喵） */
    fun observeRecentMessages(topicId: String, limit: Int = 80): Flow<List<MessageEntity>>

    fun observeMessageAttachments(topicId: String): Flow<List<MessageAttachmentEntity>>

    /** 只观察指定消息 ID 集合的附件（配合 UI 消息窗口，避免全话题查询） */
    fun observeMessageAttachmentsByIds(messageIds: List<String>): Flow<List<MessageAttachmentEntity>>

    suspend fun createPlaceholderAgent(): AgentEntity

    suspend fun createPlaceholderTopic(agentId: String): TopicEntity

    suspend fun createTopic(
        agentId: String,
        title: String,
    ): TopicEntity

    suspend fun addMessage(
        topicId: String,
        role: String,
        content: String,
        status: String = "complete",
        messageId: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        attachments: List<ChatAttachment> = emptyList(),
    ): MessageEntity

    suspend fun updateMessage(
        topicId: String,
        messageId: String,
        content: String,
        status: String,
        syncCompatHistory: Boolean = true,
        touchTopic: Boolean = true,
    )

    suspend fun deleteMessage(topicId: String, messageId: String)

    suspend fun deleteMessagesFrom(topicId: String, createdAt: Long)

    suspend fun saveAgent(agent: AgentEntity): AgentEntity

    suspend fun deleteAgent(agentId: String)

    suspend fun findAgent(agentId: String): AgentEntity?

    suspend fun findTopic(topicId: String): TopicEntity?

    suspend fun renameTopic(topicId: String, newTitle: String)

    suspend fun deleteTopic(topicId: String)

    suspend fun findMessage(messageId: String): MessageEntity?

    suspend fun findMessageAttachment(attachmentId: String): MessageAttachmentEntity?

    suspend fun loadMessages(topicId: String): List<MessageEntity>

    /** 加载整个话题的所有附件（不受 UI 消息窗口限制） */
    suspend fun loadMessageAttachmentsByTopic(topicId: String): List<MessageAttachmentEntity>

    /** Replace all messages in a topic from a JSON array (used by voicechat save). */
    suspend fun replaceMessages(topicId: String, messagesJson: org.json.JSONArray)

    /** 分页加载消息。offset=0 表示最旧的消息开始。返回按 createdAt ASC 排序。 */
    suspend fun loadMessagesPaged(topicId: String, limit: Int = 50, offset: Int = 0): List<MessageEntity>

    /** 获取话题下消息总数，用于分页 UI 计算。 */
    suspend fun countMessages(topicId: String): Int

    suspend fun recentMessages(topicId: String, limit: Int = 12): List<MessageEntity>

    suspend fun loadRegexRules(agentId: String): List<RegexRuleEntity>

    /** 搜索指定 agent 下包含 query 的 topicId 列表 */
    suspend fun searchTopicIds(agentId: String, query: String): List<String>

    /** 全局搜索消息内容 */
    suspend fun searchMessages(query: String, limit: Int = 50): List<MessageEntity>
}

class RoomWorkspaceRepository(
    private val database: AppDatabase,
    private val agentDao: AgentDao,
    private val topicDao: TopicDao,
    private val messageDao: MessageDao,
    private val messageAttachmentDao: MessageAttachmentDao,
    private val regexRuleDao: RegexRuleDao,
    private val fileStore: AppFileStore,
) : WorkspaceRepository {
    override fun observeAgents(): Flow<List<AgentEntity>> = agentDao.observeAll()

    override fun observeHomeOverview(recentLimit: Int): Flow<HomeOverview> =
        combine(
            agentDao.observeAll(),
            topicDao.observeLatestTopicPerAgent(recentLimit),
        ) { agents, recentChats ->
            HomeOverview(
                agents = agents,
                recentChats = recentChats.map(RecentChatRow::toHomeRecentChat),
            )
        }

    override fun observeTopics(agentId: String): Flow<List<TopicEntity>> =
        topicDao.observeByAgent(agentId)

    override fun observeMessages(topicId: String): Flow<List<MessageEntity>> =
        messageDao.observeByTopic(topicId)

    override fun observeRecentMessages(topicId: String, limit: Int): Flow<List<MessageEntity>> =
        messageDao.observeRecent(topicId, limit)

    override fun observeMessageAttachments(topicId: String): Flow<List<MessageAttachmentEntity>> =
        messageAttachmentDao.observeByTopic(topicId)

    override fun observeMessageAttachmentsByIds(messageIds: List<String>): Flow<List<MessageAttachmentEntity>> =
        if (messageIds.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else messageAttachmentDao.observeByMessageIds(messageIds)

    override suspend fun createPlaceholderAgent(): AgentEntity {
        val nextIndex = agentDao.count() + 1
        val timestamp = System.currentTimeMillis()
        val placeholderPrompt = "你是 占位 Agent $nextIndex。"
        val agent = AgentEntity(
            id = buildId("agent", timestamp),
            name = "占位 Agent $nextIndex",
            systemPrompt = placeholderPrompt,
            promptMode = "original",
            originalSystemPrompt = placeholderPrompt,
            sortOrder = nextIndex,
            updatedAt = timestamp,
        )
        agentDao.insert(agent)
        syncCompatAgentSnapshot(agent.id)
        return agent
    }

    override suspend fun createPlaceholderTopic(agentId: String): TopicEntity {
        val nextIndex = topicDao.countByAgent(agentId) + 1
        val timestamp = System.currentTimeMillis()
        val titleSuffix = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
        return createTopic(
            agentId = agentId,
            title = "新话题 $nextIndex · $titleSuffix",
        )
    }

    override suspend fun createTopic(
        agentId: String,
        title: String,
    ): TopicEntity {
        val timestamp = System.currentTimeMillis()
        val sourceTopicId = buildId("topic", timestamp)
        val topic = TopicEntity(
            id = sourceTopicId,
            agentId = agentId,
            sourceTopicId = sourceTopicId,
            title = title,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        topicDao.insert(topic)
        syncCompatAgentSnapshot(agentId)
        syncCompatHistory(topic)
        return topic
    }

    override suspend fun addMessage(
        topicId: String,
        role: String,
        content: String,
        status: String,
        messageId: String?,
        createdAt: Long,
        attachments: List<ChatAttachment>,
    ): MessageEntity {
        val timestamp = System.currentTimeMillis()
        val message = MessageEntity(
            id = messageId ?: buildId("msg_${role}", timestamp),
            topicId = topicId,
            role = role,
            content = content,
            status = status,
            createdAt = createdAt,
            updatedAt = timestamp,
        )
        database.withTransaction {
            messageDao.insert(message)
            attachments.forEachIndexed { index, attachment ->
                messageAttachmentDao.insert(
                    attachment.toEntity(
                        messageId = message.id,
                        attachmentOrder = index,
                    ),
                )
            }
            topicDao.touch(topicId, timestamp)
        }
        // draft/streaming 中间态不导出 compat 历史——省 IO 喵
        val isIntermediate = status in setOf("draft", "streaming")
        if (!isIntermediate) {
            syncCompatHistory(topicId, debounce = false)
        }
        return message
    }

    override suspend fun updateMessage(
        topicId: String,
        messageId: String,
        content: String,
        status: String,
        syncCompatHistory: Boolean,
        touchTopic: Boolean,
    ) {
        val timestamp = System.currentTimeMillis()
        database.withTransaction {
            messageDao.updateContent(
                messageId = messageId,
                content = content,
                status = status,
                updatedAt = timestamp,
            )
            if (touchTopic) {
                topicDao.touch(topicId, timestamp)
            }
        }
        if (syncCompatHistory) {
            val isStreaming = status in setOf("draft", "streaming")
            syncCompatHistory(topicId, debounce = isStreaming)
        }
    }

    override suspend fun deleteMessage(topicId: String, messageId: String) {
        val timestamp = System.currentTimeMillis()
        database.withTransaction {
            messageDao.deleteById(messageId)
            topicDao.touch(topicId, timestamp)
        }
        syncCompatHistory(topicId)
    }

    override suspend fun deleteMessagesFrom(topicId: String, createdAt: Long) {
        val timestamp = System.currentTimeMillis()
        database.withTransaction {
            messageDao.deleteFrom(topicId, createdAt)
            topicDao.touch(topicId, timestamp)
        }
        syncCompatHistory(topicId)
    }

    override suspend fun saveAgent(agent: AgentEntity): AgentEntity {
        val savedAgent = agent.copy(updatedAt = System.currentTimeMillis())
        database.withTransaction {
            if (agentDao.findById(savedAgent.id) == null) {
                agentDao.insert(savedAgent)
            } else {
                agentDao.update(savedAgent)
            }
        }
        syncCompatAgentSnapshot(savedAgent.id)
        return savedAgent
    }

    override suspend fun deleteAgent(agentId: String) {
        agentDao.deleteById(agentId)
        withContext(Dispatchers.IO) {
            val compatDir = fileStore.compatAgentDir(agentId)
            if (compatDir.exists()) {
                compatDir.deleteRecursively()
            }
        }
    }

    override suspend fun findAgent(agentId: String): AgentEntity? = agentDao.findById(agentId)

    override suspend fun findTopic(topicId: String): TopicEntity? = topicDao.findById(topicId)

    override suspend fun renameTopic(topicId: String, newTitle: String) {
        val topic = topicDao.findById(topicId) ?: error("找不到要重命名的话题。")
        topicDao.updateTitle(topicId, newTitle, System.currentTimeMillis())
        syncCompatAgentSnapshot(topic.agentId)
    }

    override suspend fun deleteTopic(topicId: String) {
        val topic = topicDao.findById(topicId) ?: error("找不到要删除的话题。")
        topicDao.delete(topicId)
        syncCompatAgentSnapshot(topic.agentId)
    }

    override suspend fun findMessage(messageId: String): MessageEntity? =
        messageDao.findById(messageId)

    override suspend fun findMessageAttachment(attachmentId: String): MessageAttachmentEntity? =
        messageAttachmentDao.findById(attachmentId)

    override suspend fun loadMessages(topicId: String): List<MessageEntity> =
        messageDao.loadByTopic(topicId)

    override suspend fun loadMessageAttachmentsByTopic(topicId: String): List<MessageAttachmentEntity> =
        messageAttachmentDao.loadByTopic(topicId)

    override suspend fun replaceMessages(topicId: String, messagesJson: org.json.JSONArray) {
        database.withTransaction {
            messageDao.deleteByTopic(topicId)
            val now = System.currentTimeMillis()
            for (i in 0 until messagesJson.length()) {
                val obj = messagesJson.optJSONObject(i) ?: continue
                val role = obj.optString("role", "user")
                val content = obj.optString("content", "")
                val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                val ts = obj.optLong("timestamp", now + i)
                messageDao.insert(
                    MessageEntity(
                        id = id,
                        topicId = topicId,
                        role = role,
                        content = content,
                        status = "complete",
                        createdAt = ts,
                        updatedAt = ts,
                    ),
                )
            }
        }
    }

    override suspend fun loadMessagesPaged(topicId: String, limit: Int, offset: Int): List<MessageEntity> =
        messageDao.loadPaged(topicId, limit, offset)

    override suspend fun countMessages(topicId: String): Int =
        messageDao.countByTopic(topicId)

    override suspend fun recentMessages(topicId: String, limit: Int): List<MessageEntity> =
        messageDao.loadRecent(topicId, limit).asReversed()

    override suspend fun loadRegexRules(agentId: String): List<RegexRuleEntity> =
        regexRuleDao.loadByAgent(agentId)

    override suspend fun searchTopicIds(agentId: String, query: String): List<String> =
        messageDao.searchTopicIds(agentId, query)

    override suspend fun searchMessages(query: String, limit: Int): List<MessageEntity> =
        messageDao.searchMessages(query, limit)

    private val historySyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastHistorySyncTimeMs = ConcurrentHashMap<String, AtomicLong>()
    private val pendingHistorySyncJobs = ConcurrentHashMap<String, Job>()
    // 流式传输期间节流：避免每个 TextDelta 都写一次文件
    // 从 2s 提到 5s——流式期间中间状态写入文件毫无意义，省电喵
    private val historySyncMinIntervalMs = 5_000L
    // 非流式变更节流：编辑/删除消息后连续操作合并为一次写入
    private val historySyncNonStreamDebounceMs = 1_500L

    private suspend fun syncCompatHistory(topicId: String, debounce: Boolean = false) {
        val lastSyncRef = lastHistorySyncTimeMs.computeIfAbsent(topicId) { AtomicLong(0L) }
        val interval = if (debounce) historySyncMinIntervalMs else historySyncNonStreamDebounceMs
        val elapsed = System.currentTimeMillis() - lastSyncRef.get()

        if (debounce) {
            if (elapsed < interval) {
                return
            }
            pendingHistorySyncJobs.remove(topicId)?.cancel()
            performCompatHistorySync(topicId, lastSyncRef)
            return
        }

        if (elapsed < interval) {
            pendingHistorySyncJobs.remove(topicId)?.cancel()
            val pendingJob = historySyncScope.launch {
                try {
                    val remainingDelayMs = interval - (System.currentTimeMillis() - lastSyncRef.get())
                    if (remainingDelayMs > 0) {
                        delay(remainingDelayMs)
                    }
                    if (System.currentTimeMillis() - lastSyncRef.get() >= interval) {
                        performCompatHistorySync(topicId, lastSyncRef)
                    }
                } finally {
                    pendingHistorySyncJobs.remove(topicId, this.coroutineContext[Job])
                }
            }
            pendingHistorySyncJobs[topicId] = pendingJob
            return
        }

        pendingHistorySyncJobs.remove(topicId)?.cancel()
        performCompatHistorySync(topicId, lastSyncRef)
    }

    private suspend fun performCompatHistorySync(
        topicId: String,
        lastSyncRef: AtomicLong,
    ) {
        lastSyncRef.set(System.currentTimeMillis())
        val topic = topicDao.findById(topicId) ?: return
        syncCompatHistory(topic)
    }

    private suspend fun syncCompatAgentSnapshot(agentId: String) {
        withContext(Dispatchers.IO) {
            val agent = agentDao.findById(agentId) ?: return@withContext
            val topics = topicDao.loadByAgent(agentId)
            val regexRules = regexRuleDao.loadByAgent(agentId)
            val compatAgentDir = fileStore.compatAgentDir(agentId).apply { mkdirs() }

            val existingConfig = readJsonObject(File(compatAgentDir, CONFIG_FILE_NAME))
            val existingTopics = existingConfig?.optJSONArray("topics")
                ?.let(::indexTopicsById)
                .orEmpty()

            val configJson = (existingConfig ?: JSONObject()).also { json ->
                mergeJsonObject(
                    target = json,
                    source = readJsonObject(agent.extraJson),
                    overwrite = false,
                )
            }.apply {
                put("name", agent.name)
                put("systemPrompt", agent.systemPrompt)
                put("promptMode", agent.promptMode)
                put("originalSystemPrompt", agent.originalSystemPrompt)
                if (agent.advancedSystemPromptJson.isBlank()) {
                    remove("advancedSystemPrompt")
                } else {
                    put(
                        "advancedSystemPrompt",
                        readJsonObject(agent.advancedSystemPromptJson)
                            ?: readJsonArray(agent.advancedSystemPromptJson)
                            ?: agent.advancedSystemPromptJson,
                    )
                }
                put("presetSystemPrompt", agent.presetSystemPrompt)
                if (agent.presetPromptPath.isBlank()) {
                    remove("presetPromptPath")
                } else {
                    put("presetPromptPath", agent.presetPromptPath)
                }
                if (agent.selectedPreset.isBlank()) {
                    remove("selectedPreset")
                } else {
                    put("selectedPreset", agent.selectedPreset)
                }
                put("model", agent.model)
                put("temperature", agent.temperature)
                put("contextTokenLimit", agent.contextTokenLimit)
                put("maxOutputTokens", agent.maxOutputTokens)
                put("top_p", agent.topP)
                put("top_k", agent.topK)
                put("streamOutput", agent.streamOutput)
                put(
                    "topics",
                    JSONArray().apply {
                        topics.forEach { topic ->
                            val existingTopic = existingTopics[topic.sourceTopicId]
                            put(
                                (existingTopic ?: JSONObject()).also { topicJson ->
                                    mergeJsonObject(
                                        target = topicJson,
                                        source = readJsonObject(topic.extraJson),
                                        overwrite = false,
                                    )
                                }.apply {
                                    put("id", topic.sourceTopicId)
                                    put("name", topic.title)
                                    put("createdAt", topic.createdAt)
                                    if (!has("locked")) {
                                        put("locked", true)
                                    }
                                    if (!has("unread")) {
                                        put("unread", false)
                                    }
                                    if (!has("creatorSource")) {
                                        put("creatorSource", "ui")
                                    }
                                },
                            )
                        }
                    },
                )
            }
            // Agent 配置保留 pretty-print（人类需要读，且写入频率低）
            AtomicFileWriter.writeJson(File(compatAgentDir, CONFIG_FILE_NAME), configJson.toString(2))

            val regexJson = JSONArray().apply {
                regexRules.forEach { rule ->
                    put(buildRegexRuleJson(rule))
                }
            }
            AtomicFileWriter.writeJson(File(compatAgentDir, REGEX_RULES_FILE_NAME), regexJson.toString(2))

            syncCompatAgentAvatar(
                compatAgentDir = compatAgentDir,
                avatarPath = agent.avatarPath,
            )
        }
    }

    private suspend fun syncCompatHistory(topic: TopicEntity) {
        withContext(Dispatchers.IO) {
            val historyFile = fileStore.agentHistoryFile(
                agentId = topic.agentId,
                topicId = topic.sourceTopicId,
            )
            historyFile.parentFile?.mkdirs()
            val messages = messageDao.loadByTopic(topic.id)
            val attachmentsByMessageId = messageAttachmentDao.loadByTopic(topic.id)
                .groupBy { it.messageId }
            val existingHistory = readJsonArray(historyFile)
                ?.let(::indexMessagesById)
                .orEmpty()
            val historyJson = JSONArray().apply {
                messages.forEach { message ->
                    val existingMessage = existingHistory[message.id]
                    val attachments = attachmentsByMessageId[message.id].orEmpty()
                    put(
                        (existingMessage ?: JSONObject())
                            .put("id", message.id)
                            .put("role", message.role)
                            .put("content", message.content)
                            .put("timestamp", message.createdAt)
                            .put("updatedAt", message.updatedAt)
                            .apply {
                                if (attachments.isEmpty()) {
                                    if (existingMessage?.has("attachments") != true) {
                                        remove("attachments")
                                    }
                                } else {
                                    put(
                                        "attachments",
                                        JSONArray().apply {
                                            attachments.forEach { attachment ->
                                                put(buildCompatAttachmentJson(attachment))
                                            }
                                        },
                                    )
                                }
                                remove("isThinking")
                                when (message.status) {
                                    "interrupted" -> {
                                        put("interrupted", true)
                                        put("finishReason", "interrupted")
                                    }

                                    "error" -> {
                                        put("isError", true)
                                        remove("interrupted")
                                        remove("finishReason")
                                    }

                                    else -> {
                                        remove("interrupted")
                                        remove("finishReason")
                                        remove("isError")
                                    }
                                }
                            },
                    )
                }
            }
            // 不用 toString(2) pretty-print：省去格式化开销，1000 条消息差距明显
            AtomicFileWriter.writeJson(historyFile, historyJson.toString())
        }
    }

    private fun buildCompatAttachmentJson(
        attachment: MessageAttachmentEntity,
    ): JSONObject {
        val runtimePath = attachment.src.ifBlank {
            attachment.internalPath.removePrefix("file://")
        }.ifBlank {
            File(fileStore.attachmentsDir, attachment.internalFileName).absolutePath
        }
        val fileManagerData = JSONObject()
            .put("id", attachment.fileId())
            .put("name", attachment.name)
            .put("internalFileName", attachment.internalFileName)
            .put("internalPath", attachment.internalPath.ifBlank { "file://$runtimePath" })
            .put("type", attachment.mimeType)
            .put("size", attachment.size)
            .put("hash", attachment.hash)
            .put("createdAt", attachment.createdAt)

        attachment.extractedText?.takeIf(String::isNotBlank)?.let { extractedText ->
            fileManagerData.put("extractedText", extractedText)
        }
        // imageFrames 不再内联到 compat JSON 中以减少磁盘占用。
        // JS 侧通过 get-file-as-base64 IPC 按需读取 PDF 原文件并渲染帧。
        // 仅标记帧数量供 JS 判断这是扫描版 PDF。
        readJsonArray(attachment.imageFramesJson)?.takeIf { it.length() > 0 }?.let { frames ->
            fileManagerData.put("pdfFrameCount", frames.length())
        }

        return JSONObject()
            .put("type", attachment.mimeType)
            .put("src", runtimePath)
            .put("name", attachment.name)
            .put("size", attachment.size)
            .put("_fileManagerData", fileManagerData)
    }

    private fun buildRegexRuleJson(rule: RegexRuleEntity): JSONObject {
        val json = readJsonObject(rule.extraJson)?.takeIf { it.has("findPattern") || it.has("findRegex") }
            ?: JSONObject()
        return json.apply {
            put("findPattern", rule.findPattern)
            put("replaceWith", rule.replaceWith)
            put("applyToContext", rule.applyToContext)
            put("applyToFrontend", rule.applyToFrontend)
            put("applyToRoles", JSONArray(parseRoles(rule.applyToRolesJson)))
            put("minDepth", rule.minDepth)
            put("maxDepth", rule.maxDepth)
        }
    }

    private fun syncCompatAgentAvatar(
        compatAgentDir: File,
        avatarPath: String?,
    ) {
        SUPPORTED_AVATAR_EXTENSIONS.forEach { extension ->
            File(compatAgentDir, "$AVATAR_BASENAME.$extension").takeIf(File::exists)?.delete()
        }

        val runtimeAvatarPath = avatarPath?.takeIf { it.isNotBlank() } ?: return
        val sourceFile = File(fileStore.rootDir, runtimeAvatarPath).takeIf(File::isFile) ?: return
        val targetFile = File(compatAgentDir, "$AVATAR_BASENAME.${sourceFile.extension.lowercase()}")
        sourceFile.copyTo(targetFile, overwrite = true)
    }

    private fun indexTopicsById(topicsArray: JSONArray): Map<String, JSONObject> =
        buildMap {
            for (index in 0 until topicsArray.length()) {
                val topicObject = topicsArray.optJSONObject(index) ?: continue
                val topicId = topicObject.optString("id")
                if (topicId.isNotBlank()) {
                    put(topicId, topicObject)
                }
            }
        }

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }

        return try {
            JSONObject(file.readText())
        } catch (_: JSONException) {
            null
        }
    }

    private fun readJsonObject(raw: String?): JSONObject? =
        raw?.takeIf { it.isNotBlank() }?.let { text ->
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                null
            }
        }

    private fun readJsonArray(file: File): JSONArray? {
        if (!file.isFile) {
            return null
        }

        return try {
            JSONArray(file.readText())
        } catch (_: JSONException) {
            null
        }
    }

    private fun readJsonArray(raw: String?): JSONArray? =
        raw?.takeIf { it.isNotBlank() }?.let { text ->
            try {
                JSONArray(text)
            } catch (_: JSONException) {
                null
            }
        }

    private fun mergeJsonObject(
        target: JSONObject,
        source: JSONObject?,
        overwrite: Boolean,
    ) {
        if (source == null) {
            return
        }

        val iterator = source.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (overwrite || !target.has(key)) {
                target.put(key, source.opt(key))
            }
        }
    }

    private fun indexMessagesById(historyArray: JSONArray): Map<String, JSONObject> =
        buildMap {
            for (index in 0 until historyArray.length()) {
                val messageObject = historyArray.optJSONObject(index) ?: continue
                val messageId = messageObject.optString("id")
                if (messageId.isNotBlank()) {
                    put(messageId, messageObject)
                }
            }
        }

    private fun parseRoles(applyToRolesJson: String): List<String> =
        runCatching {
            JSONArray(applyToRolesJson).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val role = array.optString(index)
                        if (role.isNotBlank()) {
                            add(role)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())

    private fun buildId(prefix: String, timestamp: Long): String =
        "${prefix}_${java.lang.Long.toString(timestamp, 36)}_${idCounter.incrementAndGet()}"

    private companion object {
        private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

        const val AVATAR_BASENAME = "avatar"
        const val CONFIG_FILE_NAME = "config.json"
        const val REGEX_RULES_FILE_NAME = "regex_rules.json"
        val SUPPORTED_AVATAR_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
    }
}

private fun RecentChatRow.toHomeRecentChat(): HomeRecentChat =
    HomeRecentChat(
        agent = agent,
        topic = topic,
    )

private fun MessageAttachmentEntity.fileId(): String =
    if (hash.isNotBlank()) {
        "attachment_$hash"
    } else {
        id
    }

/** Entity → Model 转换：供 ViewModel 创建分支等场景使用 */
fun MessageAttachmentEntity.toChatAttachment(): ChatAttachment =
    ChatAttachment(
        id = id,
        fileId = if (hash.isNotBlank()) "attachment_$hash" else id,
        name = name,
        mimeType = mimeType,
        size = size,
        src = src,
        internalFileName = internalFileName,
        internalPath = internalPath,
        hash = hash,
        createdAt = createdAt,
        extractedText = extractedText,
        imageFrames = runCatching {
            imageFramesJson?.let { json ->
                val arr = JSONArray(json)
                List(arr.length()) { i -> arr.getString(i) }
            }.orEmpty()
        }.getOrDefault(emptyList()),
    )

private fun ChatAttachment.toEntity(
    messageId: String,
    attachmentOrder: Int,
): MessageAttachmentEntity =
    MessageAttachmentEntity(
        id = "${messageId}_att_$attachmentOrder",
        messageId = messageId,
        attachmentOrder = attachmentOrder,
        name = name,
        mimeType = mimeType,
        size = size,
        src = src,
        internalFileName = internalFileName,
        internalPath = internalPath,
        hash = hash,
        createdAt = createdAt,
        extractedText = extractedText,
        imageFramesJson = imageFrames
            .takeIf { it.isNotEmpty() }
            ?.let(::JSONArray)
            ?.toString(),
    )
