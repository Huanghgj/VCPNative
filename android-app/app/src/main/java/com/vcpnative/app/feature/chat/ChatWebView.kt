package com.vcpnative.app.feature.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.content.Context
import android.util.Base64
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.Toast
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vcpnative.app.bridge.BridgeLogger
import com.vcpnative.app.data.room.MessageAttachmentEntity
import com.vcpnative.app.data.room.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Escape a string for safe interpolation into JavaScript single-quoted literals.
 * Prevents JS injection when msg.id / msg.role / msg.status are spliced into evaluateJavascript() calls.
 */
private fun jsStringEscape(s: String): String =
    s.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

private var ttsInstance: android.speech.tts.TextToSpeech? = null

private const val TAG = "ChatWebView"
private const val CHAT_HTML_URL = "file:///android_asset/vcpchat/chat.html"

private fun Context.vibratorOrNull(): android.os.Vibrator? =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }

private fun Context.performSafeChatHaptic(kind: String) {
    val vibrator = vibratorOrNull() ?: return
    if (!vibrator.hasVibrator()) {
        return
    }
    val hasPermission =
        packageManager.checkPermission(android.Manifest.permission.VIBRATE, packageName) ==
            PackageManager.PERMISSION_GRANTED
    if (!hasPermission) {
        BridgeLogger.w(TAG, "Skipping haptic '$kind': missing VIBRATE permission")
        return
    }

    runCatching {
        when (kind) {
            "pet" -> {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createWaveform(
                            longArrayOf(0, 30, 60, 30),
                            -1,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 30, 60, 30), -1)
                }
            }

            "message" -> {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            15,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(15)
                }
            }
        }
    }.onFailure { error ->
        when (error) {
            is SecurityException -> BridgeLogger.w(TAG, "Haptic '$kind' denied: ${error.message}")
            else -> BridgeLogger.e(TAG, "Haptic '$kind' failed: ${error.message}")
        }
    }
}

