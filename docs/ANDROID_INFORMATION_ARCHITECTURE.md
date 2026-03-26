# Android 信息架构

更新时间：2026-03-25

本文回答的是：Android 版到底是一个什么形态的 App，入口在哪里，页面怎么走，状态放哪一层。

这里先明确一件事：

- Android App 在本项目里是一个客户端一体应用
- 所谓“前端”只指页面/UI 层
- 外部后端仍然只有 `/root/VCPToolBox`

也就是说，App 内部仍然会有页面层、客户端业务层、本地持久化层、网络兼容层，但不会在 App 内再拆出一个独立“后端项目”。

---

## 1. 信息架构基线

Android 首版的信息架构必须服从这几个前提：

1. 首版只做单聊主链路
2. 宿主壳必须是原生 Android
3. 默认先迁移现有 `VCPChat` 客户端逻辑，再做局部适配
4. 本地持久化默认使用应用私有目录
5. `/root/VCPToolBox` 不改

因此，首版信息架构不应该长成一个“多模块工具广场”，而应该长成一个围绕单聊工作区展开的聊天客户端。

---

## 2. App 总体结构

建议把 Android App 理解成四层：

```text
Android App
├── Native Host Shell
│   ├── 生命周期
│   ├── 导航
│   ├── 文件选择 / 分享入口
│   ├── 应用私有目录路径解析
│   ├── 前后台切换处理
│   └── Android 系统能力接入
├── Migrated VCPChat Compatibility Layer
│   ├── settings / agent / topic / history 语义
│   ├── 请求编译
│   ├── SSE 流解析
│   ├── interrupt 兼容
│   ├── 附件去重元数据
│   └── 后续的 sanitizer / folding / group orchestration
├── Data Layer
│   ├── 设置存储
│   ├── 实体持久化
│   ├── 附件文件存储
│   └── AppData 导入导出转换
└── UI Layer
    ├── 启动 / Bootstrap
    ├── 设置
    ├── Agent 列表
    ├── Topic 列表
    ├── 单聊页
    └── 附件查看
```

这四层里：

- Native Host Shell 负责 Android 原生能力
- Compatibility Layer 负责承接被迁过来的 `VCPChat` 行为语义
- Data Layer 负责把语义落到 Android 本地
- UI Layer 只负责展示、交互和状态订阅

不要让 UI 直接读写旧 JSON，也不要让 UI 直接拼网络请求。

---

## 3. 顶层导航结构

P1 建议采用单 Activity 原生壳，页面结构如下：

```text
App
├── Bootstrap
├── SetupGate
│   └── SettingsEditor
├── Workspace
│   ├── AgentList
│   ├── TopicList(itemId)
│   └── SingleChat(itemId, topicId)
├── Settings
└── AttachmentViewer(attachmentId)
```

P1 不建议一上来做底部 Tab 主导航，原因很简单：

- 当前首版主线不是多工具分发，而是单聊工作区
- Agent / Topic / Chat 本身就是一条连续工作流
- 底部 Tab 会把本来强相关的层级误做成平级模块

因此，P1 的顶层结构应该是：

1. 启动与校验
2. 工作区
3. 设置
4. 附件查看

而不是：

1. 首页
2. 聊天
3. 工具
4. 我的

---

## 4. 启动流

冷启动建议固定为下面这条路径：

1. 进入 `Bootstrap`
2. 解析应用私有目录
3. 读取本地设置
4. 校验 `vcpServerUrl` / `vcpApiKey`
5. 读取最近一次打开的 `itemId` / `topicId`
6. 决定后续入口

分流规则：

- 设置不完整：进入 `SetupGate -> SettingsEditor`
- 设置完整且最近会话有效：直接恢复到 `SingleChat`
- 设置完整但最近会话无效：进入 `AgentList`

这个启动流的重点是：

- 先恢复工作状态
- 不是先展示“门户首页”

Android 版首屏本质上是工作恢复页，不是内容分发页。

---

## 5. P1 页面层级

### 5.1 手机形态

手机上建议使用三级工作流：

1. Agent 列表
2. Topic 列表
3. 单聊页

说明：

- Agent 列表是工作区入口
- Topic 列表是某个 Agent 下的话题入口
- 单聊页是最终工作面
- 设置页从工作区头部入口进入
- 附件查看页以全屏页或模态页打开

### 5.2 大屏形态

大屏设备不改信息架构，只改布局形态：

