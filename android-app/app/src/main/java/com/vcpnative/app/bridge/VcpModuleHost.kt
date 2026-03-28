package com.vcpnative.app.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val TAG = "VcpBridge"
private const val BRIDGE_NAME = "VcpBridge"
private const val SHIM_ASSET_PATH = "vcpchat/bridge-shim.js"
private const val MODULE_BASE_URL = "file:///android_asset/vcpchat/modules/"

/**
 * Generic WebView host that loads a VCPChat HTML module and provides
 * the Electron IPC bridge via JavascriptInterface.
 */
@Composable
fun VcpModuleHost(
    modulePath: String,
    ipcDispatcher: IpcDispatcher,
    onCloseRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeShimJs = remember {
        context.assets.open(SHIM_ASSET_PATH).bufferedReader().readText()
    }
    val mobileOverrideCss = remember {
        context.assets.open("vcpchat/mobile-overrides.css").bufferedReader().readText()
    }

    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(modulePath) {
        onDispose {
            webViewRef[0]?.let { wv ->
                wv.stopLoading()
                wv.removeJavascriptInterface(BRIDGE_NAME)
                wv.loadUrl("about:blank")
                wv.destroy()
                webViewRef[0] = null
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            createModuleWebView(
                context = viewContext,
                scope = scope,
                bridgeShimJs = bridgeShimJs,
                mobileOverrideCss = mobileOverrideCss,
                modulePath = modulePath,
                ipcDispatcher = ipcDispatcher,
                onCloseRequest = onCloseRequest,
            ).also { webViewRef[0] = it }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createModuleWebView(
    context: Context,
    scope: CoroutineScope,
    bridgeShimJs: String,
    mobileOverrideCss: String,
    modulePath: String,
    ipcDispatcher: IpcDispatcher,
    onCloseRequest: () -> Unit,
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(android.graphics.Color.TRANSPARENT)

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Allow ES module imports (file:// doesn't support them, but just in case)
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // Bridge interface
        val bridge = BridgeInterface(
            modulePath = modulePath,
            scope = scope,
            ipcDispatcher = ipcDispatcher,
            onCloseRequest = onCloseRequest,
            evaluateJs = { js -> post { evaluateJavascript(js, null) } },
        )
        addJavascriptInterface(bridge, BRIDGE_NAME)

        // Forward JS console.log/warn/error to BridgeLogger
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val source = msg.sourceId()?.substringAfterLast('/') ?: "?"
                val text = "[${source}:${msg.lineNumber()}] ${msg.message()}"
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> BridgeLogger.e("js:$modulePath", text)
                    ConsoleMessage.MessageLevel.WARNING -> BridgeLogger.w("js:$modulePath", text)
                    else -> BridgeLogger.d("js:$modulePath", text)
                }
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                super.onPageStarted(view, url, favicon)
                BridgeLogger.d(modulePath, "Page started: $url")
                // Inject bridge shim as early as possible (before DOMContentLoaded).
                view.evaluateJavascript(bridgeShimJs, null)
                view.evaluateJavascript("window.__vcpPlatform = 'android';", null)

                // Inject mobile viewport meta + CSS overrides
                val escapedCss = mobileOverrideCss
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                view.evaluateJavascript("""
                    (function(){
                        if(!document.querySelector('meta[name=viewport]')){
                            var m=document.createElement('meta');
                            m.name='viewport';
                            m.content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no';
                            (document.head||document.documentElement).appendChild(m);
                        }
                        var s=document.createElement('style');
                        s.id='vcp-mobile-overrides';
                        s.textContent='$escapedCss';
                        (document.head||document.documentElement).appendChild(s);
                    })();
                """.trimIndent(), null)
                BridgeLogger.d(modulePath, "Bridge shim + mobile overrides injected")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                BridgeLogger.d(modulePath, "Page finished: $url")

                // Re-inject shim (safety net if onPageStarted injection was too early)
                view.evaluateJavascript(
                    "if(!window.electronAPI){$bridgeShimJs}",
                    null,
                )

                // Push current theme
                val isDark = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val theme = if (isDark) "dark" else "light"
                view.evaluateJavascript(
                    "if(window.__vcpBridge){window.__vcpBridge.emit('theme-updated','\"$theme\"');}",
                    null,
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                val url = request?.url?.toString() ?: "?"
                val desc = error?.description ?: "unknown"
                val code = error?.errorCode ?: -1
                BridgeLogger.e(modulePath, "Resource error: $url ($code: $desc)")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Log all asset requests for debugging
                if (url.startsWith("file:///android_asset/")) {
                    val assetPath = url.removePrefix("file:///android_asset/")
                    try {
                        // Verify asset exists
                        val stream = view?.context?.assets?.open(assetPath)
                        stream?.close()
                    } catch (e: Exception) {
                        BridgeLogger.w(modulePath, "Asset not found: $assetPath")
                    }
                }

                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                BridgeLogger.d(modulePath, "Navigation: $url")
                // Open external links in system browser
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

        // Load the module HTML
        val fullUrl = MODULE_BASE_URL + modulePath
        BridgeLogger.i(modulePath, "Loading: $fullUrl")
        loadUrl(fullUrl)
    }
}

/**
 * JavascriptInterface exposed to WebView as `VcpBridge`.
 */
private class BridgeInterface(
    private val modulePath: String,
    private val scope: CoroutineScope,
    private val ipcDispatcher: IpcDispatcher,
    private val onCloseRequest: () -> Unit,
    private val evaluateJs: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(json: String) {
        scope.launch(Dispatchers.Default) {
            try {
                val msg = JSONObject(json)
                val type = msg.optString("type", "invoke")
                val channel = msg.getString("channel")
                val args = msg.optJSONArray("args") ?: JSONArray()

                // Handle window control channels locally
                when (channel) {
                    "close-window", "hide-window" -> {
                        BridgeLogger.d(modulePath, "IPC close-window")
                        scope.launch(Dispatchers.Main) { onCloseRequest() }
                        return@launch
                    }
                    "minimize-window", "maximize-window", "unmaximize-window",
                    "minimize-to-tray", "close-app" -> {
                        return@launch
                    }
                }

                if (type == "invoke") {
                    val id = msg.getString("id")
                    val startMs = System.currentTimeMillis()
                    try {
                        val result = ipcDispatcher.handle(channel, args)
                        val elapsed = System.currentTimeMillis() - startMs
                        val resultPreview = result?.toString()?.take(200) ?: "null"
                        BridgeLogger.d(modulePath, "IPC OK: $channel (${elapsed}ms) → $resultPreview")
                        val resultJson = result?.toString() ?: "null"
                        scope.launch(Dispatchers.Main) {
                            evaluateJs("window.__vcpBridge.resolve('$id',$resultJson);")
                        }
                    } catch (e: Exception) {
                        val elapsed = System.currentTimeMillis() - startMs
                        BridgeLogger.e(modulePath, "IPC FAIL: $channel (${elapsed}ms) ${e.message}")
                        val errorMsg = e.message?.replace("'", "\\'")?.replace("\n", " ")
                            ?: "Unknown error"
                        scope.launch(Dispatchers.Main) {
                            evaluateJs("window.__vcpBridge.reject('$id','$errorMsg');")
                        }
                    }
                } else {
                    // Fire-and-forget
                    BridgeLogger.d(modulePath, "IPC send: $channel")
                    try {
                        ipcDispatcher.handle(channel, args)
                    } catch (e: Exception) {
                        BridgeLogger.w(modulePath, "IPC send FAIL: $channel ${e.message}")
                    }
                }
            } catch (e: Exception) {
                BridgeLogger.e(modulePath, "Bridge parse error: ${json.take(300)}")
            }
        }
    }
}

/**
 * Emit an event from Kotlin to the WebView's JS listeners.
 */
fun emitToWebView(webView: WebView, channel: String, data: Any?) {
    val dataJson = when (data) {
        null -> "null"
        is String -> JSONObject.quote(data)
        is JSONObject, is JSONArray -> data.toString()
        is Number, is Boolean -> data.toString()
        else -> JSONObject.quote(data.toString())
    }
    webView.post {
        webView.evaluateJavascript(
            "if(window.__vcpBridge){window.__vcpBridge.emit('$channel',$dataJson);}",
            null,
        )
    }
}
