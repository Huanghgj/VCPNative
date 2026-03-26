# Android 领域模型草案

更新时间：2026-03-25

本文不是桌面端现有字段的简单抄录，而是面向 Android 实现的建议模型。  
其中：

- “兼容字段”来自当前 `VCPChat` 代码的真实依赖
- “建议字段/建议模型”属于 Android 侧设计推断，用于后续实现落地

---

## 1. 建模原则

Android 端建议把模型分成三层：

1. 兼容导入导出模型
   负责读写桌面端 `AppData` 语义

2. 领域模型
   负责业务逻辑、状态流转、消息编译

3. 持久化模型
   负责 Room / 文件缓存 / 附件索引

不要让 UI 直接持有桌面端 JSON 结构，也不要让网络请求直接吃 UI 状态对象。

---

## 2. Settings

### 2.1 领域模型

```kotlin
data class AppSettings(
    val userName: String,
    val vcpServerUrl: String,
    val vcpApiKey: String,
    val vcpLogUrl: String?,
    val vcpLogKey: String?,
    val enableVcpToolInjection: Boolean,
    val enableAgentBubbleTheme: Boolean,
    val enableThoughtChainInjection: Boolean,
    val enableContextSanitizer: Boolean,
    val contextSanitizerDepth: Int,
    val enableContextFolding: Boolean,
    val contextFoldingKeepRecentMessages: Int,
    val contextFoldingTriggerMessageCount: Int,
    val contextFoldingTriggerCharCount: Int,
    val contextFoldingExcerptCharLimit: Int,
    val lastOpenItemId: String?,
    val lastOpenItemType: ItemType?,
    val lastOpenTopicId: String?,
    val agentOrder: List<String>,
    val combinedItemOrder: List<OrderedItemRef>
)
```

### 2.2 说明

- 这是首版最重要的配置聚合根之一。
- Android 首版可以先不暴露全部设置项，但数据模型最好一次定稳。

---

## 3. Agent

### 3.1 领域模型

```kotlin
data class Agent(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val model: String?,
    val temperature: Double,
    val contextTokenLimit: Int?,
    val maxOutputTokens: Int?,
    val topP: Double?,
    val topK: Int?,
    val streamOutput: Boolean,
    val avatar: AvatarRef?,
    val avatarCalculatedColor: String?,
    val disableCustomColors: Boolean,
    val useThemeColorsInChat: Boolean,
    val promptProfile: PromptProfile?,
    val regexRules: List<RegexRule>,
    val topics: List<TopicSummary>
)
```

### 3.2 说明

- `promptProfile` 是建议字段，用来包住 Promptmodules 相关数据。
- 首版可以先只用 `systemPrompt`，但不要把后续 Prompt 扩展空间堵死。

---

## 4. Topic

### 4.1 通用 Topic 摘要模型

```kotlin
data class TopicSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val locked: Boolean = true,
    val unread: Boolean = false,
    val creatorSource: String = "unknown"
)
```

### 4.2 说明

- 这是单聊 topic 当前最稳定的一组兼容字段。
- Group Topic 当前不一定完整持有 `locked/unread/creatorSource`，但 Android 内部可以统一模型，兼容导入时缺失就补默认值。

---

## 5. Group

### 5.1 领域模型

```kotlin
data class AgentGroup(
    val id: String,
    val name: String,
    val avatar: AvatarRef?,
    val avatarCalculatedColor: String?,
    val members: List<String>,
    val mode: GroupMode,
    val tagMatchMode: GroupTagMatchMode,
    val memberTags: Map<String, String>,
    val groupPrompt: String,
    val invitePrompt: String,
    val useUnifiedModel: Boolean,
    val unifiedModel: String?,
    val createdAt: Long,
    val topics: List<TopicSummary>
)
```

### 5.2 枚举建议

```kotlin
enum class GroupMode {
    SEQUENTIAL,
    NATURE_RANDOM,
    INVITE_ONLY
}

enum class GroupTagMatchMode {
    STRICT,
    NATURAL
}
```

