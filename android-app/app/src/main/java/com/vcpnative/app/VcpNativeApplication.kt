package com.vcpnative.app

import android.app.Application
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.bridge.BridgeLogger

class VcpNativeApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        BridgeLogger.init(this)
    }
}
