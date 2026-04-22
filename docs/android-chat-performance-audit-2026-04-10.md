# Android 聊天页面性能审查记录

日期：2026-04-10  
范围：`android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`、`android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`、`android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatWebView.kt`、`android-app/app/src/main/java/com/vcpnative/app/data/repository/WorkspaceRepository.kt`、`android-app/app/src/main/java/com/vcpnative/app/data/room/AppDatabase.kt`、`android-app/app/src/main/assets/vcpchat/chat.html`  
说明：本次只审查聊天页面的性能敏感路径，重点看流式渲染、消息与附件匹配、WebView 桥接、Room 更新、compat 历史落盘，以及动画/HTML 预览对移动端资源的真实压力。

## 结论

当前聊天页的主要性能风险，不是单个按钮动画，也不是单个 SQL 查询慢，而是三条链路叠在一起：

1. 一次消息状态变化会同时触发 Room、topic `updatedAt`、WebView 同步、compat 历史导出
2. UI 只显示最近 `80` 条消息，但附件观察和 compat 导出仍然按整话题全量处理
3. WebView 流式渲染虽然做了增量优化，但对“普通长文本”和“完整 HTML”这两类高频场景仍然会退化成重路径

另外，动画本身不是当前第一瓶颈。页面已经把环境粒子和触摸尾迹默认关闭，离屏消息也会暂停动画；真正更重的是消息完成时的整段重渲染、HTML iframe 自动运行、以及 compat 历史全量重写。

以下按严重程度记录。

## 发现列表

### 1. 每次消息写入都会触发整话题 compat 历史重建和整文件重写，写放大非常明显

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/repository/WorkspaceRepository.kt`

问题链：

- 一次正常发送至少会经历“用户消息入库”“assistant draft 入库”“assistant 完成更新”三步。
- `RoomWorkspaceRepository.addMessage()` 和 `deleteMessage()` / `deleteMessagesFrom()` 默认都会调用 `syncCompatHistory(topicId)`。
- `syncCompatHistory(topic)` 不是增量 append，而是重新：
  - 读取整话题所有消息
  - 读取整话题所有附件
  - 读取现有 history JSON
  - 重建整份 `JSONArray`
  - 再把整文件写回磁盘

影响：

- 对长话题来说，这条路径是明显的 `O(total_messages + total_attachments)`。
- 空的 assistant draft 也会触发一次全量导出，纯属额外成本。
- 这个问题会直接放大 IO、JSON 序列化、GC、耗电和闪存写入次数。

关键位置：

- `ChatViewModel.kt:541-561`
- `ChatViewModel.kt:666-669`
- `WorkspaceRepository.kt:215-247`
- `WorkspaceRepository.kt:250-270`
- `WorkspaceRepository.kt:273-289`
- `WorkspaceRepository.kt:389-423`
- `WorkspaceRepository.kt:534-598`

建议：

1. 把 compat 历史导出从“每次消息写操作内联触发”改成后台合并任务。
2. draft / streaming 中间态不要导出整话题。
3. compat 层如果必须保留，优先做 topic 级延迟批处理，或者改成增量 patch/append，而不是整文件重写。

### 2. 流式 checkpoint 虽然没每次写 compat 文件，但仍然每 2 秒触发 topic `updatedAt` 变更，扩大了全局 UI 抖动面

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/repository/WorkspaceRepository.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/room/AppDatabase.kt`

问题链：

- 流式输出时，`ChatViewModel` 每 `2s` 做一次 assistant checkpoint 持久化。
- 这次 checkpoint 虽然把 `syncCompatHistory` 关掉了，但 `WorkspaceRepository.updateMessage()` 仍然会 `topicDao.touch(topicId, timestamp)`。
- `TopicDao.observeByAgent()` 和 `observeLatestTopicPerAgent()` 都按 `updatedAt` 驱动排序和发射。

影响：

