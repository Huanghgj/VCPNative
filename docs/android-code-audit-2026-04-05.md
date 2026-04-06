# Android 代码审查记录

日期：2026-04-05  
范围：`android-app/app/src/main/java`、`android-app/app/src/test/java`、`android-app/app/src/main/assets/vcpchat`  
说明：本次以仓库级代码审查为主，重点覆盖 Android Kotlin 代码、WebView 资产脚本，以及当前工作区里新增的技能链路实现；第三方 `vendor` 资源、二进制文件和文档未逐行审查。

## 结论

当前仓库存在几类需要优先处理的问题：

1. 同步链路存在服务端可控的路径穿越写入风险
2. Notes IPC 文件写删改链路存在目录逃逸风险
3. 浏览器 HTML 渲染链路允许模型输出脚本主动回流发送消息
4. 新增技能链路缺少递归/预算保护
5. `settings.json` 的“智能合并”实现失效
6. 增量同步的删除集合未执行
7. 语音聊天历史保存链路已与 Android IPC 协议脱节

以下按严重程度记录。

## 发现列表

### 1. `SyncEngine` 直接信任远端清单路径，可写出 `AppData` 根目录之外

文件：

- `android-app/app/src/main/java/com/vcpnative/app/data/sync/SyncEngine.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/files/AppFileStore.kt`

问题：

- `downloadFile()` 直接将远端返回的 `path` 拼到 `compatAppDataDir()` 下。
- 写入前没有做 canonical path 校验，也没有阻止 `..`。
- 如果同步服务端返回恶意路径，可把文件写到 `AppData` 目录之外。

风险：

- 恶意或被劫持的同步端可实现任意文件覆盖。
- 该问题属于高风险本地文件系统写入漏洞。

关键位置：

- `SyncEngine.kt:174-183`
- `AppFileStore.kt:25`

### 2. `NotesFileManager` 的写、删、改接口都存在目录逃逸面

文件：

- `android-app/app/src/main/java/com/vcpnative/app/bridge/IpcHandlers.kt`
- `android-app/app/src/main/java/com/vcpnative/app/bridge/NotesFileManager.kt`

问题：

- `writeTxtNote()` 用用户输入的 `title` 直接拼接目标文件名，生成后没有再次校验是否仍在 notes 根目录内。
- `createFolder()` 和 `renameItem()` 对 `folderName` / `newName` 也没有做最终路径校验。
- `deleteItem()` 只用 `absolutePath.startsWith(notesRoot.absolutePath)` 做判断，没有 canonicalize，也没有分隔符保护。
- 上述接口都通过 IPC 暴露给 WebView 模块使用。

风险：

- 可通过 `../`、前缀碰撞等方式访问或删除 notes 根目录外的路径。
- 该问题属于高风险本地文件系统越权访问。

关键位置：

- `IpcHandlers.kt:216-245`
- `NotesFileManager.kt:80-95`
- `NotesFileManager.kt:108-118`
- `NotesFileManager.kt:124-153`
- `NotesFileManager.kt:239-247`

### 3. 浏览器 HTML 渲染链路允许模型输出脚本主动触发发消息行为

文件：

- `android-app/app/src/main/java/com/vcpnative/app/chat/render/ChatMessageRenderer.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`

问题链：

- `buildBrowserHtmlDocument()` 直接把原始 `head` / `body` 包进启用 JavaScript 的 `WebView` 文档。
- 注入的 `window.input()` 会把文本编码到 `vcpnative-action://` URL 中。
- `handleBrowserHtmlNavigation()` 捕获这个 scheme 后，会把内容转成 `[[点击按钮:...]]`。
- 聊天页对 assistant/system 消息把 `onActionMessage` 直接接到 `onSendMessage`。

风险：

- 一段由模型生成的 `<script>` 可以在渲染时自动继续发消息。
- 这会导致自触发对话、提示注入、消息风暴，且行为发生在渲染层而非显式用户操作。

关键位置：

- `ChatMessageRenderer.kt:4592-4832`
- `ChatMessageRenderer.kt:4842-4887`
- `ChatRoute.kt:1703-1705`

