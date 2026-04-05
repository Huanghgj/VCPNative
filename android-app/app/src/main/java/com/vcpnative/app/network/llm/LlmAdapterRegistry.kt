package com.vcpnative.app.network.llm

import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registry for LLM adapters — resolves the correct adapter for a profile.
 * Inspired by aio-hub's adapter registry pattern.
 */
class LlmAdapterRegistry(httpClient: OkHttpClient) {
    private val adapters = mapOf<String, LlmAdapter>(
        "openai" to OpenAiCompatibleAdapter(httpClient, "openai"),
        "openai-compatible" to OpenAiCompatibleAdapter(httpClient, "openai-compatible"),
        "deepseek" to OpenAiCompatibleAdapter(httpClient, "deepseek"),
        "groq" to OpenAiCompatibleAdapter(httpClient, "groq"),
        "openrouter" to OpenAiCompatibleAdapter(httpClient, "openrouter"),
        "ollama" to OpenAiCompatibleAdapter(httpClient, "ollama"),
        "siliconflow" to OpenAiCompatibleAdapter(httpClient, "siliconflow"),
        "anthropic" to AnthropicAdapter(httpClient),
        "google" to GoogleGeminiAdapter(httpClient),
    )

    fun getAdapter(provider: String): LlmAdapter? = adapters[provider]

    fun supportedProviders(): Set<String> = adapters.keys
}

/**
 * API Key manager — round-robin with circuit breaking.
 * Inspired by aio-hub's useLlmKeyManager.
 */
class LlmKeyManager {
    private val keyStates = ConcurrentHashMap<String, KeyState>()
    private val rotationIndex = ConcurrentHashMap<String, AtomicInteger>()

    data class KeyState(
        val key: String,
        @Volatile var isEnabled: Boolean = true,
        @Volatile var isBroken: Boolean = false,
        @Volatile var errorCount: Int = 0,
        @Volatile var disabledTime: Long = 0,
    )

    /** Select the next available key for a profile, rotating through healthy keys.
     *  @Synchronized because recovery check + state mutation must be atomic across threads. */
    @Synchronized
    fun selectKey(profile: LlmProfile): String? {
        val keys = profile.apiKeys.filter { it.isNotBlank() }
        if (keys.isEmpty()) return null

        // Auto-recover broken keys after 60 seconds
        keys.forEach { key ->
            val state = keyStates.getOrPut(key) { KeyState(key) }
            if (state.isBroken && System.currentTimeMillis() - state.disabledTime > RECOVERY_TIMEOUT_MS) {
                state.isBroken = false
                state.errorCount = 0
                Log.d(TAG, "Key recovered: ${key.take(8)}...")
            }
        }

        val available = keys.filter { key ->
            val state = keyStates[key]
            state == null || (state.isEnabled && !state.isBroken)
        }
        if (available.isEmpty()) return keys.first() // Fallback: use first even if broken

        val index = rotationIndex.getOrPut(profile.id) { AtomicInteger(0) }
        val idx = index.getAndIncrement() % available.size
        return available[idx]
    }

    /** Report a key failure — increments error count, breaks after threshold. */
    @Synchronized
    fun reportError(key: String) {
        val state = keyStates.getOrPut(key) { KeyState(key) }
        state.errorCount++
        if (state.errorCount >= ERROR_THRESHOLD) {
            state.isBroken = true
            state.disabledTime = System.currentTimeMillis()
            Log.w(TAG, "Key broken (${state.errorCount} errors): ${key.take(8)}...")
        }
    }

    /** Report a key success — resets error count. */
    @Synchronized
    fun reportSuccess(key: String) {
        keyStates[key]?.let {
            it.errorCount = 0
            it.isBroken = false
        }
    }

    companion object {
        private const val TAG = "LlmKeyManager"
        private const val ERROR_THRESHOLD = 3
        private const val RECOVERY_TIMEOUT_MS = 60_000L
    }
}
