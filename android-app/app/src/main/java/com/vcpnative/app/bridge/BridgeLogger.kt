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
    private const val MAX_LOG_FILE_SIZE = 2 * 1024 * 1024L // 2 MB, rotate

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

    /** Observable entry count — UI can collect this to know when to refresh. */
    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

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

    fun log(level: Level, module: String, message: String) {
        // Always write to logcat
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
        _entryCount.value = _entries.size

        // File
        writeToFile(entry)
    }

    // ---- File output ----

    private fun writeToFile(entry: Entry) {
        val file = logFile ?: return
        try {
            // Rotate if too large
            if (file.exists() && file.length() > MAX_LOG_FILE_SIZE) {
                val backup = File(file.parent, "${file.nameWithoutExtension}_prev.log")
                backup.delete()
                file.renameTo(backup)
            }
            file.appendText(entry.formatted() + "\n")
        } catch (_: Exception) {
            // Don't crash for logging failures
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
        _entryCount.value = 0
        logFile?.delete()
    }
}
