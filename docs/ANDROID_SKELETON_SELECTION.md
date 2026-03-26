# Android 骨架选型

更新时间：2026-03-25

本文回答两个问题：

1. `VCPChat` Android 版应从什么工程骨架起步
2. GitHub 上哪些开源项目适合借，哪些不适合整仓拿来

需要先说明：

- 下面对开源仓库的“适合 / 不适合”判断，是基于它们的 README、`settings.gradle*`、主要模块依赖和目录结构做出的工程推断。
- 这里的“骨架”指 Android 首个可运行工程的起步方式，不等于后续永远不演进。

---

## 1. 选型前提

这次选型不按“哪个聊天 App 看起来更像”来做，而按下面这些硬约束来做：

- Android 宿主壳必须是原生应用
- 项目性质是迁移 `VCPChat`，不是重写一个新的聊天产品
- `/root/VCPToolBox` 不动
- P1 只做单聊主链路
- Android 运行时真相来源已经固定为 `Room + DataStore + files`
- Android 端必须承接请求编译、SSE 流、历史保存、中断，而不只是消息列表 UI
- 默认策略仍然是“先抄业务逻辑，再做宿主适配，最后才局部改写”

这意味着：

- 不能只选一个“聊天页面很好看”的 demo
- 不能选一个强绑定 OpenAI / Stream / 第三方 SaaS 聊天模型的产品工程
- 不能为了工程体面，先上过重的 module / build-logic / DI 体系

---

## 2. 评估标准

本次评估只看下面七条：

1. 是否是原生 Android 工程，而不是 WebView / Hybrid 壳
2. 是否适合从单聊 MVP 起步
3. 是否允许直接承接我们已有的数据层和流状态机设计
4. 是否没有强绑定外部聊天 SDK 或特定云产品
5. 是否不会在开工第一天就引入过重工程复杂度
6. 是否许可证和工程形态适合参考或借模式
7. 是否有利于后续直接抄 `VCPChat` 的业务代码

---

## 3. 结论先行

最终结论不是“整仓采用某个现成开源项目”，而是：

- 用 `Jetchat` 作为聊天 UI、输入区、导航与 Compose 页面组织参考
- 用 `Now in Android` 作为官方分层、状态边界、仓储职责和工程演进参考
- 在 `/root/VCPNative/android-app` 自己搭一个原生 Kotlin + Compose 的轻量骨架
- 首阶段固定为单 `:app` 模块，不先拆多 module，不先上 Hilt，不先接第三方聊天 SDK

也就是说：

- `Jetchat` 和 `Now in Android` 是“借模式”
- 不是“选一个整仓抄下来当底板”

---

## 4. 候选项目逐项判断

### 4.1 `android/compose-samples` 的 `Jetchat`

可以确认的事实：

- `Jetchat` README 明确说明它是一个基于 Jetpack Compose 的 sample chat app，重点展示 UI state、Navigation、ViewModel、输入框管理、动画、配置变更状态保存和 UI tests。
- `Jetchat/settings.gradle.kts` 只包含 `:app`。
- `Jetchat/app` 下主要是 `conversation / data / profile / theme / widget` 等轻量目录。

判断：

- 它很适合做“页面骨架参考”。
- 它不适合直接承接 `VCPChat` 的数据层、请求编译器、SSE 和历史落盘。
- 它的优势是轻、原生、单模块、聊天页结构直观。
- 它的短板是 sample 属性很强，业务深度不够。

结论：

- `Jetchat` 适合借聊天页骨架、输入栏、导航和 Compose UI 组织方式。
- `Jetchat` 不适合直接作为 `VCPNative` 的完整工程底板。

### 4.2 `android/nowinandroid`

可以确认的事实：

- `Now in Android` README 明确说明它是一个 fully functional Android app，并遵循 official architecture guidance。
- README 还明确说明它 fully modularized，并带有 flavors、benchmark、catalog app、Hilt testing 等完整工程设施。
- `settings.gradle.kts` 里包含大量 `core:*`、`feature:*`、`sync:*`、`benchmarks`、`app-nia-catalog` 等模块。

