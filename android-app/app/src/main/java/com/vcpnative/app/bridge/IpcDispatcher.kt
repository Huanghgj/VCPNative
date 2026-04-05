package com.vcpnative.app.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatches IPC messages from the VCPChat JS modules to Kotlin handlers.
 *
 * Each handler module registers its channels via [register]. When a JS module
 * calls electronAPI.someMethod(), the bridge shim converts it to a channel +
 * args message, and this dispatcher routes it to the matching handler.
 */
class IpcDispatcher {

    /**
     * Handler function type. Receives a JSONArray of arguments and returns
     * a result (JSONObject, JSONArray, String, Number, Boolean, or null).
     */
    fun interface Handler {
        suspend fun handle(args: JSONArray): Any?
    }

    /**
     * Callback for pushing events from Kotlin back to the WebView JS layer.
     * Set by VcpModuleHost after the WebView is created.
     */
    var eventEmitter: ((channel: String, data: Any?) -> Unit)? = null

    private val handlers = mutableMapOf<String, Handler>()

    /**
     * Register a handler for one or more IPC channels.
     */
    fun register(channel: String, handler: Handler) {
        handlers[channel] = handler
    }

    /**
     * Convenience: register multiple channels with the same handler.
     */
    fun register(channels: List<String>, handler: Handler) {
        channels.forEach { handlers[it] = handler }
    }

    /**
     * Dispatch an IPC message to its handler.
     *
     * Errors are caught and returned as a JSON error object instead of propagating,
     * matching VCPMobile's `Result<T, String>` Tauri command pattern.
     *
     * @return The handler's result, a JSON error object, or null if no handler.
     */
    suspend fun handle(channel: String, args: JSONArray): Any? {
        val handler = handlers[channel]
            ?: run {
                android.util.Log.w("IpcDispatcher", "No handler for channel: $channel")
                return null
            }
        return try {
            handler.handle(args)
        } catch (e: Exception) {
            BridgeLogger.e("IpcDispatcher", "Error in channel '$channel': ${e.message}")
            JSONObject().apply {
                put("error", true)
                put("message", e.message ?: "Unknown error in $channel")
            }
        }
    }

    /**
     * Check if a handler is registered for the given channel.
     */
    fun hasHandler(channel: String): Boolean = handlers.containsKey(channel)

    /**
     * List all registered channels (for debugging).
     */
    fun registeredChannels(): Set<String> = handlers.keys.toSet()
}