- 左栏：Agent 列表
- 中栏：Topic 列表
- 右栏：单聊页

也就是说：

- 手机是分层跳转
- 大屏是同一状态的多栏展开

不要为大屏再设计另一套业务流。

---

## 6. 单聊主工作流

P1 的单聊主流转建议固定如下：

1. 用户进入 Agent 列表
2. 选择一个 Agent
3. 进入该 Agent 的 Topic 列表
4. 选择一个 Topic，或创建一个新 Topic
5. 进入单聊页并加载本地 `history`
6. 用户输入文本，必要时添加图片附件
7. 请求进入消息编译层
8. 发送到现有 `VCPToolBox`
9. SSE 流事件驱动聊天页更新
10. 流结束或中断后回写本地历史

中断流也要纳入同一页面模型：

1. 用户点击中断
2. 本地停止流消费
3. 远程发 `/v1/interrupt`
4. 当前 assistant 消息以 partial / interrupted 状态收尾
5. 历史落盘

因此，单聊页不是一个纯显示页面，而是：

- 输入
- 流状态机
- 消息落盘
- 中断控制

共同汇聚的工作面。

---

## 7. 状态归属

建议把状态按作用域分成五层。

### 7.1 App 级状态

负责：

- Bootstrap 状态
- 设置是否完整
- 最近打开对象
- 全局网络可用性
- 全局主题/显示设置

不负责：

- 某个会话的消息列表
- 某条消息的流式中间态

### 7.2 目录级状态

负责：

- Agent 列表
- Topic 列表
- 排序
- `locked` / `unread`
- 当前选中的 `itemId` / `topicId`

不负责：

- 消息发送中的 chunk 状态

### 7.3 会话级状态

负责：

- 当前会话消息列表
- 当前正在流式生成的消息
- 发送中 / 中断中 / 失败状态
- 输入框草稿
- 待发送附件

这是 P1 最核心的一层状态。

### 7.4 附件级状态

负责：

- 用户选中的原始 URI
- 拷入私有目录后的内部文件
- 转码 / Base64 化进度
- 预览状态

附件状态不能只挂在 UI 组件里，否则切页和重建时很容易丢失。

### 7.5 临时 UI 状态

只负责：

- 滚动位置
- 抽屉开关
- 上下文菜单
- 输入框是否展开

这类状态不应成为业务真相来源。

---

## 8. 原生宿主壳与迁移逻辑的边界

### 原生宿主壳负责

- 页面导航
- 生命周期
- 应用私有目录定位
- 文件选择器 / 分享 Intent
- 后台恢复
- 权限申请
- Android 级错误提示

### 迁移逻辑负责

- `settings.json` 语义
- Agent / Topic / History 读写语义
- 单聊请求体编译
- SSE 流事件解释
- interrupt 请求兼容
- 附件哈希与引用语义
- 后续的 sanitizer / folding / regex / group orchestration

### 边界原则

- 先替换 Electron 依赖，不先改业务语义
- 先通过 adapter / bridge 接住旧逻辑，不先抽象成一套新架构
- 只有跑不通的地方，才局部改写

---

## 9. P1 不进入主信息架构的页面

以下页面或模块不进入 P1 主信息架构：

- 群聊列表
- 群聊工作区
- Notes
- Forum
- Memo
- Music
- Canvas
- Voice Chat
- Desktopmodules

它们不是“首页隐藏入口”，而是当前阶段就不进主线。

---

## 10. 后续扩展位置

为了避免 P1 的信息架构以后推翻，建议提前预留两类扩展点：

### 10.1 P2 扩展

- 设置页补齐更多开关
- 附件页补齐 txt/pdf/docx
- 聊天页补齐复杂渲染、工具调用、VCPLog 入口

### 10.2 P3 扩展

- 在 `Workspace` 下增加 Group 入口
- Group 仍然走“列表 -> Topic -> Chat”同构路径
- 不另起一套完全不同的导航系统

---

## 11. 当前结论

Android 首版的信息架构已经可以先固定成下面这条线：

1. `Bootstrap`
2. `SetupGate / Settings`
3. `Workspace`
4. `AgentList -> TopicList -> SingleChat`
5. `AttachmentViewer`

它对应的是一个：

- 原生宿主壳
- 客户端一体应用
- 以单聊工作区为核心
- 先迁移语义、后逐步扩展模块

的 Android 版 `VCPChat`。
