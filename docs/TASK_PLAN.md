# 任务计划书

更新时间：2026-03-25

本文不是高层愿景，而是按当前路线可直接执行的计划书。  
前提固定为：

- 目标是 `VCPChat` Android 应用化
- `/root/VCPToolBox` 不动
- 先冻结文档和兼容契约，再开始写实现
- 默认迁移 `/root/VCPChat` 现有代码，而不是先重写一个新客户端

---

## 1. 总目标

在 `/root/VCPNative` 中完成一个 Android 客户端，使其能够：

1. 兼容现有 `VCPToolBox`
2. 承接现有 `VCPChat` 的单聊主链路
3. 后续逐步接回客户端兼容逻辑和群聊逻辑

---

## 2. 工作流总览

整个任务拆成 8 个工作流：

1. 文档冻结
2. Android 数据层设计
3. Android 网络与请求编译层
4. Android 流状态机与消息引擎
5. Android 单聊 UI
6. 兼容性增强
7. 群聊
8. 高级模块评估

---

## 3. W0 文档冻结

### 目标

让后续实现不再反复回到“到底要做什么”的讨论。

### 当前交付物

- `REBUILD_PLAN.md`
- `VCPCHAT_MIGRATION_MAP.md`
- `APPDATA_SCHEMA.md`
- `NETWORK_AND_STREAM_CONTRACT.md`
- `DESKTOP_TO_ANDROID_FEATURE_MAP.md`
- `ANDROID_DOMAIN_MODEL.md`
- `ANDROID_DATA_LAYER_DESIGN.md`
- `APPDATA_IMPORT_EXPORT_STRATEGY.md`
- `ANDROID_INFORMATION_ARCHITECTURE.md`
- `ANDROID_SKELETON_SELECTION.md`
- `ANDROID_SINGLE_CHAT_REQUEST_PIPELINE.md`
- `ANDROID_STREAM_STATE_MACHINE.md`
- `ANDROID_PHASE_SCOPE.md`
- `TASK_PLAN.md`
- `DECISION_REGISTER.md`

### 完成标准

- 能不看桌面源码，也知道单聊主链路要兼容什么
- 能明确区分首版必做与后置项
- 能明确区分桌面专属能力与 Android 主线能力
- 能明确 Android 启动流、页面层级与状态边界
- 能明确 Android 工程应该从什么骨架起步
- 能明确本地数据默认放在应用私有目录
- 能明确单聊请求从历史到 request body 的编译顺序
- 能明确流式消息如何建草稿、收 chunk、收尾和落盘
- 能明确 `Room / DataStore / files` 的最终分工
- 能明确桌面 `AppData` 如何导入 Android、再如何导出回去

### 当前状态

`已完成，文档冻结与骨架选型已达到 Android 工程开工门槛`

---

## 4. W1 Android 数据层设计

### 目标

把桌面端 `AppData` 语义映射到 Android 内部模型和存储方案。

### 任务

1. 固化 Room / DataStore / 文件缓存职责分工
   运行时根目录已固定为应用私有目录，当前只剩分层细化
2. 定义 Agent / Topic / Message / Attachment / Group 的持久化表结构
3. 定义桌面 `AppData` 导入语义
4. 定义 Android 内部导出语义

### 交付物

- 数据层设计文档
- 表结构文档
- 导入导出策略文档
- `ANDROID_DATA_LAYER_DESIGN.md`
- `APPDATA_IMPORT_EXPORT_STRATEGY.md`

### 进入条件

- W0 基本文档冻结

### 完成标准

- 能回答“某个桌面字段在 Android 存哪”
- 能回答“history 和 attachment 如何索引”
- 能回答“未来如何导入桌面端数据”
- 能回答“为什么运行时不直接维护一套 AppData 镜像”

---

## 5. W2 Android 网络与请求编译层

### 目标

把桌面端“消息编译器”在 Android 上落出来。

### 任务

1. 定义发送前的消息编译流程
2. 兼容 `enableVcpToolInjection`
3. 兼容单聊 `requestId`
4. 兼容群聊 `messageId`
5. 兼容 `/v1/interrupt`
6. 兼容 SSE 行流解析
7. 兼容 VCPLog URL 构造

### 交付物

- 请求编译层设计文档
- 流解析层设计文档
- 中断策略文档
- `ANDROID_SINGLE_CHAT_REQUEST_PIPELINE.md`

### 依赖

- W1 的领域模型和持久化边界

### 完成标准

- 能明确把历史消息编译成模型输入
- 能明确单聊/群聊 ID 差异如何处理
- 能明确 SSE 如何转成内部 `StreamEvent`

---

## 6. W3 Android 流状态机与消息引擎

### 目标

让 Android 端在不依赖 Electron 的前提下，拥有可替代桌面端的消息生命周期控制。

### 任务

