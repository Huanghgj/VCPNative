package com.vcpnative.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vcpnative.app.bridge.BridgeLogger
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.files.AtomicFileWriter
import com.vcpnative.app.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun currentSettings(): AppSettings

    suspend fun saveConnection(serverUrl: String, apiKey: String, vcpLogUrl: String, vcpLogKey: String)

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

    suspend fun saveFloatingWindowEnabled(enabled: Boolean)

    suspend fun saveOverlayApiConfig(apiUrl: String, apiKey: String, model: String)

    suspend fun applyCompatSettings(settings: AppSettings)

    /**
     * Read settings with three-layer fallback: DataStore -> compat backup -> defaults.
     *
     * Ported from VCPMobile `app_settings_manager.rs` 3-stage recovery.
     * Use this at bootstrap to guarantee a non-null result even on corruption.
     */
    suspend fun currentSettingsWithRecovery(): AppSettings
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

    override suspend fun saveConnection(serverUrl: String, apiKey: String, vcpLogUrl: String, vcpLogKey: String) {
        applySettings(
            currentSettings().copy(
                vcpServerUrl = serverUrl.trim(),
                vcpApiKey = apiKey.trim(),
                vcpLogUrl = vcpLogUrl.trim(),
                vcpLogKey = vcpLogKey.trim(),
            ),
            syncCompatFile = true,
        )
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
        applySettings(
            currentSettings().copy(
                enableVcpToolInjection = enableVcpToolInjection,
                enableAgentBubbleTheme = enableAgentBubbleTheme,
                enableThoughtChainInjection = enableThoughtChainInjection,
                enableContextSanitizer = enableContextSanitizer,
                contextSanitizerDepth = contextSanitizerDepth,
                enableContextFolding = enableContextFolding,
                contextFoldingKeepRecentMessages = contextFoldingKeepRecentMessages,
                contextFoldingTriggerMessageCount = contextFoldingTriggerMessageCount,
                contextFoldingTriggerCharCount = contextFoldingTriggerCharCount,
                contextFoldingExcerptCharLimit = contextFoldingExcerptCharLimit,
                contextFoldingMaxSummaryEntries = contextFoldingMaxSummaryEntries,
                topicSummaryModel = topicSummaryModel,
            ),
            syncCompatFile = true,
        )
    }

    override suspend fun saveLastSession(agentId: String?, topicId: String?) {
        applyLastSession(agentId = agentId, topicId = topicId, syncCompatFile = true)
    }

    override suspend fun saveFloatingWindowEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[Keys.ENABLE_FLOATING_WINDOW] = enabled
        }
    }

    override suspend fun saveOverlayApiConfig(apiUrl: String, apiKey: String, model: String) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[Keys.OVERLAY_API_URL] = apiUrl.trim()
            preferences[Keys.OVERLAY_API_KEY] = apiKey.trim()
            preferences[Keys.OVERLAY_MODEL] = model.trim()
        }
    }

    override suspend fun applyCompatSettings(settings: AppSettings) {
        applySettings(settings, syncCompatFile = false)
    }

    override suspend fun currentSettingsWithRecovery(): AppSettings {
        // Layer 1: DataStore (primary)
        return try {
            AtomicFileWriter.retryWithBackoff(maxRetries = 3, initialDelayMs = 50) {
                currentSettings()
            }
        } catch (e: Exception) {
            BridgeLogger.w("SettingsRepo", "DataStore read failed, trying compat backup: ${e.message}")
            // Layer 2: compat settings.json backup
            try {
                val backupFile = fileStore.compatSettingsFile()
                if (backupFile.exists()) {
                    val json = readCompatSettings(backupFile)
                    parseCompatSettingsJson(json)
                } else {
                    throw java.io.FileNotFoundException("No compat backup")
                }
            } catch (e2: Exception) {
                BridgeLogger.w("SettingsRepo", "Backup read failed, using defaults: ${e2.message}")
                // Layer 3: defaults
                AppSettings()
            }
        }
    }

    /** Parse an AppSettings from a compat JSON (best-effort). */
    private fun parseCompatSettingsJson(json: JSONObject): AppSettings = AppSettings(
        vcpServerUrl = json.optString("vcpServerUrl", ""),
        vcpApiKey = json.optString("vcpApiKey", ""),
        vcpLogUrl = json.optString("vcpLogUrl", ""),
        vcpLogKey = json.optString("vcpLogKey", ""),
        enableVcpToolInjection = json.optBoolean("enableVcpToolInjection", false),
        enableAgentBubbleTheme = json.optBoolean("enableAgentBubbleTheme", false),
        enableThoughtChainInjection = json.optBoolean("enableThoughtChainInjection", false),
        enableContextSanitizer = json.optBoolean("enableContextSanitizer", true),
        contextSanitizerDepth = json.optInt("contextSanitizerDepth", 2),
        enableContextFolding = json.optBoolean("enableContextFolding", true),
        contextFoldingKeepRecentMessages = json.optInt("contextFoldingKeepRecentMessages", 12),
        contextFoldingTriggerMessageCount = json.optInt("contextFoldingTriggerMessageCount", 24),
        contextFoldingTriggerCharCount = json.optInt("contextFoldingTriggerCharCount", 24000),
        contextFoldingExcerptCharLimit = json.optInt("contextFoldingExcerptCharLimit", 160),
        contextFoldingMaxSummaryEntries = json.optInt("contextFoldingMaxSummaryEntries", 40),
        topicSummaryModel = json.optString("topicSummaryModel", "gemini-2.5-flash"),
        lastAgentId = json.optString("lastAgentId").takeIf { it.isNotBlank() },
        lastTopicId = json.optString("lastTopicId").takeIf { it.isNotBlank() },
    )

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        vcpServerUrl = this[Keys.VCP_SERVER_URL].orEmpty(),
        vcpApiKey = this[Keys.VCP_API_KEY].orEmpty(),
        vcpLogUrl = this[Keys.VCP_LOG_URL].orEmpty(),
        vcpLogKey = this[Keys.VCP_LOG_KEY].orEmpty(),
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
        enableFloatingWindow = this[Keys.ENABLE_FLOATING_WINDOW] ?: false,
        overlayApiUrl = this[Keys.OVERLAY_API_URL].orEmpty(),
        overlayApiKey = this[Keys.OVERLAY_API_KEY].orEmpty(),
        overlayModel = this[Keys.OVERLAY_MODEL].orEmpty(),
        lastAgentId = this[Keys.LAST_AGENT_ID],
        lastTopicId = this[Keys.LAST_TOPIC_ID],
    )

    private object Keys {
        val VCP_SERVER_URL = stringPreferencesKey("vcp_server_url")
        val VCP_API_KEY = stringPreferencesKey("vcp_api_key")
        val VCP_LOG_URL = stringPreferencesKey("vcp_log_url")
        val VCP_LOG_KEY = stringPreferencesKey("vcp_log_key")
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
        val ENABLE_FLOATING_WINDOW = booleanPreferencesKey("enable_floating_window")
        val OVERLAY_API_URL = stringPreferencesKey("overlay_api_url")
        val OVERLAY_API_KEY = stringPreferencesKey("overlay_api_key")
        val OVERLAY_MODEL = stringPreferencesKey("overlay_model")
        val LAST_AGENT_ID = stringPreferencesKey("last_agent_id")
        val LAST_TOPIC_ID = stringPreferencesKey("last_topic_id")
    }

    private suspend fun syncCompatSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        val settingsFile = fileStore.compatSettingsFile()
        settingsFile.parentFile?.mkdirs()
        val json = readCompatSettings(settingsFile).apply {
            put("vcpServerUrl", settings.vcpServerUrl)
            put("vcpApiKey", settings.vcpApiKey)
            put("vcpLogUrl", settings.vcpLogUrl)
            put("vcpLogKey", settings.vcpLogKey)
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
        AtomicFileWriter.writeJson(settingsFile, json.toString(2))
    }

    private suspend fun applySettings(
        settings: AppSettings,
        syncCompatFile: Boolean,
    ) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[Keys.VCP_SERVER_URL] = settings.vcpServerUrl
            preferences[Keys.VCP_API_KEY] = settings.vcpApiKey
            preferences[Keys.VCP_LOG_URL] = settings.vcpLogUrl
            preferences[Keys.VCP_LOG_KEY] = settings.vcpLogKey
            preferences[Keys.ENABLE_VCP_TOOL_INJECTION] = settings.enableVcpToolInjection
            preferences[Keys.ENABLE_AGENT_BUBBLE_THEME] = settings.enableAgentBubbleTheme
            preferences[Keys.ENABLE_THOUGHT_CHAIN_INJECTION] = settings.enableThoughtChainInjection
            preferences[Keys.ENABLE_CONTEXT_SANITIZER] = settings.enableContextSanitizer
            preferences[Keys.CONTEXT_SANITIZER_DEPTH] = settings.contextSanitizerDepth
            preferences[Keys.ENABLE_CONTEXT_FOLDING] = settings.enableContextFolding
            preferences[Keys.CONTEXT_FOLDING_KEEP_RECENT_MESSAGES] = settings.contextFoldingKeepRecentMessages
            preferences[Keys.CONTEXT_FOLDING_TRIGGER_MESSAGE_COUNT] = settings.contextFoldingTriggerMessageCount
            preferences[Keys.CONTEXT_FOLDING_TRIGGER_CHAR_COUNT] = settings.contextFoldingTriggerCharCount
            preferences[Keys.CONTEXT_FOLDING_EXCERPT_CHAR_LIMIT] = settings.contextFoldingExcerptCharLimit
            preferences[Keys.CONTEXT_FOLDING_MAX_SUMMARY_ENTRIES] = settings.contextFoldingMaxSummaryEntries
            preferences[Keys.TOPIC_SUMMARY_MODEL] = settings.topicSummaryModel
            preferences[Keys.ENABLE_FLOATING_WINDOW] = settings.enableFloatingWindow
            preferences[Keys.OVERLAY_API_URL] = settings.overlayApiUrl
            preferences[Keys.OVERLAY_API_KEY] = settings.overlayApiKey
            preferences[Keys.OVERLAY_MODEL] = settings.overlayModel
            putNullable(preferences, Keys.LAST_AGENT_ID, settings.lastAgentId)
            putNullable(preferences, Keys.LAST_TOPIC_ID, settings.lastTopicId)
        }
        if (syncCompatFile) {
            syncCompatSettings(settings)
        }
    }

    private suspend fun applyLastSession(
        agentId: String?,
        topicId: String?,
        syncCompatFile: Boolean,
    ) {
        context.appSettingsDataStore.edit { preferences ->
            putNullable(preferences, Keys.LAST_AGENT_ID, agentId)
            putNullable(preferences, Keys.LAST_TOPIC_ID, topicId)
        }
        if (!syncCompatFile) {
            return
        }
        withContext(Dispatchers.IO) {
            val settingsFile = fileStore.compatSettingsFile()
            if (settingsFile.isFile) {
                val json = readCompatSettings(settingsFile)
                json.put("lastOpenItemId", agentId)
                json.put("lastAgentId", agentId)
                json.put("lastOpenTopicId", topicId)
                json.put("lastTopicId", topicId)
                AtomicFileWriter.writeJson(settingsFile, json.toString(2))
            }
        }
    }

    private fun putNullable(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            preferences.remove(key)
        } else {
            preferences[key] = value
        }
    }

    private fun readCompatSettings(settingsFile: java.io.File): JSONObject {
        if (!settingsFile.isFile) {
            return JSONObject()
        }

        return try {
            val text = settingsFile.readText()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: JSONException) {
            android.util.Log.w("SettingsRepo", "Compat settings corrupted, resetting: ${e.message}")
            JSONObject()
        }
    }
}
