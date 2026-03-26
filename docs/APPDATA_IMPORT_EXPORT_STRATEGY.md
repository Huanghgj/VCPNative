# AppData 导入导出策略

更新时间：2026-03-25

本文用于冻结 Android 端如何处理桌面 `VCPChat/AppData` 的导入和导出。

这里要先明确一条边界：

- 导入导出是兼容动作
- 不是运行时主数据模型

---

## 1. 目标

这份策略文档要解决四件事：

1. 如何把桌面 `AppData` 安全导入 Android
2. 如何把 Android 当前数据导出成桌面可理解结构
3. 如何处理 Android 当前不支持但又不能丢的数据
4. 如何避免导入导出把运行时真相搞成双份

---

## 2. 导入导出总原则

固定原则如下：

1. 桌面 `AppData` 是兼容基准
2. Android 运行时真相仍然是 `DataStore + Room + files`
3. 导入导出都走显式操作
4. 不支持的模块数据尽量 passthrough 保留
5. Android 当前支持的数据以 Android 当前真相为准导出

---

## 3. P1 支持范围

### 3.1 P1 导入必须支持

- `settings.json`
- `Agents/<agentId>/config.json`
- `Agents/<agentId>/regex_rules.json`
- `Agents/<agentId>/avatar.*`
- `UserData/<agentId>/topics/<topicId>/history.json`
- `UserData/attachments/<sha256>.<ext>`
- `UserData/user_avatar.*`

### 3.2 P1 导入建议保留但不主线使用

- `AgentGroups/`
- `UserData/<groupId>/topics/...`
- `forum.config.json`
- `memo.config.json`
- `songlist.json`
- `generated_lists/`
- `network-notes-cache.json`
- `MusicCoverCache/`
- `WallpaperThumbnailCache/`
- `ResampleCache/`
- `canvas/`

### 3.3 P1 导出必须支持

- 当前 Android 已理解的单聊主链路数据
- App-private 里的头像与附件
- 当前设置

### 3.4 P1 还不承诺

- 智能 merge 导入
- 群聊可运行导出验证
- Notes / Forum / Memo 的结构化可编辑回写

---

## 4. 导入来源格式

P1 建议正式支持两种输入：

1. 目录导入
2. ZIP 导入

规则：

- 如果用户给的是目录，直接进入 staging
- 如果用户给的是 zip，先解包到 staging 再处理

统一 staging 目录：

- `cache/vcpchat/import-staging/<sessionId>/`

---

## 5. 导入模式决策

这里正式拍板：

- `P1` 只支持 `replace_all`

不支持：

- `merge`

原因很直接：

1. Agent / Topic / Message 冲突合并规则过重
2. 当前阶段目标是迁移，不是双向协作编辑
3. merge 做不稳，极容易把历史和附件索引搞坏

因此 P1 导入行为固定为：

1. 先解析完整导入包
2. 校验成功后
3. 清空当前 Android 运行时数据
4. 再一次性写入新数据

如果需要 merge：

- 放到 `P2/P3`

---

## 6. 导入流水线

建议导入按下面 10 步执行：

1. 创建 `importSessionId`
2. 将输入内容复制/解压到 staging
3. 校验是否存在 `AppData/` 根或等价根
4. 扫描支持路径与 passthrough 路径
5. 解析 `settings.json`
6. 解析 Agent / Topic / History / Attachment / Avatar
7. 构建内存导入模型
8. 做完整性校验
9. 通过事务写入 `DataStore + Room + files`
10. 记录导入报告并清理 staging

重要约束：

- 在第 8 步校验通过前，不改运行时真相

---

## 7. 导入映射表

### 7.1 `settings.json`

导入目标：

- `DataStore` 已知字段
- `settings_extra_json` 未知字段

规则：

- 已知字段拆开写
- 未知字段保留到 extra 容器

### 7.2 `Agents/<agentId>/config.json`

导入目标：

- `agents`
- `topics`
- `agents.extra_json`
- `topics.extra_json`

规则：

- 已知 Agent 字段写主列
- `topics[]` 拆入 `topics`
- 未知字段写 `extra_json`

### 7.3 `Agents/<agentId>/regex_rules.json`

导入目标：

- `regex_rules`

规则：

- 保持原顺序
- 无法理解的字段进 `extra_json`

### 7.4 `Agents/<agentId>/avatar.*`

导入目标：

- `files/vcpchat/avatars/agents/<agentId>.<ext>`
- `agents.avatar_rel_path`

### 7.5 `AgentGroups/<groupId>/config.json`

导入目标：

- `agent_groups`
- `group_members`
- `topics`

但在 P1 的运行时作用是：

- 允许导入保存
- 不要求进入主 UI

### 7.6 `UserData/<itemId>/topics/<topicId>/history.json`

导入目标：

- `messages`
- `message_attachments`

规则：

- 保持原顺序
- 生成 `conversation_id`
- 每条消息保留 `extra_json`
- `attachments` 拆成关联记录

### 7.7 `UserData/attachments/<sha256>.<ext>`

导入目标：

- `files/vcpchat/attachments/<sha256>.<ext>`
- `attachments`

如果附件元数据里带：

- `extractedText`
- `imageFrames`

则：

- `extractedText` 写到 `attachment-meta/<attachmentId>/extracted.txt`
- `imageFrames` 解出到 `attachment-meta/<attachmentId>/frames/`

### 7.8 `UserData/user_avatar.*`

导入目标：

- `files/vcpchat/avatars/user/`
- `DataStore` 中的用户头像相对路径字段

### 7.9 其他不支持文件

导入目标：

- `files/vcpchat/passthrough/<importSessionId>/...`

运行时不作为主业务数据使用。

---

## 8. 路径重写规则

