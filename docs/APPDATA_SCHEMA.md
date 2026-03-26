# AppData 数据结构

更新时间：2026-03-25

本文只记录当前代码已经明确依赖的本地数据结构。  
没有在本文中出现的字段，不代表绝对不存在，只代表它不是当前迁移基线中的“已确认核心字段”。

---

## 1. 根目录

桌面端当前约定的数据根目录为：

```text
VCPChat/AppData/
```

核心结构如下：

```text
AppData/
├── settings.json
├── Agents/
│   └── <agentId>/
│       ├── config.json
│       ├── avatar.png|jpg|jpeg|gif|webp
│       └── regex_rules.json
├── AgentGroups/
│   └── <groupId>/
│       ├── config.json
│       └── avatar.png|jpg|jpeg|gif|webp
├── UserData/
│   ├── user_avatar.png
│   ├── attachments/
│   │   └── <sha256>.<ext>
│   ├── <agentId>/
│   │   └── topics/<topicId>/history.json
│   ├── <groupId>/
│   │   └── topics/<topicId>/history.json
│   ├── forum.config.json
│   └── memo.config.json
├── songlist.json
├── generated_lists/
├── network-notes-cache.json
├── MusicCoverCache/
├── WallpaperThumbnailCache/
├── ResampleCache/
└── canvas/
```

Android 版内部存储可以不用完全复刻这个目录结构，但导入/导出语义应该尽量兼容。

### 1.1 Android 运行时存储原则

Android 端已经固定一条硬约束：

- 持久化本地数据默认放在应用私有目录

这意味着：

- `settings / agent / topic / history / attachment / avatar` 的运行时真相来源都在 app-private storage
- 不以公共外部存储作为主数据根目录
- 导入/导出只是显式动作，不是运行时主工作目录

建议的物理落点如下：

```text
<app-private>/
├── files/
│   └── vcpchat/
│       ├── settings/
│       ├── agents/
│       ├── agent-groups/
│       ├── user-data/
│       ├── attachments/
│       └── avatars/
└── cache/
    └── vcpchat/
        ├── import-staging/
        ├── attachment-transcode/
        └── preview/
```

说明：

- `files/` 下放持久化数据
- `cache/` 下放导入暂存、转码中间文件、预览缓存
- 具体哪些数据最终落 Room、DataStore 或 JSON 文件，属于后续实现细化
- 但物理存储根一定是在应用私有目录内

### 1.2 路径兼容原则

桌面端当前很多对象会出现 `file://` 路径。  
Android 端兼容时要区分两件事：

1. 运行时主标识
2. 导入导出时的兼容表示

规则固定为：

- Android 运行时主标识优先使用 `id / hash / relative path`
- 不把桌面 `file://` 绝对路径当成 Android 内部真相
- 如果导入桌面数据，需要在兼容层把旧路径重新映射到 Android 私有目录

---

## 2. settings.json

### 2.1 核心字段

这些字段是代码里已经明确有默认值或明确读写的核心字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sidebarWidth` | number | 左侧边栏宽度 |
| `notificationsSidebarWidth` | number | 通知栏宽度 |
| `userName` | string | 用户显示名 |
| `vcpServerUrl` | string | 主聊天服务地址 |
| `vcpApiKey` | string | 主聊天服务密钥 |
| `vcpLogUrl` | string | VCPLog WebSocket 基地址 |
| `vcpLogKey` | string | VCPLog 密钥 |
| `networkNotesPaths` | string[] | 网络笔记路径列表 |
| `enableAgentBubbleTheme` | boolean | 是否注入 `{{VarDivRender}}` |
| `enableSmoothStreaming` | boolean | 是否启用平滑流式渲染 |
| `minChunkBufferSize` | number | 最小 chunk 缓冲尺寸 |
| `smoothStreamIntervalMs` | number | 流式刷新间隔 |
| `assistantAgent` | string | 助手 Agent 标识 |
| `enableDistributedServer` | boolean | 分布式服务开关 |
| `agentMusicControl` | boolean | 是否注入点歌台能力 |
| `enableDistributedServerLogs` | boolean | 分布式日志开关 |
| `enableVcpToolInjection` | boolean | 是否切换到 `/v1/chatvcp/completions` |
| `enableThoughtChainInjection` | boolean | 是否保留思维链 |
| `enableContextSanitizer` | boolean | 是否启用上下文净化 |
| `contextSanitizerDepth` | number | 净化深度 |
| `enableContextFolding` | boolean | 是否启用上下文折叠 |
| `contextFoldingKeepRecentMessages` | number | 折叠时保留最近消息数 |
| `contextFoldingTriggerMessageCount` | number | 达到多少条消息后触发折叠 |
| `contextFoldingTriggerCharCount` | number | 达到多少字符后触发折叠 |
| `contextFoldingExcerptCharLimit` | number | 折叠摘要单条截断长度 |
| `lastOpenItemId` | string\|null | 上次打开的 agent/group |
| `lastOpenItemType` | string\|null | 上次打开对象类型 |
| `lastOpenTopicId` | string\|null | 上次打开的话题 |
| `combinedItemOrder` | array | Agent + Group 混合顺序 |
| `agentOrder` | string[] | Agent 排序 |

### 2.2 代码中还会附加或写入的字段

这些字段没有都在默认模板里出现，但代码会读写它们：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userAvatarUrl` | string\|null | 运行时返回字段，不应持久化为文件路径 |
| `userAvatarCalculatedColor` | string\|null | 用户头像计算色 |
| `currentThemeMode` | string | 当前主题模式 |
| `themeLastUpdated` | number | 主题更新时间戳 |
| `flowlockContinueDelay` | number | 心流锁续写延时 |

