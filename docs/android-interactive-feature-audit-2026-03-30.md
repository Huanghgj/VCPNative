# Android 交互功能代码审查记录

日期：2026-03-30  
范围：`android-app/app/src/main/java`、`android-app/app/src/main/assets/vcpchat`、`android-app/app/src/test/java`  
重点：拍照、附件、语音输入、语音聊天、图片查看/保存、分享入口，以及模块桥接层的小功能实现。

## 结论

本仓库的“原生聊天主链路”和“Web 模块链路”成熟度差异很大。

- 原生聊天里的附件导入、拍照附图、附件预览、图片查看/保存，主链路基本是通的。
- 聊天输入框里的语音输入有实现，但缺少 Android 运行时权限和失败反馈，真实设备上很容易直接失效。
- `voicechat` 模块在 Android 侧基本处于不可用状态：UI 已暴露，但 IPC、事件回推和数据契约都没有对齐。
- 若把“分享进应用”也算一个小功能，目前 Manifest 已声明入口，但应用内没有消费逻辑，属于名义支持、实际未接线。
- 单元测试编译链再次断开，导致这类交互改动缺少自动化回归保护。

## 已读通的主要链路

### 1. 附件/拍照链路

`ChatRoute.kt` 中：

- 文件选择：`OpenMultipleDocuments()` -> `ChatViewModel.importAttachments()`
- 拍照：`TakePicture()` -> `ChatViewModel.importAttachments()`

`ChatAttachmentManager.kt` 中：

- 把外部 `Uri` 复制到应用私有目录
- 用 `SHA-256` 做去重
- 文本文件抽取文本
- PDF 生成页图预览

`WorkspaceRepository.kt` 中：

- 发送时把附件实体和消息一起落库
- `AttachmentViewerScreen.kt` 负责图片/PDF/文本预览

### 2. 聊天输入框语音输入链路

`ChatRoute.kt` 的 `ChatComposerBar()` 中直接创建 `SpeechRecognizer`：

- 点击麦克风
- 构造 `RecognizerIntent`
- 注册 `RecognitionListener`
- 最终把识别结果拼回草稿框

### 3. 语音聊天模块链路

`voicechat/voicechat.js` 依赖：

- `window.electronAPI.startSpeechRecognition()`
- `window.electronAPI.stopSpeechRecognition()`
- `window.electronAPI.sendToVCP()`
- `window.electronAPI.onVCPStreamEvent()`
- `window.electronAPI.sovitsSpeak()`
- `window.electronAPI.onPlayTtsAudio()`
- `window.electronAPI.onSpeechRecognitionResult()`

Android 侧对应入口在：

- `bridge-shim.js`
- `VcpModuleHost.kt`
- `IpcHandlers.kt`

这条链路是本次审查里问题最多的部分。

## 发现列表

### 1. `voicechat` 模块在 Android 上基本不可用

关键文件：

- `android-app/app/src/main/assets/vcpchat/modules/voicechat/voicechat.js`
- `android-app/app/src/main/assets/vcpchat/bridge-shim.js`
- `android-app/app/src/main/java/com/vcpnative/app/bridge/IpcHandlers.kt`
- `android-app/app/src/main/java/com/vcpnative/app/bridge/VcpModuleHost.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/tools/ToolsRoute.kt`

问题分三层：

1. Android 侧把关键通道直接做成了 stub。
   - `IpcHandlers.kt` 里把 `open-voice-chat-window`、`start-speech-recognition`、`stop-speech-recognition`、`send-to-vcp`、`sovits-speak`、`sovits-stop` 放进了 `stubChannels`。
   - 这意味着 `voicechat.js` 调到这些 API 时，大多只会得到 `null` 或 no-op。

2. 模块依赖的事件从未被 Android 侧回推。
   - `voicechat.js` 监听 `voice-chat-data`、`speech-recognition-result`、`vcp-stream-event`、`play-tts-audio`、`stop-tts-audio`。
   - `VcpModuleHost.kt` 提供了 `emitToWebView()`，但仓库里没有任何地方真正用它去发这些事件。
   - 实际效果是模块即便打开，也收不到初始化数据、识别结果、流式回复和音频播放事件。

