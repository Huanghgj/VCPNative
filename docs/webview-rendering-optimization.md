# WebView 动态渲染性能优化方案

更新时间：2026-03-26

## 现状分析

### 架构概览

```
LazyColumn (ChatRoute)
  └─ MessageBubble
       └─ ChatMessageContent
            ├─ NativeMarkdownBlockView  → TextView (轻量)
            ├─ NativeHtmlBlockView      → TextView + Spannable (中等)
            └─ BrowserHtmlBlockView     → WebView (重量级)
                 ├─ 独立 Chromium 渲染管线
                 ├─ 独立 V8 JS 引擎
                 └─ 独立 GPU 合成层
```

当消息包含 CSS 动画、3D transform、SVG、`<canvas>`、`<video>` 等高级内容时，
`shouldUseBrowserHtmlRenderer()` 返回 true，走 WebView 渲染路径。

### 核心问题

| # | 问题 | 严重度 | 说明 |
|---|------|--------|------|
| 1 | WebView 无池化 | 高 | 每条消息新建 WebView，滑出销毁、滑回重建，初始化开销大 |
| 2 | 动画永不暂停 | 高 | `pauseDynamicContent` 硬编码 `false`；`applyBrowserHtmlPausedState()` 已实现但从未被调用 |
| 3 | 不可见 WebView 仍在渲染 | 高 | 无可见性检测，屏幕外的 CSS 动画和 JS 持续占用 CPU/GPU |
| 4 | WebView + Compose 双 GPU 管线 | 中 | WebView 内容先经 Chromium GPU 合成，再作为纹理贴到 Compose Surface |
| 5 | 3D CSS 触发 GPU 层提升 | 中 | 每个 `transform: translateZ/rotateX/Y` 元素创建独立 GPU 纹理 |
| 6 | 高度探测多次回调 | 低 | JS bridge 在 load 后 60/240/600ms 各报一次高度，触发 recompose |

### 已有但未启用的基础设施

代码中已实现了暂停机制的完整链路，但未接通：

1. **CSS class**: `.vcp-scroll-paused` — 设置 `animation-play-state: paused`，已注入到每个 WebView 的 HTML 模板中
2. **JS 函数**: `window.__vcpSetPaused(paused)` — 遍历 `document.getAnimations()` 并 pause/play，已注入
3. **Kotlin 函数**: `applyBrowserHtmlPausedState(webView, paused)` — 调用 `webView.onPause()/onResume()` + 执行上述 JS
4. **参数**: `pauseDynamicContent: Boolean` — 从 ChatRoute 传递到 BrowserHtmlBlockView，但 ChatRoute 始终传 `false`
5. **BrowserHtmlBlockView 接受参数但内部未使用它**

---

## 优化方案

### 方案 A：可见性驱动暂停（推荐优先实施）

**原理**：利用 `LazyListState` 检测可见 item 范围，对不可见的 WebView 暂停渲染。

**改动点**：

1. **ChatRoute.kt** — 观察 `listState.layoutInfo.visibleItemsInfo`，计算可见消息 ID 集合
2. **ChatRoute.kt** — 将 `pauseDynamicContent` 从硬编码 `false` 改为基于可见性判断
3. **BrowserHtmlBlockView** — 在 `update` lambda 中调用 `applyBrowserHtmlPausedState(webView, pauseDynamicContent)`

```
预期效果：屏幕外的 WebView 动画/JS 全部暂停，CPU/GPU 开销降低 50-80%
改动量：~30 行
风险：低（已有完整基础设施，只需接通）
```

### 方案 B：滚动时全局暂停

**原理**：滚动过程中暂停所有 WebView 动态内容，停止滚动后恢复。

**改动点**：

1. **ChatRoute.kt** — 通过 `listState.isScrollInProgress` 或 `snapshotFlow { listState.firstVisibleItemIndex }` 检测滚动状态
2. **ChatRoute.kt** — 滚动时 `pauseDynamicContent = true`，停止 200ms 后恢复 `false`

```
预期效果：滚动流畅度显著提升，避免滚动时 WebView 和 Compose 争抢 GPU
改动量：~20 行
风险：低
```

### 方案 C：WebView 池化复用

**原理**：维护一个 WebView 对象池，避免反复创建/销毁。

**改动点**：

1. 新建 `WebViewPool` 类，维护 idle/active 两个队列
2. `BrowserHtmlBlockView` 的 `factory` 从池中获取，`onRelease` 归还而非销毁
3. 归还时重置状态：`loadUrl("about:blank")` + 移除 JS interface + 清除 tag

```
预期效果：消除 WebView 初始化开销，滑动时不再闪白
改动量：~80 行（新建类 + 修改 BrowserHtmlBlockView）
风险：中（需要处理池内 WebView 状态泄漏、内存上限）
池大小建议：3-5 个（同时可见的 WebView 消息通常不超过 3 条）
```

### 方案 D：硬件加速显式配置

**改动点**：

1. **AndroidManifest.xml** — Activity 级别显式启用 `android:hardwareAccelerated="true"`（当前依赖默认值）
2. **BrowserHtmlBlockView** — 对 WebView 设置 `setLayerType(LAYER_TYPE_HARDWARE, null)` 强制 GPU 光栅化
3. 对不含 3D/动画的静态 HTML WebView，改用 `LAYER_TYPE_SOFTWARE` 避免不必要的 GPU 层

```
预期效果：3D 渲染更流畅，静态内容减少 GPU 开销
改动量：~15 行
风险：低（但 LAYER_TYPE_SOFTWARE 会禁用部分 CSS 效果）
```

### 方案 E：减少高度回调频率

**改动点**：

1. JS 端 `postHeightNow()` 加入节流（当前 requestAnimationFrame 已有一帧节流，但 load 后 4 次定时回调过于频繁）
2. 将 load 后的 60/240/600ms 回调合并为一次 300ms 延迟
3. Kotlin 端高度变化阈值从 2px 提高到 4-8px

```
预期效果：减少不必要的 recompose 次数
改动量：~10 行
风险：极低
```

---

## 推荐实施顺序

```
第一轮（投入产出比最高）：
  方案 A（可见性暂停）+ 方案 B（滚动暂停）
  → 共 ~50 行改动，预期性能提升最大

第二轮（进一步优化）：
  方案 E（减少回调）+ 方案 D（硬件加速配置）
  → 共 ~25 行改动，精细调优

第三轮（深度优化）：
  方案 C（WebView 池化）
  → ~80 行改动，消除初始化开销
```

---

## 验证方法

1. **GPU 渲染分析**：开发者选项 → Profile GPU Rendering → 观察柱状图高度变化
2. **内存监控**：Android Studio Profiler → 对比优化前后 WebView 个数和内存占用
3. **帧率**：`adb shell dumpsys gfxinfo <package>` 对比优化前后 janky frames 比例
4. **主观测试**：打开包含多个 3D/动画消息的对话，快速上下滑动，观察流畅度和发热情况

---

## 关键文件索引

| 文件 | 相关内容 |
|------|----------|
| `ChatMessageRenderer.kt:2143-2314` | BrowserHtmlBlockView（WebView 创建/生命周期） |
| `ChatMessageRenderer.kt:2345-2364` | applyBrowserHtmlPausedState（已实现未调用） |
| `ChatMessageRenderer.kt:4242-4396` | buildBrowserHtmlDocument（HTML/CSS/JS 模板） |
| `ChatRoute.kt:715-731` | LazyListState 使用 |
| `ChatRoute.kt:877-908` | LazyColumn + items 配置 |
| `ChatRoute.kt:895` | pauseDynamicContent = false（硬编码） |
