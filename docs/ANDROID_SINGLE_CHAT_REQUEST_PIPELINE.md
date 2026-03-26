# Android 单聊请求编译流程

更新时间：2026-03-25

本文定义 Android 单聊主链路里，“用户点发送”到“HTTP 请求真正发出”之间必须经过的编译流程。

这里的重点不是重新设计理想协议，而是：

- 把当前 `VCPChat` 已经在做的客户端编译工作抽出来
- 让 Android 端在不改 `/root/VCPToolBox` 的前提下先兼容
- 明确哪些步骤属于 `P1`，哪些属于 `P2`

---

## 1. 范围

本文当前只覆盖：

- 单聊
- Android 客户端侧请求编译
- 向现有 `VCPToolBox` 发起聊天请求
- 向现有 `/v1/interrupt` 发起单聊中断

本文当前不覆盖：

- 群聊本地编排
- VCPLog
- Notes / Forum / Memo 等高级模块

---

## 2. 真相来源

当前请求编译行为以这些文件为第一真相来源：

- `/root/VCPChat/modules/chatManager.js`
- `/root/VCPChat/modules/ipc/chatHandlers.js`
- `/root/VCPChat/modules/fileManager.js`
- `/root/VCPChat/modules/contextSanitizer.js`
- `/root/VCPChat/modules/contextFolder.js`

如果 Android 设计与这些代码不一致，以代码行为优先。

---

## 3. 编译目标

Android 单聊请求编译器的输出必须同时满足两件事：

1. 生成可直接发送给现有 `VCPToolBox` 的请求体
2. 让请求 ID、附件、多模态、上下文注入、中断关联保持兼容

输出结果至少包含：

```json
{
  "messages": [],
  "model": "xxx",
  "temperature": 0.7,
  "max_tokens": 1000,
  "contextTokenLimit": 4000,
  "top_p": 0.95,
  "top_k": 40,
  "stream": true,
  "requestId": "msg_xxx"
}
```

这里的 `requestId` 必须与当前 assistant 草稿消息使用同一个 ID。

---

## 4. 输入

单聊请求编译至少需要这几类输入：

### 4.1 全局设置

- `vcpServerUrl`
- `vcpApiKey`
- `enableVcpToolInjection`
- `enableAgentBubbleTheme`
- `enableThoughtChainInjection`
- `enableContextSanitizer`
- `contextSanitizerDepth`
- `enableContextFolding`
- `contextFoldingKeepRecentMessages`
- `contextFoldingTriggerMessageCount`
- `contextFoldingTriggerCharCount`
- `contextFoldingExcerptCharLimit`
- `agentMusicControl`

### 4.2 当前 Agent

- `name`
- `systemPrompt`
- `model`
- `temperature`
- `contextTokenLimit`
- `maxOutputTokens`
- `top_p`
- `top_k`
- `streamOutput`
- `topics`
- `stripRegexes`
- `agentDataPath`

### 4.3 当前 Topic

- `topicId`
- `createdAt`

### 4.4 当前历史

- 当前会话完整 `history`
- 当前正在发送的 user message
- 为该次请求创建的 assistant 草稿消息 ID

### 4.5 当前附件

每条消息上的附件至少要能拿到：

- `type`
- `src`
- `name`
- `_fileManagerData.id`
- `_fileManagerData.internalPath`
- `_fileManagerData.hash`
- `_fileManagerData.extractedText`
- `_fileManagerData.imageFrames`

---

## 5. 编译分层

Android 端建议把请求编译拆成五段，而不是一个大函数：

1. `MessageSnapshotBuilder`
   从当前会话取出待发送历史快照

2. `AttachmentExpander`
   把附件补成上下文文本和多模态片段

3. `SystemPromptEnricher`
   负责 system prompt、话题时间、history 路径等注入

4. `ContextOptimizer`
   负责 thought chain、sanitizer、folding、正则规则

5. `RequestBodyBuilder`
   负责模型参数、URL 切换、`requestId`、序列化

