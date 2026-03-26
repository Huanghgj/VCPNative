package com.vcpnative.app.macrobenchmark

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Before
    fun ensureWorkspace() {
        ensureBenchmarkWorkspace(
            device = device,
            targetContext = instrumentation.targetContext,
            packageName = TARGET_PACKAGE,
        )
    }

    @Test
    fun startupColdNoCompilation() = benchmarkStartup(
        compilationMode = CompilationMode.None(),
    )

    @Test
    fun startupColdBaselineProfile() = benchmarkStartup(
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
        ),
    )

    private fun benchmarkStartup(
        compilationMode: CompilationMode,
    ) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric(),
            ),
            compilationMode = compilationMode,
            iterations = 10,
            startupMode = StartupMode.COLD,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
            waitForChatScreen()
        }
    }
}

private fun ensureBenchmarkWorkspace(
    device: UiDevice,
    targetContext: Context,
    packageName: String,
) {
    launchApp(targetContext, packageName)
    waitForLandingScreen(device)

    if (device.hasObject(By.text("进入设置"))) {
        completeSetupGate(device)
    }

    if (!isChatVisible(device) && device.hasObject(By.desc(CREATE_AGENT_DESC))) {
        device.waitForObject(UI_WAIT_TIMEOUT_MS, By.desc(CREATE_AGENT_DESC)).click()
        waitForLandingScreen(device)
    }

    if (!isChatVisible(device) && device.hasObject(By.desc(CREATE_TOPIC_DESC))) {
        device.waitForObject(UI_WAIT_TIMEOUT_MS, By.desc(CREATE_TOPIC_DESC)).click()
    }

    waitForChatScreen(device)
    device.pressHome()
}

private fun launchApp(
    targetContext: Context,
    packageName: String,
) {
    val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        ?: error("Unable to resolve launch intent for $packageName")
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
    targetContext.startActivity(launchIntent)
}

private fun completeSetupGate(device: UiDevice) {
    device.waitForObject(UI_WAIT_TIMEOUT_MS, By.text("进入设置")).click()
    device.waitForObject(UI_WAIT_TIMEOUT_MS, By.desc(SETTINGS_SERVER_URL_DESC))
        .setText(BENCHMARK_SERVER_URL)
    device.waitForObject(UI_WAIT_TIMEOUT_MS, By.desc(SETTINGS_API_KEY_DESC))
        .setText(BENCHMARK_API_KEY)
    runCatching {
        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("保存并继续")
    }
    device.waitForObject(UI_WAIT_TIMEOUT_MS, By.desc(SAVE_SETTINGS_DESC)).click()
    waitForLandingScreen(device)
}

private fun MacrobenchmarkScope.waitForChatScreen() {
    waitForChatScreen(device)
}

private fun waitForLandingScreen(device: UiDevice) {
    device.waitForObject(
        UI_WAIT_TIMEOUT_MS,
        By.text("进入设置"),
        By.text("Agent 列表"),
        By.desc(CREATE_AGENT_DESC),
        By.desc(CREATE_TOPIC_DESC),
        By.text("话题"),
        By.text("输入消息"),
    )
}

private fun waitForChatScreen(device: UiDevice) {
    device.waitForObject(
        UI_WAIT_TIMEOUT_MS,
        By.text("输入消息"),
        By.text("话题"),
        By.text("新建"),
    )
}

private fun isChatVisible(device: UiDevice): Boolean =
    device.hasObject(By.text("输入消息")) ||
        (device.hasObject(By.text("话题")) && device.hasObject(By.text("新建")))

private fun UiDevice.waitForObject(
    timeoutMillis: Long,
    vararg selectors: BySelector,
): UiObject2 {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        waitForIdle()
        selectors.firstNotNullOfOrNull(::findObject)?.let { return it }
        SystemClock.sleep(UI_POLL_INTERVAL_MS)
    }
    error("Timed out waiting for UI selectors in $timeoutMillis ms")
}

private const val TARGET_PACKAGE = "com.vcpnative.app"
private const val UI_WAIT_TIMEOUT_MS = 15_000L
private const val UI_POLL_INTERVAL_MS = 250L
private const val BENCHMARK_SERVER_URL = "https://benchmark.invalid"
private const val BENCHMARK_API_KEY = "benchmark-key"
private const val SETTINGS_SERVER_URL_DESC = "settings_server_url_field"
private const val SETTINGS_API_KEY_DESC = "settings_api_key_field"
private const val SAVE_SETTINGS_DESC = "save_settings_button"
private const val CREATE_AGENT_DESC = "create_placeholder_agent"
private const val CREATE_TOPIC_DESC = "create_placeholder_topic"