### 5.3 说明

- Group 是独立聚合根，不建议挂在 Agent 下。
- `memberTags` 当前是 `agentId -> string`，Android 内部依然建议保留这个语义。

---

## 6. Message

### 6.1 领域模型

```kotlin
data class Message(
    val id: String,
    val role: MessageRole,
    val content: MessageContent,
    val timestamp: Long,
    val name: String?,
    val agentId: String?,
    val isThinking: Boolean,
    val isGroupMessage: Boolean,
    val groupId: String?,
    val topicId: String?,
    val avatar: AvatarRef?,
    val avatarCalculatedColor: String?,
    val attachments: List<MessageAttachmentRef>,
    val interrupted: Boolean,
    val finishReason: String?
)
```

### 6.2 枚举

```kotlin
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
```

### 6.3 Content 建议建模

当前桌面端历史里常用字符串 `content`，但发送给模型前又会编译成多模态数组。  
因此 Android 端建议显式拆成两层：

```kotlin
sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class RichText(val rawText: String) : MessageContent
}
```

### 6.4 说明

- 对历史持久化，首版可以仍然保存 `rawText`
- 对网络发送，再通过 `MessageCompiler` 转成模型输入格式

不要让“历史内容”和“模型输入内容”共享一个类。

---

## 7. Attachment

### 7.1 消息附件引用

```kotlin
data class MessageAttachmentRef(
    val type: String,
    val src: String?,
    val name: String,
    val size: Long?,
    val storage: StoredAttachment?
)
```

### 7.2 存储层附件模型

```kotlin
data class StoredAttachment(
    val id: String,
    val originalName: String,
    val internalFileName: String,
    val internalPath: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val extractedText: String?,
    val imageFrames: List<String>?
)
```

### 7.3 说明

- `StoredAttachment` 对应桌面端 `_fileManagerData`
- `MessageAttachmentRef` 对应 history 里挂在消息上的附件对象
- Android 内部不要依赖 `file://` 这种桌面路径格式作为主标识，主标识应是 `id` / `sha256`

---

## 8. Avatar

```kotlin
data class AvatarRef(
    val localUri: String?,
    val calculatedColor: String?
)
```

说明：

- 桌面端既有头像文件路径，又有 `avatarCalculatedColor`
- Android 内部建议统一成 `AvatarRef`

---

## 9. Prompt Profile

Promptmodules 很重，首版不一定实现，但模型上最好预留。

```kotlin
data class PromptProfile(
    val promptMode: PromptMode,
    val originalSystemPrompt: String?,
    val advancedSystemPrompt: AdvancedPromptState?,
    val presetSystemPrompt: String?,
    val presetPromptPath: String?,
    val selectedPreset: String?
)
```

```kotlin
enum class PromptMode {
    ORIGINAL,
    MODULAR,
    PRESET
}
```

```kotlin
data class AdvancedPromptState(
    val blocks: List<PromptBlock>,
    val hiddenBlocks: Map<String, List<PromptBlock>>,
    val warehouseOrder: List<String>
)
```

```kotlin
data class PromptBlock(
    val id: String,
    val type: String,
    val content: String,
    val name: String?,
    val disabled: Boolean,
    val variants: List<String>,
    val selectedVariant: Int?
)
```

说明：

- 这是“建议模型”，不是首版必做。
- 预留模型有助于后续不打断 Agent 配置结构。

---

## 10. Regex Rule

```kotlin
data class RegexRule(
    val findPattern: String,
    val replaceWith: String,
    val applyToContext: Boolean,
    val applyToFrontend: Boolean,
    val applyToRoles: List<MessageRole>,
    val minDepth: Int?,
    val maxDepth: Int?
)
```

---

## 11. 网络发送模型

### 11.1 编译后请求

```kotlin
data class CompiledChatRequest(
    val endpointUrl: String,
    val authToken: String,
    val requestBody: ChatRequestBody,
    val context: RequestContext
)
```