不要把 UI Message 直接当 HTTP Request Body。

---

## 6. 单聊编译主流程

Android 单聊发送主流程建议固定如下：

1. 用户点击发送
2. 创建本次请求的 `requestId`
3. 以同一个 `requestId` 创建 assistant 草稿消息
4. 读取当前会话 `history` 快照
5. 把每条历史消息编译成模型输入消息
6. 为当前 Agent 生成 system message
7. 应用客户端侧上下文优化和注入
8. 生成最终 request body
9. 根据设置决定最终 URL
10. 发起 HTTP POST

注意：

- Android 端运行时允许保留 Room/文件/内存三套对象
- 但发请求前必须先收敛成“编译后消息数组”

---

## 7. 历史消息编译规则

### 7.1 基础规则

每条历史消息先转成：

```json
{
  "role": "user|assistant|system",
  "content": []
}
```

其中 `content` 在进入网络层前建议统一为：

- 纯文本消息：`[{ "type": "text", "text": "..." }]`
- 多模态消息：文本片段 + `image_url` 片段

### 7.2 文本来源

每条消息的文本基底来自历史里的 `content`。

如果是当前正在发送的用户消息，则文本基底还要并入：

- 当前输入框文本
- 当前消息附件补出来的上下文文本
- 当前消息内可能存在的 Canvas 占位符替换结果

### 7.3 正则规则

桌面端当前会在上下文侧应用 Agent 正则规则。

规则特点：

- 作用域可以是 `context`
- 要按消息角色判断
- 要按对话深度判断

Android 端结论：

- 这一步属于请求编译的一部分
- 不能只在前端显示时做
- `P1` 可以先只保留接口和执行点
- `P2` 再补全与桌面一致的深度规则

---

## 8. 附件扩展规则

### 8.1 上下文文本扩展

桌面端会把历史消息附件再补成上下文文本。

规则如下：

- 有 `imageFrames` 的扫描 PDF：补一条“扫描版 PDF 已转图片”的说明
- 有 `extractedText` 的附件：把提取文本拼回上下文
- 没有提取文本的附件：至少把文件路径/文件名语义补进去

Android 端结论：

- 附件不是只给 UI 看的
- 附件提取结果属于请求编译输入

### 8.2 多模态扩展

桌面端会把附件继续转成多模态 `content[]`。

规则如下：

- 图片文件转为 `image_url`
- 扫描 PDF 的图片帧转为 `image_url`
- 音频也先兼容成 `image_url`
- 视频也先兼容成 `image_url`

例子：

```json
[
  { "type": "text", "text": "用户文本" },
  { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } }
]
```

兼容怪异点：

- 音频当前也是 `image_url`
- 视频当前也是 `image_url`

Android 端结论：

- `P1` 先做图片
- `P2` 再补音频/视频
- 在补之前不要擅自“标准化重写”

### 8.3 附件顺序

建议保持与桌面端相同的拼装顺序：

1. 文本片段
2. 图片片段
3. 音频片段
4. 视频片段

如果用户只发附件没有文本，仍要保证最终 `content[]` 可发送。

---

## 9. System Message 注入规则

如果当前 Agent 有 `systemPrompt`，则请求体最前面必须存在 system message。

桌面端当前会在 system prompt 前面补两类信息：

### 9.1 history 路径语义

当前桌面端会注入类似：

```text
当前聊天记录文件路径: <agentDataPath>\topics\<topicId>\history.json
```

Android 端结论：

- 需要保留“history 路径语义”
- 不需要机械保留 Windows 路径
- 应改为 Android 私有目录下的兼容表示

### 9.2 话题创建时间

当前桌面端会把当前 Topic 的 `createdAt` 格式化后拼到 system prompt 前面。

Android 端结论：

- 必须保留这个语义
- 时间格式可以统一成稳定字符串
- 不应省略

### 9.3 AgentName 替换

`systemPrompt` 内的 `{{AgentName}}` 替换必须保留。

---

## 10. 主进程发送前的兼容处理

