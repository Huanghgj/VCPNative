# Android Complete Chat Feature Notes

日期：2026-03-25

## 这轮目标

先不继续扩散到更多桌面功能，集中把 Android 单聊补到一条更完整、能和 VCPChat 语义对齐的主链：

- 聊天渲染已先完成一版 Compose 富渲染。
- 本轮继续补：
  - `/v1/models` 模型发现
  - VCPChat 风格的 active system prompt 解析
  - 上下文自动压缩 / 折叠
  - 桌面 AppData 导入后 prompt 相关字段的兼容保留

## 参考来源

- `/root/VCPChat/main.js`
- `/root/VCPChat/modules/chatManager.js`
- `/root/VCPChat/modules/ipc/promptHandlers.js`
- `/root/VCPChat/modules/contextFolder.js`

## 需要迁移的关键语义

### 1. 模型获取

VCPChat 会按当前 `vcpServerUrl + vcpApiKey` 从 `/v1/models` 拉取模型列表并缓存。

Android 侧本轮收口：

- 提供统一的 `VcpModelCatalog`
- 使用同一套连接参数直接请求 `/v1/models`
- 在设置页可刷新、查看返回模型，便于排障和后续 agent 选型

### 2. 系统提示词

VCPChat 不只看 `systemPrompt`，还会根据 `promptMode` 选择真正生效的 prompt：

- `original`
- `modular`
- `preset`

Android 侧本轮收口：

- Room `agents` 表保存：
  - `promptMode`
  - `originalSystemPrompt`
  - `advancedSystemPromptJson`
  - `presetSystemPrompt`
  - `presetPromptPath`
  - `selectedPreset`
- 编译请求时按 VCPChat 逻辑解析 active system prompt
- 继续保留已有注入：
  - `当前聊天记录文件路径`
  - `当前话题创建于`
  - `{{VarDivRender}}`
- `preset` 模式兼容桌面端目录语义：
  - 默认路径 `./AppData/systemPromptPresets`
  - 相对 `AppData` 路径映射到 Android 私有 `compat/AppData`
  - 保存时保留 `selectedPreset`，导出后仍可被桌面端识别

### 3. 上下文自动压缩

VCPChat 的 `contextFolder.js` 不是删历史，而是：

- 保留 system 消息
- 保留最近 N 条原文
- 将更早消息折叠成一个系统摘要块

Android 侧本轮收口：

- 在 request compiler 内对已编译历史执行折叠
- 保证当前待发送用户消息不被折叠
- 默认参数对齐桌面：
  - keep recent = 12
  - trigger count = 24
  - trigger chars = 24000
  - excerpt = 160
  - max summary entries = 40

### 4. Agent 编辑收口

前一版虽然已经能导入和消费桌面端 Agent 配置，但 Android 本地还不能修改这些关键字段。

Android 侧本轮追加：

- 新增 Agent 编辑页，可直接修改：
  - `name`
  - `model`
  - `promptMode`
  - `originalSystemPrompt`
  - `advancedSystemPromptJson`
  - `presetSystemPrompt`
  - `presetPromptPath`
  - `selectedPreset`
  - `temperature`
  - `contextTokenLimit`
  - `maxOutputTokens`
  - `top_p`
  - `top_k`
  - `streamOutput`
- 从 `Agents`、`Topics`、`Chat` 三处都能进入编辑页
- `preset` 模式下可以直接刷新预设目录并应用 `.md/.txt` 预设内容
- 保存时按当前 `promptMode` 解析 active prompt，并同步回写兼容字段 `systemPrompt`
- 保存后立即刷新 compat `config.json`，继续和桌面 AppData 保持一致语义

## 暂不做

- prompt 模块化可视编辑器
- 模型收藏 / 热门模型
- 群聊上下文净化器

这些后续继续跟进，但不阻塞当前 Android 单聊“能发、能流、能渲、能压缩上下文、能继承桌面 prompt 语义”的主目标。
