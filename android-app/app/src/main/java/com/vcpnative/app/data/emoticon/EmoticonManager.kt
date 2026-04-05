package com.vcpnative.app.data.emoticon

import com.vcpnative.app.bridge.BridgeLogger
import com.vcpnative.app.data.files.AppFileStore
import java.io.File
import kotlin.math.max

/**
 * Emoticon library manager with fuzzy URL matching.
 *
 * Ported from VCPMobile `emoticon_manager.rs`.
 * - Levenshtein edit distance for similarity
 * - Weighted scoring: 70% category + 30% filename
 * - 0.6 acceptance threshold
 */
class EmoticonManager(private val fileStore: AppFileStore) {

    data class EmoticonItem(
        val url: String,
        val category: String,
        val filename: String,
        val searchKey: String,
    )

    private val library = mutableListOf<EmoticonItem>()

    /** Scan the emoticons directory and rebuild the library. Returns count. */
    fun generateLibrary(): Int {
        library.clear()
        val emoticonDir = File(fileStore.compatAppDataDir(), "emoticons")
        if (!emoticonDir.exists()) {
            BridgeLogger.d(TAG, "Emoticon directory not found: ${emoticonDir.absolutePath}")
            return 0
        }

        emoticonDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .forEach { file ->
                library.add(EmoticonItem(
                    url = file.absolutePath,
                    category = file.parentFile?.name ?: "",
                    filename = file.nameWithoutExtension,
                    searchKey = file.nameWithoutExtension.lowercase(),
                ))
            }

        BridgeLogger.i(TAG, "Generated emoticon library: ${library.size} items")
        return library.size
    }

    /** Get the full library. */
    fun getLibrary(): List<EmoticonItem> = library.toList()

    /**
     * Fix a broken emoticon URL using fuzzy matching.
     *
     * @param brokenUrl The URL that no longer resolves
     * @return The best matching emoticon URL, or null if no good match
     */
    fun fixUrl(brokenUrl: String): String? {
        if (library.isEmpty()) return null

        val (targetCategory, targetFilename) = extractInfo(brokenUrl)

        var bestMatch: EmoticonItem? = null
        var bestScore = 0.0

        for (item in library) {
            val catScore = similarity(targetCategory, item.category)
            val nameScore = similarity(targetFilename, item.filename)
            val score = catScore * CATEGORY_WEIGHT + nameScore * FILENAME_WEIGHT
            if (score > bestScore) {
                bestScore = score
                bestMatch = item
            }
        }

        return if (bestScore >= ACCEPTANCE_THRESHOLD) {
            BridgeLogger.d(TAG, "Fixed URL (score=${"%.2f".format(bestScore)}): $brokenUrl -> ${bestMatch?.url}")
            bestMatch?.url
        } else {
            null
        }
    }

    /** Extract category and filename from a URL path. */
    private fun extractInfo(url: String): Pair<String, String> {
        val parts = url.replace('\\', '/').split('/')
        val filename = parts.lastOrNull()?.substringBeforeLast('.') ?: ""
        val category = if (parts.size >= 2) parts[parts.size - 2] else ""
        return category to filename
    }

    /** Compute Levenshtein edit distance between two strings. */
    private fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[m][n]
    }

    /** Normalized similarity score (0.0 - 1.0). */
    private fun similarity(a: String, b: String): Double {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - editDistance(a.lowercase(), b.lowercase()).toDouble() / maxLen
    }

    companion object {
        private const val TAG = "EmoticonManager"
        private const val CATEGORY_WEIGHT = 0.7
        private const val FILENAME_WEIGHT = 0.3
        private const val ACCEPTANCE_THRESHOLD = 0.6
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}