- 生成中的一条消息，会每 `2s` 改一次 topic 排序依据。
- 这会让话题列表、首页最近聊天等依赖 topic `updatedAt` 的 Flow 发生额外重组和重排。
- 即使用户当前只在聊天页，也会把无关页面的数据流一起搅动。

关键位置：

- `ChatViewModel.kt:579-591`
- `ChatViewModel.kt:618-627`
- `WorkspaceRepository.kt:250-270`
- `AppDatabase.kt:187-231`
- `AppDatabase.kt:257-258`

建议：

1. streaming checkpoint 只更新消息行，不要每次 touch topic。
2. 只有真正完成、失败、中断、显式编辑、删除后再更新 topic `updatedAt`。
3. 如果必须保留“生成中也算活跃”的语义，单独加一个 runtime 字段，不要污染列表排序主键。

### 3. 消息列表只取最近 `80` 条，但附件流却是整话题全量观察，数据规模不匹配

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatRoute.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/repository/WorkspaceRepository.kt`
- `android-app/app/src/main/java/com/vcpnative/app/data/room/AppDatabase.kt`

问题：

- 聊天 UI 的消息流已经限制成最近 `80` 条。
- 但 `messageAttachments` 仍然通过 `observeMessageAttachments(topicId)` 观察整话题所有附件。
- Compose 层又会在每次附件变化时 `groupBy { it.messageId }`，再把结果作为 `ChatWebView` 同步输入。

影响：

- 长话题里历史附件越多，这页每次附件变化时需要处理的数据越多。
- 当前页面实际只显示 `80` 条消息，但附件链路会按整话题体量增长。
- 这会放大 SQL join 结果、内存分配、`groupBy`、WebView 同步判断，以及后续分支创建等依赖 `messageAttachments.value` 的路径。

关键位置：

- `ChatViewModel.kt:49-75`
- `ChatRoute.kt:159-160`
- `WorkspaceRepository.kt:159-163`
- `AppDatabase.kt:286-294`
- `AppDatabase.kt:363-385`

建议：

1. 把附件观察范围收缩到“最近消息 ID 集合”。
2. 或者把附件摘要/计数直接并进消息查询，详情再按需查。
3. 不要让附件链路按整话题增长，而消息链路按最近窗口增长。

### 4. WebView 的流式“Stable/Tail”优化对代码块有效，但对普通长文本会退化成全文 Markdown + morphdom

文件：

- `android-app/app/src/main/assets/vcpchat/chat.html`
- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatViewModel.kt`

问题：

- `findExplicitStablePrefix()` 只识别三类显式闭合结构：
  - 代码围栏
  - 工具请求块
  - 工具结果块
- 对普通段落、列表、标题、长 prose，没有稳定边界推进逻辑。
- 这意味着大量常见回复里，`stableCutoff` 会长期停在 `0`。

影响：

- 我推断在“普通长回答”场景下，`tailText` 会接近整段全文。
- 即使 Kotlin 侧只按 `300ms` 推一次 delta，JS 侧仍会在每次刷新时对越来越长的 `tailText` 做 `md()` 和 `morphdom(...)`。
- 这会让所谓“增量流式”在高频真实场景里重新接近 `O(full_text)`。

关键位置：

- `ChatViewModel.kt:620-627`
- `chat.html:1598-1649`
- `chat.html:1758-1819`

建议：

1. 给普通文本也增加稳定边界策略，比如按段落、双换行、列表项、标题行推进。
2. 把当前逻辑从“只认显式 fenced block”扩展成“显式结构 + 文本块边界”双策略。
3. 在长 prose 场景下优先保证 `tail` 尺寸上限，不要让它无限接近全文。

### 5. 完整 HTML 预览默认自动运行，历史加载和消息完成时会主动创建 iframe / script 运行时

文件：

- `android-app/app/src/main/assets/vcpchat/chat.html`

问题链：

