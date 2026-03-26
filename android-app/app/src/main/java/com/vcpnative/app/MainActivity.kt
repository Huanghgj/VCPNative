package com.vcpnative.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.vcpnative.app.app.VcpNativeApp
import com.vcpnative.app.ui.theme.VcpNativeTheme

class MainActivity : ComponentActivity() {
    private var jankStats: JankStats? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VcpNativeTheme {
                VcpNativeApp()
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (!frameData.isJank) {
                return@createAndTrack
            }
            Log.w(
                TAG,
                "Jank frame ui=${frameData.frameDurationUiNanos / 1_000_000.0}ms states=${frameData.states}",
            )
        }.also {
            PerformanceMetricsState.getHolderForHierarchy(window.decorView)
                .state
                ?.putState("Activity", javaClass.simpleName)
        }
    }

    override fun onResume() {
        super.onResume()
        jankStats?.isTrackingEnabled = true
    }

    override fun onPause() {
        jankStats?.isTrackingEnabled = false
        super.onPause()
    }

    private companion object {
        const val TAG = "VcpNativeJank"
    }
}