/**
 * Single-WebView chat renderer.
 * All messages rendered inside one WebView using iMessage-style CSS.
 * Messages injected/updated via JavaScript bridge calls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatWebView(
    messages: List<MessageEntity>,
    liveMessage: MessageEntity? = null,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
    userAvatar: String = "",
    aiAvatar: String = "",
    onAction: (action: String, messageId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    var webViewReady by remember { mutableStateOf(false) }
    var appliedLiveMessageId by remember { mutableStateOf<String?>(null) }
    var appliedLiveMessageHash by remember { mutableStateOf<Int?>(null) }
    var appliedPersistedMessages by remember { mutableStateOf<List<RenderedMessageSnapshot>>(emptyList()) }
    // Always have fresh references
    val currentMessages by rememberUpdatedState(messages)
    val currentLiveMessage by rememberUpdatedState(liveMessage)
    val currentOnAction by rememberUpdatedState(onAction)

    DisposableEffect(Unit) {
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
            ttsInstance = null
            webViewRef[0]?.let { wv ->
                wv.stopLoading()
                // Clear clients + remove from parent before destroy to prevent WebView memory leak
                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()
                wv.removeJavascriptInterface("VcpChatBridge")
                wv.loadUrl("about:blank")
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
                webViewRef[0] = null
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                overScrollMode = View.OVER_SCROLL_NEVER
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // Ensure hardware-accelerated rendering for WebGL / Three.js content
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.textZoom = 100
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // NEVER use ALWAYS_ALLOW — allows HTTP resources on HTTPS pages, enabling MITM
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.mediaPlaybackRequiresUserGesture = true
                // allowFileAccess defaults to false on targetSdk >= 30;
                // required for <img src="file:///..."> to load local attachments.
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onReady() {
                        BridgeLogger.d(TAG, "WebView ready")
                        // Use currentMessages (always up-to-date via rememberUpdatedState)
                        scope.launch(Dispatchers.Main) {
                            appliedPersistedMessages = currentMessages.toRenderedSnapshots(attachmentsByMessageId)
                            appliedLiveMessageId = currentLiveMessage?.id
                            appliedLiveMessageHash = currentLiveMessage?.renderHash()
                            webViewReady = true
                            loadAllMessages(this@apply, currentMessages, currentLiveMessage, attachmentsByMessageId)
                        }
                    }

                    @JavascriptInterface
                    fun onLongPress(messageId: String) {
                        BridgeLogger.d(TAG, "Long press: $messageId")
                    }

                    @JavascriptInterface
                    fun onAction(action: String, value: String) {
                        BridgeLogger.d(TAG, "Action: $action (${value.take(100)})")
                        when (action) {
                            "copy" -> {
                                scope.launch(Dispatchers.Main) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("chat", value)
                                    )
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                            "getContent" -> {
                                // 编辑请求：返回原始内容给 WebView 编辑器
                                scope.launch(Dispatchers.Main) {
                                    val msg = currentLiveMessage?.takeIf { it.id == value }
                                        ?: currentMessages.find { it.id == value }
                                    if (msg != null) {
                                        val b64 = android.util.Base64.encodeToString(
                                            msg.content.toByteArray(Charsets.UTF_8),
                                            android.util.Base64.NO_WRAP,
                                        )
                                        this@apply.evaluateJavascript(
                                            "vcpChat.openEditor('${jsStringEscape(msg.id)}',b64d('$b64'));",
                                            null,
                                        )
                                    }
                                }
                            }
                            "saveEdit" -> {
                                // 保存编辑：value 格式为 "messageId|||newContent"
                                val parts = value.split("|||", limit = 2)
                                if (parts.size == 2) {
                                    scope.launch(Dispatchers.Main) {
                                        currentOnAction("saveEdit", value)
                                    }
                                }
                            }
                            "tts" -> {
                                scope.launch(Dispatchers.Main) {
                                    if (ttsInstance == null) {
                                        ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
                                            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                                                ttsInstance?.language = java.util.Locale.CHINESE
                                                ttsInstance?.speak(value, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "vcp_tts")
                                            }
                                        }
                                    } else {
                                        ttsInstance?.speak(value, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "vcp_tts")
                                    }
                                }
                            }
                            "ttsStop" -> {
                                ttsInstance?.stop()
                            }
                            // 自定义头像：通知 Compose 层打开图片选择器
                            "changeAvatar" -> {
                                scope.launch(Dispatchers.Main) {
                                    currentOnAction("changeAvatar", value)
                                }
                            }
                            // 猫娘震动反馈喵～摸头和新消息到达时触发
                            "haptic" -> {
                                scope.launch(Dispatchers.Main) {
                                    context.performSafeChatHaptic(value)
                                }
                            }
                            else -> {
                                scope.launch(Dispatchers.Main) {
                                    currentOnAction(action, value)
                                }
                            }
                        }
                    }

                    @JavascriptInterface
                    fun saveImage(imageUrl: String) {
                        BridgeLogger.d(TAG, "Save image: $imageUrl")
                        if (imageUrl.isBlank() || !imageUrl.startsWith("http")) return
                        scope.launch(Dispatchers.Main) {
                            try {
                                val fileName = "VCPChat_${System.currentTimeMillis()}.png"
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val request = DownloadManager.Request(Uri.parse(imageUrl)).apply {
                                    setTitle(fileName)
                                    setDescription("保存图片")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "VCPChat/$fileName")
                                }
                                dm.enqueue(request)
                                Toast.makeText(context, "图片已开始下载", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                BridgeLogger.e(TAG, "Save image failed: ${e.message}")
                                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, "VcpChatBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        BridgeLogger.d("chat:js", "[${msg.lineNumber()}] ${msg.message()}")
                        return true
                    }

                    override fun onJsAlert(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: android.webkit.JsResult?,
                    ): Boolean {
                        android.app.AlertDialog.Builder(viewContext)
                            .setMessage(message)
                            .setPositiveButton("确定") { _, _ -> result?.confirm() }
                            .setOnCancelListener { result?.cancel() }
                            .show()
                        return true
                    }

                    override fun onJsConfirm(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: android.webkit.JsResult?,
                    ): Boolean {
                        android.app.AlertDialog.Builder(viewContext)
                            .setMessage(message)
                            .setPositiveButton("确定") { _, _ -> result?.confirm() }
                            .setNegativeButton("取消") { _, _ -> result?.cancel() }
                            .setOnCancelListener { result?.cancel() }
                            .show()
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        BridgeLogger.d(TAG, "Page finished: $url")
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url),
                                    ),
                                )
                            } catch (_: Exception) {}
                            return true
                        }
                        return false
                    }
                }

                loadUrl(CHAT_HTML_URL)
                webViewRef[0] = this
            }
        },
    )

    // 持久化消息变化尽量按条目增量同步，避免流式过程中整页 clear/load 导致滚动抖动。
    // attachmentsByMessageId 也作为 key：Room 事务同时插入消息和附件，
    // 但两个 Flow 的 recomposition 可能时序不同步，需要确保附件到达后也触发同步。
    LaunchedEffect(messages, attachmentsByMessageId, webViewReady) {
        if (!webViewReady) return@LaunchedEffect
        val wv = webViewRef[0] ?: return@LaunchedEffect
        val currentPersistedMessages = messages.toRenderedSnapshots(attachmentsByMessageId)
        if (!canIncrementallySync(appliedPersistedMessages, currentPersistedMessages)) {
            loadAllMessages(wv, messages, liveMessage, attachmentsByMessageId)
            appliedLiveMessageId = liveMessage?.id
            appliedLiveMessageHash = liveMessage?.renderHash()
        } else {
            syncPersistedMessages(
                webView = wv,
                previous = appliedPersistedMessages,
                current = messages,
                attachmentsByMessageId = attachmentsByMessageId,
                liveMessageId = appliedLiveMessageId,
            )
        }

        appliedPersistedMessages = currentPersistedMessages
        if (liveMessage == null) {
            appliedLiveMessageId = null
            appliedLiveMessageHash = null
        }
    }

    // 高频流式更新只处理当前正在变化的那一条消息。
    LaunchedEffect(liveMessage, webViewReady) {
        if (!webViewReady) return@LaunchedEffect
        val wv = webViewRef[0] ?: return@LaunchedEffect
        val message = liveMessage ?: return@LaunchedEffect
        val messageHash = message.renderHash()

        if (appliedLiveMessageId != message.id) {
            val previousLiveMessageId = appliedLiveMessageId
            if (
                previousLiveMessageId != null &&
                previousLiveMessageId != message.id &&
                messages.none { it.id == previousLiveMessageId }
            ) {
                wv.evaluateJavascript(
                    "vcpChat.removeMessage('${jsStringEscape(previousLiveMessageId)}');",
                    null,
                )
            }

            if (messages.any { it.id == message.id }) {
                updateRenderedMessage(wv, message)
            } else {
                appendMessage(wv, message)
            }
            appliedLiveMessageId = message.id
            appliedLiveMessageHash = messageHash
            return@LaunchedEffect
        }

        if (appliedLiveMessageHash == messageHash) {
            return@LaunchedEffect
        }

        val b64 = toBase64(message.content)
        wv.evaluateJavascript(
            "vcpChat.updateMessage('${jsStringEscape(message.id)}',b64d('$b64'),'${jsStringEscape(message.status)}');",
            null,
        )
        appliedLiveMessageHash = messageHash
    }

    // 头像变化时同步到 WebView 喵
    LaunchedEffect(userAvatar, aiAvatar, webViewReady) {
        if (!webViewReady) return@LaunchedEffect
        val wv = webViewRef[0] ?: return@LaunchedEffect
        if (userAvatar.isNotBlank() || aiAvatar.isNotBlank()) {
            val safeUser = jsStringEscape(userAvatar)
            val safeAi = jsStringEscape(aiAvatar)
            wv.evaluateJavascript(
                "if(vcpChat&&vcpChat.setAvatars)vcpChat.setAvatars('$safeUser','$safeAi');",
                null,
            )
        }
    }
}

/** Encode string to Base64 for safe JS transport. */
private fun toBase64(text: String): String =
    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private fun attachmentsToJsonArray(attachments: List<MessageAttachmentEntity>): JSONArray {
    val arr = JSONArray()
    for (att in attachments) {
        arr.put(JSONObject().apply {
            put("name", att.name)
            put("type", att.mimeType)
            put("src", att.internalPath.ifBlank { att.src })
        })
    }
    return arr
}