判断：

- 它非常适合作为“官方工程实践参考”。
- 它不适合直接作为当前迁移项目的起步骨架。
- 它的强项是分层清晰、测试体系成熟、状态边界明确。
- 它的问题是过重。对当前项目来说，直接照搬会在真正迁 `VCPChat` 逻辑之前，先花很多时间复制它的工程化复杂度。

结论：

- `Now in Android` 适合借分层思路、状态归属、repository 边界和后续工程演进方式。
- `Now in Android` 不适合作为当前阶段的首个落地骨架。

### 4.3 `skydoves/chatgpt-android`

可以确认的事实：

- README 明确说明这是 OpenAI ChatGPT on Android 的演示工程，并且聊天系统依赖 Stream Chat SDK for Compose。
- README 的构建步骤要求配置 `STREAM_API_KEY` 和 `GPT_API_KEY`。
- `settings.gradle.kts` 包含多个 `core-*`、`feature-*`、`benchmark` 和 `build-logic` 模块。
- `feature-chat/build.gradle.kts` 直接依赖 `stream.compose` 和 `stream.offline`。

判断：

- 它是一个“带明确产品假设”的聊天工程，而不是通用迁移骨架。
- 它的消息模型、实时事件模型、账号和聊天基础设施假设，都不是 `VCPChat <-> VCPToolBox` 这条链路的假设。
- 如果拿它当骨架，会把项目早早带向 Stream/OpenAI 体系，而不是现有 VCP 兼容体系。

结论：

- 不选它做骨架。
- 最多只把它当作“Compose 聊天产品工程”的旁路参考。

### 4.4 `flyun/chatAir`

可以确认的事实：

- README 明确说明它是一个原生 Android 聊天产品，支持 ChatGPT、Gemini、Claude、DeepSeek。
- 仓库许可证是 GPL-3.0。
- 默认分支是 `chatair`，`settings.gradle` 里包含 `TMessagesProj`、多个 app 模块、`openai_*` 模块和多套 markdown 模块。
- `TMessagesProj/build.gradle` 里可以看到 JNI/CMake、Firebase、Google services、多种 build type 和大量产品依赖。

判断：

- 它不是轻骨架，而是一个体量很重的成熟产品工程。
- 它更像“整套现成聊天产品”，不是“适合承接迁移代码的起步底板”。
- GPL-3.0 也使它不适合作为我们后续直接抄代码的默认来源。
- 从 `TMessagesProj` 命名和工程结构看，它还有很明显的历史包袱和重宿主特征。

结论：

- 不选它做骨架。
- 最多参考它的产品完成度，不参考它的工程底板。

---

## 5. 为什么最终不是“整仓抄一个开源项目”

原因很简单：

- `VCPChat` 真正难迁的不是聊天页长什么样，而是请求编译、流事件、历史保存、上下文和兼容逻辑。
- 现成聊天开源项目大多自带自己的后端假设、消息模型和登录体系。
- 我们当前必须兼容的是 `VCPChat` 的现实行为，而不是这些项目的理想聊天模型。

所以更合理的做法是：

- UI 壳借官方轻样例
- 分层方法借官方完整工程
- 核心业务直接围绕 `VCPChat` 自己迁

这才符合“迁移项目，不是重构项目”的路线。

---

## 6. 最终固定下来的 Android 骨架

### 6.1 形态

Android 首个可运行工程固定为：

- 原生 Kotlin
- Jetpack Compose UI
- 单 `:app` 模块
- `Application + MainActivity + Compose Nav` 基础壳
- `Room + DataStore + private files` 从第一天进入骨架
- 轻量 `AppContainer` 或等价 manual DI

这里特别强调：

- Compose 是原生 UI，不是 WebView，不是前端页面容器。
- 这仍然满足“Android 宿主壳必须原生”的约束。

### 6.2 初始包级结构

建议起步结构如下：

