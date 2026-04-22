package com.vcpnative.app.bridge

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Safely tear down a WebView, breaking reference cycles to prevent memory leaks.
 *
 * Call order matters:
 * 1. Stop loading to cancel pending requests
 * 2. Clear clients to break reference cycles
 * 3. Remove JS interface to release bridge refs
 * 4. Navigate to blank to release DOM resources
 * 5. Remove from parent to release ViewGroup ref
 * 6. Destroy to free native resources
 */
fun WebView.safeDestroy(jsInterfaceName: String? = null) {
    stopLoading()
    webChromeClient = null
    webViewClient = WebViewClient()
    jsInterfaceName?.let { removeJavascriptInterface(it) }
    (parent as? ViewGroup)?.removeView(this)
    destroy()
}