private fun attachmentsToJsonString(attachments: List<MessageAttachmentEntity>): String {
    if (attachments.isEmpty()) return "null"
    return attachmentsToJsonArray(attachments).toString()
}

private data class RenderedMessageSnapshot(
    val id: String,
    val role: String,
    val content: String,
    val status: String,
    val attachmentCount: Int = 0,
)

private fun MessageEntity.renderHash(): Int =
    content.hashCode() xor status.hashCode()

private fun MessageEntity.toRenderedSnapshot(
    attachmentCount: Int = 0,
): RenderedMessageSnapshot =
    RenderedMessageSnapshot(
        id = id,
        role = role,
        content = content,
        status = status,
        attachmentCount = attachmentCount,
    )

private fun List<MessageEntity>.toRenderedSnapshots(
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
): List<RenderedMessageSnapshot> =
    map { it.toRenderedSnapshot(attachmentsByMessageId[it.id]?.size ?: 0) }

private fun mergeMessagesForRender(
    messages: List<MessageEntity>,
    liveMessage: MessageEntity?,
): List<MessageEntity> {
    if (liveMessage == null) {
        return messages
    }

    val existingIndex = messages.indexOfFirst { it.id == liveMessage.id }
    if (existingIndex < 0) {
        return messages + liveMessage
    }

    return messages.toMutableList().apply {
        set(existingIndex, liveMessage)
    }
}

