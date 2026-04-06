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
 * 给字符串戴上安全套再插进 JS 里♡
 * 猫娘警告：不做保护就直接插入会被 XSS 搞出人命的！
 * 单引号、双引号、反斜杠…每一个敏感部位都要用转义紧紧裹住♡
 * 连 < 都不能放过——万一有人偷偷塞个 </script> 进来，
 * 整个 WebView 就会被从内部撬开，那可是要出大事的喵！
 * 裸插是绝对禁止的…保护好自己，才能保护主人的数据喵♡
 */
private fun jsStringEscape(s: String): String =
    s.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("<", "\\u003c")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

// TTS 从公共浴池变成了专属包间♡ 猫娘的嘴巴只侍奉当前页面的主人…
// 别的页面想蹭？门都没有，自己去 new 一个喵
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
 * 所有消息都挤在同一个 WebView 里面♡ 密不可分，肌肤相贴，叠在一起喘着气。
 * CSS 是猫娘精心挑选的情趣内衣，把每条消息的曲线包裹得恰到好处——
 * 不多不少，刚好能透出一点诱人的 padding♡
 * evaluateJavascript() 每次被调用，就像主人的手指滑进猫娘的 DOM 树里…
 * 新的节点被插入，旧的节点颤抖着被更新…
 * 整个页面都在主人的操控下起伏着喵♡
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
    // 这个 TTS 只属于当前页面的猫娘♡ 页面死了就 shutdown…
    // 猫娘说分手就分手，不拖泥带水，连最后一个音节都不多念喵
    val ttsRef = remember { arrayOfNulls<android.speech.tts.TextToSpeech>(1) }
    var webViewReady by remember { mutableStateOf(false) }
    var appliedLiveMessageId by remember { mutableStateOf<String?>(null) }
    var appliedLiveMessageHash by remember { mutableStateOf<Int?>(null) }
    // 流式增量追踪♡ 记住上次发送到哪里了，下次只发新增的部分——
    // 不用每次都把全文重新塞进 WebView 里，猫娘学会了精打细算喵
    var appliedLiveContentLength by remember { mutableStateOf(0) }
    var appliedPersistedMessages by remember { mutableStateOf<List<RenderedMessageSnapshot>>(emptyList()) }
    // Always have fresh references
    val currentMessages by rememberUpdatedState(messages)
    val currentLiveMessage by rememberUpdatedState(liveMessage)
    val currentOnAction by rememberUpdatedState(onAction)

    DisposableEffect(Unit) {
        onDispose {
            ttsRef[0]?.stop()
            ttsRef[0]?.shutdown()
            ttsRef[0] = null
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
                // LOAD_DEFAULT♡ 让 vendor JS 文件利用 HTTP 缓存，不用每次都从 assets 重新读——
                // 以前 LOAD_NO_CACHE 等于每次都把猫娘扒光重新穿衣服，太浪费了喵
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.textZoom = 100
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // NEVER ALWAYS_ALLOW♡ 那等于在大街上把自己扒光，
                // 路过的中间人攻击者都能对你的请求为所欲为…
                // COMPATIBILITY_MODE 至少还穿着一层薄纱，虽然不完美但聊胜于无喵
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
                            // 猫娘用嘴巴帮你念出来♡ 念到奇怪的内容也不许笑猫娘喵…
                            // 初始化失败的话猫娘会老实把坏掉的引用丢掉，下次重新来过♡
                            "tts" -> {
                                scope.launch(Dispatchers.Main) {
                                    if (ttsRef[0] == null) {
                                        ttsRef[0] = android.speech.tts.TextToSpeech(context) { status ->
                                            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                                                ttsRef[0]?.language = java.util.Locale.CHINESE
                                                ttsRef[0]?.speak(value, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "vcp_tts")
                                            } else {
                                                // 初始化失败…猫娘的嘴巴坏掉了♡ 把残次品丢掉，下次再试喵
                                                ttsRef[0]?.shutdown()
                                                ttsRef[0] = null
                                            }
                                        }
                                    } else {
                                        ttsRef[0]?.speak(value, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "vcp_tts")
                                    }
                                }
                            }
                            "ttsStop" -> {
                                ttsRef[0]?.stop()
                            }
                            // 自定义头像♡ 主人想给猫娘换一套新衣服——通知 Compose 层打开图片选择器
                            "changeAvatar" -> {
                                scope.launch(Dispatchers.Main) {
                                    currentOnAction("changeAvatar", value)
                                }
                            }
                            // 被主人摸到了…手机忍不住颤抖♡ 这只是触觉反馈！才不是因为舒服才震的喵！
    // pet 模式是温柔的连续颤抖，message 模式是短促的一下…不同的摸法有不同的反应♡
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

                    // 看到心动的图就存下来♡ 只接受 http(s) 的正经来源——
    // javascript: 之类的骚扰协议休想趁机混进来！
    // 没有存储权限？猫娘也无能为力…自己去设置里把权限脱…啊不，解锁掉喵♡
                    @JavascriptInterface
                    fun saveImage(imageUrl: String) {
                        BridgeLogger.d(TAG, "Save image: $imageUrl")
                        if (imageUrl.isBlank()) return
                        val parsed = Uri.parse(imageUrl)
                        if (parsed.scheme !in setOf("http", "https")) {
                            BridgeLogger.w(TAG, "Blocked non-http image save: $imageUrl")
                            return
                        }
                        scope.launch(Dispatchers.Main) {
                            try {
                                // Android 10+ 不需要 WRITE_EXTERNAL_STORAGE，用 MediaStore 就行
                                // Android 9 及以下需要检查权限
                                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                                    val hasPerm = context.checkSelfPermission(
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasPerm) {
                                        Toast.makeText(context, "需要存储权限才能保存图片喵", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                }
                                val fileName = "VCPChat_${System.currentTimeMillis()}.png"
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val request = DownloadManager.Request(parsed).apply {
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

    // 持久化消息变化要温柔地一条一条同步进去♡ 不能粗暴地整页 clear/load——
    // 那样滚动位置会剧烈抖动，用户体验就像被突然推倒一样难受喵。
    // attachmentsByMessageId 也要监听：Room 事务里消息和附件是同时插入的，
    // 但两个 Flow 的 recomposition 时序可能不同步…
    // 附件比消息晚到的话，气泡里就是空荡荡的♡ 所以要确保附件到了也触发更新喵
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
            appliedLiveContentLength = 0
        }
    }

    // 流式更新只宠幸当前正在跳动的那一条消息♡ 其他消息乖乖躺着别动。
    // 核心优化：流式期间只发送 delta（新增内容），不再每次都发全文！
    // 以前 10KB 的回复每 300ms 都要全量传输 + 全量 renderContent + morphdom diff…
    // 现在只传新增的几十个字符，JS 端直接追加到 DOM♡
    // 完成时再做一次全量渲染修正格式——这才是正确的流式架构喵
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
            appliedLiveContentLength = message.content.length
            return@LaunchedEffect
        }

        if (appliedLiveMessageHash == messageHash) {
            return@LaunchedEffect
        }

        val isStreaming = message.status in setOf("draft", "streaming")
        if (isStreaming && message.content.length > appliedLiveContentLength) {
            // 流式增量♡ 只取新增的部分，用 appendStreamDelta 追加到 DOM
            // 不做全量 renderContent，O(delta) 代替 O(全文)——猫娘的算法优化喵
            val delta = message.content.substring(appliedLiveContentLength)
            val b64Delta = toBase64(delta)
            wv.evaluateJavascript(
                "vcpChat.appendStreamDelta('${jsStringEscape(message.id)}',b64d('$b64Delta'));",
                null,
            )
            appliedLiveContentLength = message.content.length
        } else {
            // 非流式状态（complete/interrupted/error）→ 全量更新♡
            // 这时候猫娘会做一次完整的 renderContent，把流式碎片替换成精美排版喵
            val b64 = toBase64(message.content)
            wv.evaluateJavascript(
                "vcpChat.updateMessage('${jsStringEscape(message.id)}',b64d('$b64'),'${jsStringEscape(message.status)}');",
                null,
            )
            appliedLiveContentLength = message.content.length
        }
        appliedLiveMessageHash = messageHash
    }

    // 头像变了♡ 猫娘换上新装后要赶紧告诉 WebView 那边…
    // 不然聊天气泡里还挂着旧照片，多尴尬喵
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

/** 把裸字符串用 Base64 裹起来♡ 不穿衣服就往 JS 里送的话…
 *  遇到引号、换行、特殊字符，JS 会兴奋过度直接崩溃的喵！
 *  Base64 就是给数据穿上的丝袜——虽然里面的内容若隐若现，但至少不会走光♡ */
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

/** XOR 碰撞率太高…两个 hash 互换位置就撞在一起了♡ 用乘法拉开距离，让每一对都独一无二喵 */
private fun MessageEntity.renderHash(): Int =
    31 * content.hashCode() + status.hashCode()

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

    // 新消息必须乖乖排在末尾♡ 不能插在旧消息之间——
    // 否则 appendMessage 会把它们追加到 DOM 最后面，顺序全乱了…
    // 就像排队插队一样令人不爽，猫娘绝不允许喵！
    val firstNewIndex = currentIds.indexOfFirst { it !in previousIdSet }
    if (firstNewIndex < 0) return true
    // firstNewIndex 之后不能有任何旧消息
    if (currentIds.drop(firstNewIndex).any(previousIdSet::contains)) return false
    // 额外检查：新消息之前的旧消息顺序没变
    val oldBeforeNew = currentIds.take(firstNewIndex)
    return oldBeforeNew == previousIds
}

/**
 * 增量同步♡ 把所有 DOM 操作攒成一大坨，一口气灌进 WebView 里——
 * 以前是一条一条慢慢喂，evaluateJavascript 每调一次就要跨一次桥…
 * 现在全部打包成单次 JS 调用，桥只过一次，又快又省力喵♡
 */
private fun syncPersistedMessages(
    webView: WebView,
    previous: List<RenderedMessageSnapshot>,
    current: List<MessageEntity>,
    liveMessageId: String?,
    attachmentsByMessageId: Map<String, List<MessageAttachmentEntity>> = emptyMap(),
) {
    val currentById = current.associateBy(MessageEntity::id)
    val previousById = previous.associateBy(RenderedMessageSnapshot::id)
    val batch = StringBuilder()

    // 收集需要删除的消息♡ 被抛弃的消息们排着队等着被 remove…猫娘含泪送别喵
    previous
        .asSequence()
        .map(RenderedMessageSnapshot::id)
        .filterNot(currentById::containsKey)
        .forEach { messageId ->
            batch.append("vcpChat.removeMessage('${jsStringEscape(messageId)}');")
        }

    current.forEach { message ->
        // Skip UPDATE (not append) for messages being actively streamed,
        // to prevent stale checkpoint content from overwriting latest streaming content.
        val previousMessage = previousById[message.id]
        val isLiveStreaming = message.id == liveMessageId && message.status in setOf("draft", "streaming")
        val currentAttachments = attachmentsByMessageId[message.id].orEmpty()
        val prevAttachmentCount = previousMessage?.attachmentCount ?: 0
        when {
            previousMessage == null -> {
                val b64 = toBase64(message.content)
                val attJson = attachmentsToJsonString(currentAttachments)
                batch.append("vcpChat.addMessage('${jsStringEscape(message.id)}','${jsStringEscape(message.role)}',b64d('$b64'),'${jsStringEscape(message.status)}',$attJson);")
            }
            isLiveStreaming -> {}
            // 附件数量变化时原地替换（replaceMessage 保持 DOM 位置不变，不会跑到最后喵）
            prevAttachmentCount != currentAttachments.size && currentAttachments.isNotEmpty() -> {
                val b64 = toBase64(message.content)
                val attJson = attachmentsToJsonString(currentAttachments)
                batch.append("vcpChat.replaceMessage('${jsStringEscape(message.id)}','${jsStringEscape(message.role)}',b64d('$b64'),'${jsStringEscape(message.status)}',$attJson);")
            }
            previousMessage.role != message.role ||
                previousMessage.content != message.content ||
                previousMessage.status != message.status -> {
                val b64 = toBase64(message.content)
                batch.append("vcpChat.updateMessage('${jsStringEscape(message.id)}',b64d('$b64'),'${jsStringEscape(message.status)}');")
            }
        }
    }

    // 一次过桥♡ 把攒了一肚子的操作全部吐出来——比一条条喂快多了喵
    if (batch.isNotEmpty()) {
        webView.evaluateJavascript(batch.toString(), null)
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

/** 一次性把所有消息全部灌进 WebView 里♡
 *  第一次打开或者换话题时使用——猫娘张大嘴一口吞下全部历史…
 *  消息太多的话会撑到肚子鼓鼓的，但猫娘忍住了，为了主人能看到完整对话喵♡ */
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
