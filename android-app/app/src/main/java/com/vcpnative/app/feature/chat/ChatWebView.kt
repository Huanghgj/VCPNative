package com.vcpnative.app.feature.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
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
import com.vcpnative.app.data.room.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ChatWebView"
private const val CHAT_HTML_URL = "file:///android_asset/vcpchat/chat.html"

/**
 * Single-WebView chat renderer.
 * All messages rendered inside one WebView using iMessage-style CSS.
 * Messages injected/updated via JavaScript bridge calls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatWebView(
    messages: List<MessageEntity>,
    onAction: (action: String, messageId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    val sentIds = remember { mutableSetOf<String>() }
    var webViewReady by remember { mutableStateOf(false) }
    // Always have fresh references
    val currentMessages by rememberUpdatedState(messages)
    val currentOnAction by rememberUpdatedState(onAction)

    DisposableEffect(Unit) {
        onDispose {
            webViewRef[0]?.let { wv ->
                wv.stopLoading()
                wv.removeJavascriptInterface("VcpChatBridge")
                wv.loadUrl("about:blank")
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
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.mediaPlaybackRequiresUserGesture = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onReady() {
                        BridgeLogger.d(TAG, "WebView ready")
                        // Use currentMessages (always up-to-date via rememberUpdatedState)
                        scope.launch(Dispatchers.Main) {
                            webViewReady = true
                            loadAllMessages(this@apply, currentMessages, sentIds)
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
                                    val msg = currentMessages.find { it.id == value }
                                    if (msg != null) {
                                        val b64 = android.util.Base64.encodeToString(
                                            msg.content.toByteArray(Charsets.UTF_8),
                                            android.util.Base64.NO_WRAP,
                                        )
                                        this@apply.evaluateJavascript(
                                            "vcpChat.openEditor('${msg.id}',b64d('$b64'));",
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

    // 跟踪每条消息的内容指纹，只同步真正变化的
    val contentHash = remember { mutableMapOf<String, Int>() }

    // 增量同步：只同步 delta，避免 O(n) 全扫描
    LaunchedEffect(messages, webViewReady) {
        if (!webViewReady) return@LaunchedEffect
        val wv = webViewRef[0] ?: return@LaunchedEffect

        // 在后台线程计算 delta + Base64 编码
        val jsCommands = kotlinx.coroutines.withContext(Dispatchers.Default) {
            buildDeltaCommands(messages, sentIds, contentHash)
        }

        // 回到主线程批量执行 JS（一次 evaluateJavascript 调用）
        if (jsCommands.isNotBlank()) {
            wv.evaluateJavascript(jsCommands, null)
        }
    }
}

/** Encode string to Base64 for safe JS transport. */
private fun toBase64(text: String): String =
    Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

/** Build all JS commands for delta sync on background thread. */
private fun buildDeltaCommands(
    messages: List<MessageEntity>,
    sentIds: MutableSet<String>,
    contentHash: MutableMap<String, Int>,
): String {
    val sb = StringBuilder()
    val currentIds = mutableSetOf<String>()

    for (msg in messages) {
        currentIds.add(msg.id)
        val hash = msg.content.hashCode() xor msg.status.hashCode()

        if (msg.id !in sentIds) {
            // 新消息
            val b64 = toBase64(msg.content)
            sb.append("vcpChat.addMessage('${msg.id}','${msg.role}',b64d('$b64'),'${msg.status}');")
            sentIds.add(msg.id)
            contentHash[msg.id] = hash
        } else if (contentHash[msg.id] != hash) {
            // 内容或状态变了才更新
            val b64 = toBase64(msg.content)
            sb.append("vcpChat.updateMessage('${msg.id}',b64d('$b64'),'${msg.status}');")
            contentHash[msg.id] = hash
        }
        // 未变化的消息：跳过（O(1)）
    }

    // 删除消息
    val removed = sentIds.filter { it !in currentIds }
    for (id in removed) {
        sb.append("vcpChat.removeMessage('$id');")
        sentIds.remove(id)
        contentHash.remove(id)
    }

    return sb.toString()
}

/** Load all messages at once (initial load / topic switch). */
private fun loadAllMessages(
    webView: WebView,
    messages: List<MessageEntity>,
    sentIds: MutableSet<String>,
) {
    sentIds.clear()
    val jsonArray = JSONArray()
    for (msg in messages) {
        jsonArray.put(JSONObject().apply {
            put("id", msg.id)
            put("role", msg.role)
            put("content", msg.content)
            put("status", msg.status)
        })
        sentIds.add(msg.id)
    }
    val b64 = toBase64(jsonArray.toString())
    webView.evaluateJavascript(
        "vcpChat.clearChat();vcpChat.loadHistory(b64d('$b64'));",
        null,
    )
    BridgeLogger.d(TAG, "Loaded ${messages.size} messages")
}
