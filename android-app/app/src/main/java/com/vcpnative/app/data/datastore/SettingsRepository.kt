package com.vcpnative.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun currentSettings(): AppSettings

    suspend fun saveConnection(serverUrl: String, apiKey: String)

    suspend fun saveCompilerOptions(
        enableVcpToolInjection: Boolean,
        enableAgentBubbleTheme: Boolean,
        enableThoughtChainInjection: Boolean,
        enableContextSanitizer: Boolean,
        contextSanitizerDepth: Int,
        enableContextFolding: Boolean,
        contextFoldingKeepRecentMessages: Int,
        contextFoldingTriggerMessageCount: Int,
        contextFoldingTriggerCharCount: Int,
        contextFoldingExcerptCharLimit: Int,
        contextFoldingMaxSummaryEntries: Int,
        topicSummaryModel: String,
    )

    suspend fun saveLastSession(agentId: String?, topicId: String?)
}

class DataStoreSettingsRepository(
    private val context: Context,
    private val fileStore: AppFileStore,
) : SettingsRepository {
    override val settings: Flow<AppSettings> =
        context.appSettingsDataStore.data.map { preferences ->
            preferences.toAppSettings()
        }

    override suspend fun currentSettings(): AppSettings = settings.first()

    override suspend fun saveConnection(serverUrl: String, apiKey: String) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[Keys.VCP_SERVER_URL] = serverUrl.trim()
            preferences[Keys.VCP_API_KEY] = apiKey.trim()
        }
        syncCompatSettings(currentSettings())
    }

    override suspend fun saveCompilerOptions(
        enableVcpToolInjection: Boolean,
        enableAgentBubbleTheme: Boolean,
        enableThoughtChainInjection: Boolean,
        enableContextSanitizer: Boolean,
        contextSanitizerDepth: Int,
        enableContextFolding: Boolean,
        contextFoldingKeepRecentMessages: Int,
        contextFoldingTriggerMessageCount: Int,
        contextFoldingTriggerCharCount: Int,
        contextFoldingExcerptCharLimit: Int,
        contextFoldingMaxSummaryEntries: Int,
        topicSummaryModel: String,
    ) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[Keys.ENABLE_VCP_TOOL_INJECTION] = enableVcpToolInjection
            preferences[Keys.ENABLE_AGENT_BUBBLE_THEME] = enableAgentBubbleTheme
            preferences[Keys.ENABLE_THOUGHT_CHAIN_INJECTION] = enableThoughtChainInjection
            preferences[Keys.ENABLE_CONTEXT_SANITIZER] = enableContextSanitizer
            preferences[Keys.CONTEXT_SANITIZER_DEPTH] = contextSanitizerDepth
            preferences[Keys.ENABLE_CONTEXT_FOLDING] = enableContextFolding
            preferences[Keys.CONTEXT_FOLDING_KEEP_RECENT_MESSAGES] = contextFoldingKeepRecentMessages
            preferences[Keys.CONTEXT_FOLDING_TRIGGER_MESSAGE_COUNT] = contextFoldingTriggerMessageCount
            preferences[Keys.CONTEXT_FOLDING_TRIGGER_CHAR_COUNT] = contextFoldingTriggerCharCount
            preferences[Keys.CONTEXT_FOLDING_EXCERPT_CHAR_LIMIT] = contextFoldingExcerptCharLimit
            preferences[Keys.CONTEXT_FOLDING_MAX_SUMMARY_ENTRIES] = contextFoldingMaxSummaryEntries
            preferences[Keys.TOPIC_SUMMARY_MODEL] = topicSummaryModel
        }
        syncCompatSettings(currentSettings())
    }

    override suspend fun saveLastSession(agentId: String?, topicId: String?) {
        context.appSettingsDataStore.edit { preferences ->
            if (agentId.isNullOrBlank()) {
                preferences.remove(Keys.LAST_AGENT_ID)
            } else {
                preferences[Keys.LAST_AGENT_ID] = agentId
            }

            if (topicId.isNullOrBlank()) {
                preferences.remove(Keys.LAST_TOPIC_ID)
            } else {
                preferences[Keys.LAST_TOPIC_ID] = topicId
            }
        }
        syncCompatSettings(currentSettings())
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        vcpServerUrl = this[Keys.VCP_SERVER_URL].orEmpty(),
        vcpApiKey = this[Keys.VCP_API_KEY].orEmpty(),
        enableVcpToolInjection = this[Keys.ENABLE_VCP_TOOL_INJECTION] ?: false,
        enableAgentBubbleTheme = this[Keys.ENABLE_AGENT_BUBBLE_THEME] ?: false,
        enableThoughtChainInjection = this[Keys.ENABLE_THOUGHT_CHAIN_INJECTION] ?: false,
        enableContextSanitizer = this[Keys.ENABLE_CONTEXT_SANITIZER] ?: true,
        contextSanitizerDepth = this[Keys.CONTEXT_SANITIZER_DEPTH] ?: 2,
        enableContextFolding = this[Keys.ENABLE_CONTEXT_FOLDING] ?: true,
        contextFoldingKeepRecentMessages = this[Keys.CONTEXT_FOLDING_KEEP_RECENT_MESSAGES] ?: 12,
        contextFoldingTriggerMessageCount = this[Keys.CONTEXT_FOLDING_TRIGGER_MESSAGE_COUNT] ?: 24,
        contextFoldingTriggerCharCount = this[Keys.CONTEXT_FOLDING_TRIGGER_CHAR_COUNT] ?: 24_000,
        contextFoldingExcerptCharLimit = this[Keys.CONTEXT_FOLDING_EXCERPT_CHAR_LIMIT] ?: 160,
        contextFoldingMaxSummaryEntries = this[Keys.CONTEXT_FOLDING_MAX_SUMMARY_ENTRIES] ?: 40,
        topicSummaryModel = this[Keys.TOPIC_SUMMARY_MODEL] ?: "gemini-2.5-flash",
        lastAgentId = this[Keys.LAST_AGENT_ID],
        lastTopicId = this[Keys.LAST_TOPIC_ID],
    )

    private object Keys {
        val VCP_SERVER_URL = stringPreferencesKey("vcp_server_url")
        val VCP_API_KEY = stringPreferencesKey("vcp_api_key")
        val ENABLE_VCP_TOOL_INJECTION = booleanPreferencesKey("enable_vcp_tool_injection")
        val ENABLE_AGENT_BUBBLE_THEME = booleanPreferencesKey("enable_agent_bubble_theme")
        val ENABLE_THOUGHT_CHAIN_INJECTION = booleanPreferencesKey("enable_thought_chain_injection")
        val ENABLE_CONTEXT_SANITIZER = booleanPreferencesKey("enable_context_sanitizer")
        val CONTEXT_SANITIZER_DEPTH = intPreferencesKey("context_sanitizer_depth")
        val ENABLE_CONTEXT_FOLDING = booleanPreferencesKey("enable_context_folding")
        val CONTEXT_FOLDING_KEEP_RECENT_MESSAGES = intPreferencesKey("context_folding_keep_recent_messages")
        val CONTEXT_FOLDING_TRIGGER_MESSAGE_COUNT = intPreferencesKey("context_folding_trigger_message_count")
        val CONTEXT_FOLDING_TRIGGER_CHAR_COUNT = intPreferencesKey("context_folding_trigger_char_count")
        val CONTEXT_FOLDING_EXCERPT_CHAR_LIMIT = intPreferencesKey("context_folding_excerpt_char_limit")
        val CONTEXT_FOLDING_MAX_SUMMARY_ENTRIES = intPreferencesKey("context_folding_max_summary_entries")
        val TOPIC_SUMMARY_MODEL = stringPreferencesKey("topic_summary_model")
        val LAST_AGENT_ID = stringPreferencesKey("last_agent_id")
        val LAST_TOPIC_ID = stringPreferencesKey("last_topic_id")
    }

    private fun syncCompatSettings(settings: AppSettings) {
        val settingsFile = fileStore.compatSettingsFile()
        settingsFile.parentFile?.mkdirs()
        val json = readCompatSettings(settingsFile).apply {
            put("vcpServerUrl", settings.vcpServerUrl)
            put("vcpApiKey", settings.vcpApiKey)
            put("enableVcpToolInjection", settings.enableVcpToolInjection)
            put("enableAgentBubbleTheme", settings.enableAgentBubbleTheme)
            put("enableThoughtChainInjection", settings.enableThoughtChainInjection)
            put("enableContextSanitizer", settings.enableContextSanitizer)
            put("contextSanitizerDepth", settings.contextSanitizerDepth)
            put("enableContextFolding", settings.enableContextFolding)
            put("contextFoldingKeepRecentMessages", settings.contextFoldingKeepRecentMessages)
            put("contextFoldingTriggerMessageCount", settings.contextFoldingTriggerMessageCount)
            put("contextFoldingTriggerCharCount", settings.contextFoldingTriggerCharCount)
            put("contextFoldingExcerptCharLimit", settings.contextFoldingExcerptCharLimit)
            put("contextFoldingMaxSummaryEntries", settings.contextFoldingMaxSummaryEntries)
            put("topicSummaryModel", settings.topicSummaryModel)
            put("lastOpenItemId", settings.lastAgentId)
            put("lastAgentId", settings.lastAgentId)
            put("lastOpenTopicId", settings.lastTopicId)
            put("lastTopicId", settings.lastTopicId)
        }
        settingsFile.writeText(json.toString(2))
    }

    private fun readCompatSettings(settingsFile: java.io.File): JSONObject {
        if (!settingsFile.isFile) {
            return JSONObject()
        }

        return try {
            JSONObject(settingsFile.readText())
        } catch (_: JSONException) {
            JSONObject()
        }
    }
}
