package com.vcpnative.app.data.groupchat

import android.util.Log
import com.vcpnative.app.chat.session.StreamSessionManager
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.model.CompiledChatRequest
import com.vcpnative.app.model.CompiledMessage
import com.vcpnative.app.model.StreamSessionEvent
import com.vcpnative.app.network.vcp.VcpServiceConfig
import com.vcpnative.app.network.vcp.toServiceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Group chat engine — aligns with VCPChat's groupchat.js
 *
 * Determines which agents speak and orchestrates their responses
 * in sequence, emitting streaming events for each agent.
 */
class GroupChatEngine(
    private val repository: GroupChatRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val streamSessionManager: StreamSessionManager,
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Send a user message to the group and get agent responses.
     * Returns a flow of events for UI updates.
     */
    fun sendMessage(
        groupId: String,
        topicId: String,
        userText: String,
        userId: String = "user",
    ): Flow<GroupStreamEvent> = flow {
        val group = repository.getGroupConfig(groupId)
            ?: run { emit(GroupStreamEvent.Error("群组不存在")); return@flow }
        val settings = settingsRepository.currentSettings()
        val serviceConfig = settings.toServiceConfig()

        // 1. Save user message
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("name", userId)
            put("content", userText)
            put("timestamp", System.currentTimeMillis())
            put("id", "msg_user_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().substring(0, 8)}")
        }
        repository.appendMessage(groupId, topicId, userMsg)
        emit(GroupStreamEvent.UserMessageSaved(userMsg.getString("id")))

        // 2. Load member agents
        val agents = mutableListOf<AgentEntity>()
        for (memberId in group.members) {
            workspaceRepository.findAgent(memberId)?.let { agents.add(it) }
        }
        if (agents.isEmpty()) {
            emit(GroupStreamEvent.Error("群组没有成员"))
            return@flow
        }

        // 3. Determine speakers (复用同一份 history，避免重复读文件)
        var currentHistory = repository.loadHistory(groupId, topicId)
        val speakers = when (group.mode) {
            "sequential" -> agents
            "naturerandom" -> selectNatureRandomSpeakers(agents, currentHistory, group, userText)
            "invite_only" -> emptyList() // No auto, wait for invite
            else -> agents
        }

        if (speakers.isEmpty() && group.mode != "invite_only") {
            emit(GroupStreamEvent.NoResponse)
            return@flow
        }

        // 4. Each speaker responds in sequence
        // 每个 Agent 发言后追加到本地引用，下一个 Agent 不需要重新 reload 文件
        for (agent in speakers) {
            val msgId = "msg_group_${System.currentTimeMillis()}_${agent.id}"
            emit(GroupStreamEvent.AgentThinking(agent.id, agent.name, msgId))

            val response = executeAgentTurn(
                group = group,
                agent = agent,
                history = currentHistory,
                serviceConfig = serviceConfig,
                settings = settings,
                msgId = msgId,
            )

            val accumulated = StringBuilder(4096)
            var persistFailed = false
            response.collect { event ->
                // 持久化失败后忽略后续事件，避免产生孤立/重复消息喵
                if (persistFailed) return@collect
                when (event) {
                    is StreamSessionEvent.TextDelta -> {
                        if (accumulated.length < MAX_ACCUMULATED_CHARS) {
                            accumulated.append(event.text)
                        }
                        emit(GroupStreamEvent.AgentDelta(agent.id, agent.name, msgId, event.text))
                    }
                    is StreamSessionEvent.Completed -> {
                        val finalText = event.fullText.ifBlank { accumulated.toString() }
                        // Save to history
                        val assistantMsg = JSONObject().apply {
                            put("role", "assistant")
                            put("name", agent.name)
                            put("agentId", agent.id)
                            put("content", finalText)
                            put("timestamp", System.currentTimeMillis())
                            put("id", msgId)
                            put("isGroupMessage", true)
                            put("groupId", groupId)
                            put("topicId", topicId)
                        }
                        try {
                            repository.appendMessage(groupId, topicId, assistantMsg)
                            // 追加到本地 history 引用，下一个 Agent 不需要重新 reload 文件
                            currentHistory.put(assistantMsg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to persist group message $msgId", e)
                            persistFailed = true
                            emit(GroupStreamEvent.AgentError(agent.id, agent.name, msgId, "持久化失败: ${e.message}"))
                            return@collect
                        }
                        emit(GroupStreamEvent.AgentCompleted(agent.id, agent.name, msgId, finalText))
                    }
                    is StreamSessionEvent.Failed -> {
                        emit(GroupStreamEvent.AgentError(agent.id, agent.name, msgId, event.message))
                    }
                    is StreamSessionEvent.Interrupted -> {
                        val partial = event.partialText.ifBlank { accumulated.toString() }
                        if (partial.isNotBlank()) {
                            val assistantMsg = JSONObject().apply {
                                put("role", "assistant")
                                put("name", agent.name)
                                put("agentId", agent.id)
                                put("content", partial)
                                put("timestamp", System.currentTimeMillis())
                                put("id", msgId)
                                put("interrupted", true)
                            }
                            repository.appendMessage(groupId, topicId, assistantMsg)
                        }
                        emit(GroupStreamEvent.AgentCompleted(agent.id, agent.name, msgId, partial))
                    }
                    else -> {}
                }
            }
            if (persistFailed) break
        }

        emit(GroupStreamEvent.AllCompleted)
    }

    /**
     * Invite a specific agent to speak (for invite_only mode).
     */
    fun inviteAgent(
        groupId: String,
        topicId: String,
        agentId: String,
    ): Flow<GroupStreamEvent> = flow {
        val group = repository.getGroupConfig(groupId)
            ?: run { emit(GroupStreamEvent.Error("群组不存在")); return@flow }
        val agent = workspaceRepository.findAgent(agentId)
            ?: run { emit(GroupStreamEvent.Error("Agent 不存在")); return@flow }
        val settings = settingsRepository.currentSettings()
        val serviceConfig = settings.toServiceConfig()
        val history = repository.loadHistory(groupId, topicId)

        val msgId = "msg_group_invited_${System.currentTimeMillis()}_${agentId}"
        emit(GroupStreamEvent.AgentThinking(agentId, agent.name, msgId))

        val response = executeAgentTurn(group, agent, history, serviceConfig, settings, msgId)
        val accumulated = StringBuilder(4096)

        response.collect { event ->
            when (event) {
                is StreamSessionEvent.TextDelta -> {
                    if (accumulated.length < MAX_ACCUMULATED_CHARS) {
                        accumulated.append(event.text)
                    }
                    emit(GroupStreamEvent.AgentDelta(agentId, agent.name, msgId, event.text))
                }
                is StreamSessionEvent.Completed -> {
                    val finalText = event.fullText.ifBlank { accumulated.toString() }
                    val assistantMsg = JSONObject().apply {
                        put("role", "assistant")
                        put("name", agent.name)
                        put("agentId", agentId)
                        put("content", finalText)
                        put("timestamp", System.currentTimeMillis())
                        put("id", msgId)
                        put("isGroupMessage", true)
                        put("groupId", groupId)
                        put("topicId", topicId)
                    }
                    repository.appendMessage(groupId, topicId, assistantMsg)
                    emit(GroupStreamEvent.AgentCompleted(agentId, agent.name, msgId, finalText))
                }
                is StreamSessionEvent.Failed -> {
                    emit(GroupStreamEvent.AgentError(agentId, agent.name, msgId, event.message))
                }
                is StreamSessionEvent.Interrupted -> {
                    val partial = event.partialText.ifBlank { accumulated.toString() }
                    if (partial.isNotBlank()) {
                        val assistantMsg = JSONObject().apply {
                            put("role", "assistant")
                            put("name", agent.name)
                            put("agentId", agentId)
                            put("content", partial)
                            put("timestamp", System.currentTimeMillis())
                            put("id", msgId)
                            put("interrupted", true)
                        }
                        repository.appendMessage(groupId, topicId, assistantMsg)
                    }
                    emit(GroupStreamEvent.AgentCompleted(agentId, agent.name, msgId, partial))
                }
                else -> {}
            }
        }
        emit(GroupStreamEvent.AllCompleted)
    }

    // ── Internal ────────────────────────────────────

    private fun executeAgentTurn(
        group: AgentGroup,
        agent: AgentEntity,
        history: JSONArray,
        serviceConfig: VcpServiceConfig,
        settings: com.vcpnative.app.model.AppSettings,
        msgId: String,
    ): Flow<StreamSessionEvent> {
        // Build system prompt
        val systemPrompt = buildString {
            append(agent.systemPrompt)
            if (group.groupPrompt.isNotBlank()) {
                append("\n\n")
                append(group.groupPrompt)
            }
        }

        // Build context messages with speaker tags: [Name的发言]: content
        val contextMessages = mutableListOf<CompiledMessage>()
        for (i in 0 until history.length()) {
            val msg = history.optJSONObject(i) ?: continue
            val name = msg.optString("name", msg.optString("role"))
            val content = msg.optString("content")
            contextMessages.add(
                CompiledMessage(
                    role = if (msg.optString("role") == "assistant") "assistant" else "user",
                    textContent = "[${name}的发言]: $content",
                )
            )
        }

        // Resolve model
        val model = if (group.useUnifiedModel && group.unifiedModel.isNotBlank()) {
            group.unifiedModel
        } else {
            agent.model
        }

        val compiled = CompiledChatRequest(
            agentId = agent.id,
            topicId = "group",
            endpoint = serviceConfig.chatUrl(settings.enableVcpToolInjection),
            apiBaseUrl = serviceConfig.apiRootUrl,
            apiKey = serviceConfig.apiKey,
            model = model,
            temperature = agent.temperature,
            topP = agent.topP,
            topK = agent.topK,
            maxTokens = agent.maxOutputTokens,
            contextTokenLimit = agent.contextTokenLimit,
            stream = agent.streamOutput,
            requestId = msgId,
            messages = listOf(
                CompiledMessage(role = "system", textContent = systemPrompt),
            ) + contextMessages,
        )

        return streamSessionManager.submit(compiled)
    }

    // ── NatureRandom speaker selection ──────────────

    private fun selectNatureRandomSpeakers(
        agents: List<AgentEntity>,
        history: JSONArray,
        group: AgentGroup,
        userText: String,
    ): List<AgentEntity> {
        val speakers = mutableListOf<AgentEntity>()
        val spoken = mutableSetOf<String>()
        val userLower = userText.lowercase()

        // Recent context for tag matching
        val contextWindow = 8
        val histLen = history.length()
        val recentText = buildString {
            for (i in maxOf(0, histLen - contextWindow) until histLen) {
                append(history.optJSONObject(i)?.optString("content", "")?.lowercase() ?: "")
                append(" ")
            }
        }

        // Priority 1: @AgentName mention
        for (agent in agents) {
            if (userLower.contains("@${agent.name.lowercase()}")) {
                speakers.add(agent)
                spoken.add(agent.id)
            }
        }

        // Priority 2: Tag matching
        for (agent in agents) {
            if (spoken.contains(agent.id)) continue
            val tags = (group.memberTags[agent.id] ?: "")
                .split(Regex("[,，]"))
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            if (tags.isEmpty()) continue

            if (group.tagMatchMode == "natural") {
                // Natural mode: distinguish tag source (self vs others)
                val recentMsgs = mutableListOf<JSONObject>()
                for (i in maxOf(0, histLen - contextWindow) until histLen) {
                    history.optJSONObject(i)?.let { recentMsgs.add(it) }
                }
                val tagInOtherMsgs = recentMsgs
                    .filter { it.optString("agentId") != agent.id }
                    .any { msg -> tags.any { tag -> msg.optString("content", "").lowercase().contains(tag) } }
                val tagInUserMsg = tags.any { it in userLower }
                val tagInOwnMsgs = recentMsgs
                    .filter { it.optString("agentId") == agent.id }
                    .any { msg -> tags.any { tag -> msg.optString("content", "").lowercase().contains(tag) } }

                if (tagInOtherMsgs || tagInUserMsg) {
                    speakers.add(agent) // 100% trigger
                    spoken.add(agent.id)
                } else if (tagInOwnMsgs) {
                    // Self-contamination: lower probability
                    val isLastSpeaker = history.optJSONObject(histLen - 1)
                        ?.optString("agentId") == agent.id
                    val prob = if (isLastSpeaker) 0.5f else 0.2f
                    if (Random.nextFloat() < prob) {
                        speakers.add(agent)
                        spoken.add(agent.id)
                    }
                }
            } else {
                // Strict mode: tag anywhere = trigger
                val tagInContext = tags.any { it in recentText || it in userLower }
                if (tagInContext) {
                    speakers.add(agent)
                    spoken.add(agent.id)
                }
            }
        }

        // Priority 3: @所有人
        if ("@所有人" in userLower) {
            for (agent in agents) {
                if (!spoken.contains(agent.id)) {
                    speakers.add(agent)
                    spoken.add(agent.id)
                }
            }
        }

        // Priority 4: Random for remaining
        for (agent in agents) {
            if (spoken.contains(agent.id)) continue
            if (Random.nextFloat() < 0.15f) {
                speakers.add(agent)
                spoken.add(agent.id)
            }
        }

        // Priority 5: Fallback
        if (speakers.isEmpty()) {
            speakers.add(agents[Random.nextInt(agents.size)])
        }

        return speakers
    }

    companion object {
        private const val TAG = "GroupChatEngine"
        // Cap accumulated text to prevent OOM when many agents produce long responses in sequence
        private const val MAX_ACCUMULATED_CHARS = 2 * 1024 * 1024
    }
}

/** Events emitted during group chat message processing. */
sealed class GroupStreamEvent {
    data class UserMessageSaved(val messageId: String) : GroupStreamEvent()
    data class AgentThinking(val agentId: String, val agentName: String, val messageId: String) : GroupStreamEvent()
    data class AgentDelta(val agentId: String, val agentName: String, val messageId: String, val text: String) : GroupStreamEvent()
    data class AgentCompleted(val agentId: String, val agentName: String, val messageId: String, val fullText: String) : GroupStreamEvent()
    data class AgentError(val agentId: String, val agentName: String, val messageId: String, val error: String) : GroupStreamEvent()
    data object NoResponse : GroupStreamEvent()
    data object AllCompleted : GroupStreamEvent()
    data class Error(val message: String) : GroupStreamEvent()
}
