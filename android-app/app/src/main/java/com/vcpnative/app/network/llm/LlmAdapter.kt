package com.vcpnative.app.network.llm

import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType

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
    /** Multimodal content parts (OpenAI format). When non-empty, adapters
     *  should serialize these instead of the flat [content] string. */
    val contentParts: List<LlmContentPart> = emptyList(),
)

/** A single content part for multimodal messages. */
data class LlmContentPart(
    val type: String,            // "text" | "image_url"
    val text: String? = null,
    val imageUrl: String? = null, // data URI or https URL
)

data class LlmRequestOptions(
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stream: Boolean = true,
    val requestId: String? = null,
)

/** Shared constants for LLM adapters. */
object LlmAdapterConstants {
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    val DATA_URI_REGEX = Regex("^data:([^;]+);base64,(.+)$")
}

sealed interface LlmStreamEvent {
    data object Started : LlmStreamEvent
    data class Delta(val text: String) : LlmStreamEvent
    /** Extended thinking / reasoning content (Claude thinking, DeepSeek reasoning). */
    data class Thinking(val text: String) : LlmStreamEvent
    data class Completed(val fullText: String, val inputTokens: Int = 0, val outputTokens: Int = 0) : LlmStreamEvent
    data class Error(val message: String) : LlmStreamEvent
}
