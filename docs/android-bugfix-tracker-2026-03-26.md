# Android Bugfix Tracker

日期：2026-03-26

## 目标

按用户最新反馈，逐项修复以下 3 个问题，并在每个问题修复后立刻更新本文档：

1. 聊天渲染器 / 渲染解析器疑似有问题，部分内容渲染，部分内容不渲染
2. Android 应用私有目录应落在 `/storage/emulated/0/Android/data/com.vcpnative.app`
3. 无法通过 API 获取模型列表

---

## 当前状态总览

| 编号 | 问题 | 状态 | 当前判断 |
| --- | --- | --- | --- |
| 1 | 渲染器 / 解析器部分渲染 | `已修复（待 APK 回归）` | 聊天主渲染链已调整为“普通内容原生 + 浏览器级 HTML 隔离 WebView”的混合方案，避免把复杂 DOM/CSS/JS 强塞给原生解析器 |
| 2 | 私有目录路径不符合预期 | `已修复` | `rootDir` 仍优先落在 `context.getExternalFilesDir(null)?.parentFile`，即 `/storage/emulated/0/Android/data/com.vcpnative.app` |
| 3 | API 获取模型失败 | `已修复` | 模型/聊天/中断 URL 已统一按规范化 API root 生成，并移除 `org.json` 对本地测试环境的阻塞 |

---

## Issue 1: 渲染器 / 解析器部分渲染

### 用户现象

- 同一条 AI 消息中，部分 HTML / 特殊内容被渲染，另一部分仍以原文显示
- 参考对象明确是 `/root/VCPChat` 的桌面渲染器与渲染预处理链

### 当前判断

- 根因已定位并修正：
  - 当前主问题不是某一个 HTML 标签漏判，而是把“浏览器级 HTML”误塞进原生解析/渲染链，导致要么显示成纯文本，要么在极端消息下拖垮渲染骨架
  - 对真实数据 `history.json` 复核后，确认这类消息本质上就是桌面 DOM/CSS/JS 文档，不是普通 markdown/html
  - 本轮修正为混合架构：普通 markdown/html 继续走 `noties/Markwon` / 原生链路，浏览器级 HTML 改为隔离 `WebView`
  - 同时补了 `WebView` 骨架修正：高度回调切回主线程、流式更新延迟提交、释放时销毁实例，避免高频整页重载

### 参考基线

- `/root/VCPChat/modules/messageRenderer.js`
- `/root/VCPChat/modules/renderer/contentProcessor.js`
- `/root/VCPChat/modules/renderer/streamManager.js`
- `https://github.com/noties/Markwon`

### 修复记录

- `2026-03-26`：引入 GitHub 原生 Android 渲染库 `Markwon`，聊天通用渲染改为 `TextView/Spannable` 原生链路
- `2026-03-26`：`ChatMessageRenderer.kt` 调整为混合架构；浏览器级 HTML 重新接回隔离 `WebView`，普通 markdown/html 仍走原生链路
- `2026-03-26`：普通 markdown/html 不再默认走自写块级解析；当前只对以下 VCP 专有内容保留特殊分流：
  - `<<<[TOOL_REQUEST]>>>`
  - `[[VCP调用结果信息汇总: ... VCP调用结果结束]]`
  - `<<<DailyNoteStart>>>`
  - `[--- VCP元思考链 ---]`
  - `<think>...</think>`
  - `<<<[DESKTOP_PUSH]>>>`
  - `<<<[ROLE_DIVIDE_*]>>>`
  - `[[点击按钮:...]]`
  - `{{VCPChatCanvas}}`
- `2026-03-26`：HTML `<button>` 已改为原生动作链接回发，继续兼容 `data-send` 与 `onclick="input('...')"`
- `2026-03-26`：修正 `ensureNewlineAfterCodeBlock`，不再破坏 ` ```mermaid ` / ` ```python ` 这类合法代码围栏
- `2026-03-26`：渲染解析单测通过，覆盖 HTML fragment、按钮、tool request 分流、mermaid 围栏
- `2026-03-26`：新增浏览器级 HTML 分流判定与超大消息安全阈值测试；真实复杂 HTML 不再错误打回纯文本安全模式

### 验证口径

- 普通 markdown/html 消息应整体由 Android 原生 markdown renderer 完整渲染，不再出现“前半段渲染、后半段原文漏出”
- 浏览器级 HTML 应完整进入 `WebView` 渲染，而不是退回纯文本
- `input('...')`、`button[data-send]` 等 VCPChat 风格交互应继续回发到现有发送链路
- `mermaid` 代码围栏不能再被预处理错误拆坏

