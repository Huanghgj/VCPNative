# Android 代码审查记录

日期：2026-03-29  
范围：`android-app/app/src/main/java`、`android-app/app/src/test/java`、`android-app/app/src/main/assets/vcpchat`

## 结论

当前仓库存在几类需要优先处理的问题：

1. 资源/线程泄漏
2. 流式聊天链路在长会话下的线性性能退化
3. 日志/审批消息丢失风险
4. WebView 增量同步竞态
5. 兼容层历史写入节流失效
6. 调试日志页刷新失效
7. 单元测试编译链已断开
8. 资源包重复打包

以下按严重程度记录。

## 发现列表

### 1. `BridgeLogger` 存在线程泄漏和重复调度风险

文件：`android-app/app/src/main/java/com/vcpnative/app/bridge/BridgeLogger.kt`

- `log()` 每次进入延迟刷盘分支都会调用 `Executors.newSingleThreadScheduledExecutor()`。
- 该 executor 没有被复用，也没有关闭。
- `flushScheduled` 只是普通 `@Volatile Boolean`，不能保证并发下只调度一次。

风险：

- 高频日志时会不断创建新线程。
- 后台刷盘任务会越来越多。
- 调试/桥接日志越多，额外性能消耗越明显。

关键位置：

- `BridgeLogger.kt:94-102`