### 2.3 设计约束

- `settings.json` 当前是“弱 schema”文件，允许随着功能增长增加字段。
- Android 版不能假设只有固定字段集。
- `userAvatarUrl` 这类本地文件路径字段应当视为运行时派生值，不应直接原样跨平台迁移。

---

## 3. Agent 配置

路径：

```text
AppData/Agents/<agentId>/config.json
```

### 3.1 核心字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | Agent 名称 |
| `systemPrompt` | string | Agent 系统提示 |
| `model` | string | 默认模型 |
| `temperature` | number | 温度 |
| `contextTokenLimit` | number | 上下文上限 |
| `maxOutputTokens` | number | 最大输出 |
| `top_p` | number | 可选采样参数 |
| `top_k` | number | 可选采样参数 |
| `streamOutput` | boolean\|string | 是否启用流式 |
| `avatarCalculatedColor` | string\|null | 头像计算色 |
| `disableCustomColors` | boolean | 是否禁用自定义颜色 |
| `useThemeColorsInChat` | boolean | 是否使用主题色 |
| `topics` | Topic[] | 话题列表 |

### 3.2 Topic 对象

当前 Agent Topic 至少有这些字段：

```json
{
  "id": "topic_1742920000000",
  "name": "新话题 1",
  "createdAt": 1742920000000,
  "locked": true,
  "unread": false,
  "creatorSource": "ui"
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 话题 ID |
| `name` | string | 话题名称 |
| `createdAt` | number | 时间戳 |
| `locked` | boolean | 是否锁定 |
| `unread` | boolean | 是否未读 |
| `creatorSource` | string | 创建来源，如 `ui` |

兼容性规则：

- 旧数据没有 `locked` 时，当前代码会补成 `true`
- 旧数据没有 `unread` 时，当前代码会补成 `false`
- 旧数据没有 `creatorSource` 时，当前代码会补成 `unknown`

### 3.3 正则规则

路径：

```text
AppData/Agents/<agentId>/regex_rules.json
```

该文件与 `config.json` 分离保存。  
当前代码把它当作 `stripRegexes` 的持久化载体。

单条规则的最低已知字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `findPattern` | string | 正则表达式字符串 |
| `replaceWith` | string | 替换文本 |
| `applyToContext` | boolean | 是否作用于发送给模型的上下文 |
| `applyToFrontend` | boolean | 是否作用于前端显示 |
| `applyToRoles` | string[] | 适用角色 |
| `minDepth` | number | 最小轮次深度 |
| `maxDepth` | number | 最大轮次深度 |

---

## 4. Group 配置

路径：

```text
AppData/AgentGroups/<groupId>/config.json
```

当前 Group 的默认结构：

```json
{
  "id": "group_xxx",
  "name": "群组名",
  "avatar": null,
  "avatarCalculatedColor": null,
  "members": [],
  "mode": "sequential",
  "tagMatchMode": "strict",
  "memberTags": {},
  "groupPrompt": "",
  "invitePrompt": "现在轮到你{{VCPChatAgentName}}发言了。",
  "useUnifiedModel": false,
  "unifiedModel": "",
  "createdAt": 1742920000000,
  "topics": [
    {
      "id": "group_topic_1742920000000",
      "name": "主要群聊",
      "createdAt": 1742920000000
    }
  ]
}
```

### 4.1 关键字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `members` | string[] | 群成员 Agent ID 列表 |
| `mode` | string | `sequential` / `naturerandom` / `invite_only` |
| `tagMatchMode` | string | `strict` / `natural` |
| `memberTags` | object | `agentId -> tag string` |
| `groupPrompt` | string | 群级提示词 |
| `invitePrompt` | string | 邀请成员发言模板 |
| `useUnifiedModel` | boolean | 是否使用群统一模型 |
| `unifiedModel` | string | 群统一模型 |
| `topics` | Topic[] | 群话题列表 |

### 4.2 说明

- Group Topic 当前没有像单聊 Topic 那样明确持久化 `locked/unread/creatorSource`。
- Group 的头像和 Agent 头像一样，是配置文件旁边的独立文件。
- Group 的历史不保存在 `AgentGroups/` 下，而保存在 `UserData/<groupId>/topics/<topicId>/history.json`。

---

## 5. history.json

路径：

```text
AppData/UserData/<itemId>/topics/<topicId>/history.json
```

其中：

- `<itemId>` 可以是 `agentId`
- 也可以是 `groupId`

### 5.1 基础消息结构

当前消息对象没有一个强制统一的 schema 文件，但代码已经明确依赖这些字段：

```json
{
  "role": "assistant",
  "content": "你好",
  "timestamp": 1742920000000,
  "id": "msg_xxx"
}
```

### 5.2 已知可选字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 发言者名称 |
| `agentId` | string | 群聊中发言 Agent ID |
| `isThinking` | boolean | 是否是思考占位消息 |
| `isGroupMessage` | boolean | 是否群聊消息 |
| `groupId` | string | 群 ID |
| `topicId` | string | 话题 ID |
| `avatarUrl` | string | 消息级头像 |
| `avatarColor` | string | 消息级头像色 |
| `attachments` | AttachmentRef[] | 附件引用 |
| `interrupted` | boolean | 是否被中断 |
| `finishReason` | string | 流结束原因 |

### 5.3 Message 的 content 形态

当前 `history.json` 中常见的是字符串 `content`。  
发送给模型前，客户端会把它重新编译为多模态 `content[]`。

所以要区分两种概念：

- 历史存档形态：更偏向 UI 可恢复对象
- 网络发送形态：更偏向模型输入对象

Android 版不要把这两者混为一谈。

---

## 6. 附件引用

历史消息里的附件对象当前至少包含这些字段：

```json
{
  "type": "image/png",
  "src": "file:///.../attachments/xxxx.png",
  "name": "foo.png",
  "size": 12345,
  "_fileManagerData": {}
}
```

### 6.1 `_fileManagerData` 结构

`_fileManagerData` 来自 `fileManager.storeFile()` 的返回值，核心字段如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 附件内部 ID |
| `name` | string | 原始文件名 |
| `internalFileName` | string | 去重后的内部文件名 |
| `internalPath` | string | `file://` 路径 |
| `type` | string | MIME |
| `size` | number | 文件大小 |
| `hash` | string | SHA-256 |
| `createdAt` | number | 时间戳 |
| `extractedText` | string\|null | 提取出来的文本 |
| `imageFrames` | string[]\|null | PDF 或多帧图像转换后的 Base64 帧 |

