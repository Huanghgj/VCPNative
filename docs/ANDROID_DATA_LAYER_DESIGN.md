# Android 数据层设计

更新时间：2026-03-25

本文用于冻结 `W1` 的核心问题：

1. Android 端运行时到底由谁存什么
2. `Room / DataStore / 文件目录` 的最终分工是什么
3. 在“迁移优先、能抄就抄”的前提下，如何既保持兼容，又不把运行时真相绑死在桌面 `AppData` 目录结构上

---

## 1. 先拍板的结论

Android 运行时数据层固定采用三路分工：

1. `DataStore`
   负责应用设置与轻量全局状态

2. `Room`
   负责结构化业务数据

3. `app-private files`
   负责二进制文件、大文本副产物、导入导出和兼容透传文件

这三路里只有一条原则：

- 同一类数据只能有一个运行时真相来源

结论如下：

- 设置真相来源是 `DataStore`
- 结构化聊天数据真相来源是 `Room`
- 附件/头像/导入导出产物真相来源是私有文件目录
- 运行时不维护一套完整的桌面 `AppData` 镜像目录作为第二真相来源

---

## 2. 为什么不直接把 `AppData` 原样当运行时存储

虽然项目整体是迁移，不是重构，但 Android 运行时仍不应直接以桌面目录为主存储格式。

原因：

1. Android 端需要稳定查询能力
   例如 Topic 列表、消息列表、未读状态、恢复最近会话

2. 历史消息和附件关系更适合结构化索引

3. 运行时直接维护大批 JSON 文件，会把并发更新、崩溃恢复和状态一致性搞得更脆弱

4. 导入导出是显式兼容动作，不该成为运行时主工作模型

因此当前正式决策是：

- 迁移语义
- 不机械照搬运行时物理结构

---

## 3. 数据层总结构

建议的 Android 数据层结构如下：

```text
android-app/
└── data/
    ├── settings/
    │   └── AppSettingsStore
    ├── db/
    │   ├── entities/
    │   ├── dao/
    │   └── VcpDatabase
    ├── files/
    │   ├── AttachmentFileStore
    │   ├── AvatarFileStore
    │   ├── ExportBundleStore
    │   └── CompatPassthroughStore
    ├── compat/
    │   ├── AppDataImportAdapter
    │   ├── AppDataExportAdapter
    │   └── AppDataCompatViewProvider
    └── repository/
        ├── AgentRepository
        ├── TopicRepository
        ├── ConversationRepository
        ├── AttachmentRepository
        └── ImportExportRepository
```

这里的关键不是 package 名，而是边界：

- 存储实现和兼容转换分开
- 仓储层和导入导出层分开
- 给后续“直接抄过来的逻辑”保留 compat view 出口

---

## 4. 运行时职责分工

### 4.1 DataStore 负责

- `userName`
- `vcpServerUrl`
- `vcpApiKey`
- `vcpLogUrl`
- `vcpLogKey`
- 各类 feature flags
- `lastOpenItemId`
- `lastOpenItemType`
- `lastOpenTopicId`
- `agentOrder`
- `combinedItemOrder`
- 侧栏宽度、流式体验参数、主题模式等轻量设置
- 设置中的未知字段透传容器

### 4.2 Room 负责

- Agent 主信息
- Topic 列表
- Group 主信息
- Group 成员与成员标签
- Message 历史
- 附件元数据索引
- 消息与附件关联
- Regex 规则
- 流草稿消息状态
- 导入后补出来的兼容扩展字段

### 4.3 私有文件目录负责

- 头像文件
- 去重后的附件二进制
- 提取文本 sidecar
- PDF/多帧文件转出的 frame 文件
- 导入暂存目录
- 导出目录
- 不进入 Android 主线但需要保留的 passthrough 文件

---

## 5. 最终物理目录

Android 私有目录最终建议固定为：

```text
<app-private>/
├── files/
│   └── vcpchat/
│       ├── db/
│       │   └── vcpnative.db
│       ├── avatars/
│       │   ├── agents/
│       │   ├── groups/
│       │   └── user/
│       ├── attachments/
│       │   └── <sha256>.<ext>
│       ├── attachment-meta/
│       │   └── <attachmentId>/
│       │       ├── extracted.txt
│       │       └── frames/
│       ├── exports/
│       │   └── <exportId>/AppData/...
│       └── passthrough/
│           └── <importSessionId>/...
├── cache/
│   └── vcpchat/
│       ├── import-staging/
│       ├── attachment-transcode/
│       └── preview/
└── datastore/
    └── app_settings.preferences_pb
```

说明：

- `db/` 放 Room
- `datastore/` 放设置
- `attachments/` 只存二进制正文
- `attachment-meta/` 放大文本与帧文件，避免把巨量内容塞进数据库
- `passthrough/` 专门用于保存当前 Android 不理解但不能丢的数据

