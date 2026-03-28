package com.vcpnative.app.network.sse

data class SseFrame(
    val event: String? = null,
    val data: String? = null,
    val isDone: Boolean = false,
)

class SseEventParser {
    private val dataLines = mutableListOf<String>()
    private var eventName: String? = null

    fun consumeLine(line: String): SseFrame? {
        if (line.isBlank()) {
            return flush()
        }

        val trimmed = line.trim()
        if (trimmed.startsWith(":")) {
            return null
        }

        when {
            trimmed.startsWith("event:") -> {
                eventName = trimmed.removePrefix("event:").trim()
                return null
            }

            trimmed.startsWith("data:") -> {
                val value = trimmed.removePrefix("data:")
                // SSE spec: remove exactly one leading space if present
                dataLines += if (value.startsWith(' ')) value.substring(1) else value
                return null
            }

            else -> return null
        }
    }

    fun flush(): SseFrame? {
        if (dataLines.isEmpty()) {
            eventName = null
            return null
        }

        val payload = dataLines.joinToString(separator = "\n")
        val frame = if (payload == "[DONE]") {
            SseFrame(event = eventName, isDone = true)
        } else {
            SseFrame(event = eventName, data = payload)
        }

        dataLines.clear()
        eventName = null
        return frame
    }
}
