package com.vcpnative.app.data.taskassistant

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Network client for the VCPToolBox admin_api — manages Agent & Task assistant configs.
 *
 * Endpoints:
 *   GET/POST /admin_api/agent-assistant/config
 *   GET/POST /admin_api/task-assistant/config
 *   GET      /admin_api/task-assistant/status
 *   POST     /admin_api/task-assistant/trigger
 */
class TaskAssistantRepository(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "TaskAssistantRepo"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /** Derive admin API base from vcpServerUrl (strip /v1/chat/completions suffix). */
    private fun adminBase(serverUrl: String): String =
        serverUrl.replace(Regex("/v1/chat/completions/?$"), "").trimEnd('/')

    private fun authHeader(username: String, password: String): String =
        "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    // ── AgentAssistant ───────────────────────────

    suspend fun fetchAAConfig(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<AAConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${adminBase(serverUrl)}/admin_api/agent-assistant/config"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body.string().take(200)}")
            JSONObject(response.body.string()).toAAConfig()
        }.onFailure { Log.w(TAG, "fetchAAConfig failed", it) }
    }

    suspend fun saveAAConfig(
        serverUrl: String,
        username: String,
        password: String,
        config: AAConfig,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${adminBase(serverUrl)}/admin_api/agent-assistant/config"
            val body = config.toJson().toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .post(body)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body.string().take(200)}")
        }.onFailure { Log.w(TAG, "saveAAConfig failed", it) }
    }

    // ── TaskAssistant ────────────────────────────

    suspend fun fetchFAConfig(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<FAConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${adminBase(serverUrl)}/admin_api/task-assistant/config"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body.string().take(200)}")
            JSONObject(response.body.string()).toFAConfig()
        }.onFailure { Log.w(TAG, "fetchFAConfig failed", it) }
    }

    suspend fun saveFAConfig(
        serverUrl: String,
        username: String,
        password: String,
        config: FAConfig,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${adminBase(serverUrl)}/admin_api/task-assistant/config"
            val body = config.toJson().toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .post(body)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body.string().take(200)}")
        }.onFailure { Log.w(TAG, "saveFAConfig failed", it) }
    }

    suspend fun fetchFAStatus(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<FAStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${adminBase(serverUrl)}/admin_api/task-assistant/status"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body.string().take(200)}")
            JSONObject(response.body.string()).toFAStatus()
        }.onFailure { Log.w(TAG, "fetchFAStatus failed", it) }
    }

    suspend fun triggerTask(
        serverUrl: String,
        username: String,
        password: String,
        taskId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${adminBase(serverUrl)}/admin_api/task-assistant/trigger"
            val body = JSONObject().put("taskId", taskId).toString().toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .post(body)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${response.body.string().take(200)}")
        }.onFailure { Log.w(TAG, "triggerTask failed", it) }
    }
}