1. 定义 thinking/start/data/end/error/full_response 状态机
2. 定义消息落盘时机
3. 定义中断后 partial content 处理
4. 定义后台恢复和页面重建后的消息一致性策略

### 交付物

- 流状态机文档
- 消息保存策略文档
- 错误与恢复策略文档
- `ANDROID_STREAM_STATE_MACHINE.md`

### 完成标准

- 流中切页面不会丢状态
- 中断不会导致历史损坏
- 非流式与流式都能统一落到同一消息模型

---

## 7. W4 Android 单聊 UI

### 目标

交付 Android 首版单聊 MVP。

这里的默认策略不是“先重写一套新的聊天页”，而是：

- 先用原生 Compose 壳把页面、导航和生命周期接住
- 先复制 `VCPChat` 的请求编译、流状态机和本地数据语义
- 对强绑定 DOM / Electron 的桌面 UI 按行为在 Android 上重建

### 任务

1. 设置页
2. Agent 列表页
3. Topic 列表页
4. 聊天页
5. 输入框和发送区
6. 基础图片附件
7. 基础消息渲染
8. 错误提示与重试入口

### 交付物

- 可运行单聊客户端

### 完成标准

1. 能选 Agent 和 Topic
2. 能发送文本
3. 能收流式回复
4. 能中断
5. 能保存并恢复历史

---

## 8. W5 兼容性增强

### 目标

把 Android 端从“能用”拉到“主要行为接近桌面端”。

### 任务

1. 正则规则
2. 思维链剥离
3. context sanitizer
4. context folding
5. txt/pdf/docx 抽取
6. 多模态兼容
7. VCPLog
8. 话题自动总结
9. 话题锁定/未读/排序

### 完成标准

- 单聊主链路与桌面端主要行为差距显著缩小
- 主观使用体验不再像“阉割版壳子”

---

## 9. W6 群聊

### 目标

迁回当前桌面端的重要群聊能力。

### 任务

1. Group 数据层
2. Group UI
3. sequential
4. naturerandom
5. invite_only
6. 邀请发言
7. redo group message
8. 群聊流状态机
9. 群聊 topic 总结

### 风险

- 群聊是当前客户端本地编排能力，不是简单 API 页面
- 单聊/群聊请求 ID 不统一
- 群聊事件比单聊复杂

### 完成标准

- 群聊至少能稳定跑 `sequential`
- 再补 `naturerandom`
- 再补 `invite_only`

---

## 10. W7 高级模块评估

### 目标

不是“全搬”，而是“按价值选择”。

### 候选

- Flowlock
- Notes
- Forum
- Memo
- Canvas
- Voice Chat
- Translator

### 不在当前主线中的模块

- Desktopmodules
- VchatManager
- VCPHumanToolBox
- 桌面托盘/悬浮窗/全局监听器
- 大量桌面宿主玩法

### 完成标准

- 每个模块都有迁/不迁结论
- 进入实现的模块都有独立范围说明

---

## 11. 近期执行顺序

如果继续按“先文档后代码”的路线，建议最近几步按这个顺序走：

1. 起 Android 工程骨架
2. 先接 DataStore + Room + 私有文件目录最小实现
3. 先跑通设置、Agent、Topic、History 最小链路
4. 再接单聊请求编译层
5. 在实施过程中逐模块判断“直接抄 / bridge / 局部改写”

---

## 12. 阶段门槛

### 12.1 开始写 Android 代码前

必须满足：

1. 文档集足够说明主链路
2. 已接受首版只做单聊
3. 已接受大量逻辑短期仍在客户端
4. 已接受桌面高级渲染不会首版复刻

### 12.2 进入群聊前

必须满足：

1. 单聊主链路稳定
2. 兼容性增强完成大半
3. 请求编译和流状态机没有结构性返工风险

### 12.3 进入高级模块前

必须满足：

1. 单聊稳定
2. 群聊稳定
3. 数据层和流层已被证明能支撑更复杂功能

---

## 13. 风险清单

### 高风险

1. 把 Android 误做成“只显示页面的前端”
2. 低估客户端请求编译逻辑
3. 低估群聊本地编排复杂度
4. 在没有冻结契约前就开始写骨架
5. 因为“想更原生”而提前推翻现有可复用代码

### 中风险

1. 多模态兼容行为和桌面端不一致
2. 中断接口单聊/群聊键名不一致
3. 过早追求桌面级复杂渲染

### 低风险

1. 文档过多

相比返工，这个风险可以接受。

---

## 14. 当前结论

当前最合理的推进方式是：

1. 以已经冻结的宿主策略、数据层、信息架构、请求编译链和流状态机为基线
2. 开始 Android 工程
3. 先落最小单聊数据闭环
4. 代码级“能抄什么”在实施时逐模块判断

这份计划书本质上是在保护后续实现节奏，避免项目再次滑回“边写边改方向”的状态。
