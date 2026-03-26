# VCPChat 迁移映射

更新时间：2026-03-25

本文记录的是“当前桌面版真实怎么工作”，不是理想化架构图。  
Android 迁移要兼容的是这里列出的现实行为。

---

## 1. 目标

本映射用于回答三个问题：

1. 现有 `VCPChat` 到底自己做了什么
2. 它和 `VCPToolBox` 现在通过什么契约协作
3. Android 版必须先迁哪些职责，哪些可以后置

---

## 2. 当前本地数据契约

桌面端核心数据根目录在：

```text
VCPChat/AppData/
├── settings.json
├── Agents/
│   └── <agentId>/
│       ├── config.json
│       ├── avatar.png|jpg|jpeg|gif
│       └── regex_rules.json
├── AgentGroups/
│   └── <groupId>/
│       ├── config.json
│       └── avatar.png|jpg|jpeg|gif
├── UserData/
│   ├── <agentId>/topics/<topicId>/history.json
│   ├── <groupId>/topics/<topicId>/history.json
│   ├── attachments/<sha256>.<ext>
│   ├── user_avatar.png
│   ├── forum.config.json
│   └── memo.config.json
├── songlist.json
├── generated_lists/
├── network-notes-cache.json
└── 其他缓存目录
```

需要注意的点：

- Agent 定义和聊天历史是分开的
- Topic 元数据主要在 `config.json`，历史正文在 `history.json`
- Group 的配置在 `AgentGroups/<groupId>/config.json`
- Group 的历史仍然落在 `UserData/<groupId>/topics/<topicId>/history.json`
- 附件是中心化去重存储，不是每条消息单独存副本

---

## 3. 当前后端兼容契约

### 3.1 全局配置字段

客户端当前依赖这些关键配置：

- `vcpServerUrl`
- `vcpApiKey`
- `vcpLogUrl`
- `vcpLogKey`
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

Android 版如果不支持这些字段，至少要明确哪些被忽略，哪些必须兼容。

### 3.2 单聊请求

当前单聊请求行为：

- 默认对 `vcpServerUrl` 发起 `POST`
- 如果 `enableVcpToolInjection === true`，则把路径切到 `/v1/chatvcp/completions`
- 请求体核心结构为：

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

### 3.3 群聊请求

当前群聊代码不是走单聊 IPC 复用，而是自己构造请求。  
它当前发送的是：

```json
{
  "messages": [],
  "model": "xxx",
  "temperature": 0.7,
  "max_tokens": 1000,
  "stream": true,
  "messageId": "msg_group_xxx"
}
```

这意味着当前桌面端对 `VCPToolBox` 的请求键名并不完全统一：

- 单聊使用 `requestId`
- 群聊使用 `messageId`

这是一条必须记录的兼容性风险。

### 3.4 流式响应

当前客户端假设流式响应是 SSE 风格：

- 每行前缀是 `data: `
- 结束标记是 `data: [DONE]`

客户端可兼容的 chunk 结构包括：

- `choices[0].delta.content`
- `delta.content`
- `content`
- `message.content`

也就是说 Android 端不能只写死一种 OpenAI 风格 delta 解析器。

### 3.5 中断接口

当前单聊中断：

- `POST /v1/interrupt`
- body 为 `{ "requestId": "<messageId>" }`

当前群聊中断：

- `POST /v1/interrupt`
- body 为 `{ "messageId": "<messageId>" }`

这是第二条必须记录的兼容性风险。

### 3.6 VCPLog

当前日志连接方式：

- WebSocket 地址：`${vcpLogUrl}/VCPlog/VCP_Key=${vcpLogKey}`

Android 若要支持日志流，需完全兼容这个地址构造。

---

## 4. 当前客户端真实职责

### 4.1 设置与配置层

当前客户端负责：

- 读写 `settings.json`
- Agent 配置读写
- Agent 头像管理
- Topic 顺序、锁定、未读状态维护
- Group 配置和头像管理

### 4.2 历史与本地状态层

当前客户端负责：

- 读写单聊 `history.json`
- 读写群聊 `history.json`
- 搜索历史内容
- 自动话题命名
- 打开最后一次的 item/topic

### 4.3 附件层

当前客户端负责：

- 文件导入
- 文件去重存储
- MIME 判断
- txt 读取
- pdf 文本抽取
- 扫描版 pdf 转图片
- docx 文本抽取
- 读取文件 Base64

当前附件在进入模型前的处理方式还包括：

- 图片转 `data:image/...;base64,...`
- 音频转 `data:audio/...;base64,...`
- 视频转 `data:video/...;base64,...`
- 这些媒体当前都被塞进多模态 `image_url` 结构中

这不是理想设计，但 Android 版如果要兼容现有后端行为，就必须照实记录。

