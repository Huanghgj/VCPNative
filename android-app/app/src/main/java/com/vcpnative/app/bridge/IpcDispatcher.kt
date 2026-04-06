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
     * 幂等性防护♡ 记录正在飞行中的非并发 channel
     * 从桌面端 ipcContracts.js 的 supportsConcurrent 概念移植而来——
     * 防止同一 channel 被并发调用导致竞态条件喵
     */
    private val inflightChannels = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val nonConcurrentChannels: Set<String> = IpcCatalog.nonConcurrentChannels()

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
     * 从 IpcCatalog 批量注册所有未实现的 stub channel♡
     * 替代以前手写的 90+ 行 stubChannels 列表喵
     */
    fun registerStubsFromCatalog() {
        IpcCatalog.stubChannels().forEach { channel ->
            if (!hasHandler(channel)) {
                register(channel) { null }
            }
        }
    }

    /**
     * Dispatch an IPC message to its handler.
     *
     * Includes idempotency guard for non-concurrent channels: if a channel
     * is already in-flight, the duplicate call is rejected with an error.
     *
     * @return The handler's result, a JSON error object, or null if no handler.
     */
    suspend fun handle(channel: String, args: JSONArray): Any? {
        val handler = handlers[channel]
            ?: run {
                android.util.Log.w("IpcDispatcher", "No handler for channel: $channel")
                return null
            }

        // 幂等性防护♡ 非并发 channel 如果已有飞行中请求，直接拒绝
        val needsGuard = channel in nonConcurrentChannels
        if (needsGuard) {
            val alreadyInflight = inflightChannels.putIfAbsent(channel, true) != null
            if (alreadyInflight) {
                BridgeLogger.w("IpcDispatcher", "Idempotency guard: '$channel' already in-flight, rejecting duplicate")
                return JSONObject().apply {
                    put("error", true)
                    put("message", "Channel '$channel' is already processing a request")
                    put("idempotencyRejected", true)
                }
            }
        }

        return try {
            handler.handle(args)
        } catch (e: Exception) {
            BridgeLogger.e("IpcDispatcher", "Error in channel '$channel': ${e.message}")
            JSONObject().apply {
                put("error", true)
                put("message", e.message ?: "Unknown error in $channel")
            }
        } finally {
            if (needsGuard) {
                inflightChannels.remove(channel)
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
