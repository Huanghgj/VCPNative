# AI Overlay Agent: Screen Reading + Device Control

> 悬浮窗 AI Agent 设计文档 — 通过无障碍服务读取屏幕并操控手机

## 1. 目标

在现有悬浮窗 AI 助手基础上增加两项核心能力：

1. **屏幕读取** — 通过 AccessibilityService 遍历 UI 树，提取当前屏幕的结构化描述
2. **设备操控** — AI 可以执行点击、滑动、输入文字、返回等操作

用户在悬浮窗对话中下达自然语言指令（如"帮我打开微信发消息给xxx"），AI 自主循环执行：读屏 → 决策 → 操作 → 读屏 → ... 直到任务完成。

## 2. 架构概览

```
┌─────────────────────────────────────────────┐
│              AiOverlayService               │
│          (AccessibilityService)             │
│                                             │
│  ┌───────────┐  ┌──────────────┐            │
│  │ScreenReader│  │DeviceController│          │
│  │(读取UI树)  │  │(执行操作)      │          │
│  └─────┬─────┘  └──────┬───────┘            │
│        │               │                    │
│  ┌─────┴───────────────┴─────┐              │
│  │     AgentToolExecutor     │              │
│  │  (工具定义 + 调用路由)     │              │
│  └─────────────┬─────────────┘              │
│                │                            │
│  ┌─────────────┴─────────────┐              │
│  │    OverlayChatManager     │              │
│  │  (Agent Loop + LLM 通信)  │              │
│  └─────────────┬─────────────┘              │
│                │                            │
│  ┌─────────────┴─────────────┐              │
│  │  OverlayComposeContent    │              │
│  │  (悬浮窗 UI)              │              │
│  └───────────────────────────┘              │
└─────────────────────────────────────────────┘
```

## 3. 组件设计

### 3.1 ScreenReader

**职责**: 遍历 `AccessibilityNodeInfo` 树，输出结构化的屏幕描述。

**文件**: `feature/overlay/agent/ScreenReader.kt`

**核心接口**:
```kotlin
class ScreenReader(private val service: AccessibilityService) {

    data class ScreenNode(
        val id: Int,                    // 自增编号，用于 AI 引用
        val className: String,          // android.widget.Button 等
        val text: String?,              // 显示文字
        val contentDescription: String?, // 无障碍描述
        val resourceId: String?,        // view ID (com.xxx:id/btn_send)
        val bounds: Rect,               // 屏幕坐标
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
    )

    data class ScreenSnapshot(
        val packageName: String,        // 前台应用包名
        val activityName: String?,      // 当前 Activity
        val nodes: List<ScreenNode>,    // 可交互节点列表
        val timestamp: Long,
    )

    fun readScreen(): ScreenSnapshot
}
```

**遍历策略**:
- 从 `service.rootInActiveWindow` 开始 DFS
- 只保留**可交互**节点（clickable / scrollable / editable / checkable）以及**有文字**的节点
- 每个节点分配自增 ID（从 1 开始），AI 通过 ID 引用节点
- 去重：bounds 中心点距离 < 10px 的节点合并
- 限制最大节点数 50 个，超出时优先保留 clickable 和有文字的

**输出格式** (给 AI 的文本描述):
```
当前应用: com.tencent.mm (微信)
屏幕元素:
[1] Button "发送" (540,1800)-(720,1860) clickable
[2] EditText "输入消息" (60,1700)-(900,1780) editable
[3] TextView "张三" (60,120)-(300,168)
[4] ImageButton [返回] (0,48)-(96,144) clickable
...
```

### 3.2 DeviceController

**职责**: 封装所有设备操控能力。

**文件**: `feature/overlay/agent/DeviceController.kt`

**核心接口**:
```kotlin
class DeviceController(private val service: AccessibilityService) {

    // 手势操作 (dispatchGesture)
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean
    suspend fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long = 300,
    ): Boolean

    // 节点操作 (performAction)
    fun clickNode(nodeId: Int, screenReader: ScreenReader): Boolean
    fun setTextOnNode(nodeId: Int, text: String, screenReader: ScreenReader): Boolean
    fun scrollNode(nodeId: Int, direction: ScrollDirection, screenReader: ScreenReader): Boolean

    // 全局操作 (performGlobalAction)
    fun pressBack(): Boolean
    fun pressHome(): Boolean
    fun openRecents(): Boolean
    fun openNotifications(): Boolean
    fun openQuickSettings(): Boolean

    // 应用操作
    fun launchApp(packageName: String): Boolean

    enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
}
```

**手势实现**:
- `tap` → `GestureDescription` 单点 Path，duration 50ms
- `longPress` → 单点 Path，duration 1000ms
- `swipe` → 单线段 Path，可配置 duration
- 所有手势返回 `suspendCancellableCoroutine` 包装的 callback 结果

### 3.3 AgentToolExecutor

**职责**: 定义 AI 可用的工具集，解析 AI 返回的工具调用，路由到对应的执行器。

**文件**: `feature/overlay/agent/AgentToolExecutor.kt`

