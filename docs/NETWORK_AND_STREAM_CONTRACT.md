# 网络与流式契约

更新时间：2026-03-25

本文定义的是当前 `VCPChat` 客户端与 `VCPToolBox` 协作时，已经被代码依赖的兼容行为。

重点不是“理想接口该怎么设计”，而是“Android 版必须先兼容什么”。

---

## 1. 地址与配置

### 1.1 主聊天地址

主聊天配置来自：

- `settings.vcpServerUrl`
- `settings.vcpApiKey`

客户端默认行为：

- 对 `vcpServerUrl` 发起 `POST`
- Header 带 `Authorization: Bearer <vcpApiKey>`

### 1.2 VCP Tool Injection

如果：

```json
{
  "enableVcpToolInjection": true
}
```

则客户端会把主聊天路径改成：

```text
/v1/chatvcp/completions
```

这一步是客户端侧路径切换，不是后端协商。

### 1.3 VCPLog

日志 WebSocket 地址构造方式为：

```text
${vcpLogUrl}/VCPlog/VCP_Key=${vcpLogKey}
```

Android 若支持日志流，应完全兼容这条 URL 规则。

---

## 2. 单聊请求契约

### 2.1 基础请求体

当前单聊请求体的核心结构：

```json
{
  "messages": [],
  "model": "gemini-pro",
  "temperature": 0.7,
  "max_tokens": 1000,
  "contextTokenLimit": 4000,
  "top_p": 0.95,
  "top_k": 40,
  "stream": true,
  "requestId": "msg_xxx"
}
```

说明：

- `requestId` 当前是单聊中断的关键关联字段
- `stream` 为 `true` 时，客户端进入 SSE 解析模式
- `messages` 最终是客户端编译后的模型输入，不是 UI 层原始历史

### 2.2 发送前客户端会做的编译步骤

单聊请求在发出前，会按配置进行这些变换：

1. 规范化消息 `content`
2. 拼入附件文本抽取结果
3. 把图片、音频、视频转成多模态 `content[]`
4. 注入当前话题创建时间
5. 注入当前历史文件路径语义
6. 可选注入音乐播放列表和 `点歌台{{VCPMusicController}}`
7. 可选注入 `输出规范要求：{{VarDivRender}}`
8. 可选剥离思维链
9. 可选执行 context sanitizer
10. 可选执行 context folding
11. 应用 Agent 正则规则

Android 版需要兼容的是这套“请求编译流程”，不是只兼容最后那个 HTTP POST。

---

## 3. 群聊请求契约

### 3.1 群聊不是单聊 IPC 的简单复用

当前群聊代码自己构造请求，并且客户端本地承担了大量群聊编排逻辑。

### 3.2 群聊请求体

当前群聊请求体核心结构：

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

### 3.3 关键差异

与单聊相比，群聊当前最重要的兼容差异有两条：

| 场景 | 单聊 | 群聊 |
| --- | --- | --- |
| 请求 ID 字段 | `requestId` | `messageId` |
| 中断 body 字段 | `requestId` | `messageId` |

这是现有行为，不是推荐设计。  
Android 版在接管群聊前必须先确认 `VCPToolBox` 当前对这两种写法的兼容度。

### 3.4 群聊上下文构造

群聊不是直接把历史原文传给后端，而是由客户端做这些工作：

- 给每条消息补发言者标记
- 将历史附件抽取文本拼回上下文
- 将图片/音频/视频再编码为多模态内容
- 将 `groupPrompt` 合入系统提示
- 将 `invitePrompt` 作为触发当前成员发言的最后一条用户消息
- 按 `sequential` / `naturerandom` / `invite_only` 选择成员

所以群聊迁移实质上是迁客户端编排器，不是迁一个按钮。

---

## 4. 多模态内容契约

### 4.1 当前发送格式

客户端发给模型的消息 `content` 可能是：

```json
[
  { "type": "text", "text": "用户文本" },
  { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } }
]
```

### 4.2 当前的非标准兼容点

当前桌面端还会把音频和视频也编码进 `image_url` 风格对象里：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:audio/mpeg;base64,..."
  }
}
```

以及：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:video/mp4;base64,..."
  }
}
```

这是一个明确的兼容性怪异点。

结论：