private fun canIncrementallySync(
    previous: List<RenderedMessageSnapshot>,
    current: List<RenderedMessageSnapshot>,
): Boolean {
    // If current is empty but previous had messages, force a full reload rather than
    // letting syncPersistedMessages remove all messages one-by-one (which would leave the
    // chat empty if Room emitted a spurious empty list).
    if (current.isEmpty() && previous.isNotEmpty()) {
        return false
    }
    if (previous.isEmpty()) {
        return true
    }

    val previousIds = previous.map(RenderedMessageSnapshot::id)
    val currentIds = current.map(RenderedMessageSnapshot::id)
    val previousIdSet = previousIds.toSet()
    val currentIdSet = currentIds.toSet()

    val retainedPreviousIds = previousIds.filter(currentIdSet::contains)
    val retainedCurrentIds = currentIds.filter(previousIdSet::contains)
    if (retainedPreviousIds != retainedCurrentIds) {
        return false
    }

    val firstNewIndex = currentIds.indexOfFirst { it !in previousIdSet }
    return firstNewIndex < 0 || currentIds.drop(firstNewIndex).none(previousIdSet::contains)
}

private fun syncPersistedMessages(
    webView: WebView,
    previous: List<RenderedMessageSnapshot>,
    current: List<MessageEntity>,
    liveMessageId: String?,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
) {
    val currentById = current.associateBy(MessageEntity::id)
    val previousById = previous.associateBy(RenderedMessageSnapshot::id)

    previous
        .asSequence()
        .map(RenderedMessageSnapshot::id)
        .filterNot(currentById::containsKey)
        .forEach { messageId ->
            webView.evaluateJavascript(
                "vcpChat.removeMessage('${jsStringEscape(messageId)}');",
                null,
            )
        }

    current.forEach { message ->
        // Skip UPDATE (not append) for messages being actively streamed,
        // to prevent stale checkpoint content from overwriting latest streaming content.
        val previousMessage = previousById[message.id]
        val isLiveStreaming = message.id == liveMessageId && message.status in setOf("draft", "streaming")
        val currentAttachments = attachmentsByMessageId[message.id].orEmpty()
        val prevAttachmentCount = previousMessage?.attachmentCount ?: 0
        when {
            previousMessage == null -> appendMessage(webView, message, currentAttachments)
            isLiveStreaming -> {}
            // 附件数量变化时 remove + re-append（updateMessage 不含附件参数）
            prevAttachmentCount != currentAttachments.size && currentAttachments.isNotEmpty() -> {
                webView.evaluateJavascript(
                    "vcpChat.removeMessage('${jsStringEscape(message.id)}');",
                    null,
                )
                appendMessage(webView, message, currentAttachments)
            }
            previousMessage.role != message.role ||
                previousMessage.content != message.content ||
                previousMessage.status != message.status -> updateRenderedMessage(webView, message)
        }
    }
}

private fun appendMessage(
    webView: WebView,
    message: MessageEntity,
    attachments: List<MessageAttachmentEntity> = emptyList(),
) {
    val b64 = toBase64(message.content)
    val attJson = attachmentsToJsonString(attachments)
    webView.evaluateJavascript(
        "vcpChat.addMessage('${jsStringEscape(message.id)}','${jsStringEscape(message.role)}',b64d('$b64'),'${jsStringEscape(message.status)}',$attJson);",
        null,
    )
}

private fun updateRenderedMessage(
    webView: WebView,
    message: MessageEntity,
) {
    val b64 = toBase64(message.content)
    webView.evaluateJavascript(
        "vcpChat.updateMessage('${jsStringEscape(message.id)}',b64d('$b64'),'${jsStringEscape(message.status)}');",
        null,
    )
}

/** Load all messages at once (initial load / topic switch). */
private fun loadAllMessages(
    webView: WebView,
    messages: List<MessageEntity>,
    liveMessage: MessageEntity?,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
) {
    val mergedMessages = mergeMessagesForRender(messages, liveMessage)
    val jsonArray = JSONArray()
    for (msg in mergedMessages) {
        jsonArray.put(JSONObject().apply {
            put("id", msg.id)
            put("role", msg.role)
            put("content", msg.content)
            put("status", msg.status)
            val atts = attachmentsByMessageId[msg.id]
            if (!atts.isNullOrEmpty()) {
                put("attachments", attachmentsToJsonArray(atts))
            }
        })
    }
    val b64 = toBase64(jsonArray.toString())
    webView.evaluateJavascript(
        "vcpChat.clearChat();vcpChat.loadHistory(b64d('$b64'));",
        null,
    )
    BridgeLogger.d(TAG, "Loaded ${mergedMessages.size} messages")
}