**工具定义** (共 10 个):

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `read_screen` | 无 | 读取当前屏幕所有可交互元素 |
| `tap` | `node_id: Int` | 点击指定编号的元素 |
| `tap_xy` | `x: Int, y: Int` | 点击屏幕坐标 |
| `long_press` | `node_id: Int` | 长按指定元素 |
| `type_text` | `node_id: Int, text: String` | 在指定输入框输入文字 |
| `swipe` | `direction: String, distance: String` | 滑动屏幕 (up/down/left/right, short/medium/long) |
| `press_key` | `key: String` | 按键 (back/home/recents/notifications) |
| `open_app` | `package_name: String` | 启动指定应用 |
| `screenshot` | 无 | 截取当前屏幕 (视觉分析) |
| `finish` | `summary: String` | 任务完成，返回总结 |

**AI 工具调用格式** (JSON):
```json
{
  "tool": "tap",
  "args": { "node_id": 3 }
}
```

**工具执行结果格式**:
```json
{
  "tool": "tap",
  "success": true,
  "result": "已点击 [3] Button \"发送\""
}
```

### 3.4 Agent Loop (OverlayChatManager 改造)

**循环流程**:

```
用户输入任务
    │
    ▼
自动调用 read_screen 获取初始屏幕状态
    │
    ▼
┌── 构造 system prompt + 屏幕状态 + 用户任务 → 发给 LLM
│       │
│       ▼
│   LLM 返回工具调用?
│       │
│   ┌───┴───┐
│   │ Yes   │ No (纯文本回复)
│   │       │
│   ▼       ▼
│   执行工具  显示回复 → 结束
│   │
│   ▼
│   将工具结果追加到消息历史
│   │
│   ▼
│   循环次数 < MAX_ROUNDS (15)?
│   │
│   ┌───┴───┐
│   │ Yes   │ No
│   │       │
│   ▼       ▼
└── 继续    提示达到上限 → 结束
```

**System Prompt**:
```
你是 AI 悬浮助手，运行在用户的 Android 手机上。你可以读取屏幕内容并操控手机来帮助用户完成任务。

## 可用工具
- read_screen: 读取当前屏幕所有可交互元素
- tap(node_id): 点击指定编号的元素
- tap_xy(x, y): 点击屏幕指定坐标
- long_press(node_id): 长按指定元素
- type_text(node_id, text): 在输入框输入文字
- swipe(direction, distance): 滑动屏幕
- press_key(key): 按键 (back/home/recents)
- open_app(package_name): 打开应用
- screenshot: 截屏用于视觉分析
- finish(summary): 任务完成

## 使用规则
1. 每次操作前先 read_screen 了解当前状态
2. 每次只执行一个工具调用
3. 操作后等待下一轮 read_screen 确认结果
4. 如果操作失败或界面没变化，尝试其他方法
5. 无法完成时调用 finish 说明原因
6. 回复格式: 先简要说明你要做什么，然后给出工具调用 JSON

## 响应格式
思考: <你的分析>
操作: <工具调用 JSON>
```

**关键参数**:
- `MAX_AGENT_ROUNDS = 15` — 单次任务最大循环次数
- `OPERATION_DELAY_MS = 500` — 每次操作后等待 UI 更新的时间
- `MAX_HISTORY_MESSAGES = 30` — Agent 模式下消息历史上限

### 3.5 安全机制

| 风险 | 对策 |
|------|------|
| AI 误操作 | 默认模式下每步操作前弹窗确认；可切换为自动模式 |
| 无限循环 | MAX_ROUNDS=15 硬限制 |
| 敏感操作 | 检测支付/删除关键词时强制确认 |
| 隐私 | 读取的屏幕内容仅在内存中，不持久化到磁盘 |
| 后台误触 | Agent 循环中检测前台应用变化，异常切换时暂停 |

**敏感操作关键词** (强制确认):
- 支付/付款/转账/红包
- 删除/卸载/清除
- 发送/确认/提交

## 4. 文件结构

```
feature/overlay/
├── AiOverlayService.kt          (已有，需改造)
├── OverlayChatManager.kt        (已有，需改造)
├── OverlayComposeContent.kt     (已有，需改造)
├── OverlayMessage.kt            (从 OverlayChatManager 提取)
└── agent/
    ├── ScreenReader.kt           (新建)
    ├── DeviceController.kt       (新建)
    └── AgentToolExecutor.kt      (新建)
```

## 5. 无障碍服务配置变更

```xml
<!-- ai_overlay_service_config.xml -->
<accessibility-service
    android:canRetrieveWindowContent="true"     <!-- false → true -->
    android:canTakeScreenshot="true"
    android:canPerformGestures="true"           <!-- 新增 -->
    android:accessibilityFlags="flagDefault|flagReportViewIds|flagRequestEnhancedWebAccessibility"
    ... />
```

## 6. 参考项目

- **Hermes Android** — ScreenReader/ActionExecutor 的工具封装、结构化 UI 树输出
- **PhoneClaw** — 端侧 Agent Loop 设计、ClawScript 操作 API
- **AppAgent** — 视觉标注 + LLM 决策格式 (Observation/Thought/Action)

## 7. 后续扩展

- 截图 + UI 树双通道感知（视觉模型理解复杂 UI）
- 操作录制与回放（学习用户习惯）
- 多步任务规划（复杂任务拆解为子目标）
- 应用专属知识库（类似 AppAgent 的 documentation 机制）