---

## 6. 运行时真相与兼容视图

这里要明确一个对迁移非常重要的决策：

- 运行时真相不等于兼容视图

Android 内部真相来源是：

- `DataStore + Room + files`

而给迁移逻辑、导入导出、甚至未来某些“直接抄来的逻辑”看的兼容对象，应该由：

- `AppDataCompatViewProvider`

按需重建。

也就是说：

- 不在磁盘上实时维护 `settings.json / config.json / history.json`
- 但可以在内存里按需生成这些 JSON 形态对象

这条决策的价值很高：

- 既不把运行时绑死在旧目录结构
- 也不给“能抄就抄”的路径设置障碍

---

## 7. Settings 存储决策

### 7.1 Canonical Store

`settings` 的运行时真相来源固定为：

- `DataStore<Preferences>`

选择原因：

- 当前 `settings.json` 是弱 schema
- 字段会继续增加
- 首版不需要为了 schema 严格性付出 Proto 维护成本

### 7.2 存储策略

已知核心字段分别以独立 key 存储。

未知字段处理方式固定为：

- 额外维护一个 `settings_extra_json`

这意味着：

- Android 能稳定读取自己关心的字段
- 导出时还能尽量保留桌面端尚未被 Android 理解的字段

### 7.3 为什么不把设置放 Room

因为它更像：

- 全局偏好
- 启动路由状态
- 轻量配置

而不是需要复杂 join 的领域实体。

---

## 8. Room 作为结构化真相来源

### 8.1 数据库文件

数据库文件固定为：

- `files/vcpchat/db/vcpnative.db`

### 8.2 Room 负责的主表

P1/P2 建议至少固定这些表：

1. `agents`
2. `topics`
3. `agent_groups`
4. `group_members`
5. `messages`
6. `attachments`
7. `message_attachments`
8. `regex_rules`

### 8.3 为什么 topics 建议统一表

不要分别做：

- `agent_topics`
- `group_topics`

的两套运行时表。

更稳的做法是统一成：

- `topics`

并显式带上：

- `owner_type`
- `owner_id`

这样单聊和群聊在数据层能共用一套查询与排序逻辑。

---

## 9. 表结构建议

### 9.1 `agents`

建议字段：

- `id` PK
- `name`
- `system_prompt`
- `model`
- `temperature`
- `context_token_limit`
- `max_output_tokens`
- `top_p`
- `top_k`
- `stream_output`
- `avatar_rel_path`
- `avatar_calculated_color`
- `disable_custom_colors`
- `use_theme_colors_in_chat`
- `prompt_profile_json`
- `extra_json`
- `updated_at`

### 9.2 `topics`

建议字段：

- `conversation_id` PK
- `owner_type`
- `owner_id`
- `topic_id`
- `name`
- `created_at`
- `locked`
- `unread`
- `creator_source`
- `sort_index`
- `extra_json`
- `updated_at`

索引建议：

- `(owner_type, owner_id, sort_index)`
- `(owner_type, owner_id, topic_id)` unique

这里的 `conversation_id` 固定建议为：

```text
<ownerType>:<ownerId>:<topicId>
```

它是后续 `messages` 的外键锚点。

### 9.3 `agent_groups`

建议字段：

- `id` PK
- `name`
- `avatar_rel_path`
- `avatar_calculated_color`
- `mode`
- `tag_match_mode`
- `group_prompt`
- `invite_prompt`
- `use_unified_model`
- `unified_model`
- `created_at`
- `extra_json`
- `updated_at`

### 9.4 `group_members`

建议字段：

- `group_id`
- `agent_id`
- `member_order`
- `member_tag`

主键建议：

- `(group_id, agent_id)`

### 9.5 `messages`

建议字段：

- `id` PK
- `conversation_id`
- `owner_type`
- `owner_id`
- `topic_id`
- `role`
- `content_raw_text`
- `name`
- `agent_id`
- `avatar_rel_path`
- `avatar_color`
- `is_group_message`
- `is_thinking`
- `interrupted`
- `finish_reason`
- `stream_state`
- `ordinal`
- `timestamp`
- `updated_at`
- `extra_json`

索引建议：

- `(conversation_id, ordinal)`
- `(conversation_id, timestamp)`
- `(conversation_id, id)`

这里要特别强调：

- `content_raw_text` 存历史显示文本
- 网络发送时的多模态数组不直接落主表

### 9.6 `attachments`

建议字段：

- `id` PK
- `sha256` unique
- `original_name`
- `internal_file_name`
- `file_rel_path`
- `mime_type`
- `size`
- `created_at`
- `extracted_text_rel_path`
- `frames_dir_rel_path`
- `frame_count`
- `extra_json`

结论：

- 附件正文放文件
- Room 只放索引与 sidecar 路径

### 9.7 `message_attachments`

建议字段：