---

## Issue 2: 私有目录路径不符合预期

### 用户现象

- 用户要求 Android 应用私有目录应为 `/storage/emulated/0/Android/data/com.vcpnative.app`

### 当前判断

- 当前实现位于 `context.filesDir/vcpnative`
- 这对应内部私有目录，通常形如 `/data/user/0/com.vcpnative.app/files/vcpnative`
- 路径与用户预期不一致

### 相关文件

- `/root/VCPNative/android-app/app/src/main/java/com/vcpnative/app/data/files/AppFileStore.kt`

### 修复记录

- `2026-03-26`：`AndroidPrivateFileStore.rootDir` 已从 `context.filesDir/vcpnative` 调整为优先使用 `context.getExternalFilesDir(null)?.parentFile`
- `2026-03-26`：当系统未提供外部应用专属目录时，仍保留回退到内部目录的兜底逻辑，避免启动失败
- `2026-03-26`：本轮复核代码实现，当前仍保持上述路径优先级；本轮未再改动该逻辑

### 验证口径

- 应用显示的根目录应切换到外部应用专属目录
- 根目录路径应落在 `/storage/emulated/0/Android/data/com.vcpnative.app`

---

## Issue 3: API 获取模型失败

### 用户现象

- 设置页无法通过 API 获取模型列表

### 当前判断

- 根因已定位并修正：
  - URL 规范化过于粗糙，模型接口无法稳定复用带前缀路径的 API root
  - `VcpModelCatalog` 使用 Android `org.json`，本地 JVM 单测下相关方法会被 SDK stub 掉，阻塞持续验证

### 相关文件

- `/root/VCPNative/android-app/app/src/main/java/com/vcpnative/app/feature/settings/SettingsRoute.kt`
- `/root/VCPNative/android-app/app/src/main/java/com/vcpnative/app/network/vcp/VcpModelCatalog.kt`
- `/root/VCPNative/android-app/app/src/main/java/com/vcpnative/app/network/vcp/VcpNetwork.kt`
- `/root/VCPNative/android-app/app/src/test/java/com/vcpnative/app/network/vcp/VcpNetworkTest.kt`
- `/root/VCPChat/main.js`

### 修复记录

- `2026-03-26`：`VcpServiceConfig` 改为统一规范化 API root，支持以下输入稳定派生：
  - origin 根地址
  - 带前缀但未显式带 `/v1` 的 base path
  - 完整 `/v1/chat/completions` 或 `/proxy/v1/chat/completions` 端点
- `2026-03-26`：新增 `apiRootUrl` / `modelsUrl` / `interruptUrl`，模型发现不再手工拼接 `origin + /v1/models`
- `2026-03-26`：`VcpModelCatalog` 改为 `kotlinx.serialization.json` 解析，去掉 `org.json` 对本地测试环境的阻塞
- `2026-03-26`：模型链路相关单测全部通过

### 验证口径

- 未离开设置页、仅修改 URL / API Key 后，应能直接刷新模型列表
- 模型接口失败时，应返回明确错误信息而不是静默空列表

---

## 最新构建状态

- `2026-03-26` 已通过：
  - `cd /root/VCPNative/android-app && ./gradlew :app:testDebugUnitTest --tests 'com.vcpnative.app.network.vcp.VcpModelCatalogTest' --tests 'com.vcpnative.app.network.vcp.VcpNetworkTest' --no-daemon -Pkotlin.incremental=false -Pksp.incremental=false -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' -Dkotlin.compiler.execution.strategy=in-process`
  - `cd /root/VCPNative/android-app && ./gradlew :app:assembleDebug --no-daemon -Pkotlin.incremental=false -Pksp.incremental=false -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' -Dkotlin.compiler.execution.strategy=in-process`
- `2026-03-26` 本轮新增通过：
  - `cd /root/VCPNative/android-app && ./gradlew :app:testDebugUnitTest --tests 'com.vcpnative.app.chat.render.VcpChatMessageParserTest' --no-daemon -Pkotlin.incremental=false -Pksp.incremental=false -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' -Dkotlin.compiler.execution.strategy=in-process`
- 说明：这台机器的 `debugUnitTest` Kotlin 增量缓存不稳定，因此本轮测试显式关闭了 Kotlin/KSP 增量编译