### 2. 聊天流式更新链路会随消息数线性退化

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/room/AppDatabase.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatWebView.kt`

问题链：

- `ChatViewModel` 在流式过程中约每 `240ms` 落库一次。
- `Room` 观察的是整话题 `Flow<List<MessageEntity>>`。
- `ChatWebView` 每次收到新的 `messages` 列表后仍会重新扫描整表做 diff。

风险：

- 对话越长，单次增量更新成本越高。
- 长会话下更容易出现掉帧、卡顿、输入延迟。

关键位置：

- `ChatRoute.kt:551-566`
- `ChatRoute.kt:568-675`
- `AppDatabase.kt:252-256`
- `ChatWebView.kt:341-352`
- `ChatWebView.kt:376-411`

### 3. `VcpLogClient` 高流量下会直接丢消息

文件：`android-app/app/src/main/java/com/vcpnative/app/network/vcplog/VcpLogClient.kt`

- `_messages` 仅配置了 `extraBufferCapacity = 256`。
- 发消息时使用 `tryEmit()`。
- 失败后仅写日志，不重试、不排队、不落盘。

风险：

- 高峰期通知会丢。
- `tool_approval_request` 也可能丢，属于功能性错误。

关键位置：

- `VcpLogClient.kt:66`
- `VcpLogClient.kt:297-303`

### 4. `ChatWebView` 的增量同步有竞态

文件：`android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatWebView.kt`

- `LaunchedEffect(messages, webViewReady)` 每次消息变化都会重启。
- 后台线程里会修改 `sentIds` 和 `contentHash`。
- 这两个集合由多个 effect 轮次共享。
- `buildDeltaCommands()` 不含挂起点，被取消的旧任务仍可能继续执行到结束。

风险：

- 旧 diff 结果可能晚于新 diff 执行。
- WebView 中的消息状态可能被过期数据污染。

关键位置：

- `ChatWebView.kt:341-352`
- `ChatWebView.kt:376-411`

### 5. 兼容历史写入节流逻辑与注释不一致，实际上基本未节流

文件：`android-app/app/src/main/java/com/vcpnative/app/data/repository/WorkspaceRepository.kt`

- `syncCompatHistory(topicId, debounce)` 中的条件为：
  `if (now - lastSync < interval && !lastHistorySyncTimeMs.compareAndSet(lastSync, now)) return`
- 这意味着在节流窗口内，只要 `compareAndSet` 成功，就不会返回，仍然继续同步。
- 注释写的是“节流窗口内跳过本次”，实现与描述相反。

风险：

- 流式过程中仍会产生大量 JSON 序列化和文件写入。
- 与“减少兼容层写放大”的目标不一致。

关键位置：

- `WorkspaceRepository.kt:336-354`

### 6. Debug 日志页在缓冲区打满后可能停止刷新

文件：

- `android-app/app/src/main/java/com/vcpnative/app/bridge/BridgeLogger.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/debug/DebugLogRoute.kt`

- UI 用 `entryCount` 驱动重组。
- `entryCount` 只在内存日志条数变化时更新。
- 环形缓冲到达 `MAX_MEMORY_ENTRIES=500` 后，size 不再变化。

风险：

- 新日志虽然进入了环形缓冲，但 UI 不再更新。

关键位置：

- `BridgeLogger.kt:83-92`
- `DebugLogRoute.kt:54-67`

### 7. 单元测试编译链已断

文件：

- `android-app/app/src/main/java/com/vcpnative/app/data/datastore/SettingsRepository.kt`
- `android-app/app/src/test/java/com/vcpnative/app/network/vcp/VcpModelCatalogTest.kt`

现象：

- `SettingsRepository.saveConnection()` 已升级为 4 个参数：
  `serverUrl, apiKey, vcpLogUrl, vcpLogKey`
- 测试桩 `FakeSettingsRepository` 仍实现旧签名。

结果：

- `./gradlew testDebugUnitTest` 在 `:app:compileDebugUnitTestKotlin` 失败。
- 目前测试还没有运行到断言阶段，回归链已不可用。

关键位置：

- `SettingsRepository.kt:27`
- `VcpModelCatalogTest.kt:127-144`

### 8. Web 资产存在明显重复打包

目录：

- `android-app/app/src/main/assets/vcpchat/vendor`
- `android-app/app/src/main/assets/vcpchat/modules/vendor`
- `android-app/app/src/main/assets/vcpchat/styles`
- `android-app/app/src/main/assets/vcpchat/modules/styles`
- `android-app/app/src/main/assets/vcpchat/renderer`
- `android-app/app/src/main/assets/vcpchat/modules/modules/renderer`

抽样结果：

- `domBuilder.js` 相同
- `base.css` 相同
- `highlight.min.js` 相同

体积：

- `vcpchat/vendor`: `5.6M`
- `vcpchat/modules/vendor`: `5.6M`
- `vcpchat/styles`: `332K`
- `vcpchat/modules/styles`: `332K`
- `vcpchat/renderer`: `276K`
- `vcpchat/modules/modules/renderer`: `276K`

风险：

- APK 体积被重复资源放大。
- 首次安装、解压、资源索引和 WebView 资产访问成本都会增加。

## 验证记录

执行命令：

```bash
cd android-app
./gradlew testDebugUnitTest
```

结果：

- 主 app Kotlin 编译阶段通过。
- `:app:compileDebugUnitTestKotlin` 失败。
- 原因：`VcpModelCatalogTest.kt` 中的 `FakeSettingsRepository` 未同步 `SettingsRepository` 新签名。

## 建议修复顺序

1. 修复 `BridgeLogger` 线程泄漏与刷新机制。
2. 修复 `SettingsRepository` 测试桩，恢复单元测试编译链。
3. 修复 `WorkspaceRepository.syncCompatHistory()` 节流逻辑。
4. 修复 `ChatWebView` 增量同步竞态。
5. 收紧 `VcpLogClient` 的背压与消息保留策略。
6. 优化聊天流式链路，避免每次更新都走全量列表。
7. 清理重复 Web 资产，统一引用路径。

## 当前状态

本文件仅记录 2026-03-29 的审查结果。  

## 修复进展（2026-03-29）

本轮已完成以下修复：

1. `BridgeLogger` 改为复用单个刷盘调度器，移除按日志创建线程的行为。
2. Debug 日志页改为使用刷新版本号驱动，缓冲区打满后仍会持续刷新。
3. `VcpLogClient` 增加有序消息队列，消除高压下 `tryEmit()` 直接丢消息的问题。
4. `WorkspaceRepository.syncCompatHistory()` 改为真正节流，并为非流式写入补上延迟同步，避免跳过后永远不落盘。
5. `VcpModelCatalogTest` 的 `FakeSettingsRepository` 已同步到新的 `saveConnection()` 签名，单测编译链恢复。
6. 聊天流式链路改为“内存态高频更新 + Room 低频 checkpoint / 最终落库”，不再每 240ms 触发整条 Room 观察链路。
7. `ChatWebView` 改为“持久化消息低频全量重载 + 当前流式消息单条增量更新”，移除了共享 diff 状态导致的竞态。
8. 模块 Web 资源统一改为复用根目录 `vendor` / `styles` / `renderer`，已删除重复打包目录。

验证结果：

```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

结果：

- 两个命令均执行成功。
- 主 app Kotlin 编译、单元测试编译和 Debug APK 构建均恢复正常。