```text
android-app/
└── app/
    └── src/main/java/<pkg>/
        ├── VcpNativeApplication.kt
        ├── MainActivity.kt
        ├── app/
        │   ├── AppContainer.kt
        │   ├── VcpNativeApp.kt
        │   └── navigation/
        ├── model/
        ├── data/
        │   ├── room/
        │   ├── datastore/
        │   ├── files/
        │   └── repository/
        ├── network/
        │   ├── vcp/
        │   └── sse/
        ├── compat/
        │   └── appdata/
        ├── chat/
        │   ├── compiler/
        │   ├── stream/
        │   └── session/
        └── feature/
            ├── bootstrap/
            ├── settings/
            ├── agents/
            ├── topics/
            └── chat/
```

这套结构的重点是：

- 先在单模块里把真实边界放清楚
- 但不先把它们拆成一堆 Gradle module

### 6.3 为什么先单 `:app`

这里不是反对 modularization，而是反对过早 modularization。

官方 Android modularization 指南明确提到：

- 过多模块会带来 build complexity 和 boilerplate
- 小项目或早期阶段，把 data layer 放在 single module 里是可以接受的

结合当前项目约束，先单 `:app` 更合理，因为：

- 当前主要复杂度在迁移 `VCPChat` 逻辑，不在拆 module
- 先单模块更方便直接抄代码、改依赖、跑通主链路
- 等单聊稳定后，再按真实热点拆 module，成本更低

---

## 7. 对 `VCPChat` 的复制策略

在这个骨架里，复制策略也一起固定下来：

### 7.1 直接抄并适配

- 请求编译逻辑
- 消息模型转换逻辑
- SSE 事件解析逻辑
- 中断请求构造逻辑
- 历史合并与保存策略
- Topic / Agent / Message 的语义字段

### 7.2 按行为重建

- DOM 消息渲染
- Electron preload API
- 桌面窗口和通知表现
- 输入区桌面交互细节

这些不是“不抄”，而是：

- 逻辑照抄
- 宿主表现改成 Compose / Android 版本

### 7.3 不进入 Android 主线

- Desktop Push
- Tray / BrowserWindow
- 全局键盘监听
- 桌面悬浮窗
- 其他明确桌面专属宿主行为

---

## 8. 对现有文档体系的影响

这次选型落地后，下面几件事正式固定：

- Android 首个工程不是 WebView 壳
- Android UI 默认不是去桥接桌面 DOM，而是原生 Compose 重建
- 复制重点从“桌面前端页面”修正为“桌面业务逻辑和兼容逻辑”
- 文档冻结已经达到 Android 工程开工门槛

---

## 9. 最终建议

如果现在立刻开工，推荐执行顺序是：

1. 建立单 `:app` 的原生 Compose 工程
2. 先落 `Application / MainActivity / navigation / AppContainer`
3. 接入 `DataStore / Room / files`
4. 接入 `network/vcp` 和 `chat/stream`
5. 先做 `settings / agents / topics / chat` 四个 P1 页面
6. 再逐步把 `VCPChat` 的请求编译与流状态机代码抄进来

---

## 10. 参考来源

- `Jetchat` 仓库：https://github.com/android/compose-samples/tree/main/Jetchat
- `Jetchat README`：https://raw.githubusercontent.com/android/compose-samples/main/Jetchat/README.md
- `Now in Android` 仓库：https://github.com/android/nowinandroid
- `Now in Android README`：https://raw.githubusercontent.com/android/nowinandroid/main/README.md
- `skydoves/chatgpt-android` 仓库：https://github.com/skydoves/chatgpt-android
- `skydoves/chatgpt-android README`：https://raw.githubusercontent.com/skydoves/chatgpt-android/main/README.md
- `flyun/chatAir` 仓库：https://github.com/flyun/chatAir
- `flyun/chatAir README`：https://raw.githubusercontent.com/flyun/chatAir/chatair/README.md
- Android 官方 modularization 指南：https://developer.android.com/topic/modularization
