package com.vcpnative.app.model

data class AppSettings(
    val vcpServerUrl: String = "",
    val vcpApiKey: String = "",
    val vcpLogUrl: String = "",
    val vcpLogKey: String = "",
    val enableVcpToolInjection: Boolean = false,
    val enableAgentBubbleTheme: Boolean = false,
    val enableThoughtChainInjection: Boolean = false,
    // Context sanitizer: converts HTML→Markdown in older assistant messages.
    // Default OFF — VCPToolBox backend handles context management via contextTokenLimit.
    // Only enable if using direct LLM API without VCPToolBox.
    val enableContextSanitizer: Boolean = false,
    val contextSanitizerDepth: Int = 2,
    // Context folding: compresses old messages into summaries.
    // Default OFF — VCPToolBox backend handles context truncation server-side.
    // When VCPToolBox receives contextTokenLimit, it intelligently trims context.
    // Client-side folding destroys information the backend could have preserved.
    // Only enable as last resort for extremely long conversations on memory-constrained devices.
    val enableContextFolding: Boolean = false,
    val contextFoldingKeepRecentMessages: Int = 12,
    val contextFoldingTriggerMessageCount: Int = 24,
    val contextFoldingTriggerCharCount: Int = 24_000,
    val contextFoldingExcerptCharLimit: Int = 500,
    val contextFoldingMaxSummaryEntries: Int = 40,
    val topicSummaryModel: String = "gemini-2.5-flash",
    val enableFloatingWindow: Boolean = false,
    val overlayApiUrl: String = "",
    val overlayApiKey: String = "",
    val overlayModel: String = "",
    val lastAgentId: String? = null,
    val lastTopicId: String? = null,
) {
    val isConfigured: Boolean
        get() = vcpServerUrl.isNotBlank() && vcpApiKey.isNotBlank()

    /** Overlay uses its own config if set, otherwise falls back to global VCP settings. */
    val effectiveOverlayApiUrl: String
        get() = overlayApiUrl.ifBlank { vcpServerUrl }
    val effectiveOverlayApiKey: String
        get() = overlayApiKey.ifBlank { vcpApiKey }
    val effectiveOverlayModel: String
        get() = overlayModel.ifBlank { "gemini-2.5-flash" }
}

data class VcpModelInfo(
    val id: String,
    val ownedBy: String? = null,
)

data class ChatAttachment(
    val id: String,
    val fileId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val src: String,
    val internalFileName: String,
    val internalPath: String,
    val hash: String,
    val createdAt: Long,
    val extractedText: String? = null,
    val imageFrames: List<String> = emptyList(),
)

data class CompiledMessage(
    val role: String,
    val textContent: String? = null,
    val contentParts: List<CompiledMessagePart> = emptyList(),
)

data class CompiledMessagePart(
    val type: String,
    val text: String? = null,
    val dataUrl: String? = null,
)

data class CompiledChatRequest(
    val agentId: String,
    val topicId: String,
    val requestId: String,
    val endpoint: String,
    val apiBaseUrl: String? = null,
    val apiKey: String,
    val model: String = "gemini-2.5-flash",
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val contextTokenLimit: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stream: Boolean = true,
    val thinking: Boolean? = null,
    val messages: List<CompiledMessage>,
)

sealed interface StreamSessionEvent {
    data object Started : StreamSessionEvent

    data class TextDelta(
        val text: String,
    ) : StreamSessionEvent

    data class Completed(
        val fullText: String,
    ) : StreamSessionEvent

    data class Interrupted(
        val partialText: String,
    ) : StreamSessionEvent

    data class Failed(
        val partialText: String,
        val message: String,
    ) : StreamSessionEvent
}

data class StreamInterruptResult(
    val success: Boolean,
    val message: String? = null,
)
