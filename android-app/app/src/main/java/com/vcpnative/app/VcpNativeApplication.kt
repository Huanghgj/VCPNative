package com.vcpnative.app

import android.app.Application
import com.vcpnative.app.app.AppContainer

class VcpNativeApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }
}
