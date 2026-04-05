package com.vcpnative.app.network.llm

import android.util.Log
import com.vcpnative.app.data.files.AtomicFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent storage for LLM service profiles (API keys, base URLs).
 * Inspired by aio-hub's useLlmProfiles composable.
 */
class LlmProfileStore(
    private val configFile: File,
) {
    private val _profiles = MutableStateFlow<List<LlmProfile>>(emptyList())
    val profiles: StateFlow<List<LlmProfile>> = _profiles.asStateFlow()

    suspend fun load() {
        _profiles.value = withContext(Dispatchers.IO) {
            try {
                if (configFile.exists()) {
                    val text = configFile.readText()
                    if (text.isBlank()) {
                        Log.w(TAG, "LLM profiles file is empty")
                        emptyList()
                    } else {
                        val arr = JSONArray(text)
                        (0 until arr.length()).mapNotNull { parseProfile(arr.optJSONObject(it)) }
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LLM profiles — file may be corrupted: ${configFile.absolutePath}", e)
                // Back up corrupted file so user data isn't silently lost forever
                runCatching {
                    val backup = File(configFile.absolutePath + ".bak")
                    if (configFile.exists() && !backup.exists()) {
                        configFile.copyTo(backup)
                        Log.w(TAG, "Corrupted profiles backed up to: ${backup.absolutePath}")
                    }
                }
                emptyList()
            }
        }
    }

    suspend fun save(profiles: List<LlmProfile>) {
        _profiles.value = profiles
        withContext(Dispatchers.IO) {
            try {
                configFile.parentFile?.mkdirs()
                val arr = JSONArray()
                profiles.forEach { arr.put(it.toJson()) }
                AtomicFileWriter.writeJson(configFile, arr.toString(2))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save LLM profiles", e)
            }
        }
    }

    suspend fun addProfile(profile: LlmProfile) {
        save(_profiles.value + profile)
    }

    suspend fun updateProfile(profile: LlmProfile) {
        save(_profiles.value.map { if (it.id == profile.id) profile else it })
    }

    suspend fun deleteProfile(profileId: String) {
        save(_profiles.value.filterNot { it.id == profileId })
    }

    fun findById(profileId: String): LlmProfile? = _profiles.value.find { it.id == profileId }

    private fun parseProfile(json: JSONObject?): LlmProfile? {
        json ?: return null
        val keysArr = json.optJSONArray("apiKeys") ?: JSONArray()
        val keys = (0 until keysArr.length()).map { keysArr.getString(it) }
        val headersObj = json.optJSONObject("customHeaders") ?: JSONObject()
        val headers = mutableMapOf<String, String>()
        headersObj.keys().forEach { headers[it] = headersObj.optString(it) }
        return LlmProfile(
            id = json.optString("id"),
            name = json.optString("name"),
            provider = json.optString("provider"),
            baseUrl = json.optString("baseUrl"),
            apiKeys = keys,
            customHeaders = headers,
        )
    }

    private fun LlmProfile.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("provider", provider)
        put("baseUrl", baseUrl)
        put("apiKeys", JSONArray(apiKeys))
        if (customHeaders.isNotEmpty()) {
            put("customHeaders", JSONObject(customHeaders))
        }
    }

    companion object {
        private const val TAG = "LlmProfileStore"
    }
}
