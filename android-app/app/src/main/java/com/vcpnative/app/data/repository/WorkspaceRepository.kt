package com.vcpnative.app.data.repository

import androidx.room.withTransaction
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.room.AgentDao
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.data.room.AppDatabase
import com.vcpnative.app.data.room.MessageAttachmentDao
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageDao
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.data.room.RegexRuleDao
import com.vcpnative.app.data.room.RegexRuleEntity
import com.vcpnative.app.data.room.TopicDao
import com.vcpnative.app.data.room.TopicEntity
import com.vcpnative.app.model.ChatAttachment
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

interface WorkspaceRepository {
    fun observeAgents(): Flow<List<AgentEntity>>

    fun observeTopics(agentId: String): Flow<List<TopicEntity>>

    fun observeMessages(topicId: String): Flow<List<MessageEntity>>

    fun observeMessageAttachments(topicId: String): Flow<List<MessageAttachmentEntity>>

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
    )

    suspend fun deleteMessage(topicId: String, messageId: String)

    suspend fun deleteMessagesFrom(topicId: String, createdAt: Long)

    suspend fun saveAgent(agent: AgentEntity): AgentEntity

    suspend fun findAgent(agentId: String): AgentEntity?

    suspend fun findTopic(topicId: String): TopicEntity?

    suspend fun findMessageAttachment(attachmentId: String): MessageAttachmentEntity?

    suspend fun loadMessages(topicId: String): List<MessageEntity>

    suspend fun recentMessages(topicId: String, limit: Int = 12): List<MessageEntity>

    suspend fun loadRegexRules(agentId: String): List<RegexRuleEntity>
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

    override fun observeTopics(agentId: String): Flow<List<TopicEntity>> =
        topicDao.observeByAgent(agentId)

    override fun observeMessages(topicId: String): Flow<List<MessageEntity>> =
        messageDao.observeByTopic(topicId)

    override fun observeMessageAttachments(topicId: String): Flow<List<MessageAttachmentEntity>> =
        messageAttachmentDao.observeByTopic(topicId)

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
        syncCompatHistory(topicId)
        return message
    }

    override suspend fun updateMessage(
        topicId: String,
        messageId: String,
        content: String,
        status: String,
        syncCompatHistory: Boolean,
    ) {
        val timestamp = System.currentTimeMillis()
        messageDao.updateContent(
            messageId = messageId,
            content = content,
            status = status,
            updatedAt = timestamp,
        )
        topicDao.touch(topicId, timestamp)
        if (syncCompatHistory) {
            syncCompatHistory(topicId)
        }
    }

    override suspend fun deleteMessage(topicId: String, messageId: String) {
        messageDao.deleteById(messageId)
        topicDao.touch(topicId, System.currentTimeMillis())
        syncCompatHistory(topicId)
    }

    override suspend fun deleteMessagesFrom(topicId: String, createdAt: Long) {
        messageDao.deleteFrom(topicId, createdAt)
        topicDao.touch(topicId, System.currentTimeMillis())
        syncCompatHistory(topicId)
    }

    override suspend fun saveAgent(agent: AgentEntity): AgentEntity {
        val savedAgent = agent.copy(updatedAt = System.currentTimeMillis())
        agentDao.insert(savedAgent)
        syncCompatAgentSnapshot(savedAgent.id)
        return savedAgent
    }

    override suspend fun findAgent(agentId: String): AgentEntity? = agentDao.findById(agentId)

    override suspend fun findTopic(topicId: String): TopicEntity? = topicDao.findById(topicId)

    override suspend fun findMessageAttachment(attachmentId: String): MessageAttachmentEntity? =
        messageAttachmentDao.findById(attachmentId)

    override suspend fun loadMessages(topicId: String): List<MessageEntity> =
        messageDao.loadByTopic(topicId)

    override suspend fun recentMessages(topicId: String, limit: Int): List<MessageEntity> =
        messageDao.loadRecent(topicId, limit).asReversed()

    override suspend fun loadRegexRules(agentId: String): List<RegexRuleEntity> =
        regexRuleDao.loadByAgent(agentId)

    private suspend fun syncCompatHistory(topicId: String) {
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

            val configJson = (existingConfig ?: JSONObject()).apply {
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
                                (existingTopic ?: JSONObject()).apply {
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
            File(compatAgentDir, CONFIG_FILE_NAME).writeText(configJson.toString(2))

            val regexJson = JSONArray().apply {
                regexRules.forEach { rule ->
                    put(buildRegexRuleJson(rule))
                }
            }
            File(compatAgentDir, REGEX_RULES_FILE_NAME).writeText(regexJson.toString(2))

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
            historyFile.writeText(historyJson.toString(2))
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
        readJsonArray(attachment.imageFramesJson)?.takeIf { it.length() > 0 }?.let { frames ->
            fileManagerData.put("imageFrames", frames)
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
        "${prefix}_${java.lang.Long.toString(timestamp, 36)}"

    private companion object {
        const val AVATAR_BASENAME = "avatar"
        const val CONFIG_FILE_NAME = "config.json"
        const val REGEX_RULES_FILE_NAME = "regex_rules.json"
        val SUPPORTED_AVATAR_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
    }
}

private fun MessageAttachmentEntity.fileId(): String =
    if (hash.isNotBlank()) {
        "attachment_$hash"
    } else {
        id
    }

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
