package com.vcpnative.app.feature.overlay.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DeviceController(private val service: AccessibilityService) {

    enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    enum class SwipeDistance(val fraction: Float) {
        SHORT(0.15f),
        MEDIUM(0.35f),
        LONG(0.6f),
    }

    // ── Gesture-based actions ──

    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(stroke)
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(stroke)
    }

    suspend fun swipe(direction: SwipeDirection, distance: SwipeDistance = SwipeDistance.MEDIUM): Boolean {
        val metrics = getDisplayMetrics()
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f

        val offsetX: Float
        val offsetY: Float
        when (direction) {
            SwipeDirection.UP -> {
                offsetX = 0f
                offsetY = -(metrics.heightPixels * distance.fraction)
            }
            SwipeDirection.DOWN -> {
                offsetX = 0f
                offsetY = metrics.heightPixels * distance.fraction
            }
            SwipeDirection.LEFT -> {
                offsetX = -(metrics.widthPixels * distance.fraction)
                offsetY = 0f
            }
            SwipeDirection.RIGHT -> {
                offsetX = metrics.widthPixels * distance.fraction
                offsetY = 0f
            }
        }

        val path = Path().apply {
            moveTo(centerX, centerY)
            lineTo(centerX + offsetX, centerY + offsetY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        return dispatchGesture(stroke)
    }

    suspend fun swipeCoordinates(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long = 300,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(stroke)
    }

    // ── Node-based actions ──

    fun clickNode(accessibilityNode: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = accessibilityNode
        while (node != null) {
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (node != accessibilityNode) node.recycle()
                return result
            }
            val parent = node.parent
            if (node != accessibilityNode) node.recycle()
            node = parent
        }
        return false
    }

    fun setTextOnNode(accessibilityNode: AccessibilityNodeInfo, text: String): Boolean {
        // Clear existing text first
        accessibilityNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return accessibilityNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollNode(accessibilityNode: AccessibilityNodeInfo, forward: Boolean): Boolean {
        var node: AccessibilityNodeInfo? = accessibilityNode
        while (node != null) {
            if (node.isScrollable) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val result = node.performAction(action)
                if (node != accessibilityNode) node.recycle()
                return result
            }
            val parent = node.parent
            if (node != accessibilityNode) node.recycle()
            node = parent
        }
        return false
    }

    // ── Global actions ──

    fun pressBack(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

    // ── App launch ──

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Internal helpers ──

    private suspend fun dispatchGesture(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
