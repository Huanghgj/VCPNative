package com.vcpnative.app.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized logger for the VCPChat bridge system.
 *
 * - Keeps a ring buffer of recent entries (viewable in-app)
 * - Optionally writes to a log file in app-private storage
 * - Controlled by an enable/disable toggle
 */
object BridgeLogger {

    private const val TAG = "VcpBridge"
    private const val MAX_MEMORY_ENTRIES = 500
    private const val LOG_FILE_NAME = "vcpbridge_debug.log"
    private const val MAX_LOG_FILE_SIZE = 2 * 1024 * 1024L // 2 MB, rotate per file
    private const val MAX_LOG_DIR_SIZE = 10 * 1024 * 1024L // 10 MB total log dir cap

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val module: String,
        val message: String,
    ) {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun formatted(): String =
            "[${fmt.format(Date(timestamp))}] ${level.name.first()}/$module: $message"
    }

    // ---- State ----

    @Volatile
    var enabled: Boolean = false

    private val _entries = ConcurrentLinkedDeque<Entry>()

    /** Observable revision — UI can collect this to know when to refresh. */
    private val _entriesVersion = MutableStateFlow(0L)
    val entriesVersion: StateFlow<Long> = _entriesVersion.asStateFlow()
    private val entriesVersionCounter = AtomicLong(0L)

    private var logFile: File? = null

    // ---- Init ----

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
    }

    // ---- Logging ----

    fun d(module: String, msg: String) = log(Level.DEBUG, module, msg)
    fun i(module: String, msg: String) = log(Level.INFO, module, msg)
    fun w(module: String, msg: String) = log(Level.WARN, module, msg)
    fun e(module: String, msg: String) = log(Level.ERROR, module, msg)

    private val pendingFileWrites = ConcurrentLinkedQueue<Entry>()
    private val flushScheduled = AtomicBoolean(false)
    private val flushScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "BridgeLoggerFlush").apply { isDaemon = true }
    }

    fun log(level: Level, module: String, message: String) {
        val logcatLevel = when (level) {
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }
        Log.println(logcatLevel, TAG, "[$module] $message")

        if (!enabled) return

        val entry = Entry(level = level, module = module, message = message)

        // Ring buffer
        _entries.addLast(entry)
        while (_entries.size > MAX_MEMORY_ENTRIES) {
            _entries.pollFirst()
        }
        _entriesVersion.value = entriesVersionCounter.incrementAndGet()

        // 批量写文件：收集到队列，延迟刷盘
        pendingFileWrites.add(entry)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return
        }
        flushScheduler.schedule(
            {
                try {
                    flushPendingWrites()
                } finally {
                    flushScheduled.set(false)
                    if (pendingFileWrites.isNotEmpty()) {
                        scheduleFlush()
                    }
                }
            },
            500,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun flushPendingWrites() {
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
                val backup = java.io.File(file.parent, "${file.nameWithoutExtension}_prev.log")
                backup.delete()
                file.renameTo(backup)
            }
            val sb = StringBuilder()
            while (true) {
                val entry = pendingFileWrites.poll() ?: break
                sb.append(entry.formatted()).append('\n')
            }
            if (sb.isNotEmpty()) {
                file.appendText(sb.toString())
            }
            // Prune old logs if total dir size exceeds cap
            pruneLogDirectory()
        } catch (_: Exception) {}
    }

    /**
     * Delete oldest log files when total size exceeds [MAX_LOG_DIR_SIZE].
     * Prevents unbounded disk usage from long-running sessions.
     */
    private fun pruneLogDirectory() {
        val dir = logFile?.parentFile ?: return
        val logFiles = dir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var totalSize = logFiles.sumOf { it.length() }
        for (file in logFiles) {
            if (totalSize <= MAX_LOG_DIR_SIZE) break
            // Never delete the active log file
            if (file.absolutePath == logFile?.absolutePath) continue
            totalSize -= file.length()
            file.delete()
        }
    }

    // ---- Read ----

    /** Get all in-memory entries (newest last). */
    fun entries(): List<Entry> = _entries.toList()

    /** Get the log file path (for sharing). */
    fun logFilePath(): String? = logFile?.takeIf { it.exists() }?.absolutePath

    /** Read the full log file content. */
    fun readLogFile(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText() else ""
    }

    /** Clear in-memory entries and log file. */
    fun clear() {
        _entries.clear()
        pendingFileWrites.clear()
        _entriesVersion.value = entriesVersionCounter.incrementAndGet()
        logFile?.delete()
    }
}