3. JS 与 Kotlin 的返回结构和字段名已经分叉。
   - `voicechat.js` 保存历史时期待 `createNewTopicForAgent()` 返回 `{ success, topicId }`，但 `IpcHandlers.kt` 的 `create-new-topic-for-agent` 只返回 `{ id }`。
   - `voicechat.js` 读取 `agentConfig.avatarUrl`、`top_p`、`top_k`、`ttsVoicePrimary` 等字段，但 `get-agent-config` 返回的是 `avatarPath`、`topP`、`topK`，TTS 配置还被塞进了 `extra` JSON，没有拍平成顶层。

影响：

- Tools/Home 中已经把 Voice 模块暴露给用户，但它在 Android 上无法稳定初始化、无法发请求、无法语音识别、无法播报 TTS、无法正常保存历史。

### 2. 聊天输入框语音输入缺少运行时权限请求，也没有把失败原因反馈给用户

关键文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/AndroidManifest.xml`

现状：

- Manifest 声明了 `RECORD_AUDIO`。
- `ChatComposerBar()` 里直接调用 `SpeechRecognizer.createSpeechRecognizer(context)` 和 `startListening()`。
- 仓库里没有任何 `RequestPermission` / `RequestMultiplePermissions` 的录音权限申请逻辑。
- `onError()` 只把 `isListening` 设回 `false`，没有 toast、没有错误码映射、没有降级提示。
- 也没有先做 `SpeechRecognizer.isRecognitionAvailable(context)` 检查。

影响：

- 在首次安装或系统收回麦克风权限后，这个按钮很容易直接失败。
- 用户只会看到按钮从“正在听”恢复成普通状态，不知道是没权限、设备不支持，还是识别服务异常。

说明：

- 这里的问题主要不在 `CAMERA` 类似的静态声明，而在 Android 6+ 的 `RECORD_AUDIO` 运行时授权缺失。

### 3. 分享进应用的入口已经声明，但应用内部没有消费

关键文件：

- `android-app/app/src/main/AndroidManifest.xml`
- `android-app/app/src/main/java/com/vcpnative/app/MainActivity.kt`

现状：

- Manifest 已声明 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`，支持 `text/plain` 和 `image/*`。
- `MainActivity.handleShareIntent()` 会把分享文本和图片写到 `SharedIntentData`。
- 但仓库里没有任何地方调用 `SharedIntentData.consume()`。

影响：

- 从系统分享菜单把文本或图片发给本应用后，数据只会被暂存到内存对象，随后无人读取。
- 对用户来说，这个入口看起来是支持的，但实际不会进入聊天草稿、不会生成待发送附件。

### 4. 拍照主链路能走通，但相机临时文件没有清理，也没有做能力判断

关键文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/res/xml/file_paths.xml`
- `android-app/app/src/main/java/com/vcpnative/app/data/attachment/ChatAttachmentManager.kt`
- `android-app/app/src/main/AndroidManifest.xml`

现状：

- 拍照使用的是 `ActivityResultContracts.TakePicture()`，属于委托系统相机，主链路本身是合理的。
- `cameraPhotoUri` 指向 `cacheDir` 下的 `camera_*.jpg`。
- 成功后 `ChatAttachmentManager` 会把照片复制进私有附件目录，但不会删除原始 cache 文件。
- 代码里也没有在取消拍照、重复拍照、发送完成后做清理。
- UI 总是显示拍照按钮，没有根据设备是否有相机/相机处理器做禁用或隐藏。

影响：

- 长时间使用后，`cacheDir` 里会积累无主相机临时文件。
- 在没有相机硬件或没有可处理 `TakePicture` 的环境里，入口仍然可点，边界体验较差。

说明：

- 这一条不是“完全不能用”，而是已实现但收尾不完整。

### 5. 已上架的多个 Web 模块仍依赖大量 Android stub 通道，小功能完成度参差不齐

关键文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/tools/ToolsRoute.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/modules/ModuleRoutes.kt`
- `android-app/app/src/main/java/com/vcpnative/app/bridge/IpcHandlers.kt`

现状：

