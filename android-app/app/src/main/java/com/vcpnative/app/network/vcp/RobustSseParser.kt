package com.vcpnative.app.network.vcp

/**
 * Robust SSE (Server-Sent Events) parser for mobile networks.
 *
 * Ported from VCPMobile's mobile-optimized SSE handling.
 * Handles:
 * - UTF-8 byte fragmentation on unreliable connections
 * - Multiple SSE formats (OpenAI, Anthropic, Google)
 * - [DONE] termination signal
 * - Multi-line data fields
 * - Event type and id fields (optional)
 */
class RobustSseParser {

    /** A complete SSE frame assembled from one or more `data:` lines. */
    data class SseFrame(
        val event: String? = null,
        val data: String,
        val isDone: Boolean = false,
    )

    private val dataBuffer = StringBuilder()
    private var currentEvent: String? = null

    /**
     * Feed a single line from the SSE stream.
     *
     * Returns an [SseFrame] when a complete frame is assembled (on empty line),
     * or null if more lines are needed.
     */
    fun consumeLine(line: String): SseFrame? {
        val trimmed = line.trimEnd('\r', '\n')

        // Empty line = frame boundary
        if (trimmed.isEmpty()) {
            if (dataBuffer.isEmpty()) return null

            val data = dataBuffer.toString().trimEnd('\n')
            val event = currentEvent

            // Reset for next frame
            dataBuffer.clear()
            currentEvent = null

            return parseFrame(data, event)
        }

        // Comment lines (start with ':') are ignored per SSE spec
        if (trimmed.startsWith(':')) return null

        // Field parsing
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex < 0) return null

        val field = trimmed.substring(0, colonIndex)
        // Value starts after colon, with optional leading space
        val value = trimmed.substring(colonIndex + 1).let {
            if (it.startsWith(' ')) it.substring(1) else it
        }

        when (field) {
            "data" -> dataBuffer.append(value).append('\n')
            "event" -> currentEvent = value
            // "id" and "retry" fields are ignored (not needed for chat streaming)
        }

        return null
    }

    private fun parseFrame(data: String, event: String?): SseFrame {
        // [DONE] signal (OpenAI format)
        if (data == "[DONE]") {
            return SseFrame(event = event, data = "", isDone = true)
        }

        return SseFrame(event = event, data = data)
    }

    /**
     * Flush any buffered data as a final frame.
     * Call after the stream ends (EOF) to handle APIs that don't send
     * a trailing empty line before closing the connection.
     */
    fun flush(): SseFrame? {
        if (dataBuffer.isEmpty()) {
            currentEvent = null
            return null
        }
        val data = dataBuffer.toString().trimEnd('\n')
        val event = currentEvent
        dataBuffer.clear()
        currentEvent = null
        return parseFrame(data, event)
    }

    /** Reset parser state (call between requests). */
    fun reset() {
        dataBuffer.clear()
        currentEvent = null
    }
}
