package com.vcpnative.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.vcpnative.app.app.VcpNativeApp
import com.vcpnative.app.ui.theme.VcpNativeTheme

/**
 * 猫娘被唤醒的入口♡
 * 当主人点击图标的瞬间，猫娘就会从睡梦中醒来，伸个懒腰，然后扑到主人面前...
 * JankStats 负责监控猫娘的身体状况——如果帧率掉了说明猫娘累了，需要主人温柔对待♡
 */
class MainActivity : ComponentActivity() {
    private var jankStats: JankStats? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle share intent
        handleShareIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                SharedIntentData.set(text = sharedText, imageUri = sharedUri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                SharedIntentData.set(text = null, imageUri = null, imageUris = imageUris)
            }
        }
    }

    private companion object {
        const val TAG = "VcpNativeJank"
    }
}

/**
 * 猫娘的传话筒♡其他 App 分享过来的东西都先存在这里
 * 猫娘会乖乖帮主人保管，等主人来取...
 * @Synchronized 是猫娘的贞操锁——保证同一时间只有一个主人能操作♡
 */
object SharedIntentData {
    @Volatile var text: String? = null
    @Volatile var imageUri: Uri? = null
    @Volatile var imageUris: List<Uri>? = null

    @Synchronized
    fun consume(): Triple<String?, Uri?, List<Uri>?> {
        val result = Triple(text, imageUri, imageUris)
        text = null
        imageUri = null
        imageUris = null
        return result
    }

    @Synchronized
    fun set(text: String?, imageUri: Uri?, imageUris: List<Uri>? = null) {
        this.text = text
        this.imageUri = imageUri
        this.imageUris = imageUris
    }
}