- Tools 页面把 `notes`、`memo`、`forum`、`canvas`、`translator`、`dice`、`themes`、`voicechat`、`ragobserver` 全部作为可进入模块暴露。
- 但 `IpcHandlers.kt` 仍把大量与这些模块直接相关的能力保留为 stub。
- 例如：
  - `themes` 依赖的 `get-themes`、`apply-theme`、`get-wallpaper-thumbnail` 仍是 stub。
  - `canvas` 依赖的 `create-new-canvas`、`load-canvas-file`、`save-canvas-file`、`rename-canvas-file`、`copy-canvas-file`、`delete-canvas-file`、`get-latest-canvas-content`、`watcher:start`、`watcher:stop` 仍是 stub。
  - `voicechat` 相关通道前面已经单列。

影响：

- 当前 Android 端更像“模块壳”和“桥接骨架”已经铺好，但很多小功能还没真正落地。
- 如果继续把这些模块统一暴露给最终用户，会持续产生“能点开、但不能完整使用”的预期落差。

### 6. 自动化回归链再次断开，交互功能改动缺少测试兜底

关键文件：

- `android-app/app/src/main/java/com/vcpnative/app/data/datastore/SettingsRepository.kt`
- `android-app/app/src/test/java/com/vcpnative/app/network/vcp/VcpModelCatalogTest.kt`

现状：

- `SettingsRepository` 新增了 `applyCompatSettings(settings: AppSettings)`。
- `VcpModelCatalogTest.kt` 里的 `FakeSettingsRepository` 没有同步实现。
- 结果是 `./gradlew testDebugUnitTest` 直接在编译阶段失败。

影响：

- 本轮审查关注的这些 UI/桥接/模块能力，本来就跨 Kotlin、WebView、资产脚本多层协作。
- 在这种前提下，测试链断掉会让“小功能回归”更难被及时发现。

## 相对完整、可继续沿用的实现

### 1. 附件导入主链路相对完整

优点：

- 会把外部 `Uri` 复制到应用私有目录，不直接依赖外部长期可读权限。
- 用内容 hash 做去重，避免重复存储。
- 对 PDF 做了页数上限和分辨率上限控制，避免无限制渲染。

### 2. 原生图片查看/保存链路基本可用

相关文件：

- `ImageViewerScreen.kt`

优点：

- 支持缩放、拖拽、双击复位/放大。
- 保存图片走 `MediaStore`，在 Android 10+ 路径上是合理的。

### 3. 聊天气泡朗读有独立原生实现

相关文件：

- `ChatRoute.kt`
- `ChatWebView.kt`

说明：

- 主聊天页至少有两套独立的原生 TTS 接入。
- 这和 `voicechat` 模块的 SoVITS/TTS 通道是分离的，因此“主聊天页可朗读”并不代表“语音聊天模块可用”。

## 验证记录

执行命令：

```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

结果：

- `testDebugUnitTest` 失败
  - 原因：`VcpModelCatalogTest.kt` 中的 `FakeSettingsRepository` 未实现 `applyCompatSettings()`
- `assembleDebug` 成功

## 建议修复顺序

1. 先收敛 Android 对外暴露的模块入口，至少把 `voicechat` 这类明显断链的入口隐藏，或补齐最小可用实现。
2. 给聊天输入框语音输入补上 `RECORD_AUDIO` 运行时权限申请、能力检测和错误提示。
3. 修复 `voicechat` 的 Android IPC 契约。
   - 补齐 `send-to-vcp` / 语音识别 / TTS / 历史保存
   - 对齐 `createNewTopicForAgent()` 返回结构
   - 对齐 `getAgentConfig()` 的字段名与字段层级
   - 真正向 WebView 发 `voice-chat-data`、`vcp-stream-event`、`speech-recognition-result` 等事件
4. 接通 `SharedIntentData.consume()`，把系统分享进来的文本/图片真正灌入聊天草稿和待发送附件。
5. 给拍照链路补临时文件清理，并在无相机能力时收起或禁用拍照入口。
6. 修复测试桩，恢复 `testDebugUnitTest`，再考虑为拍照/录音/桥接契约补回归测试。
