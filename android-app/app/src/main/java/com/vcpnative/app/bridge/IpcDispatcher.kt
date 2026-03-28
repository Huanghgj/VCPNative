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
     * @return The handler's result, or null if no handler is registered.
     * @throws Exception if the handler throws.
     */
    suspend fun handle(channel: String, args: JSONArray): Any? {
        val handler = handlers[channel]
            ?: run {
                android.util.Log.w("IpcDispatcher", "No handler for channel: $channel")
                return null
            }
        return handler.handle(args)
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