- Android 版在首阶段不能擅自“标准化重写”这部分
- 必须先以兼容现有后端为第一目标

---

## 5. SSE 流式响应契约

### 5.1 传输形式

客户端当前按 SSE 风格解析流：

- 按行读取
- 每个有效数据行以 `data: ` 开头
- 收到 `data: [DONE]` 视为显式结束

### 5.2 客户端可接受的 chunk 结构

客户端当前能从这些结构里取增量文本：

1. `choices[0].delta.content`
2. `delta.content`
3. `content`
4. `message.content`

这意味着 Android 的流解析器不能只支持一种 OpenAI 风格结构。

### 5.3 流结束

两种情况都要视为结束：

1. 收到 `[DONE]`
2. 流自然关闭

### 5.4 解析失败

当单个 chunk 不是合法 JSON 时，当前客户端不会立刻中止整个流，而是：

- 记录解析错误
- 仍然向上层抛一个带 `raw` 内容的错误块

Android 若要复制桌面体验，也应保留这种容错策略。

---

## 6. 客户端内部流事件

虽然 Android 不会复用 Electron IPC，但当前 UI 逻辑已经围绕这些事件概念组织：

| 事件 | 用途 |
| --- | --- |
| `agent_thinking` | 创建“思考中”占位气泡，主要用于群聊 |
| `start` | 正式开始流式输出，替换思考占位 |
| `data` | 增量 chunk |
| `end` | 完成当前消息 |
| `error` | 流错误或请求错误 |
| `full_response` | 非流式整包消息 |
| `no_ai_response` | 当前无需 AI 回复 |
| `remove_message` | 删除某条消息，常见于群聊 redo |

### 6.1 单聊与群聊的差异

当前行为大致是：

| 场景 | 常见事件 |
| --- | --- |
| 单聊流式 | `data` / `end` / `error` |
| 单聊非流式 | 直接返回 JSON 响应 |
| 群聊流式 | `agent_thinking` / `start` / `data` / `end` / `error` |
| 群聊非流式 | `agent_thinking` / `full_response` |

Android 端如果要统一内部状态机，建议直接围绕这组事件语义来建模。

---

## 7. 中断契约

### 7.1 单聊中断

当前单聊中断地址：

```text
POST /v1/interrupt
```

请求体：

```json
{
  "requestId": "msg_xxx"
}
```

### 7.2 群聊中断

当前群聊中断地址同样是：

```text
POST /v1/interrupt
```

但请求体为：

```json
{
  "messageId": "msg_group_xxx"
}
```

### 7.3 本地与远程双中断

桌面端当前中断行为不只是发远程接口，还包括：

- 本地 `AbortController.abort()`
- 远程 `/v1/interrupt`

Android 端也应保留“本地取消 + 远程中断”双路径，而不是只做其中之一。

---

## 8. 已确认的兼容性风险

### 8.1 ID 字段不统一

- 单聊请求用 `requestId`
- 群聊请求用 `messageId`
- 单聊中断用 `requestId`
- 群聊中断用 `messageId`

### 8.2 多模态实现并不标准

音频和视频也被塞进 `image_url` 风格结构，这不是通用规范行为。

### 8.3 客户端承担了大量网络前处理

如果 Android 端直接“按原始 history 发给后端”，效果会和桌面端不同。

### 8.4 群聊流事件比单聊复杂

群聊除了 `data/end/error`，还依赖 `agent_thinking`、`start`、`full_response`、`remove_message` 等事件语义。

---

## 9. Android 端建议

建议 Android 实现拆成三层：

1. 兼容请求编译层
   负责把本地 history 编译成发送给模型的 `messages`

2. 兼容流解析层
   负责容忍桌面端当前支持的多种 SSE chunk 结构

3. UI 状态机层
   负责把网络和本地状态转换成消息气泡生命周期

这样能避免：

- 网络协议细节散落在 UI 组件里
- 单聊和群聊逻辑重复分叉
- 后续一改兼容策略就要大改页面代码

---

## 10. 当前结论

Android 首版真正要兼容的不是“一个聊天接口”，而是：

- 一套请求编译规则
- 一套 SSE 容错规则
- 一套中断规则
- 一套单聊/群聊不同步的历史包袱

这四样冻结之前，不适合直接开始写客户端骨架。