- `PERF_FLAGS.autoRunFullHtml` 默认是 `true`。
- `processHtmlPreviews()` 在消息加载、更新、历史恢复后都会扫描 HTML 片段。
- 一旦判定为完整 HTML，就会立刻创建 iframe，并把 `srcdoc` 直接塞进去运行。

影响：

- 如果一屏或一个话题里有多条 HTML 消息，会在聊天页里并行挂多个 iframe 文档和 JS 运行时。
- 历史恢复不是“先显示代码、按需运行”，而是“进入页面就自动跑”。
- 对移动端 WebView 来说，这比普通 CSS 动画更容易成为 CPU、内存和滚动卡顿来源。

关键位置：

- `chat.html:943-947`
- `chat.html:1986-2032`
- `chat.html:2074-2088`

建议：

1. 默认把 `autoRunFullHtml` 改成 `false`。
2. 完整 HTML 统一改成手动点击“运行”。
3. 离屏 iframe 要做暂停或卸载，不能长期常驻在消息流里。

### 6. 消息完成阶段仍会对整条气泡执行一次完整重渲染和全量后处理，结束瞬间存在明显尖峰

文件：

- `android-app/app/src/main/java/com/vcpnative/app/feature/chat/ChatWebView.kt`
- `android-app/app/src/main/assets/vcpchat/chat.html`

问题链：

- 流式期间 Kotlin 侧走 `appendStreamDelta()`，这是轻路径。
- 但一旦进入 `complete / interrupted / error`，就会调用 `vcpChat.updateMessage(...)`。
- JS 侧会重新执行：
  - `renderContent(...)`
  - `postProcessContent(...)`
  - 包括 KaTeX / highlight.js / Mermaid 检测
  - 然后再走 `processButtons(...)` 和 `processHtmlPreviews(...)`

影响：

- 长代码、数学公式、Mermaid、HTML 混合消息，在“输出结束”这一刻会形成主线程尖峰。
- 这不是流式过程均匀变慢，而是结束时容易突然掉帧。
- 用户主观感受往往是“快结束时卡一下”。

关键位置：

- `ChatWebView.kt:500-507`
- `chat.html:1349-1390`
- `chat.html:1823-1848`
- `chat.html:2074-2088`

建议：

1. 把结束态后处理拆成轻重两级，先显示最终文本，再延迟做 Mermaid / iframe / 高亮增强。
2. 对超长消息增加预算控制，避免一次把所有增强都塞进同一帧。
3. 对已经在流式阶段确认稳定的块，结束时尽量复用，不要整条重做。

## 关于动画的补充判断

本轮代码里，动画并不是最大的直接瓶颈，原因有三点：

1. 环境粒子和触摸尾迹默认是关闭的：`chat.html:943-946`
2. 离屏消息会加 `.vcp-paused`，动画和媒体会被冻结：`chat.html:949-999`
3. 入场强化动画已经改成点击触发，而不是每条消息自动播放：`chat.html:2708-2728`、`chat.html:2887-2910`

但需要注意：

- 新消息追加时仍然会预加载 `anime.min.js`：`chat.html:1744-1745`
- 这不是当前主瓶颈，但说明动画系统还没有完全做到“只在真正使用时加载”

## 建议修复顺序

1. 先砍写放大：把 compat 历史导出改成后台合并任务，停止“每次消息写操作都整话题重写”。
2. 再切断全局连锁：streaming checkpoint 不要再每 `2s` touch topic。
3. 收紧数据范围：附件观察与消息窗口保持同一规模，不要消息 `80` 条、附件全话题。
4. 修流式普通文本退化：让 Stable/Tail 逻辑能识别段落边界，不要只优化 fenced block。
5. 默认关闭 HTML 自动运行，把 iframe 预览改成手动触发。
6. 最后再处理动画系统的细枝末节，比如 `anime.js` 加载时机和结束态增强拆帧。

## 当前状态

本文件只记录 2026-04-10 这轮聊天页面性能审查结果。  
当前未对上述问题做修复提交。