### 4.4 请求编排层

当前客户端在发请求前会做这些处理：

- 把用户输入和附件文本抽取结果拼起来
- 对图片/音频/视频做多模态 message 组装
- 对当前 topic 注入创建时间
- 对当前 history 注入本地 history 文件路径
- 可选注入音乐播放列表与控制器权限
- 可选注入 `{{VarDivRender}}`
- 可选进行思维链剥离
- 可选进行 context sanitizer
- 可选进行 context folding
- 应用 agent 的 regex 规则
- 处理 `{{VCPChatCanvas}}` 占位符

因此 Android 客户端真正需要迁的是“消息编译器”，不是单纯的文本输入框。

### 4.5 流式层

当前客户端负责：

- SSE 逐行解析
- thinking / start / data / end / error 事件流管理
- 流式气泡初始化与完成
- 流结束后的 history 回写
- 中途异常或中断后的 partial content 落盘

### 4.6 群聊层

当前客户端负责：

- 群组配置
- 群聊上下文拼装
- 成员选择
- sequential / naturerandom / invite_only
- 邀请发言
- 重新生成某个群消息
- 群聊 topic 总结
- 本地 group history 作为事实来源

这部分不是“仅仅调用后端群聊接口”。  
目前大量群聊逻辑就在客户端。

---

## 5. Android 端必须迁移的能力

### 5.1 P1 必须具备

- 读取设置
- 连接现有 `vcpServerUrl`
- Agent 列表与 Topic 列表
- 单聊 history 读写
- 文本发送
- SSE 流式解析
- 中断请求
- 基础图片附件
- 基础消息渲染

### 5.2 P2 必须补齐

- `enableVcpToolInjection`
- 模型参数透传
- txt/pdf/docx 抽取
- 思维链剥离
- context sanitizer
- context folding
- regex 规则
- VCPLog

### 5.3 P3 再迁

- Group 配置与 Group Topic
- sequential / naturerandom / invite_only
- 邀请发言
- redo message
- 群聊 history
- 群聊 topic 总结

### 5.4 P4 评估迁移

- Notes
- Forum
- Memo
- Music
- Canvas
- Voice Chat
- Assistant Listener
- 其他桌面附属模块

---

## 6. 明确可以先不迁的桌面专属能力

以下内容不应该阻塞 Android 主链路：

- Electron 主进程窗口体系
- 托盘
- 桌面悬浮窗
- 全局键鼠监听
- 桌面路径探测逻辑
- 大量桌面专用 HTML/DIV/Canvas 壳层
- VCPHumanToolBox 相关桌面耦合能力

---

## 7. 已识别的兼容性风险

### 7.1 请求 ID 字段不统一

- 单聊发 `requestId`
- 群聊发 `messageId`
- 单聊中断也发 `requestId`
- 群聊中断发 `messageId`

Android 版不能想当然地统一成一种，至少要先验证 `VCPToolBox` 当前的容忍度。

### 7.2 多模态字段语义不标准

当前音频和视频也被编码进 `image_url` 风格对象。  
如果 Android 端“按标准重写”，很可能反而和现有后端不兼容。

### 7.3 客户端注入了本地路径

当前桌面端会把本地 `history.json` 路径注入给 Agent。  
Android 没有同样的路径语义，也不应该暴露系统内部真实路径格式。

这部分后续要做兼容策略：

- 保留“有历史文件路径”这一语义
- 但不能机械复刻 Windows 路径样式

### 7.4 群聊逻辑高度客户端化

群聊现在不是后端原生产品能力，而是大量依赖客户端本地编排。  
这意味着群聊不能被简单视为“后端现成接口，客户端只调一下”。

### 7.5 桌面渲染能力过强

桌面端支持大量富渲染、动画、HTML、DIV、Canvas、自定义交互。  
Android 首版不应承诺与桌面完全等价。

---

## 8. 推荐的第一条落地链路

最合理的第一条链路是：

1. 导入或读取现有 Agent 配置
2. 打开 Topic
3. 读取历史
4. 发送文本
5. 收流式回复
6. 中断
7. 保存历史

只有这条链路稳定之后，再逐步接入：

- 图片附件
- 文档抽取
- context sanitizer / folding
- 群聊
- 其他高级模块

---

## 9. 当前结论

Android 版如果想真正成为“VCPChat 应用化”，至少要接受这三个前提：

1. 现有 `VCPChat` 不是纯 UI 壳
2. 不改 `VCPToolBox` 就意味着很多逻辑仍在客户端
3. 迁移顺序必须从兼容单聊主链路开始，而不是从桌面全功能复刻开始

本文作为后续 Android 设计、模块拆分和范围裁剪的第一份迁移底表。
