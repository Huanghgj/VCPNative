package com.vcpnative.app.feature.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.vcpnative.app.VcpNativeApplication
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.feature.overlay.agent.AgentToolExecutor
import com.vcpnative.app.feature.overlay.agent.DeviceController
import com.vcpnative.app.feature.overlay.agent.ScreenReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class AiOverlayService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var chatManager: OverlayChatManager? = null

    // Agent components
    private var screenReader: ScreenReader? = null
    private var deviceController: DeviceController? = null

    private var isExpanded by mutableStateOf(false)
    private var showDebugPanel by mutableStateOf(false)
    private var pendingScreenshot by mutableStateOf<Bitmap?>(null)
    private var bubbleX = 0
    private var bubbleY = 300

    override fun onServiceConnected() {
        super.onServiceConnected()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val appContainer = resolveAppContainer() ?: return
        chatManager = OverlayChatManager(appContainer, serviceScope)

        // Initialize agent components
        screenReader = ScreenReader(this)
        deviceController = DeviceController(this)
        val executor = AgentToolExecutor(
            screenReader = screenReader!!,
            deviceController = deviceController!!,
            onScreenshot = { performScreenshotForAgent() },
        )
        chatManager?.toolExecutor = executor

        createOverlayView()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are received but not actively processed.
        // The ScreenReader reads the window tree on-demand via rootInActiveWindow.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        removeOverlayView()
        serviceScope.cancel()
        pendingScreenshot?.recycle()
        pendingScreenshot = null
        super.onDestroy()
    }

    private fun resolveAppContainer(): AppContainer? {
        return (application as? VcpNativeApplication)?.appContainer
    }

    private fun createOverlayView() {
        val wm = windowManager ?: return
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AiOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AiOverlayService)
        }

        composeView.setContent {
            OverlayContent()
        }

        val params = createLayoutParams()
        wm.addView(composeView, params)
        overlayView = composeView
    }

    @Composable
    private fun OverlayContent() {
        val manager = chatManager ?: return

        if (isExpanded) {
            if (showDebugPanel) {
                OverlayDebugPanel(
                    debugLog = manager.debugLog,
                    onCopyAll = { text -> copyToClipboard(text) },
                    onClear = { manager.clearDebugLog() },
                    onClose = { showDebugPanel = false },
                )
            } else {
                OverlayChatPanel(
                    messages = manager.messages,
                    isStreaming = false,
                    pendingScreenshot = pendingScreenshot,
                    isAgentMode = manager.isAgentMode.value,
                    agentStatus = null,
                    onSend = { text ->
                        val screenshotDataUrl = pendingScreenshot?.let { bitmapToBase64DataUrl(it) }
                        pendingScreenshot?.recycle()
                        pendingScreenshot = null
                        manager.sendMessage(text, screenshotDataUrl)
                    },
                    onScreenshot = { performScreenshot() },
                    onClearHistory = { manager.clearHistory() },
                    onCollapse = {
                        isExpanded = false
                        updateLayoutParams()
                    },
                    onClearScreenshot = {
                        pendingScreenshot?.recycle()
                        pendingScreenshot = null
                    },
                    onToggleAgentMode = {
                        manager.isAgentMode.value = !manager.isAgentMode.value
                    },
                    onStopAgent = { manager.stopAgent() },
                    onToggleDebug = { showDebugPanel = true },
                )
            }
        } else {
            OverlayBubble(
                onClick = {
                    isExpanded = true
                    updateLayoutParams()
                },
                onDrag = { dx, dy ->
                    bubbleX += dx.toInt()
                    bubbleY += dy.toInt()
                    updateLayoutParams()
                },
            )
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Debug Log", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return if (isExpanded) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bubbleX
                y = bubbleY
            }
        }
    }

    private fun updateLayoutParams() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        try {
            wm.updateViewLayout(view, createLayoutParams())
        } catch (_: Exception) {}
    }

    private fun removeOverlayView() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun performScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val view = overlayView ?: return
        serviceScope.launch {
            view.visibility = android.view.View.INVISIBLE
            delay(200)

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        screenshot.hardwareBuffer.close()

                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()

                        val scaled = softwareBitmap?.let { scaleBitmap(it, 1080) }
                        if (scaled != softwareBitmap) softwareBitmap?.recycle()

                        pendingScreenshot?.recycle()
                        pendingScreenshot = scaled

                        view.visibility = android.view.View.VISIBLE
                    }

                    override fun onFailure(errorCode: Int) {
                        view.visibility = android.view.View.VISIBLE
                    }
                },
            )
        }
    }

    /**
     * Screenshot for agent tool — returns base64 data URL directly (suspend).
     */
    private suspend fun performScreenshotForAgent(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val view = overlayView ?: return null

        view.visibility = android.view.View.INVISIBLE
        delay(200)

        val result = suspendCancellableCoroutine<String?> { cont ->
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        screenshot.hardwareBuffer.close()

                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()

                        val scaled = softwareBitmap?.let { scaleBitmap(it, 1080) }
                        if (scaled != softwareBitmap) softwareBitmap?.recycle()

                        val dataUrl = scaled?.let { bitmapToBase64DataUrl(it) }
                        scaled?.recycle()

                        if (cont.isActive) cont.resume(dataUrl)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }

        view.visibility = android.view.View.VISIBLE
        return result
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    private fun bitmapToBase64DataUrl(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    companion object {
        var instance: AiOverlayService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
