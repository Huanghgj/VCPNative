package com.vcpnative.app.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.data.room.AppDatabase
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.data.room.RegexRuleEntity
import com.vcpnative.app.data.room.TopicEntity
import com.vcpnative.app.model.AppSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors desktop-side compat AppData changes back into Room with topic-level delta sync.
 *
 * Scope:
 * - Agent/topic metadata from `Agents/<agent>/config.json`
 * - Message history from `UserData/<agent>/topics/<topic>/history.json`
 *
 * It deliberately writes to Room directly to avoid re-export loops through WorkspaceRepository.
 */
class CompatDesktopSyncManager(
    private val database: AppDatabase,
    private val fileStore: AppFileStore,
    private val settingsRepository: SettingsRepository,
) {
    private val agentDao = database.agentDao()
    private val topicDao = database.topicDao()
    private val messageDao = database.messageDao()
    private val messageAttachmentDao = database.messageAttachmentDao()
    private val regexRuleDao = database.regexRuleDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val configFingerprints = ConcurrentHashMap<String, FileFingerprint>()
    private val historyFingerprints = ConcurrentHashMap<String, FileFingerprint>()
    private val regexFingerprints = ConcurrentHashMap<String, RuleSourceFingerprint>()
    @Volatile private var settingsFingerprint: FileFingerprint? = null
    @Volatile private var pendingSettingsSession: PendingSessionSelection? = null

    @Volatile
    private var scanJob: Job? = null

    fun start() {
        if (scanJob?.isActive == true) {
            return
        }
        scanJob = scope.launch {
            scanOnce()
            while (isActive) {
                delay(SCAN_INTERVAL_MS)
                scanOnce()
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    suspend fun scanOnce() {
        scanMutex.withLock {
            syncAgentConfigs()
            syncRegexRules()
            syncTopicHistories()
            syncAppSettings()
            pruneFingerprints()
        }
    }

    /**
     * Remove fingerprint entries whose agent directories no longer exist on disk.
     * Belt-and-suspenders: individual sync methods already clean up per-scan,
     * but this catches any stragglers from edge cases (e.g. directory deleted mid-scan).
     */
    private fun pruneFingerprints() {
        val agentsDir = fileStore.compatAgentsDir()
        val existingAgentIds = if (agentsDir.isDirectory) {
            agentsDir.listFiles().orEmpty()
                .filter(File::isDirectory)
                .mapTo(mutableSetOf()) { it.name }
        } else {
            emptySet()
        }

        configFingerprints.keys.removeAll { path ->
            existingAgentIds.none { agentId -> "/$agentId/" in path }
        }
        historyFingerprints.keys.removeAll { path ->
            existingAgentIds.none { agentId -> "/$agentId/" in path }
        }
        regexFingerprints.keys.removeAll { agentId ->
            agentId !in existingAgentIds
        }
    }

    private suspend fun syncAgentConfigs() {
        val agentsDir = fileStore.compatAgentsDir()
        if (!agentsDir.isDirectory) {
            configFingerprints.clear()
            return
        }

        val seenPaths = mutableSetOf<String>()
        agentsDir.listFiles().orEmpty()
            .filter(File::isDirectory)
            .forEach { agentDir ->
                val configFile = File(agentDir, CONFIG_FILE_NAME)
                if (!configFile.isFile) {
                    return@forEach
                }

                val path = configFile.absolutePath
                val fingerprint = configFile.fingerprint()
                seenPaths += path
                if (configFingerprints[path] == fingerprint) {
                    return@forEach
                }

                runCatching {
                    syncAgentConfig(agentDir.name, agentDir, configFile)
                    configFingerprints[path] = fingerprint
                }.onFailure { error ->
                    Log.w(TAG, "Failed to sync agent config: $path", error)
                }
            }

        configFingerprints.keys
            .filterNot(seenPaths::contains)
            .forEach(configFingerprints::remove)
    }

    private suspend fun syncAgentConfig(
        agentId: String,
        agentDir: File,
        configFile: File,
    ) {
        if (agentId.isBlank()) {
            return
        }

        val config = runCatching { JSONObject(configFile.readText()) }
            .getOrElse { throw IllegalArgumentException("Invalid config.json for $agentId", it) }
        val existingAgent = agentDao.findById(agentId)
        val now = System.currentTimeMillis()
        val avatarPath = syncAgentAvatar(agentId, agentDir) ?: existingAgent?.avatarPath
        val sortOrder = existingAgent?.sortOrder ?: (agentDao.count() + 1)

        val agent = AgentEntity(
            id = agentId,
            name = config.optString("name").ifBlank { existingAgent?.name ?: agentId },
            systemPrompt = config.stringOrFallback("systemPrompt", existingAgent?.systemPrompt.orEmpty()),
            promptMode = config.optString("promptMode").ifBlank { existingAgent?.promptMode ?: "original" },
            originalSystemPrompt = config.stringOrFallback(
                "originalSystemPrompt",
                existingAgent?.originalSystemPrompt.orEmpty(),
            ),
            advancedSystemPromptJson = if (config.has("advancedSystemPrompt")) {
                config.opt("advancedSystemPrompt")?.toString().orEmpty()
            } else {
                existingAgent?.advancedSystemPromptJson.orEmpty()
            },
            presetSystemPrompt = config.stringOrFallback(
                "presetSystemPrompt",
                existingAgent?.presetSystemPrompt.orEmpty(),
            ),
            presetPromptPath = config.stringOrFallback(
                "presetPromptPath",
                existingAgent?.presetPromptPath.orEmpty(),
            ),
            selectedPreset = config.stringOrFallback(
                "selectedPreset",
                existingAgent?.selectedPreset.orEmpty(),
            ),
            model = config.optString("model").ifBlank { existingAgent?.model ?: "gemini-pro" },
            temperature = config.optDouble("temperature", existingAgent?.temperature ?: 0.7),
            contextTokenLimit = config.optNullableInt("contextTokenLimit") ?: existingAgent?.contextTokenLimit,
            maxOutputTokens = config.optNullableInt("maxOutputTokens")
                ?: config.optNullableInt("maxTokens")
                ?: existingAgent?.maxOutputTokens,
            topP = config.optNullableDouble("top_p")
                ?: config.optNullableDouble("topP")
                ?: existingAgent?.topP,
            topK = config.optNullableInt("top_k")
                ?: config.optNullableInt("topK")
                ?: existingAgent?.topK,
            streamOutput = config.optFlexibleBoolean("streamOutput", existingAgent?.streamOutput ?: true),
            avatarPath = avatarPath,
            sortOrder = sortOrder,
            updatedAt = maxOf(existingAgent?.updatedAt ?: 0L, configFile.lastModified(), now),
            extraJson = existingAgent?.extraJson,
        )

        val topics = parseTopicSpecs(config)

        database.withTransaction {
            agentDao.insert(agent)
            topics.forEach { spec ->
                val existingTopic = topicDao.findByAgentAndSourceTopicId(agentId, spec.sourceTopicId)
                val topic = TopicEntity(
                    id = existingTopic?.id ?: buildConversationId(agentId, spec.sourceTopicId),
                    agentId = agentId,
                    sourceTopicId = spec.sourceTopicId,
                    title = spec.title,
                    createdAt = existingTopic?.createdAt ?: spec.createdAt,
                    updatedAt = maxOf(existingTopic?.updatedAt ?: 0L, spec.createdAt),
                    extraJson = existingTopic?.extraJson,
                )
                topicDao.insert(topic)
            }
        }
    }

    private suspend fun syncTopicHistories() {
        val userDataDir = fileStore.compatUserDataDir()
        if (!userDataDir.isDirectory) {
            historyFingerprints.clear()
            return
        }

        val seenPaths = mutableSetOf<String>()
        userDataDir.listFiles().orEmpty()
            .filter(File::isDirectory)
            .forEach { agentDir ->
                val topicsDir = File(agentDir, "topics")
                if (!topicsDir.isDirectory) {
                    return@forEach
                }

                topicsDir.listFiles().orEmpty()
                    .filter(File::isDirectory)
                    .forEach { topicDir ->
                        val historyFile = File(topicDir, HISTORY_FILE_NAME)
                        if (!historyFile.isFile) {
                            return@forEach
                        }

                        val path = historyFile.absolutePath
                        val fingerprint = historyFile.fingerprint()
                        seenPaths += path
                        if (historyFingerprints[path] == fingerprint) {
                            return@forEach
                        }

                        runCatching {
                            syncTopicHistory(
                                agentId = agentDir.name,
                                sourceTopicId = topicDir.name,
                                historyFile = historyFile,
                            )
                            historyFingerprints[path] = fingerprint
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to sync history: $path", error)
                        }
                    }
            }

        historyFingerprints.keys
            .filterNot(seenPaths::contains)
            .forEach { removedPath ->
                runCatching {
                    removeMissingTopic(removedPath)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to remove missing topic for $removedPath", error)
                }
                historyFingerprints.remove(removedPath)
            }
    }

    private suspend fun syncRegexRules() {
        val agentsDir = fileStore.compatAgentsDir()
        if (!agentsDir.isDirectory) {
            regexFingerprints.clear()
            return
        }

        val seenAgentIds = mutableSetOf<String>()
        agentsDir.listFiles().orEmpty()
            .filter(File::isDirectory)
            .forEach { agentDir ->
                val agentId = agentDir.name
                seenAgentIds += agentId

                val source = loadRegexRuleSource(agentDir)
                if (source == null) {
                    if (regexFingerprints.remove(agentId) != null || regexRuleDao.loadByAgent(agentId).isNotEmpty()) {
                        regexRuleDao.deleteByAgent(agentId)
                    }
                    return@forEach
                }

                if (regexFingerprints[agentId] == source.fingerprint) {
                    return@forEach
                }

                val rules = parseRegexRules(agentId, source.rulesArray)
                database.withTransaction {
                    regexRuleDao.deleteByAgent(agentId)
                    rules.forEach { rule ->
                        regexRuleDao.insert(rule)
                    }
                }
                regexFingerprints[agentId] = source.fingerprint
            }

        regexFingerprints.keys
            .filterNot(seenAgentIds::contains)
            .forEach { removedAgentId ->
                regexRuleDao.deleteByAgent(removedAgentId)
                regexFingerprints.remove(removedAgentId)
            }
    }

    private suspend fun syncAppSettings() {
        val settingsFile = fileStore.compatSettingsFile()
        if (!settingsFile.isFile) {
            settingsFingerprint = null
            pendingSettingsSession = null
            return
        }

        val fingerprint = settingsFile.fingerprint()
        val shouldRetryPending = pendingSettingsSession != null
        if (settingsFingerprint == fingerprint && !shouldRetryPending) {
            return
        }

        val settingsJson = runCatching {
            val text = settingsFile.readText()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to parse compat settings", error)
            settingsFingerprint = fingerprint
            return
        }

        val currentSettings = settingsRepository.currentSettings()
        val resolved = resolveCompatSettings(settingsJson, currentSettings)
        if (resolved.settings != currentSettings) {
            settingsRepository.applyCompatSettings(resolved.settings)
        }
        pendingSettingsSession = resolved.pendingSelection
        settingsFingerprint = fingerprint
    }

    private suspend fun syncTopicHistory(
        agentId: String,
        sourceTopicId: String,
        historyFile: File,
    ) {
        if (agentId.isBlank() || sourceTopicId.isBlank()) {
            return
        }

        val parsedMessages = parseHistoryFile(historyFile)
        val topic = ensureTopic(agentId, sourceTopicId, parsedMessages, historyFile)
        val topicMessages = parsedMessages.map { parsed ->
            parsed.copy(message = parsed.message.copy(topicId = topic.id))
        }
        val diskById = topicMessages.associateBy { it.message.id }

        database.withTransaction {
            val currentMessages = messageDao.loadByTopic(topic.id)
            val currentById = currentMessages.associateBy { it.id }
            val currentAttachmentsByMessageId = messageAttachmentDao.loadByTopic(topic.id)
                .groupBy { it.messageId }

            // NEVER delete messages from Room that are missing on disk.
            // The local write path (WorkspaceRepository.syncCompatHistory) is
            // debounced, so the disk file can lag behind Room by up to 500ms.
            // Deleting "missing" messages here races with the write path and
            // destroys user data.  Desktop-side deletions are not supported in
            // this direction; the desktop client should use a different sync
            // protocol if bidirectional delete is needed.

            topicMessages.forEach { diskMessage ->
                val currentMessage = currentById[diskMessage.message.id]
                // Don't overwrite a local message that is newer than the disk version.
                if (currentMessage != null && currentMessage.updatedAt >= diskMessage.message.updatedAt) {
                    return@forEach
                }
                val currentAttachments = currentAttachmentsByMessageId[diskMessage.message.id].orEmpty()
                val attachmentsChanged = attachmentsSignature(currentAttachments) != attachmentsSignature(diskMessage.attachments)
                val messageChanged = currentMessage == null || currentMessage != diskMessage.message

                if (messageChanged) {
                    messageDao.insert(diskMessage.message)
                }

                if (messageChanged || attachmentsChanged) {
                    messageAttachmentDao.deleteByMessageId(diskMessage.message.id)
                    diskMessage.attachments.forEach { attachment ->
                        messageAttachmentDao.insert(attachment)
                    }
                }
            }

            val latestTimestamp = topicMessages.maxOfOrNull { maxOf(it.message.createdAt, it.message.updatedAt) }
                ?: topic.createdAt
            topicDao.insert(
                topic.copy(
                    updatedAt = latestTimestamp,
                ),
            )
        }
    }

    private suspend fun ensureTopic(
        agentId: String,
        sourceTopicId: String,
        parsedMessages: List<ParsedMessage>,
        historyFile: File,
    ): TopicEntity {
        topicDao.findByAgentAndSourceTopicId(agentId, sourceTopicId)?.let { return it }

        val agentDir = fileStore.compatAgentDir(agentId)
        val configFile = File(agentDir, CONFIG_FILE_NAME)
        if (configFile.isFile) {
            runCatching {
                syncAgentConfig(agentId, agentDir, configFile)
            }.onFailure { error ->
                Log.w(TAG, "Failed to bootstrap agent/topic from config for $agentId", error)
            }
        }

        topicDao.findByAgentAndSourceTopicId(agentId, sourceTopicId)?.let { return it }

        val existingAgent = agentDao.findById(agentId)
        if (existingAgent == null) {
            agentDao.insert(
                AgentEntity(
                    id = agentId,
                    name = agentId,
                    sortOrder = agentDao.count() + 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        val topicSpec = readTopicSpecFromConfig(agentId, sourceTopicId)
        val createdAt = topicSpec?.createdAt
            ?: parsedMessages.minOfOrNull { it.message.createdAt }
            ?: historyFile.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val topic = TopicEntity(
            id = buildConversationId(agentId, sourceTopicId),
            agentId = agentId,
            sourceTopicId = sourceTopicId,
            title = topicSpec?.title ?: sourceTopicId,
            createdAt = createdAt,
            updatedAt = parsedMessages.maxOfOrNull { maxOf(it.message.createdAt, it.message.updatedAt) } ?: createdAt,
        )

        database.withTransaction {
            topicDao.insert(topic)
        }
        return topic
    }

    private suspend fun removeMissingTopic(historyPath: String) {
        // Intentionally a no-op.  A missing history file on disk does NOT mean
        // the topic should be deleted — the local client may simply not have
        // flushed its compat export yet, or the file may have been transiently
        // unreadable.  Deleting the topic here would CASCADE-delete all messages
        // and cause permanent data loss.
        Log.d(TAG, "History file removed (ignored): $historyPath")
    }

    private fun parseHistoryFile(historyFile: File): List<ParsedMessage> {
        val history = runCatching { JSONArray(historyFile.readText()) }
            .getOrElse { throw IllegalArgumentException("Invalid history.json: ${historyFile.absolutePath}", it) }
        val fallbackBase = historyFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

        return buildList {
            for (index in 0 until history.length()) {
                val messageObject = history.optJSONObject(index) ?: continue
                if (messageObject.optBoolean("isThinking", false)) {
                    continue
                }

                val createdAt = messageObject.optLong("timestamp").takeIf { it > 0L }
                    ?: messageObject.optLong("createdAt").takeIf { it > 0L }
                    ?: (fallbackBase + index)
                val updatedAt = messageObject.optLong("updatedAt").takeIf { it > 0L } ?: createdAt
                val rawId = messageObject.optString("id").ifBlank {
                    "synced_${historyFile.parentFile?.name ?: "topic"}_$index"
                }
                val content = extractContentText(messageObject.opt("content"))
                    ?: summarizeAttachments(messageObject.optJSONArray("attachments"))
                    ?: ""

                val attachments = parseAttachments(
                    messageId = rawId,
                    attachmentsArray = messageObject.optJSONArray("attachments"),
                    createdAt = createdAt,
                )

                add(
                    ParsedMessage(
                        message = MessageEntity(
                            id = rawId,
                            topicId = "",
                            role = normalizeRole(messageObject.optString("role")),
                            content = content,
                            status = determineStatus(messageObject),
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                        ),
                        attachments = attachments,
                    ),
                )
            }
        }
    }

    private fun loadRegexRuleSource(agentDir: File): RegexRuleSource? {
        val regexFile = File(agentDir, REGEX_RULES_FILE_NAME)
        if (regexFile.isFile) {
            val regexText = runCatching { regexFile.readText() }
                .getOrElse { error ->
                    Log.w(TAG, "Failed to read regex rules for ${agentDir.name}", error)
                    return RegexRuleSource(
                        fingerprint = RuleSourceFingerprint(
                            sourceKind = "regex",
                            lastModified = regexFile.lastModified(),
                            length = regexFile.length(),
                        ),
                        rulesArray = JSONArray(),
                    )
                }
            val regexArray = runCatching {
                if (regexText.isBlank()) JSONArray() else JSONArray(regexText)
            }.getOrElse { error ->
                Log.w(TAG, "Invalid regex_rules.json for ${agentDir.name}", error)
                return loadConfigBackedRegexRuleSource(agentDir) ?: RegexRuleSource(
                    fingerprint = RuleSourceFingerprint(
                        sourceKind = "regex-invalid",
                        lastModified = regexFile.lastModified(),
                        length = regexFile.length(),
                    ),
                    rulesArray = JSONArray(),
                )
            }
            return RegexRuleSource(
                fingerprint = RuleSourceFingerprint(
                    sourceKind = "regex",
                    lastModified = regexFile.lastModified(),
                    length = regexFile.length(),
                ),
                rulesArray = regexArray,
            )
        }
        return loadConfigBackedRegexRuleSource(agentDir)
    }

    private fun loadConfigBackedRegexRuleSource(agentDir: File): RegexRuleSource? {
        val configFile = File(agentDir, CONFIG_FILE_NAME)
        if (!configFile.isFile) {
            return null
        }
        val config = runCatching {
            val text = configFile.readText()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }.getOrElse { error ->
            Log.w(TAG, "Invalid config.json while reading regex rules for ${agentDir.name}", error)
            return null
        }
        val rulesArray = config.optJSONArray("stripRegexes") ?: return null
        return RegexRuleSource(
            fingerprint = RuleSourceFingerprint(
                sourceKind = "config",
                lastModified = configFile.lastModified(),
                length = configFile.length(),
            ),
            rulesArray = rulesArray,
        )
    }

    private fun parseRegexRules(
        agentId: String,
        rulesArray: JSONArray,
    ): List<RegexRuleEntity> =
        buildList {
            for (index in 0 until rulesArray.length()) {
                val ruleObject = rulesArray.optJSONObject(index) ?: continue
                val normalizedRule = normalizeRegexRule(ruleObject) ?: continue
                add(
                    RegexRuleEntity(
                        agentId = agentId,
                        ruleOrder = index,
                        findPattern = normalizedRule.findPattern,
                        replaceWith = normalizedRule.replaceWith,
                        applyToContext = normalizedRule.applyToContext,
                        applyToFrontend = normalizedRule.applyToFrontend,
                        applyToRolesJson = JSONArray(normalizedRule.applyToRoles).toString(),
                        minDepth = normalizedRule.minDepth,
                        maxDepth = normalizedRule.maxDepth,
                        extraJson = ruleObject.toString(),
                    ),
                )
            }
        }

    private fun normalizeRegexRule(ruleObject: JSONObject): NormalizedRegexRule? {
        val findPattern = ruleObject.optString("findPattern")
            .ifBlank { ruleObject.optString("findRegex") }
            .trim()
        if (findPattern.isBlank()) {
            return null
        }

        val scopes = ruleObject.optJSONArray("applyToScopes").toStringList().toSet()
        return NormalizedRegexRule(
            findPattern = findPattern,
            replaceWith = ruleObject.optString("replaceWith")
                .ifBlank { ruleObject.optString("replaceString") },
            applyToContext = when {
                ruleObject.has("applyToContext") -> ruleObject.optFlexibleBoolean("applyToContext", true)
                scopes.isNotEmpty() -> "context" in scopes
                else -> true
            },
            applyToFrontend = when {
                ruleObject.has("applyToFrontend") -> ruleObject.optFlexibleBoolean("applyToFrontend", true)
                scopes.isNotEmpty() -> "frontend" in scopes
                else -> true
            },
            applyToRoles = ruleObject.optJSONArray("applyToRoles").toStringList(),
            minDepth = ruleObject.optNullableInt("minDepth") ?: 0,
            maxDepth = ruleObject.optNullableInt("maxDepth") ?: -1,
        )
    }

    private suspend fun resolveCompatSettings(
        settingsJson: JSONObject,
        current: AppSettings,
    ): ResolvedCompatSettings {
        val agentSelection = settingsJson.readCompatSelection("lastOpenItemId", "lastAgentId")
        val topicSelection = settingsJson.readCompatSelection("lastOpenTopicId", "lastTopicId")

        val selectedAgentId = when {
            agentSelection.present -> agentSelection.value
            else -> current.lastAgentId
        }

        val resolvedTopicId = when {
            !topicSelection.present -> current.lastTopicId
            topicSelection.value.isNullOrBlank() -> null
            selectedAgentId.isNullOrBlank() -> null
            else -> topicDao.findByAgentAndSourceTopicId(selectedAgentId, topicSelection.value)?.id
        }

        val pendingSelection = when {
            topicSelection.present &&
                !topicSelection.value.isNullOrBlank() &&
                !selectedAgentId.isNullOrBlank() &&
                resolvedTopicId == null ->
                PendingSessionSelection(
                    agentId = selectedAgentId,
                    sourceTopicId = topicSelection.value,
                )

            else -> null
        }

        return ResolvedCompatSettings(
            settings = current.copy(
                vcpServerUrl = settingsJson.stringOrFallback("vcpServerUrl", current.vcpServerUrl),
                vcpApiKey = settingsJson.stringOrFallback("vcpApiKey", current.vcpApiKey),
                vcpLogUrl = settingsJson.stringOrFallback("vcpLogUrl", current.vcpLogUrl),
                vcpLogKey = settingsJson.stringOrFallback("vcpLogKey", current.vcpLogKey),
                enableVcpToolInjection = settingsJson.flexibleBooleanOrFallback(
                    "enableVcpToolInjection",
                    current.enableVcpToolInjection,
                ),
                enableAgentBubbleTheme = settingsJson.flexibleBooleanOrFallback(
                    "enableAgentBubbleTheme",
                    current.enableAgentBubbleTheme,
                ),
                enableThoughtChainInjection = settingsJson.flexibleBooleanOrFallback(
                    "enableThoughtChainInjection",
                    current.enableThoughtChainInjection,
                ),
                enableContextSanitizer = settingsJson.flexibleBooleanOrFallback(
                    "enableContextSanitizer",
                    current.enableContextSanitizer,
                ),
                contextSanitizerDepth = settingsJson.intOrFallback(
                    "contextSanitizerDepth",
                    current.contextSanitizerDepth,
                ),
                enableContextFolding = settingsJson.flexibleBooleanOrFallback(
                    "enableContextFolding",
                    current.enableContextFolding,
                ),
                contextFoldingKeepRecentMessages = settingsJson.intOrFallback(
                    "contextFoldingKeepRecentMessages",
                    current.contextFoldingKeepRecentMessages,
                ),
                contextFoldingTriggerMessageCount = settingsJson.intOrFallback(
                    "contextFoldingTriggerMessageCount",
                    current.contextFoldingTriggerMessageCount,
                ),
                contextFoldingTriggerCharCount = settingsJson.intOrFallback(
                    "contextFoldingTriggerCharCount",
                    current.contextFoldingTriggerCharCount,
                ),
                contextFoldingExcerptCharLimit = settingsJson.intOrFallback(
                    "contextFoldingExcerptCharLimit",
                    current.contextFoldingExcerptCharLimit,
                ),
                contextFoldingMaxSummaryEntries = settingsJson.intOrFallback(
                    "contextFoldingMaxSummaryEntries",
                    current.contextFoldingMaxSummaryEntries,
                ),
                topicSummaryModel = settingsJson.stringOrFallback(
                    "topicSummaryModel",
                    current.topicSummaryModel,
                ),
                lastAgentId = if (agentSelection.present) selectedAgentId else current.lastAgentId,
                lastTopicId = if (topicSelection.present) resolvedTopicId else current.lastTopicId,
            ),
            pendingSelection = pendingSelection,
        )
    }

    private fun parseAttachments(
        messageId: String,
        attachmentsArray: JSONArray?,
        createdAt: Long,
    ): List<MessageAttachmentEntity> {
        if (attachmentsArray == null || attachmentsArray.length() == 0) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until attachmentsArray.length()) {
                val attachmentObject = attachmentsArray.optJSONObject(index) ?: continue
                val fileManagerData = attachmentObject.optJSONObject("_fileManagerData")
                val name = attachmentObject.optString("name")
                    .ifBlank { fileManagerData?.optString("name").orEmpty() }
                    .ifBlank {
                        attachmentObject.optString("src")
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                    }
                    .ifBlank { "attachment_${index + 1}" }
                val hash = fileManagerData?.optString("hash").orEmpty()
                val internalFileName = fileManagerData?.optString("internalFileName")
                    .orEmpty()
                    .ifBlank { inferInternalFileName(hash, name) }
                val sourcePath = attachmentObject.optString("src")
                val internalPath = fileManagerData?.optString("internalPath")
                    .orEmpty()
                    .ifBlank {
                        internalFileName.takeIf { it.isNotBlank() }
                            ?.let { "file://${File(fileStore.attachmentsDir, it).absolutePath}" }
                            .orEmpty()
                    }
                val runtimePath = syncAttachmentFile(
                    internalFileName = internalFileName,
                    sourcePath = sourcePath,
                    internalPath = internalPath,
                )

                add(
                    MessageAttachmentEntity(
                        id = "${messageId}_att_$index",
                        messageId = messageId,
                        attachmentOrder = index,
                        name = name,
                        mimeType = attachmentObject.optString("type")
                            .ifBlank { fileManagerData?.optString("type").orEmpty() }
                            .ifBlank { "application/octet-stream" },
                        size = when {
                            attachmentObject.has("size") -> attachmentObject.optLong("size")
                            fileManagerData?.has("size") == true -> fileManagerData.optLong("size")
                            else -> 0L
                        },
                        src = runtimePath.ifBlank {
                            sourcePath.ifBlank {
                                internalPath.removePrefix("file://")
                            }
                        },
                        internalFileName = internalFileName,
                        internalPath = internalPath.ifBlank {
                            runtimePath.takeIf { it.isNotBlank() }?.let { "file://$it" }.orEmpty()
                        },
                        hash = hash,
                        createdAt = fileManagerData?.optLong("createdAt")?.takeIf { it > 0L } ?: createdAt,
                        extractedText = fileManagerData?.optString("extractedText")?.takeIf { it.isNotBlank() },
                        imageFramesJson = fileManagerData?.optJSONArray("imageFrames")?.toString(),
                    ),
                )
            }
        }
    }

    private fun syncAttachmentFile(
        internalFileName: String,
        sourcePath: String,
        internalPath: String,
    ): String {
        val targetFile = internalFileName.takeIf { it.isNotBlank() }
            ?.let { File(fileStore.attachmentsDir, it) }
        val sourceFile = attachmentCandidates(internalFileName, sourcePath, internalPath)
            .firstOrNull(File::isFile)

        if (targetFile != null && sourceFile != null) {
            targetFile.parentFile?.mkdirs()
            if (
                !targetFile.exists() ||
                targetFile.length() != sourceFile.length() ||
                targetFile.lastModified() < sourceFile.lastModified()
            ) {
                runCatching {
                    sourceFile.copyTo(targetFile, overwrite = true)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to copy attachment ${sourceFile.absolutePath}", error)
                }
            }
            if (targetFile.isFile) {
                return targetFile.absolutePath
            }
        }

        return when {
            targetFile?.isFile == true -> targetFile.absolutePath
            sourceFile != null -> sourceFile.absolutePath
            internalPath.startsWith("file://") -> internalPath.removePrefix("file://")
            else -> sourcePath.removePrefix("file://")
        }
    }

    private fun attachmentCandidates(
        internalFileName: String,
        sourcePath: String,
        internalPath: String,
    ): List<File> =
        buildList {
            if (internalFileName.isNotBlank()) {
                add(File(fileStore.compatUserDataDir(), "attachments/$internalFileName"))
                add(File(fileStore.attachmentsDir, internalFileName))
            }
            sourcePath.removePrefix("file://").takeIf(String::isNotBlank)?.let(::File)?.let(::add)
            internalPath.removePrefix("file://").takeIf(String::isNotBlank)?.let(::File)?.let(::add)
        }

    private fun readTopicSpecFromConfig(
        agentId: String,
        sourceTopicId: String,
    ): TopicSpec? {
        val configFile = File(fileStore.compatAgentDir(agentId), CONFIG_FILE_NAME)
        if (!configFile.isFile) {
            return null
        }
        val config = runCatching { JSONObject(configFile.readText()) }.getOrNull() ?: return null
        return parseTopicSpecs(config).firstOrNull { it.sourceTopicId == sourceTopicId }
    }

    private fun parseTopicSpecs(config: JSONObject): List<TopicSpec> {
        val topicsArray = config.optJSONArray("topics") ?: return emptyList()
        val baseTime = System.currentTimeMillis()
        return buildList {
            for (index in 0 until topicsArray.length()) {
                val topicObject = topicsArray.optJSONObject(index) ?: continue
                val sourceTopicId = topicObject.optString("id")
                if (sourceTopicId.isBlank()) {
                    continue
                }
                add(
                    TopicSpec(
                        sourceTopicId = sourceTopicId,
                        title = topicObject.optString("name")
                            .ifBlank { topicObject.optString("title") }
                            .ifBlank { sourceTopicId },
                        createdAt = topicObject.optLong("createdAt").takeIf { it > 0L } ?: (baseTime + index),
                    ),
                )
            }
        }
    }

    private fun syncAgentAvatar(
        agentId: String,
        agentDir: File,
    ): String? {
        val avatarFile = agentDir.listFiles().orEmpty().firstOrNull { file ->
            file.isFile &&
                file.nameWithoutExtension.equals(AVATAR_BASENAME, ignoreCase = true) &&
                file.extension.lowercase() in SUPPORTED_AVATAR_EXTENSIONS
        } ?: return null

        SUPPORTED_AVATAR_EXTENSIONS
            .filterNot { it == avatarFile.extension.lowercase() }
            .forEach { ext ->
                fileStore.agentAvatarFile(agentId, ext).takeIf(File::exists)?.delete()
            }

        val targetFile = fileStore.agentAvatarFile(agentId, avatarFile.extension.lowercase())
        targetFile.parentFile?.mkdirs()
        if (
            !targetFile.exists() ||
            targetFile.length() != avatarFile.length() ||
            targetFile.lastModified() < avatarFile.lastModified()
        ) {
            runCatching {
                avatarFile.copyTo(targetFile, overwrite = true)
            }.onFailure { error ->
                Log.w(TAG, "Failed to copy avatar for $agentId", error)
            }
        }
        return if (targetFile.isFile) {
            targetFile.relativeTo(fileStore.rootDir).path.replace(File.separatorChar, '/')
        } else {
            null
        }
    }

    private fun attachmentsSignature(attachments: List<MessageAttachmentEntity>): String =
        attachments.joinToString(separator = "|") { attachment ->
            listOf(
                attachment.attachmentOrder.toString(),
                attachment.name,
                attachment.mimeType,
                attachment.size.toString(),
                attachment.src,
                attachment.internalFileName,
                attachment.internalPath,
                attachment.hash,
                attachment.createdAt.toString(),
                attachment.extractedText.orEmpty(),
                attachment.imageFramesJson.orEmpty(),
            ).joinToString(separator = "\u0001")
        }

    private fun determineStatus(messageObject: JSONObject): String {
        if (messageObject.optBoolean("interrupted", false)) {
            return "interrupted"
        }
        if (messageObject.optString("finishReason").equals("interrupted", ignoreCase = true)) {
            return "interrupted"
        }
        return if (messageObject.optBoolean("isError", false)) "error" else "complete"
    }

    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "assistant", "system", "user" -> role.lowercase()
        else -> "system"
    }

    private fun extractContentText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is JSONObject -> {
            value.optString("text").takeIf { it.isNotBlank() }
                ?: extractContentText(value.opt("content"))
                ?: value.toString()
        }

        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                extractContentText(value.opt(index))
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::append)
            }
        }.ifBlank { null }

        else -> value.toString()
    }

    private fun summarizeAttachments(attachments: JSONArray?): String? {
        if (attachments == null || attachments.length() == 0) {
            return null
        }
        val names = buildList {
            for (index in 0 until attachments.length()) {
                val item = attachments.optJSONObject(index) ?: continue
                val name = item.optString("name").ifBlank { item.optString("src") }
                if (name.isNotBlank()) {
                    add(name)
                }
            }
        }
        if (names.isEmpty()) {
            return "[Synced attachment message]"
        }
        return "[Synced attachment message: ${names.joinToString(", ")}]"
    }

    private fun inferInternalFileName(hash: String, displayName: String): String =
        if (hash.isBlank()) {
            ""
        } else {
            val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            if (extension == null) hash else "$hash.$extension"
        }

    private fun buildConversationId(
        agentId: String,
        sourceTopicId: String,
    ): String = "$agentId:$sourceTopicId"

    private fun File.fingerprint(): FileFingerprint =
        FileFingerprint(
            lastModified = lastModified(),
            length = length(),
        )

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONObject.optFlexibleBoolean(key: String, defaultValue: Boolean): Boolean {
        if (!has(key) || isNull(key)) {
            return defaultValue
        }
        return when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true)
            else -> defaultValue
        }
    }

    private fun JSONObject.flexibleBooleanOrFallback(key: String, fallback: Boolean): Boolean =
        if (has(key) && !isNull(key)) optFlexibleBoolean(key, fallback) else fallback

    private fun JSONObject.intOrFallback(key: String, fallback: Int): Int =
        if (has(key) && !isNull(key)) optInt(key, fallback) else fallback

    private fun JSONObject.stringOrFallback(key: String, fallback: String): String =
        if (has(key) && !isNull(key)) optString(key) else fallback

    private fun JSONObject.readCompatSelection(vararg keys: String): CompatSelection {
        var present = false
        var value: String? = null
        keys.forEach { key ->
            if (!has(key) || isNull(key)) {
                return@forEach
            }
            present = true
            if (value.isNullOrBlank()) {
                value = optString(key).takeIf { it.isNotBlank() }
            }
        }
        return CompatSelection(
            present = present,
            value = value,
        )
    }

    private fun JSONArray?.toStringList(): List<String> =
        this?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        }.orEmpty()

    private data class FileFingerprint(
        val lastModified: Long,
        val length: Long,
    )

    private data class RuleSourceFingerprint(
        val sourceKind: String,
        val lastModified: Long,
        val length: Long,
    )

    private data class RegexRuleSource(
        val fingerprint: RuleSourceFingerprint,
        val rulesArray: JSONArray,
    )

    private data class TopicSpec(
        val sourceTopicId: String,
        val title: String,
        val createdAt: Long,
    )

    private data class ParsedMessage(
        val message: MessageEntity,
        val attachments: List<MessageAttachmentEntity>,
    )

    private data class NormalizedRegexRule(
        val findPattern: String,
        val replaceWith: String,
        val applyToContext: Boolean,
        val applyToFrontend: Boolean,
        val applyToRoles: List<String>,
        val minDepth: Int,
        val maxDepth: Int,
    )

    private data class CompatSelection(
        val present: Boolean,
        val value: String?,
    )

    private data class PendingSessionSelection(
        val agentId: String,
        val sourceTopicId: String,
    )

    private data class ResolvedCompatSettings(
        val settings: AppSettings,
        val pendingSelection: PendingSessionSelection?,
    )

    private companion object {
        const val TAG = "CompatDesktopSync"
        const val SCAN_INTERVAL_MS = 2_500L
        const val AVATAR_BASENAME = "avatar"
        const val CONFIG_FILE_NAME = "config.json"
        const val HISTORY_FILE_NAME = "history.json"
        const val REGEX_RULES_FILE_NAME = "regex_rules.json"
        val SUPPORTED_AVATAR_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
    }
}
