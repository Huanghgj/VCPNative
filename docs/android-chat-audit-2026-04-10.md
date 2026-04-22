# Android 聊天链路代码审查记录

日期：2026-04-10  
范围：`android-app/app/src/main/java/com/vcpnative/app/feature/chat`、`android-app/app/src/main/java/com/vcpnative/app/chat`、`android-app/app/src/main/java/com/vcpnative/app/bridge`、`android-app/app/src/main/assets/vcpchat/chat.html`  
说明：本次聚焦当前工作区里聊天相关改动，重点检查请求编译、流式会话、WebView 增量同步、分享/编辑/分支等用户路径。未逐行复查无关模块和第三方 `vendor` 资源。

## 结论

当前聊天链路有几类需要优先处理的问题：

1. 完整 HTML 预览仍可在无显式用户确认的情况下主动回流发送消息
2. 从消息创建分支时会静默截断长会话历史
3. 分享进入聊天页的文本草稿存在丢失路径
4. WebView 附件增量同步条件不足，删除和同数量替换不会刷新
5. 消息编辑走前端乐观更新，但持久化失败不会回滚

以下按严重程度记录。

## 发现列表

### 1. 完整 HTML 预览默认自动执行，且 iframe 可直接向父聊天页发消息

文件：

- `android-app/app/src/main/assets/vcpchat/chat.html`

问题链：

- `PERF_FLAGS.autoRunFullHtml` 当前默认是 `true`。
- 检测到完整 HTML 文档后，预览 iframe 会在消息渲染时立即写入 `srcdoc` 并执行脚本。
- 注入到 iframe 的 `_iframeBridgeScript` 暴露了 `window.input()`，它会把消息通过 `postMessage()` 发回父页面。
- 父页面的 `message` 监听只检查 `type === 'vcp-input'` 和 `text` 是否为字符串，没有校验 `source`、`origin`、容器 ID 或用户手势。
- 收到后直接调用 `window.input()`，最终回流到 `VcpChatBridge.onAction('send', ...)`。

风险：

- 模型输出的 HTML 可以在没有用户点击“运行”的情况下自动继续发消息。
- 这会把 HTML 预览变成 prompt injection、自触发对话和消息风暴入口。
- 因为触发点在渲染层，用户很难区分是“预览行为”还是“真实发送行为”。

关键位置：

- `chat.html:943-947`
- `chat.html:1941-1957`
- `chat.html:2015-2020`

### 2. 分支创建只复制最近 80 条消息，长会话会被静默截断

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`

问题：

- `persistedMessages` 订阅的是 `observeRecentMessages(topicId, limit = 80)`。
- `messages` 只是 `persistedMessages + liveMessage` 的合并视图，不是完整历史。
- `createBranchFromMessage()` 直接基于 `messages.value` 截取 `take(targetIndex + 1)` 来复制分支内容。
- 当原话题超过 80 条消息时，更早的上下文已经不在内存列表中，分支会在没有提示的情况下丢历史。

风险：

- 用户以为“从这条消息分叉”会保留完整上下文，实际只保留最近窗口。
- 分支后的模型行为会和原话题不一致，且问题较难追踪。

关键位置：

- `ChatViewModel.kt:49-67`
- `ChatViewModel.kt:406-459`

### 3. 分享进入聊天页的文本草稿可能在 UI 初始化后被消费掉

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`

问题链：

- 分享文本先通过 `SharedIntentData.consume()` 写入 `viewModel.setSharedDraft()`。
- UI 侧没有订阅 `sharedDraft`，而是在组合参数里直接调用一次 `consumeSharedDraft()`。
- `ChatComposerBar` 里的 `draft` 使用 `rememberSaveable(composerSessionKey)` 初始化，只会在首次创建时读取 `initialDraft`。
- 如果分享文本在输入框初始化之后到达，后续即使发生重组，`draft` 也不会被更新；同时 `consumeSharedDraft()` 还会把值清空。

风险：

- “分享文本到聊天”会出现输入框为空、文本丢失、行为不稳定的问题。
- 这是直接影响主路径的功能性回归。

关键位置：

- `ChatRoute.kt:200-210`
- `ChatRoute.kt:235`
- `ChatRoute.kt:564`
- `ChatViewModel.kt:191-199`

### 4. WebView 附件同步只比较数量，附件删除和同数量替换都不会刷新

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatWebView.kt`

问题：

- `RenderedMessageSnapshot` 只记录 `attachmentCount`，不记录附件内容、路径或 hash。
- 增量同步时，附件相关更新只在 `prevAttachmentCount != currentAttachments.size && currentAttachments.isNotEmpty()` 时触发 `replaceMessage()`。
- 这意味着：
  - 附件从 1 个删到 0 个时不会刷新
  - 1 张图替换成另一张图时不会刷新
  - 同数量附件的名称、顺序、路径变化都不会刷新

风险：

- WebView 会继续显示旧附件，和数据库/Compose 状态分叉。
- 这类错误通常只在编辑、重试、同步恢复后暴露，回归测试很容易漏掉。

关键位置：

- `ChatWebView.kt:552-558`
- `ChatWebView.kt:575-578`
- `ChatWebView.kt:665-679`

### 5. 消息编辑采用前端乐观更新，但后端失败不会提示或回滚

文件：

- `android-app/app/src/main/assets/vcpchat/chat.html`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`

问题链：

- WebView `saveEdit()` 在把编辑内容发给 Kotlin 后，会立刻执行 `vcpChat.updateMessage(msgId, newContent, 'complete')`。
- Compose 侧收到 `"saveEdit"` 后仅启动协程调用 `onEditAssistantMessage(...)`，但没有检查 `Result<Unit>`。
- `ChatViewModel.editAssistantMessage()` 明确可能因为正在发送、消息不存在、角色不允许、内容为空等条件失败。
- 失败后没有 toast、没有回滚、没有重新拉取这条消息。

风险：

- 用户会先看到“编辑成功”的前端结果，但数据库实际可能仍是旧内容。
- 后续刷新页面或重新进入话题时，消息会突然变回去，造成数据一致性问题。

关键位置：

- `chat.html:2127-2138`
- `ChatRoute.kt:487-502`
- `ChatViewModel.kt:355-387`

## 验证记录

执行命令：

```bash
cd android-app
./gradlew :app:testDebugUnitTest --tests 'com.vcpnative.app.chat.render.VcpChatMessageParserTest' --tests 'com.vcpnative.app.network.vcp.VcpNetworkTest'
```

结果：

- 定向单元测试通过。
- 当前没有看到覆盖“分享草稿”“HTML 预览回流”“附件同步”“消息编辑回滚”“长会话分支复制”的自动化测试。

## 建议修复顺序

1. 先收紧 HTML 预览链路，默认关闭自动执行，并校验 iframe 回流消息来源。
2. 修复分支创建逻辑，改为从仓库读取完整历史而不是 80 条 UI 窗口。
3. 把分享草稿改成显式状态订阅或一次性事件消费，避免在组合阶段直接 `consume`。
4. 给 WebView 附件同步增加稳定的附件签名，而不是只比较数量。
5. 给消息编辑补上失败提示和 UI 回滚，或者改成“持久化成功后再更新 WebView”。

## 当前状态

本文件仅记录 2026-04-10 这轮聊天专项审查结果。  
当前未对上述问题做修复提交。