### 6.2 设计约束

- `src` 更偏向前端可读路径。
- `_fileManagerData.internalPath` 更偏向存储层路径。
- Android 版应保留“附件引用 + 抽取结果 + 哈希去重”这三个核心能力，而不是机械保留 `file://` 路径字符串。

---

## 7. 迁移建议

Android 端建议把本地数据分成三层：

1. 持久化实体层
   对应 Room 表或文件。

2. 运行时领域模型层
   对应 Agent、Topic、Message、Attachment 的 Kotlin 模型。

3. 兼容导入导出层
   负责读写桌面端当前的 `AppData` 语义。

这样能做到：

- 内部实现现代化
- 外部兼容性不立刻断裂
- 后续想脱离桌面结构时，也不会把核心逻辑绑死在旧路径格式上
- 同时满足 Android 应用私有目录存储约束

---

## 8. 当前结论

`AppData` 不是一堆松散文件，而是当前 `VCPChat` 的本地事实来源。  
在不改 `VCPToolBox` 的前提下，Android 版首先要兼容的不是“某个接口”，而是这套本地数据语义。

但这套语义在 Android 上的物理承载位置，已经固定为：

- 应用私有目录

也就是：

- 语义兼容
- 路径不机械照搬
- 存储归属明确站在 Android 本地私有空间
