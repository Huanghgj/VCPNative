package com.vcpnative.app.app.lifecycle

import com.vcpnative.app.bridge.BridgeLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-phase application lifecycle manager.
 *
 * Ported from VCPMobile `lifecycle_manager.rs`.
 * Provides observable startup state so the UI can show progress.
 *
 * Phase flow:
 *   INITIALIZING -> LOADING_DATA -> CONNECTING -> READY
 *                                                   |
 *                                              ERROR (any phase)
 */
enum class CoreStatus {
    /** Database and core services initializing */
    INITIALIZING,
    /** Loading settings, model catalog, profiles */
    LOADING_DATA,
    /** Connecting to external services (VcpLog, etc.) */
    CONNECTING,
    /** All systems go */
    READY,
    /** Bootstrap failed; see [AppLifecycleManager.lastError] */
    ERROR,
}

/**
 * Describes what the lifecycle manager is currently doing (human-readable).
 */
data class LifecyclePhase(
    val status: CoreStatus,
    val label: String,
)

class AppLifecycleManager(
    private val initDatabase: () -> Unit,
    private val loadSettings: suspend () -> Unit,
    private val loadModels: suspend () -> Unit,
    private val loadProfiles: suspend () -> Unit,
    private val connectServices: suspend () -> Unit,
) {
    private val _status = MutableStateFlow(CoreStatus.INITIALIZING)
    val status: StateFlow<CoreStatus> = _status.asStateFlow()

    private val _phase = MutableStateFlow(LifecyclePhase(CoreStatus.INITIALIZING, ""))
    val phase: StateFlow<LifecyclePhase> = _phase.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Run the full bootstrap sequence.
     *
     * Mirrors VCPMobile's linearized bootstrap:
     * sequential critical ops + parallel non-blocking tasks.
     */
    suspend fun bootstrap() {
        try {
            // Phase 1: Database init (sequential, must come first)
            setPhase(CoreStatus.INITIALIZING, "初始化数据库…")
            initDatabase()

            // Phase 2: Load core data (parallel where possible)
            setPhase(CoreStatus.LOADING_DATA, "加载配置数据…")
            coroutineScope {
                val settingsJob = async { loadSettings() }
                val modelsJob = async { loadModels() }
                val profilesJob = async { loadProfiles() }
                settingsJob.await()
                modelsJob.await()
                profilesJob.await()
            }

            // Phase 3: Connect external services
            setPhase(CoreStatus.CONNECTING, "连接服务…")
            connectServices()

            // Phase 4: Ready
            setPhase(CoreStatus.READY, "就绪")
            BridgeLogger.i(TAG, "Bootstrap completed successfully")

        } catch (e: Exception) {
            BridgeLogger.e(TAG, "Bootstrap failed: ${e.message}")
            _lastError.value = e.message ?: "Unknown bootstrap error"
            setPhase(CoreStatus.ERROR, "启动失败: ${e.message}")
        }
    }

    /**
     * Reset to allow re-bootstrap (e.g. after settings change).
     */
    fun reset() {
        _status.value = CoreStatus.INITIALIZING
        _lastError.value = null
        _phase.value = LifecyclePhase(CoreStatus.INITIALIZING, "")
    }

    private fun setPhase(status: CoreStatus, label: String) {
        _status.value = status
        _phase.value = LifecyclePhase(status, label)
        BridgeLogger.d(TAG, "Phase: $status - $label")
    }

    companion object {
        private const val TAG = "Lifecycle"
    }
}
