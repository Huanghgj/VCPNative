package com.vcpnative.app.network.vcp

import com.vcpnative.app.bridge.BridgeLogger
import kotlinx.coroutines.Job
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global tracker for in-flight API requests.
 *
 * Ported from VCPMobile `vcp_client.rs` ActiveRequests (Arc<DashMap>).
 * Tracks every streaming call so it can be interrupted by requestId,
 * or bulk-cancelled on topic switch / app backgrounding.
 */
class ActiveRequestTracker {

    data class ActiveRequest(
        val requestId: String,
        val call: Call,
        val job: Job,
        val startedAt: Long = System.currentTimeMillis(),
        val interrupted: AtomicBoolean = AtomicBoolean(false),
    )

    private val requests = ConcurrentHashMap<String, ActiveRequest>()

    /** Number of currently active requests. */
    val activeCount: Int get() = requests.size

    /** All currently tracked request IDs (snapshot). */
    val activeIds: Set<String> get() = requests.keys.toSet()

    /**
     * Start tracking a request.
     * Returns the [ActiveRequest] handle so the caller can check [ActiveRequest.interrupted].
     */
    fun track(requestId: String, call: Call, job: Job): ActiveRequest {
        val request = ActiveRequest(requestId, call, job)
        requests[requestId] = request
        BridgeLogger.d(TAG, "Tracking request $requestId (active: ${requests.size})")
        return request
    }

    /**
     * Stop tracking a request (normal completion).
     */
    fun remove(requestId: String) {
        requests.remove(requestId)
        BridgeLogger.d(TAG, "Removed request $requestId (active: ${requests.size})")
    }

    /**
     * Interrupt a specific request by ID.
     *
     * Sets the interrupted flag, cancels both the OkHttp Call and the coroutine Job.
     * Returns true if the request was found and interrupted.
     */
    fun interrupt(requestId: String): Boolean {
        val request = requests.remove(requestId) ?: return false
        request.interrupted.set(true)
        request.call.cancel()
        request.job.cancel()
        BridgeLogger.i(TAG, "Interrupted request $requestId")
        return true
    }

    /**
     * Interrupt ALL active requests.
     * Use on topic switch or app backgrounding.
     */
    fun interruptAll(): Int {
        val ids = requests.keys.toList()
        var count = 0
        for (id in ids) {
            if (interrupt(id)) count++
        }
        if (count > 0) {
            BridgeLogger.i(TAG, "Interrupted all: $count requests cancelled")
        }
        return count
    }

    /**
     * Remove stale requests that have been active longer than [maxAgeMs].
     * Safety net to prevent resource leaks from abandoned requests.
     */
    fun cleanupStale(maxAgeMs: Long = 10 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val staleIds = requests.entries
            .filter { (_, req) -> now - req.startedAt > maxAgeMs }
            .map { it.key }
        for (id in staleIds) {
            val req = requests.remove(id) ?: continue
            req.call.cancel()
            req.job.cancel()
            BridgeLogger.w(TAG, "Cleaned up stale request $id (age: ${(now - req.startedAt) / 1000}s)")
        }
    }

    companion object {
        private const val TAG = "RequestTracker"
    }
}
