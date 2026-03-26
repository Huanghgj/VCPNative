# Android 流状态机与消息落盘

更新时间：2026-03-25

本文定义 Android 端如何承接当前 `VCPChat` 的流式消息生命周期。  
目标是把：

- 流事件
- 消息状态
- UI 更新
- 本地落盘
- 中断与错误收尾

统一成一套可执行的 Android 方案。

---

## 1. 真相来源

当前流式行为以这些文件为第一真相来源：

- `/root/VCPChat/modules/vcpClient.js`
- `/root/VCPChat/renderer.js`
- `/root/VCPChat/modules/renderer/streamManager.js`
- `/root/VCPChat/modules/messageRenderer.js`

---

## 2. 为什么必须单独建状态机

当前桌面端并不是“收到 chunk 就直接往页面上拼字”这么简单。

它实际还做了这些事：

- 流开始前先创建 assistant 草稿消息
- 流开始前后都可能收到事件
- chunk 可能先到，消息初始化后到
- 当前页面不在前台时也要更新历史
- 流结束时要重新渲染完整内容
- 错误、中断、非流式都要落到同一消息模型

因此 Android 端如果没有显式状态机，后面一定会出现：

- UI 有内容，历史没落盘
- 流切页后丢状态
- 中断后消息残缺
- 非流式和流式两套模型并存

---

## 3. 事件输入模型

Android 内部建议统一接收这些事件语义：

```kotlin
sealed interface StreamEvent {
    data class AgentThinking(val messageId: String, val context: ChatContext) : StreamEvent
    data class Start(val messageId: String, val context: ChatContext) : StreamEvent
    data class Data(val messageId: String, val chunk: Any, val context: ChatContext) : StreamEvent
    data class End(val messageId: String, val finishReason: String?, val context: ChatContext) : StreamEvent
    data class Error(val messageId: String, val error: String, val context: ChatContext) : StreamEvent
    data class FullResponse(val messageId: String, val fullResponse: String, val context: ChatContext) : StreamEvent
    data class NoAiResponse(val messageId: String, val context: ChatContext) : StreamEvent
    data class RemoveMessage(val messageId: String, val context: ChatContext) : StreamEvent
}
```

虽然 P1 主做单聊，但内部事件模型最好直接兼容桌面端全套语义。

---

## 4. Android 内部需要的两层状态

建议不要只做一个状态枚举，而是拆成两层：

### 4.1 请求状态

表示一次网络会话：

- `CREATED`
- `SENT`
- `STREAMING`
- `COMPLETED`
- `INTERRUPTING`
- `INTERRUPTED`
- `FAILED`

### 4.2 消息状态

表示 assistant 消息本身：

- `DRAFT`
- `STREAMING`
- `COMPLETED`
- `INTERRUPTED`
- `FAILED`

原因：

- 一次请求失败，不代表消息一定没有部分内容
- 一条消息被中断，也不代表请求侧没有正常发起过

---

## 5. 单聊主状态机

Android 单聊主链路建议固定为下面这条状态图：

```text
Idle
-> DraftCreated
-> WaitingStream
-> Streaming
-> Finalizing
-> Completed
```

异常分支：

```text
WaitingStream -> Failed
Streaming -> Interrupted
Streaming -> Failed
DraftCreated -> Interrupted
```

---

## 6. 标准时序

### 6.1 发送时

1. 用户点击发送
2. 创建 `requestId`
3. 本地插入 assistant 草稿消息
4. 将草稿消息落盘
5. 请求发出
6. 进入 `WaitingStream`

### 6.2 收到流开始

当前桌面端的“流开始”来源有两种：

- 显式 `start`
- 第一条 `data`

Android 端结论：

- 不能把 `start` 当成唯一开始信号
- 只要收到了可用 chunk，也要能进入 `Streaming`

### 6.3 收到增量 chunk

1. 从 chunk 中提取文本增量
2. 追加到 `accumulatedText`
3. 更新当前消息状态为 `STREAMING`
4. 当前会话可见时刷新 UI
5. 不可见时仍更新内存/仓储状态

### 6.4 收到结束

1. 将 `accumulatedText` 作为优先最终文本
2. 标记 `finishReason`
3. 消息状态改为 `COMPLETED`
4. 重渲染最终内容
5. 落盘

---

## 7. 草稿消息策略

当前桌面端的关键做法不是持久化 “thinking 气泡”，而是：

- 在历史里先插入同 ID 的 assistant 草稿记录
- 流结束后再把它补成最终消息

Android 端建议保留这条策略。

### 7.1 为什么要先落草稿

原因有三条：

1. `requestId` 与消息 ID 统一
2. 流式消息有稳定锚点可更新
3. App 被切后台或页面重建时，能重新找到这条消息

### 7.2 草稿消息最低字段

```json
{
  "id": "msg_xxx",
  "role": "assistant",
  "content": "",
  "timestamp": 1742920000000,
  "finishReason": null,
  "interrupted": false
}
```

注意：

- UI 可以显示“思考中”
- 但持久化主记录不应依赖“思考中...”文本作为真相

---

## 8. 预缓冲策略

当前桌面端流管理器支持“chunk 先到、消息初始化后到”。

Android 端也应该支持预缓冲：

