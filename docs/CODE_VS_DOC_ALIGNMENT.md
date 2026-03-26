# 源码与设计文档对照报告

更新时间：2026-03-26

本文记录当前 Android 源码与设计文档之间的对齐状态，用于指导后续补齐工作。

---

## 1. 对齐良好

以下部分源码与文档基本一致，无需调整：

### 1.1 信息架构

- 单 Activity + NavHost 导航
- Bootstrap → SetupGate → Settings → Workspace 启动流
- AgentList → TopicList → SingleChat 三级工作流
- AttachmentViewer / AgentEditor 辅助页面
- 启动恢复最近会话

### 1.2 数据层方向

- DataStore 管设置
- Room 管结构化聊天数据
- 私有文件目录管二进制和导入导出

### 1.3 网络兼容契约

- URL 规范化与 `/v1/chatvcp/completions` 切换
- SSE 多路径文本提取（5 种 JSON 路径）
- `[DONE]` 结束信号
- 单 chunk 解析失败不中止流
- 中断：本地 call.cancel() + 远程 `/v1/interrupt`
- 中断 body 使用 `requestId`

### 1.4 请求编译链

- 历史快照 → 附件扩展 → system 注入 → 上下文优化 → 请求组装
- system prompt 注入 history 路径、话题创建时间、`{{AgentName}}`、`{{VarDivRender}}`
- 附件上下文文本扩展
- 图片/音频/视频转多模态 `image_url`
- requestId = assistant 草稿消息 ID
- 正则规则按角色/深度应用
- Context Folding
- Prompt mode (original/modular/preset)

### 1.5 渲染

- 混合架构：普通 markdown/html 走 Markwon 原生，浏览器级 HTML 走隔离 WebView
- VCP 专有块分流渲染
- 超出文档原始预期，实际做得更细

---

## 2. 主要偏离

以下部分源码与文档存在结构性差异：

### 2.1 `extra_json` 兼容字段全线缺失

- 文档要求：agents / topics / messages / attachments 等所有结构化表都应保留 `extra_json` 字段
- 源码现状：所有表都没有 `extra_json`
- 影响：导入桌面 AppData 时，Android 尚未理解的字段会丢失；导出时无法带回
- 文档原文：「这条策略是迁移项目非常重要的保险丝」
- 优先级：建议尽早补，影响导出兼容性

### 2.2 附件大对象直接存 Room

- 文档要求：`extractedText` 和 `imageFrames` 应存 sidecar 文件，Room 只存定位信息
- 源码现状：`message_attachments` 表直接存 `extractedText` 和 `imageFramesJson`
- 影响：数据库体积膨胀（PDF 帧可达数 MB）
- 文档原文：「大文本提取结果存 sidecar 文件，图像帧存 sidecar 目录，Room 只存定位信息」
- 优先级：中期，当前数据量小时可用

### 2.3 独立 `attachments` 去重索引表缺失

- 文档要求：独立 `attachments` 表以 SHA256 为唯一键做去重索引
- 源码现状：附件元数据直接嵌在 `message_attachments` 里，无独立去重索引
- 影响：同一文件被多条消息引用时会重复存储元数据
- 优先级：中期

### 2.4 compat 文件实时维护 vs 按需重建

- 文档要求：「不在磁盘上实时维护 settings.json / config.json / history.json」
- 源码现状：每次写入后通过 `syncCompatHistory` / `syncCompatAgentSnapshot` 实时同步 compat 文件
- 影响：额外 IO 开销，但保证了导出随时可用
- 判断：实际做法比文档更保守但更安全，可暂不改

### 2.5 topics 表缺 `owner_type`

- 文档要求：统一 topics 表带 `owner_type` + `owner_id`，支持单聊和群聊共用
- 源码现状：只有 `agentId` 外键，无 owner_type
- 影响：群聊扩展时需重构表结构
- 优先级：P3 前必须解决

### 2.6 Repository 未拆分

- 文档要求：拆 5 个 Repository（Settings / Agent / Conversation / Attachment / ImportExport）
- 源码现状：1 个 WorkspaceRepository + 1 个 SettingsRepository + 独立的 ImportManager / ExportManager
- 影响：WorkspaceRepository 职责较重，但当前功能量下可维护
- 优先级：低，可在代码量增长时重构

---

## 3. 功能缺失（按计划阶段）

### 3.1 P1 遗留

| 缺失项 | 文档位置 | 说明 |
| --- | --- | --- |
| 启动恢复悬空草稿 | STREAM_STATE_MACHINE §14 | App 被杀后 draft 消息应标记为 interrupted |
| 预缓冲（chunk 先到、消息后到） | STREAM_STATE_MACHINE §8 | 当前未做 |
| 显式请求状态机 | STREAM_STATE_MACHINE §4.1 | 当前用隐式 boolean 驱动 |

