package com.vcpnative.app.feature.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    val sentIds = remember { mutableSetOf<String>() }
    var webViewReady by remember { mutableStateOf(false) }
    // Always have a fresh reference to current messages
    val currentMessages by rememberUpdatedState(messages)

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

    // React to message list changes AFTER WebView is ready
    LaunchedEffect(messages, webViewReady) {
        if (!webViewReady) return@LaunchedEffect
        val wv = webViewRef[0] ?: return@LaunchedEffect
        syncMessages(wv, messages, sentIds)
    }
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
    val escaped = escapeForJs(jsonArray.toString())
    webView.evaluateJavascript("vcpChat.clearChat();vcpChat.loadHistory('$escaped');", null)
    BridgeLogger.d(TAG, "Loaded ${messages.size} messages")
}

/** Incremental sync: add new messages, update changed ones. */
private fun syncMessages(
    webView: WebView,
    messages: List<MessageEntity>,
    sentIds: MutableSet<String>,
) {
    for (msg in messages) {
        val escapedContent = escapeForJs(msg.content)
        if (msg.id !in sentIds) {
            webView.evaluateJavascript(
                "vcpChat.addMessage('${msg.id}','${msg.role}','$escapedContent','${msg.status}');",
                null,
            )
            sentIds.add(msg.id)
        } else {
            webView.evaluateJavascript(
                "vcpChat.updateMessage('${msg.id}','$escapedContent','${msg.status}');",
                null,
            )
        }
    }

    // Remove deleted messages
    val currentIds = messages.map { it.id }.toSet()
    val removed = sentIds.filter { it !in currentIds }
    for (id in removed) {
        webView.evaluateJavascript("vcpChat.removeMessage('$id');", null)
        sentIds.remove(id)
    }
}

/** Escape content for safe embedding in JS string literals. */
private fun escapeForJs(text: String): String =
    text.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("</script>", "<\\/script>")