1. 如果消息还没完成初始化，先把 chunk 放到 buffer
2. 消息进入 `READY/STREAMING` 后再回放这些 chunk

否则会出现：

- 第一段 token 丢失
- 页面切换或初始化慢时首段内容消失

---

## 9. 增量文本提取规则

当前桌面端会从这些位置提取文本：

1. `choices[0].delta.content`
2. `delta.content`
3. `content`
4. `message.content`

Android 端结论：

- 解析器不能只写死 OpenAI 一种格式
- 解析失败的 chunk 不能直接导致整个流中止

如果单个 chunk JSON 解析失败：

- 记录错误
- 丢弃该 chunk 的可视输出
- 整个流继续

---

## 10. 可见会话与后台会话

当前桌面端有一个很重要的行为：

- 当前视图可见时更新 DOM
- 当前视图不可见时也更新对应历史

Android 端必须保留同样的分层。

### 10.1 可见会话

负责：

- 增量 UI 更新
- 滚动到底部
- 最终重渲染 Markdown / 图片 / 富内容

### 10.2 不可见会话

负责：

- 更新仓储里的消息内容
- 落盘
- 保持状态一致

不负责：

- 增量界面渲染

---

## 11. 结束收尾规则

### 11.1 `end`

收到 `end` 时：

1. 优先使用累积文本
2. 写入 `finishReason`
3. 清除 streaming 标记
4. 执行最终渲染
5. 保存历史

### 11.2 `full_response`

非流式返回也要走同一消息模型：

1. 直接用完整内容更新草稿消息
2. 状态改成 `COMPLETED`
3. 落盘

不要为非流式再单独维护另一套 assistant message 类型。

### 11.3 `error`

收到错误时：

1. 结束当前流会话
2. 若已有可用 partial text，则保留
3. 若没有可用文本，则消息标记为 `FAILED`
4. 记录错误信息
5. 落盘

---

## 12. 中断策略

Android 单聊中断必须拆成两件事：

### 12.1 本地中断

- 停止继续消费该请求的 SSE
- 请求状态改为 `INTERRUPTING`

### 12.2 远程中断

- 向 `/v1/interrupt` 发 body
- body 使用 `requestId`

### 12.3 中断后的消息收尾

中断后的 assistant 消息规则建议固定为：

- 保留已收到的 partial text
- `interrupted = true`
- `finishReason = "interrupted"`
- 消息状态改为 `INTERRUPTED`

不能因为用户点了中断，就把整条部分回复直接丢掉。

---

## 13. 落盘策略

当前桌面端的落盘节奏可以总结成：

1. 草稿创建后保存一次
2. 流结束后保存一次
3. 使用防抖，避免每个 chunk 都写磁盘

Android 端建议延续这个策略。

### 13.1 必须落盘的时机

- 草稿 assistant 消息创建后
- `end` 收尾后
- `full_response` 完成后
- `error` / `interrupt` 收尾后

### 13.2 不建议的时机

- 每收到一个 chunk 就全量写盘

原因：

- IO 太重
- 移动端前后台频繁切换时更容易抖

### 13.3 推荐实现

- 内存中维护 `activeStreamSession`
- 仓储层维护 `AssistantDraft`
- 按 debounce 或状态切换触发写盘

---

## 14. 启动恢复策略

Android 相比桌面端，必须更认真处理“App 被系统杀掉”的情况。

建议固定一条恢复规则：

如果启动时发现存在：

- assistant 草稿消息
- `finishReason == null`
- 请求已不再活跃

则把该消息标记为：

- `interrupted = true`
- `finishReason = "recovered_after_process_death"`
- 状态转为 `INTERRUPTED`

不要让这类悬空草稿一直停留在“正在生成中”。

---

## 15. Android 建议建模

建议至少有这几个对象：

```kotlin
data class ActiveStreamSession(
    val requestId: String,
    val chatContext: ChatContext,
    val state: RequestState,
    val accumulatedText: String,
    val startedAt: Long,
    val updatedAt: Long,
    val errorMessage: String?
)

enum class RequestState {
    CREATED,
    SENT,
    STREAMING,
    COMPLETED,
    INTERRUPTING,
    INTERRUPTED,
    FAILED
}
```

以及：

```kotlin
enum class MessageStreamState {
    DRAFT,
    STREAMING,
    COMPLETED,
    INTERRUPTED,
    FAILED
}
```

不要只靠 UI 上的“正在生成”布尔值来驱动整个消息生命周期。

---

## 16. P1 / P2 边界

### P1 必做

- 单聊草稿消息
- SSE 增量解析
- `data / end / error / full_response`
- 中断后 partial message 保留
- 当前页与后台页统一消息模型
- 应用私有目录历史落盘

### P2 补齐

- 更细的错误分类
- 超长流 checkpoint
- VCPLog 关联
- 更丰富的 finishReason
- 群聊事件模型并轨

---

## 17. 当前结论

Android 端真正要迁的，不是“一个流式文本框”，而是一套消息生命周期控制：

1. 先建草稿
2. 再接事件
3. 增量累积
4. 统一收尾
5. 可靠落盘
6. 中断和异常也进入同一模型

把这套状态机先定死，后面页面怎么换、布局怎么变，都不会把消息一致性打乱。
