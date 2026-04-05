package com.vcpnative.app.network.llm

import kotlinx.coroutines.flow.Flow

/**
 * Unified LLM adapter interface — inspired by aio-hub's adapter pattern.
 * Supports 15+ providers through a common request/response contract.
 */
interface LlmAdapter {
    val providerId: String

    fun chat(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        options: LlmRequestOptions,
    ): Flow<LlmStreamEvent>
}

/** Provider profile — API key, base URL, and provider-specific config. */
data class LlmProfile(
    val id: String,
    val name: String,
    val provider: String,        // "openai" | "anthropic" | "google" | "deepseek" | "ollama" | "openai-compatible"
    val baseUrl: String,
    val apiKeys: List<String>,
    val customHeaders: Map<String, String> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
)

data class LlmMessage(
    val role: String,            // "system" | "user" | "assistant"
    val content: String,
    val name: String? = null,
)

data class LlmRequestOptions(
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stream: Boolean = true,
    val requestId: String? = null,
)

sealed interface LlmStreamEvent {
    data object Started : LlmStreamEvent
    data class Delta(val text: String) : LlmStreamEvent
    data class Completed(val fullText: String, val inputTokens: Int = 0, val outputTokens: Int = 0) : LlmStreamEvent
    data class Error(val message: String) : LlmStreamEvent
}