### 3.2 P2 计划

| 缺失项 | 文档位置 |
| --- | --- |
| Thought chain 处理 | REQUEST_PIPELINE §10.5 |
| Context sanitizer | REQUEST_PIPELINE §10.6 |
| VCPLog WebSocket | NETWORK_CONTRACT §1.3 |
| `userName` / `vcpLogUrl` / `vcpLogKey` 设置字段 | DOMAIN_MODEL §2 |
| `enableThoughtChainInjection` / `enableContextSanitizer` 设置字段 | DOMAIN_MODEL §2 |

### 3.3 P3 计划

| 缺失项 | 文档位置 |
| --- | --- |
| Group 相关表和模型 | DOMAIN_MODEL §5, DATA_LAYER §9.3-9.4 |
| 群聊请求用 `messageId` | NETWORK_CONTRACT §3 |
| 群聊流事件（AgentThinking / NoAiResponse / RemoveMessage） | DOMAIN_MODEL §12 |
| messages 表群聊字段（name / agentId / isGroupMessage / groupId） | DATA_LAYER §9.5 |

---

## 4. 领域模型差异明细

### 4.1 AppSettings

| 文档字段 | 源码 | 状态 |
| --- | --- | --- |
| userName | 缺 | P2 |
| vcpServerUrl | 有 | OK |
| vcpApiKey | 有 | OK |
| vcpLogUrl | 缺 | P2 |
| vcpLogKey | 缺 | P2 |
| enableVcpToolInjection | 有 | OK |
| enableAgentBubbleTheme | 有 | OK |
| enableThoughtChainInjection | 缺 | P2 |
| enableContextSanitizer | 缺 | P2 |
| contextSanitizerDepth | 缺 | P2 |
| enableContextFolding | 有 | OK |
| contextFolding* (5 个参数) | 有 | OK |
| lastOpenItemId / lastOpenItemType | 有 lastAgentId / lastTopicId | 等价 |
| agentOrder | 缺 | P2 |
| combinedItemOrder | 缺 | P3 |

### 4.2 Agent (AgentEntity)

| 文档字段 | 源码 | 状态 |
| --- | --- | --- |
| id / name / systemPrompt / model / temperature | 有 | OK |
| contextTokenLimit / maxOutputTokens / topP / topK / streamOutput | 有 | OK |
| avatarPath | 有 | OK |
| avatarCalculatedColor | 缺 | P2 |
| disableCustomColors / useThemeColorsInChat | 缺 | P2 |
| promptProfile (PromptProfile) | 拍平为 6 个独立字段 | 等价 |
| extra_json | 缺 | 建议尽早补 |
| sortOrder / updatedAt | 有 | OK |

### 4.3 Topic (TopicEntity)

| 文档字段 | 源码 | 状态 |
| --- | --- | --- |
| id / agentId / title / createdAt / updatedAt | 有 | OK |
| sourceTopicId | 有 | OK |
| owner_type / owner_id | 缺（只有 agentId） | P3 |
| locked / unread / creatorSource | 缺 | P2 |
| extra_json | 缺 | 建议尽早补 |

### 4.4 Message (MessageEntity)

| 文档字段 | 源码 | 状态 |
| --- | --- | --- |
| id / topicId / role / content / status / createdAt / updatedAt | 有 | OK |
| name / agentId | 缺 | P3（群聊） |
| isThinking / isGroupMessage / groupId | 缺 | P3 |
| interrupted / finishReason | 缺（用 status string 代替） | 等价 |
| avatar_rel_path / avatar_color | 缺 | P2 |
| ordinal / stream_state | 缺 | P2 |
| extra_json | 缺 | 建议尽早补 |

### 4.5 StreamSessionEvent vs StreamEvent

| 文档事件 | 源码事件 | 状态 |
| --- | --- | --- |
| AgentThinking | 缺 | P3（群聊） |
| Start | Started | OK |
| Data | TextDelta | OK |
| End | Completed | OK |
| Error | Failed | OK |
| FullResponse | 融入 Completed | 等价 |
| NoAiResponse | 缺 | P3 |
| RemoveMessage | 缺 | P3 |
| Interrupted | 有 | OK |

---

## 5. 结论

作为 P1 单聊 MVP，核心功能链路与文档对齐：

- 能选 Agent / Topic
- 能发消息、收流式回复、中断
- 能保存和恢复历史
- 能导入导出 AppData
- 能编辑 Agent 配置（含 prompt mode）
- 渲染方案超出文档预期

主要技术债务集中在：

1. `extra_json` 保险丝缺失（影响导出兼容性）
2. 附件大对象存 Room（影响数据库体积）
3. 群聊预留结构不足（P3 前需补）
4. 启动恢复悬空草稿未实现（P1 应补）

这些偏离在当前 P1 阶段可接受，但应在进入 P2 前有计划地补齐。
