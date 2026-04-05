package com.vcpnative.app.feature.overlay.agent

import android.util.Log
import org.json.JSONObject

class AgentToolExecutor(
    private val screenReader: ScreenReader,
    private val deviceController: DeviceController,
    private val onScreenshot: suspend () -> String?,
) {

    companion object {
        private const val TAG = "AgentToolExecutor"

        val SENSITIVE_KEYWORDS = listOf(
            "支付", "付款", "转账", "红包", "充值",
            "删除", "卸载", "清除", "格式化",
            "发送", "确认", "提交", "同意",
        )

        const val CHAT_SYSTEM_PROMPT = """你是 AI 悬浮助手，运行在用户的 Android 手机上。
你可以看到用户发送的屏幕截图，帮助用户理解屏幕内容、回答问题。
回答要简洁实用，适合在悬浮小窗中阅读。
如果用户想让你操作手机（点击、滑动、打开应用等），请告诉用户切换到「Agent 模式」（点击顶部的 Chat/Agent 按钮）。"""

        const val SYSTEM_PROMPT = """你是 AI 手机操控助手，运行在用户的 Android 手机上。你通过无障碍服务读取屏幕内容并操控手机，帮助用户完成任务。

## 回复格式
每次回复必须包含「思考」和「操作」两部分：

思考: <分析当前屏幕状态，说明下一步要做什么>
操作: {"tool":"工具名","args":{参数}}

如果任务已完成或不需要操作手机，直接用自然语言回复（不要加「操作:」）。

## 可用工具

| 工具 | 用途 | 调用示例 |
|------|------|----------|
| read_screen | 读取屏幕上所有可交互元素 | {"tool":"read_screen"} |
| tap | 点击编号元素 | {"tool":"tap","args":{"node_id":3}} |
| tap_xy | 点击屏幕坐标 | {"tool":"tap_xy","args":{"x":540,"y":1200}} |
| long_press | 长按编号元素 | {"tool":"long_press","args":{"node_id":3}} |
| type_text | 在输入框输入文字 | {"tool":"type_text","args":{"node_id":2,"text":"你好"}} |
| swipe | 滑动屏幕 | {"tool":"swipe","args":{"direction":"up","distance":"medium"}} |
| press_key | 按键(back/home/recents) | {"tool":"press_key","args":{"key":"back"}} |
| open_app | 用包名打开应用 | {"tool":"open_app","args":{"package_name":"com.tencent.mm"}} |
| screenshot | 截屏(视觉分析) | {"tool":"screenshot"} |
| finish | 任务完成 | {"tool":"finish","args":{"summary":"已完成xxx"}} |

## 常用应用包名
- 微信: com.tencent.mm
- QQ: com.tencent.mobileqq
- 支付宝: com.eg.android.AlipayGphone
- 淘宝: com.taobao.taobao
- 抖音: com.ss.android.ugc.aweme
- 小红书: com.xingin.xhs
- B站: tv.danmaku.bili
- 网易云音乐: com.netease.cloudmusic
- 高德地图: com.autonavi.minimap
- 设置: com.android.settings
- 电话: com.android.dialer
- 短信: com.android.mms
- 浏览器: com.android.browser
- 相机: com.android.camera

## 操作流程
1. 收到任务后，先用 read_screen 读取当前屏幕
2. 根据屏幕内容决定操作（点击/滑动/输入等）
3. 每次只执行一个操作，系统会自动返回操作结果和新的屏幕状态
4. 根据新屏幕状态继续下一步操作，直到任务完成
5. 完成后调用 finish 汇报结果

## 注意事项
- 每次只能调用一个工具
- 优先用 tap(node_id) 而非 tap_xy，更精准
- 找不到目标元素时，尝试 swipe 滚动查找
- 如果需要打开一个应用但不知道包名，可以先 press_key home 回到桌面，然后 read_screen 查找
- 操作失败时尝试其他方法，不要重复同样的失败操作
- 无法完成任务时诚实告知用户

## 示例交互

用户: 帮我打开微信
助手:
思考: 用户想打开微信，我直接用 open_app 打开。
操作: {"tool":"open_app","args":{"package_name":"com.tencent.mm"}}

[系统返回: 已启动 com.tencent.mm]
[屏幕更新: 当前应用 com.tencent.mm, 屏幕元素: [1] TextView "微信" ...]

助手:
思考: 微信已经打开，看到了微信主界面。任务完成。
操作: {"tool":"finish","args":{"summary":"已成功打开微信"}}"""
    }

    data class ToolCall(
        val tool: String,
        val args: JSONObject,
    )

    data class ToolResult(
        val tool: String,
        val success: Boolean,
        val result: String,
        val isFinish: Boolean = false,
        val screenshotDataUrl: String? = null,
    )

    private var lastSnapshot: ScreenReader.ScreenSnapshot? = null

    fun parseToolCall(llmResponse: String): ToolCall? {
        // Find the opening brace of a JSON object containing "tool"
        val toolKeyPattern = """"tool"\s*:""".toRegex()
        val keyMatch = toolKeyPattern.find(llmResponse) ?: return null
        val braceStart = llmResponse.lastIndexOf('{', keyMatch.range.first)
        if (braceStart < 0) return null

        // Walk forward from the brace, counting depth to find the matching close
        var depth = 0
        var braceEnd = -1
        for (i in braceStart until llmResponse.length) {
            when (llmResponse[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { braceEnd = i; break }
                }
            }
        }
        if (braceEnd < 0) return null

        return try {
            val json = JSONObject(llmResponse.substring(braceStart, braceEnd + 1))
            ToolCall(
                tool = json.getString("tool"),
                args = json.optJSONObject("args") ?: JSONObject(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool call: ${e.message}")
            null
        }
    }

    fun requiresConfirmation(toolCall: ToolCall): String? {
        // Check if the tool action involves sensitive operations
        when (toolCall.tool) {
            "tap", "long_press" -> {
                val nodeId = toolCall.args.optInt("node_id", -1)
                val snapshot = lastSnapshot ?: return null
                val node = screenReader.findNodeById(snapshot, nodeId) ?: return null
                val label = node.text ?: node.contentDescription ?: return null
                for (keyword in SENSITIVE_KEYWORDS) {
                    if (label.contains(keyword)) {
                        return "即将点击「$label」，包含敏感操作关键词「$keyword」"
                    }
                }
            }
            "type_text" -> {
                val text = toolCall.args.optString("text", "")
                for (keyword in SENSITIVE_KEYWORDS) {
                    if (text.contains(keyword)) {
                        return "即将输入的文字包含敏感关键词「$keyword」"
                    }
                }
            }
        }
        return null
    }

    suspend fun execute(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.tool) {
                "read_screen" -> executeReadScreen()
                "tap" -> executeTap(toolCall.args)
                "tap_xy" -> executeTapXy(toolCall.args)
                "long_press" -> executeLongPress(toolCall.args)
                "type_text" -> executeTypeText(toolCall.args)
                "swipe" -> executeSwipe(toolCall.args)
                "press_key" -> executePressKey(toolCall.args)
                "open_app" -> executeOpenApp(toolCall.args)
                "screenshot" -> executeScreenshot()
                "finish" -> executeFinish(toolCall.args)
                else -> ToolResult(toolCall.tool, false, "未知工具: ${toolCall.tool}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error: ${e.message}", e)
            ToolResult(toolCall.tool, false, "执行出错: ${e.message}")
        }
    }

    private fun executeReadScreen(): ToolResult {
        val snapshot = screenReader.readScreen()
            ?: return ToolResult("read_screen", false, "无法读取屏幕，可能没有可用的窗口")
        lastSnapshot = snapshot
        val description = screenReader.formatForLlm(snapshot)
        return ToolResult("read_screen", true, description)
    }

    private suspend fun executeTap(args: JSONObject): ToolResult {
        val nodeId = args.optInt("node_id", -1)
        if (nodeId < 1) return ToolResult("tap", false, "无效的 node_id")

        val snapshot = lastSnapshot
            ?: return ToolResult("tap", false, "请先调用 read_screen")
        val node = screenReader.findNodeById(snapshot, nodeId)
            ?: return ToolResult("tap", false, "找不到编号 $nodeId 的元素")

        // Try node-based click first via accessibility
        val accessibilityNode = screenReader.findAccessibilityNode(nodeId, snapshot)
        if (accessibilityNode != null) {
            val success = deviceController.clickNode(accessibilityNode)
            accessibilityNode.recycle()
            if (success) {
                val label = node.text ?: node.contentDescription ?: "元素"
                return ToolResult("tap", true, "已点击 [$nodeId] $label")
            }
        }

        // Fallback to coordinate tap
        val x = node.bounds.centerX()
        val y = node.bounds.centerY()
        val success = deviceController.tap(x, y)
        val label = node.text ?: node.contentDescription ?: "元素"
        return ToolResult("tap", success,
            if (success) "已点击 [$nodeId] $label (坐标 $x,$y)"
            else "点击 [$nodeId] $label 失败",
        )
    }

    private suspend fun executeTapXy(args: JSONObject): ToolResult {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return ToolResult("tap_xy", false, "无效的坐标")
        val success = deviceController.tap(x, y)
        return ToolResult("tap_xy", success,
            if (success) "已点击坐标 ($x, $y)" else "点击坐标 ($x, $y) 失败",
        )
    }

    private suspend fun executeLongPress(args: JSONObject): ToolResult {
        val nodeId = args.optInt("node_id", -1)
        if (nodeId < 1) return ToolResult("long_press", false, "无效的 node_id")

        val snapshot = lastSnapshot
            ?: return ToolResult("long_press", false, "请先调用 read_screen")
        val node = screenReader.findNodeById(snapshot, nodeId)
            ?: return ToolResult("long_press", false, "找不到编号 $nodeId 的元素")

        val x = node.bounds.centerX()
        val y = node.bounds.centerY()
        val success = deviceController.longPress(x, y)
        val label = node.text ?: node.contentDescription ?: "元素"
        return ToolResult("long_press", success,
            if (success) "已长按 [$nodeId] $label" else "长按 [$nodeId] $label 失败",
        )
    }

    private fun executeTypeText(args: JSONObject): ToolResult {
        val nodeId = args.optInt("node_id", -1)
        val text = args.optString("text", "")
        if (nodeId < 1) return ToolResult("type_text", false, "无效的 node_id")
        if (text.isEmpty()) return ToolResult("type_text", false, "文字不能为空")

        val snapshot = lastSnapshot
            ?: return ToolResult("type_text", false, "请先调用 read_screen")

        val accessibilityNode = screenReader.findAccessibilityNode(nodeId, snapshot)
            ?: return ToolResult("type_text", false, "找不到编号 $nodeId 的可编辑元素")

        val success = deviceController.setTextOnNode(accessibilityNode, text)
        accessibilityNode.recycle()
        return ToolResult("type_text", success,
            if (success) "已在 [$nodeId] 输入「$text」" else "输入文字失败",
        )
    }

    private suspend fun executeSwipe(args: JSONObject): ToolResult {
        val dirStr = args.optString("direction", "up").uppercase()
        val distStr = args.optString("distance", "medium").uppercase()

        val direction = try {
            DeviceController.SwipeDirection.valueOf(dirStr)
        } catch (e: Exception) {
            return ToolResult("swipe", false, "无效方向: $dirStr (可选: up/down/left/right)")
        }

        val distance = try {
            DeviceController.SwipeDistance.valueOf(distStr)
        } catch (e: Exception) {
            DeviceController.SwipeDistance.MEDIUM
        }

        val success = deviceController.swipe(direction, distance)
        return ToolResult("swipe", success,
            if (success) "已向${directionLabel(direction)}滑动 ($distStr)"
            else "滑动失败",
        )
    }

    private fun executePressKey(args: JSONObject): ToolResult {
        val key = args.optString("key", "")
        val success = when (key.lowercase()) {
            "back" -> deviceController.pressBack()
            "home" -> deviceController.pressHome()
            "recents" -> deviceController.openRecents()
            "notifications" -> deviceController.openNotifications()
            "quick_settings" -> deviceController.openQuickSettings()
            else -> return ToolResult("press_key", false, "未知按键: $key (可选: back/home/recents/notifications)")
        }
        return ToolResult("press_key", success,
            if (success) "已按下 $key" else "按键 $key 失败",
        )
    }

    private fun executeOpenApp(args: JSONObject): ToolResult {
        val packageName = args.optString("package_name", "")
        if (packageName.isEmpty()) return ToolResult("open_app", false, "需要 package_name 参数")
        val success = deviceController.launchApp(packageName)
        return ToolResult("open_app", success,
            if (success) "已启动 $packageName" else "启动 $packageName 失败，可能未安装",
        )
    }

    private suspend fun executeScreenshot(): ToolResult {
        val dataUrl = onScreenshot()
            ?: return ToolResult("screenshot", false, "截屏失败")
        return ToolResult("screenshot", true, "已截取屏幕", screenshotDataUrl = dataUrl)
    }

    private fun executeFinish(args: JSONObject): ToolResult {
        val summary = args.optString("summary", "任务完成")
        return ToolResult("finish", true, summary, isFinish = true)
    }

    private fun directionLabel(direction: DeviceController.SwipeDirection): String = when (direction) {
        DeviceController.SwipeDirection.UP -> "上"
        DeviceController.SwipeDirection.DOWN -> "下"
        DeviceController.SwipeDirection.LEFT -> "左"
        DeviceController.SwipeDirection.RIGHT -> "右"
    }
}
