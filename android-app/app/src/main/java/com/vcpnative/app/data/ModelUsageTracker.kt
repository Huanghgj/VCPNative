package com.vcpnative.app.data

import android.util.Log
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.files.AtomicFileWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks model usage counts and favorite models.
 * Aligns with VCPChat's modelUsageTracker.js.
 *
 * Data is persisted as JSON files in the compat AppData directory:
 * - model_usage_stats.json: { "model-id": count, ... }
 * - model_favorites.json: ["model-id", ...]
 */
class ModelUsageTracker(
    private val fileStore: AppFileStore,
) {
    private val mutex = Mutex()
    private var usageCache: MutableMap<String, Int>? = null
    private var favoritesCache: MutableList<String>? = null

    private val statsFile: File get() = File(fileStore.compatAppDataDir(), "model_usage_stats.json")
    private val favoritesFile: File get() = File(fileStore.compatAppDataDir(), "model_favorites.json")

    /**
     * Debounced stats persistence.
     * Ported from VCPMobile model_manager.rs: 5-second debounce to batch writes.
     */
    private val statsDirty = AtomicBoolean(false)
    private val debounceScope = CoroutineScope(Dispatchers.IO)
    private var debounceJob: Job? = null

    /** Record a model usage (called when a chat message is sent). */
    suspend fun recordUsage(modelId: String) {
        if (modelId.isBlank()) return
        mutex.withLock {
            val stats = loadStats()
            stats[modelId] = (stats[modelId] ?: 0) + 1
            usageCache = stats
            statsDirty.set(true)
            scheduleDebouncedSave()
        }
    }

    private fun scheduleDebouncedSave() {
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(DEBOUNCE_MS)
            if (statsDirty.compareAndSet(true, false)) {
                mutex.withLock {
                    usageCache?.let { flushStatsToDisk(it) }
                }
            }
        }
    }

    /** Get top N most-used model IDs. */
    suspend fun getHotModels(topN: Int = 10): List<String> = mutex.withLock {
        loadStats()
            .entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { it.key }
    }

    /** Toggle a model's favorite status. Returns true if now favorited. */
    suspend fun toggleFavorite(modelId: String): Boolean = mutex.withLock {
        val favorites = loadFavorites()
        val index = favorites.indexOf(modelId)
        if (index == -1) {
            favorites.add(modelId)
            saveFavorites(favorites)
            true
        } else {
            favorites.removeAt(index)
            saveFavorites(favorites)
            false
        }
    }

    /** Get all favorite model IDs. */
    suspend fun getFavorites(): List<String> = mutex.withLock {
        loadFavorites().toList()
    }

    private suspend fun loadStats(): MutableMap<String, Int> {
        usageCache?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                if (statsFile.exists()) {
                    val json = JSONObject(statsFile.readText())
                    val map = mutableMapOf<String, Int>()
                    json.keys().forEach { key -> map[key] = json.optInt(key, 0) }
                    map
                } else {
                    mutableMapOf()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load model usage stats", e)
                mutableMapOf()
            }
        }.also { usageCache = it }
    }

    private suspend fun flushStatsToDisk(stats: MutableMap<String, Int>) {
        withContext(Dispatchers.IO) {
            try {
                statsFile.parentFile?.mkdirs()
                AtomicFileWriter.writeJson(statsFile, JSONObject(stats as Map<*, *>).toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save model usage stats", e)
            }
        }
    }

    private suspend fun loadFavorites(): MutableList<String> {
        favoritesCache?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                if (favoritesFile.exists()) {
                    val arr = JSONArray(favoritesFile.readText())
                    (0 until arr.length()).map { arr.getString(it) }.toMutableList()
                } else {
                    mutableListOf()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load model favorites", e)
                mutableListOf()
            }
        }.also { favoritesCache = it }
    }

    private suspend fun saveFavorites(favorites: MutableList<String>) {
        favoritesCache = favorites
        withContext(Dispatchers.IO) {
            try {
                favoritesFile.parentFile?.mkdirs()
                AtomicFileWriter.writeJson(favoritesFile, JSONArray(favorites).toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save model favorites", e)
            }
        }
    }

    companion object {
        private const val TAG = "ModelUsageTracker"
        /** Debounce delay for stats persistence (matching VCPMobile's 5s). */
        private const val DEBOUNCE_MS = 5000L
    }
}