### 4. 新增技能链路会递归重提请求，但没有深度、预算或幂等保护

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/java/com/vcpnative/app/chat/skill/SkillInvocationDetector.kt`

问题：

- `USE_SKILL` 和 `SKILL_EXEC` 都通过模型输出特殊标记来触发 follow-up 请求。
- `SKILL_EXEC` 在 `isSkillFollowUp = true` 的后续轮次里仍会继续执行。
- 每次 follow-up 都在原消息链后追加两条新消息，再递归 `submitPreparedRequest()`。
- 当前实现没有最大递归次数、最大技能执行次数、同一标记去重或 token/长度预算限制。

风险：

- 模型只要持续输出标记，就会无限自调用。
- 结果会表现为上下文膨胀、重复请求、额度消耗、UI 卡死或服务端超时。

关键位置：

- `ChatRoute.kt:658-724`
- `SkillInvocationDetector.kt:16-43`

### 5. `settings.json` 的“智能合并”已经失效，实际读取的是同一份覆盖后文件

文件：

- `android-app/app/src/main/java/com/vcpnative/app/data/sync/SyncEngine.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/files/AppFileStore.kt`

问题：

- `downloadFile()` 会先把远端 `settings.json` 写到 `compatSettingsFile()`。
- 随后 `mergeSettings()` 读取的 `localFile` 和 `remoteFile` 实际是同一个路径。
- 所谓“保留 mobile-only fields”的前提是要同时拿到覆盖前本地文件和远端文件；当前实现拿不到旧本地版本。

风险：

- 注释承诺的“智能合并”并未真正发生。
- 移动端私有字段会被远端版本静默覆盖。

关键位置：

- `SyncEngine.kt:174-183`
- `SyncEngine.kt:190-200`
- `AppFileStore.kt:31`

### 6. `computeDelta()` 计算了 `toDelete`，但执行阶段根本没有处理

文件：`android-app/app/src/main/java/com/vcpnative/app/data/sync/SyncEngine.kt`

问题：

- `computeDelta()` 会收集远端已不存在、但本地仍存在的文件到 `toDelete`。
- `executeSync()` 只循环处理 `toDownload`，完全忽略 `toDelete`。

风险：

- 远端已删除的配置、历史或附件会永久残留在本地。
- 长期会造成同步漂移、脏数据积累和调试困难。

关键位置：

- `SyncEngine.kt:120-138`
- `SyncEngine.kt:145-170`

### 7. 语音聊天退出时的“保存历史”链路已经断开

文件：

- `android-app/app/src/main/assets/vcpchat/modules/voicechat/voicechat.js`
- `android-app/app/src/main/java/com/vcpnative/app/bridge/IpcHandlers.kt`

问题：

- 前端期望 `createNewTopicForAgent()` 返回 `{ success, topicId }`。
- Android 侧当前只返回 `{ id }`。
- 即使协议改对，`save-chat-history` 在 Android 侧仍是 stub，不会真正写入历史。

风险：

- 用户关闭语音聊天窗口后，历史不会保存到新话题中。
- 前端日志会显示保存流程，但功能实际不可用。

关键位置：

- `voicechat.js:91-105`
- `IpcHandlers.kt:146-150`
- `IpcHandlers.kt:185-189`

## 验证记录

执行命令：

```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew lintDebug
```

结果：

- `./gradlew testDebugUnitTest` 通过。
- `./gradlew lintDebug` 长时间无报告输出，未在合理时间内完成，已停止。

## 建议修复顺序

1. 先修复 `SyncEngine` 路径校验和 `NotesFileManager` 目录逃逸问题。
2. 收紧浏览器 HTML 渲染链路，禁止模型脚本直接回流触发发送。
3. 给技能 follow-up 增加递归深度、执行次数和上下文预算保护。
4. 修复 `settings.json` 的真实双版本合并逻辑。
5. 在 `executeSync()` 中落实 `toDelete` 删除阶段。
6. 对齐语音聊天模块与 Android IPC 协议，并补上真实历史写入实现。

## 当前状态

本文件仅记录 2026-04-05 的审查结果。  
当前未对上述问题做修复提交。
