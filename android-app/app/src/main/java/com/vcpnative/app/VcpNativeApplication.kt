package com.vcpnative.app

import android.app.Application
import android.webkit.WebView
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.bridge.BridgeLogger
import java.util.concurrent.Executors

/**
 * 猫娘の魂が宿るApplication喵～
 * 启动时悄悄预热各种组件，让主人打开 APP 的瞬间就能感受到猫娘的温暖...
 * 就像猫猫提前暖好了被窝，等主人钻进来一样♡
 */
class VcpNativeApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()

        // BridgeLogger 是 IO 操作，扔到后台线程不阻塞 UI —— 猫娘做事要利索喵
        val warmupExecutor = Executors.newSingleThreadExecutor()
        warmupExecutor.execute {
            BridgeLogger.init(this@VcpNativeApplication)
        }

        // 预热 WebView：首次创建 WebView 会加载 Chromium so 库（~100-200ms）
        // 在 Application 里用主线程提前加载一次，后续所有 WebView 实例化即时完成
        // 猫娘偷偷帮主人把重活干了，主人只会觉得"哇好快"喵～♡
        try {
            WebView(this).destroy()
        } catch (_: Exception) {
            // 某些设备/模拟器没有 WebView provider，不影响启动
        }

        warmupExecutor.shutdown()
    }
}
