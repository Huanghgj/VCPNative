package com.vcpnative.app.data.cache

import com.vcpnative.app.bridge.BridgeLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

/**
 * Thread-safe compiled-regex cache.
 *
 * Ported from VCPMobile `chat_manager.rs` which uses DashMap<String, Regex>
 * to avoid recompiling the same pattern on every message.
 */
object RegexCache {

    private const val TAG = "RegexCache"

    /** pattern-string -> compiled Regex */
    private val cache = ConcurrentHashMap<String, Regex>(64)

    /**
     * Return a compiled [Regex] for [pattern], creating and caching it if absent.
     *
     * Invalid patterns are silently downgraded to a literal match so that
     * a single bad rule does not crash the whole processing pipeline.
     */
    fun get(pattern: String): Regex {
        return cache.getOrPut(pattern) {
            try {
                Regex(pattern)
            } catch (e: PatternSyntaxException) {
                BridgeLogger.w(TAG, "Invalid regex pattern, falling back to literal: $pattern")
                Regex(Regex.escape(pattern))
            }
        }
    }

    /**
     * Return a compiled [Regex] with explicit [options], caching by pattern+options key.
     */
    fun get(pattern: String, options: Set<RegexOption>): Regex {
        val key = "$pattern|${options.joinToString(",")}"
        return cache.getOrPut(key) {
            try {
                Regex(pattern, options)
            } catch (e: PatternSyntaxException) {
                BridgeLogger.w(TAG, "Invalid regex pattern, falling back to literal: $pattern")
                Regex(Regex.escape(pattern), options)
            }
        }
    }

    /** Number of cached patterns. */
    val size: Int get() = cache.size

    /** Evict all cached patterns. */
    fun clear() {
        cache.clear()
        BridgeLogger.d(TAG, "Cache cleared")
    }
}