在真正发 HTTP 之前，还要走一层发送前兼容处理。

### 10.1 content 规范化

要兼容这些情况：

- `content` 是字符串
- `content` 是多模态数组
- `content` 是带 `text` 字段的对象
- 无法识别的对象先转字符串

### 10.2 URL 切换

如果：

- `enableVcpToolInjection === true`

则把主聊天地址切到：

```text
/v1/chatvcp/completions
```

### 10.3 音乐注入

桌面端当前可能注入：

- 当前播放歌曲信息
- `点歌台{{VCPMusicController}}`
- 播放列表摘要

Android 端结论：

- `P1` 先不做
- 但请求编译器要预留该注入点

### 10.4 Agent Bubble Theme 注入

如果启用气泡主题输出，则 system message 中要补：

```text
输出规范要求：{{VarDivRender}}
```

### 10.5 Thought Chain 处理

当前桌面端默认会剥离 thought chain，除非：

- `enableThoughtChainInjection === true`

Android 端结论：

- `P2` 要补齐
- 执行点应在 system 注入之后、sanitizer 之前

### 10.6 Context Sanitizer

当前桌面端只对非 system 消息做 sanitizer。

Android 端结论：

- system message 不参与 sanitizer
- 失败时不能阻断请求发送

### 10.7 Context Folding

当前桌面端会在发送前对上下文做 folding。

Android 端结论：

- folding 属于发送前优化，不属于持久化变更
- 失败时仍应继续用原消息发送

---

## 11. 模型参数映射

单聊模型配置当前至少要透传这些字段：

- `model`
- `temperature`
- `max_tokens`
- `contextTokenLimit`
- `top_p`
- `top_k`
- `stream`

规则：

- `stream` 取决于 Agent `streamOutput`
- `requestId` 固定等于当前 assistant 草稿消息 ID

---

## 12. HTTP 发送规则

请求发出时：

- 方法：`POST`
- Header：`Content-Type: application/json`
- Header：`Authorization: Bearer <vcpApiKey>`
- Body：编译后的 JSON

返回分两类：

- 流式：进入 SSE 解析
- 非流式：直接取整包 JSON

---

## 13. 中断规则

单聊中断必须兼容当前行为：

1. 当前请求的本地主键是 `messageId`
2. 发给后端时仍然用 `requestId`
3. `/v1/interrupt` body 也用 `requestId`

也就是说：

- Android 内部可以统一叫 `messageId`
- 但发给现有后端时必须映射成 `requestId`

中断 URL：

```text
<server-origin>/v1/interrupt
```

中断 body：

```json
{
  "requestId": "msg_xxx"
}
```

---

## 14. Android 实现建议

Android 端建议至少抽出这几个接口：

```kotlin
interface SingleChatRequestCompiler {
    suspend fun compile(input: CompileInput): CompiledRequest
}

data class CompiledRequest(
    val requestId: String,
    val finalUrl: String,
    val headers: Map<String, String>,
    val body: ChatRequestBody,
    val context: ChatRequestContext
)
```

内部模型至少分开：

- `UiMessage`
- `StoredMessage`
- `CompiledModelMessage`
- `ChatRequestBody`

不要让一个 `Message` 类同时承担 UI、存储、网络三种职责。

---

## 15. P1 / P2 边界

### P1 必做

- 文本消息编译
- 单聊 `requestId`
- 图片附件转多模态
- system prompt
- 话题创建时间注入
- history 路径语义注入
- 基础 URL 切换

### P2 补齐

- 正则规则
- thought chain 处理
- context sanitizer
- context folding
- txt/pdf/docx 提取
- 音频/视频兼容
- 音乐控制注入

---

## 16. 当前结论

Android 单聊真正要迁的不是一个“发送按钮”，而是一条客户端请求编译链：

1. 历史快照
2. 附件扩展
3. system 注入
4. 上下文优化
5. URL / body 组装
6. `requestId` 兼容

只有把这条链先冻结，后面 Android 实现才不会重新滑回“把页面做出来再说”。