- `message_id`
- `attachment_id`
- `position`
- `display_type`
- `display_src`
- `display_name`
- `display_size`
- `extra_json`

主键建议：

- `(message_id, position)`

说明：

- `display_src` 是兼容导入导出的显示语义
- 不作为内部真实文件主键

### 9.8 `regex_rules`

建议字段：

- `agent_id`
- `rule_order`
- `find_pattern`
- `replace_with`
- `apply_to_context`
- `apply_to_frontend`
- `apply_to_roles_json`
- `min_depth`
- `max_depth`
- `extra_json`

主键建议：

- `(agent_id, rule_order)`

---

## 10. 为什么附件元数据不全塞 Room

当前桌面端附件里可能携带：

- 大段 `extractedText`
- 大量 `imageFrames`

这些内容直接塞进 Room 会有两个问题：

1. 数据库体积快速膨胀
2. 导入导出和解码时会让数据库承担不该承担的大对象存储职责

所以这里正式拍板：

- 大文本提取结果存 sidecar 文件
- 图像帧存 sidecar 目录
- Room 只存定位信息

---

## 11. 兼容字段保留策略

为了支持“能抄就抄”和无损导出，每个结构化对象都建议保留：

- `extra_json`

作用：

1. 导入时保留 Android 尚未理解的字段
2. 导出时尽量把原字段带回去
3. 避免每次 AppData 长字段变化都要立刻改数据库 schema

推荐使用点：

- `agents.extra_json`
- `topics.extra_json`
- `agent_groups.extra_json`
- `messages.extra_json`
- `attachments.extra_json`
- `settings_extra_json`

这条策略是迁移项目非常重要的保险丝。

---

## 12. passthrough 文件策略

以下内容当前不进入 Android 主线，但导入时不应直接丢弃：

- `forum.config.json`
- `memo.config.json`
- `songlist.json`
- `generated_lists/`
- `network-notes-cache.json`
- `MusicCoverCache/`
- `WallpaperThumbnailCache/`
- `ResampleCache/`
- `canvas/`
- 其他 Android 当前未识别文件

这些文件导入后固定进入：

- `files/vcpchat/passthrough/<importSessionId>/...`

运行时不读取它们作为主业务数据，但导出时要尽量带回去。

---

## 13. 仓储边界

建议的 Repository 边界如下：

### 13.1 `SettingsRepository`

负责：

- DataStore 读写
- settings compat view

### 13.2 `AgentRepository`

负责：

- Agent
- Regex rules
- Agent avatar

### 13.3 `ConversationRepository`

负责：

- Topic
- Message
- 会话草稿
- 会话排序与恢复

### 13.4 `AttachmentRepository`

负责：

- 附件落盘
- 哈希去重
- sidecar 管理
- message attachment 关系

### 13.5 `ImportExportRepository`

负责：

- AppData 导入
- AppData 导出
- passthrough 合并
- compat rebuild

不要把导入导出逻辑塞回日常业务仓储里。

---

## 14. 写入流程建议

### 14.1 发送消息

1. User message 写入 `messages`
2. 相关附件元数据写入 `attachments` / `message_attachments`
3. assistant 草稿写入 `messages`
4. 流式过程中只更新内存会话状态
5. `end/error/interrupted` 时统一写回 assistant 消息

### 14.2 创建 Topic

1. 在 `topics` 表插入新记录
2. 不需要先创建空 `history.json`
3. 导出时再重建桌面历史文件

### 14.3 更新 Agent

1. 基本字段更新 `agents`
2. 正则规则更新 `regex_rules`
3. 头像文件更新 `avatars/agents/`

---

## 15. 读路径建议

### 15.1 正常 UI 读路径

统一走：

- Repository -> Room/DataStore/files

### 15.2 兼容逻辑读路径

统一走：

- Repository -> Compat View Provider -> `settings/config/history` 形态对象

这能保证：

- UI 不碰旧 JSON
- 迁移逻辑又不被迫直接碰 Room

---

## 16. P1 / P2 边界

### P1 必须落地

- DataStore settings
- Room 的 `agents / topics / messages / attachments / message_attachments`
- 头像/附件私有文件目录
- assistant 草稿消息
- compat view provider 的最小单聊子集

### P2 补齐

- `agent_groups / group_members`
- `regex_rules`
- 大文本 sidecar
- 扫描 PDF frame sidecar
- passthrough 更细粒度合并
- Prompt profile 持久化细化

---

## 17. 当前结论

Android 数据层已经正式拍板成：

1. `DataStore` 管设置
2. `Room` 管结构化聊天数据
3. 私有文件目录管二进制和 sidecar
4. compat view 按需重建旧 `AppData` 形态
5. 运行时不维护一套完整 `AppData` 镜像

这个方案既站得住 Android 原生实现，也没有把“直接迁移/直接抄逻辑”的路堵死。
