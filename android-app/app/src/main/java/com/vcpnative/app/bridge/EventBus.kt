package com.vcpnative.app.bridge

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Dual-channel event bus: WebView (JS) + Kotlin (Compose).
 *
 * Ported from VCPMobile's Tauri Event System which emits events
 * to both the frontend and internal Rust listeners.
 *
 * Well-known channels:
 * - `vcp-stream-event`        : AI streaming response chunks
 * - `vcp-stream-chunk`        : Batched text delta for WebView
 * - `vcp-file-change`         : External file modifications
 * - `vcp-group-turn-finished` : Group chat turn completion
 * - `topic-index-updated`     : Topic metadata changes
 * - `theme-changed`           : Theme switch notifications
 * - `settings-changed`        : Settings update notifications
 */
class EventBus {

    /** Payload for Kotlin-side consumers. */
    data class BusEvent(
        val channel: String,
        val data: Any? = null,
    )

    // ── WebView channel ──

    /** Callback to push events into the WebView via evaluateJavascript. */
    @Volatile
    var webViewEmitter: ((channel: String, data: Any?) -> Unit)? = null

    // ── Kotlin channel ──

    private val _events = MutableSharedFlow<BusEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Compose / coroutine consumers collect this flow. */
    val events: SharedFlow<BusEvent> = _events.asSharedFlow()

    /**
     * Emit an event to both channels (WebView + Kotlin).
     *
     * Safe to call from any thread.
     */
    fun emit(channel: String, data: Any? = null) {
        // Push to WebView
        webViewEmitter?.invoke(channel, data)
        // Push to Kotlin consumers
        _events.tryEmit(BusEvent(channel, data))
    }

    /**
     * Emit only to Kotlin consumers (no WebView push).
     * Use for internal state changes that the JS side doesn't need.
     */
    fun emitInternal(channel: String, data: Any? = null) {
        _events.tryEmit(BusEvent(channel, data))
    }
}