导入时桌面端对象可能带：

- `file://` 绝对路径
- Windows 盘符路径

这些都不能直接变成 Android 内部真相。

规则固定如下：

1. 文件正文靠哈希或 staging 路径重新定位
2. 运行时内部主路径改写成 Android 相对路径
3. 原始路径如果需要保留，写入 `extra_json`

也就是说：

- Android 会保留“这个对象原来指向某个本地文件”的语义
- 但不会继续把桌面绝对路径当主真相

---

## 9. 历史消息导入规则

每条历史消息导入时至少要做这些处理：

1. 保留 `id`
2. 保留 `role`
3. 保留 `content`
4. 保留 `timestamp`
5. 保留 `name / agentId / groupId / topicId`
6. 保留 `interrupted / finishReason`
7. 附件拆到 `message_attachments`
8. 未知字段放入 `extra_json`

如果消息附件里 `_fileManagerData` 缺失，但 `src/name/type` 仍存在：

- 仍保留关联
- 标记为降级导入
- 在导入报告里记录

---

## 10. 导入校验规则

导入前必须至少校验：

1. `settings.json` 可解析
2. Agent config 可解析
3. `topics` 至少能建出 conversation
4. 历史消息是数组
5. 附件引用若声明了哈希文件，文件应尽量能在包内找到

校验失败策略：

- 单个不关键对象失败：记录 warning，可继续
- 结构主干失败：整次导入失败，不改当前运行时数据

主干失败例子：

- 根目录非法
- `settings.json` 完全不可读
- 大量 Agent config 全部损坏

---

## 11. passthrough 策略

为了不把 Android 当前不支持的数据导入后直接蒸发，正式采用 passthrough 策略。

### 11.1 什么进入 passthrough

- Android 当前不理解的顶层文件
- Android 当前不主线支持的模块目录
- 已知对象上的未知伴随文件

### 11.2 passthrough 怎么存

固定存到：

- `files/vcpchat/passthrough/<importSessionId>/...`

并生成一个 manifest，至少记录：

- 原始相对路径
- 导入 session
- 文件大小
- 修改时间

### 11.3 导出时怎么处理

- 先导出 Android 当前支持的数据
- 再把 passthrough 内容补回未被主线覆盖的路径

规则：

- Android 主线已生成的支持路径优先级更高
- passthrough 只补空位，不覆盖主线产物

---

## 12. 导出目标格式

导出输出固定重建成桌面风格目录：

```text
AppData/
├── settings.json
├── Agents/
├── AgentGroups/
├── UserData/
└── ...
```

P1 建议支持两种导出结果：

1. 导出到私有 `exports/<exportId>/AppData/`
2. 再由用户选择分享/复制到外部位置

这样做的好处是：

- 先在私有目录完成完整构建和校验
- 再做外部分享
- 避免半导出半失败

---

## 13. 导出流水线

建议固定为：

1. 创建 `exportId`
2. 在 `files/vcpchat/exports/<exportId>/AppData/` 建空目录
3. 从 `DataStore` 重建 `settings.json`
4. 从 `Room` 重建 `Agents/` 和 `AgentGroups/`
5. 从 `Room` 重建 `history.json`
6. 从私有文件目录复制头像和附件
7. 从 passthrough 目录补齐非主线路径
8. 写出 manifest 与导出报告
9. 校验完成后再提供分享

---

## 14. 导出重建规则

### 14.1 `settings.json`

来源：

- DataStore 已知字段
- `settings_extra_json`

规则：

- Android 当前维护的已知字段优先
- 未知字段从 extra 容器补入

### 14.2 Agent `config.json`

来源：

- `agents`
- `topics`
- `agents.extra_json`
- `topics.extra_json`

规则：

- 已知字段按 Android 当前真相输出
- 未知字段从 extra 容器拼回

### 14.3 `regex_rules.json`

来源：

- `regex_rules`

### 14.4 `history.json`

来源：

- `messages`
- `message_attachments`
- `attachments`

规则：

- 按 `ordinal` 输出
- 恢复消息上的附件对象
- 若有 `extra_json`，尽量补回

### 14.5 附件导出

来源：

- `files/vcpchat/attachments/`
- `attachment-meta/`

规则：

- 正文文件直接复制
- 如果目标桌面格式需要 `_fileManagerData.extractedText` 或 `imageFrames`，则从 sidecar 重建

---

## 15. 冲突处理规则

导出时如果出现：

- passthrough 里有 `settings.json`
- Android 主线也生成了 `settings.json`

固定规则：

- Android 主线生成值优先

原因：

- 这些路径已经属于 Android 明确支持的主线对象
- passthrough 不能反向污染当前主真相

---

## 16. 导入报告与导出报告

每次导入导出都建议生成 report，至少记录：

- sessionId
- 开始/结束时间
- 成功数
- warning 数
- 失败数
- 被 passthrough 的路径列表

P1 可以先写成：

- `report.json`

保存在对应 session 目录。

---

## 17. P1 / P2 边界

### P1 必须做

- 目录/zip 导入
- `replace_all`
- 单聊主链路数据导入
- 单聊主链路数据导出
- passthrough 保留 unsupported 文件

### P2 再做

- merge 导入
- 群聊主链路导出验证
- unsupported 文件差量合并
- 更严格的冲突分析

---

## 18. 当前结论

`AppData` 在 Android 端的正确处理方式已经固定为：

1. 作为导入导出兼容基准
2. 不作为运行时主存储
3. 支持 `replace_all` 导入
4. 支持主线路径导出重建
5. unsupported 数据通过 passthrough 尽量保留

这样才能同时满足：

- 迁移优先
- 不丢已有数据
- Android 私有目录运行时真相

这三条目标。