```kotlin
data class ChatRequestBody(
    val messages: List<CompiledMessage>,
    val model: String?,
    val temperature: Double?,
    val maxTokens: Int?,
    val contextTokenLimit: Int?,
    val topP: Double?,
    val topK: Int?,
    val stream: Boolean,
    val requestId: String?,
    val messageId: String?
)
```

### 11.2 编译后消息

```kotlin
data class CompiledMessage(
    val role: String,
    val content: List<CompiledContentPart>
)
```

```kotlin
sealed interface CompiledContentPart {
    data class Text(val text: String) : CompiledContentPart
    data class ImageUrl(val url: String) : CompiledContentPart
}
```

说明：

- 这里故意同时保留 `requestId` 和 `messageId`，因为当前桌面端单聊/群聊并不统一
- 不要在模型层硬编码“只会有一种 ID 键名”

---

## 12. 流事件模型

Android 内部建议直接围绕桌面现有事件语义建模：

```kotlin
sealed interface StreamEvent {
    val messageId: String
    val context: RequestContext?

    data class AgentThinking(
        override val messageId: String,
        override val context: RequestContext?
    ) : StreamEvent

    data class Start(
        override val messageId: String,
        override val context: RequestContext?
    ) : StreamEvent

    data class Data(
        override val messageId: String,
        override val context: RequestContext?,
        val chunk: RawStreamChunk,
        val hasContent: Boolean?
    ) : StreamEvent

    data class End(
        override val messageId: String,
        override val context: RequestContext?,
        val fullResponse: String?,
        val interrupted: Boolean,
        val finishReason: String?
    ) : StreamEvent

    data class Error(
        override val messageId: String,
        override val context: RequestContext?,
        val error: String
    ) : StreamEvent

    data class FullResponse(
        override val messageId: String,
        override val context: RequestContext?,
        val fullResponse: String
    ) : StreamEvent

    data class NoAiResponse(
        override val messageId: String,
        override val context: RequestContext?,
        val reason: String?
    ) : StreamEvent

    data class RemoveMessage(
        override val messageId: String,
        override val context: RequestContext?
    ) : StreamEvent
}
```

### 12.1 RequestContext

```kotlin
data class RequestContext(
    val agentId: String?,
    val agentName: String?,
    val groupId: String?,
    val topicId: String?,
    val isGroupMessage: Boolean
)
```

说明：

- 这套事件模型比“只处理文本增量”更贴近桌面真实行为
- 尤其对群聊阶段很重要

---

## 13. 持久化建议

建议 Android 至少拆成这些持久化单元：

| 持久化单元 | 建议形式 |
| --- | --- |
| `settings` | 单表或 DataStore |
| `agents` | Room |
| `agent_topics` | Room |
| `groups` | Room |
| `group_topics` | Room |
| `messages` | Room |
| `attachments` | Room + 文件缓存 |
| `prompt_blocks` | Room 或 JSON blob |
| `regex_rules` | Room 或 JSON blob |

说明：

- 历史消息量大，建议 `messages` 独立表
- 附件二进制不要塞数据库本体，数据库只存索引

---

## 14. 不变量

建议在 Android 端明确这些不变量：

1. `Message.id` 必须唯一
2. `Topic.id` 在所属 Agent 或 Group 范围内唯一
3. `StoredAttachment.sha256` 可视为去重主键
4. `Group.members` 中的每个值都必须对应一个 Agent
5. `CompiledChatRequest` 在发送前必须完成“历史 -> 模型输入”的编译，不允许 UI 直接构造裸请求

---

## 15. 当前结论

Android 端最核心的模型不是 UI 页面模型，而是：

1. `Agent / Group / Topic / Message / Attachment`
2. `CompiledChatRequest`
3. `StreamEvent`

只要这三层模型定稳，后面无论做单聊、群聊还是兼容增强，代码都会顺很多。
