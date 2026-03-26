# VCPChat 应用化迁移方案

更新时间：2026-03-25

## 1. 目标重定义

本项目当前的正确目标，不是重做一套新的 `Android + 新后端` VCP 体系，而是：

- 把 `/root/VCPChat` 应用化为 Android 客户端
- 保持现有 `VCPChat -> VCPToolBox` 的协作模式
- 不改 `/root/VCPToolBox`
- 在 `/root/VCPNative` 中完成新的移动端实现与迁移文档沉淀

换句话说，`VCPNative` 的任务是做“VCPChat 安卓版”，不是做“替代 VCPToolBox 的全新后端项目”。

---

## 1.1 迁移优先原则

本项目从现在起明确采用：

- 迁移优先
- 抄代码优先
- 适配优先于重写

具体含义是：

1. 默认从 `/root/VCPChat` 直接搬已有代码和已有行为
2. 能抄的先抄，不能抄的再适配
3. 只有在宿主差异导致根本跑不起来时，才局部重写
4. 不为“架构更漂亮”而主动重构
5. 不为“技术更纯”而主动改成另一套新系统

因此，本项目不是“设计一个更先进的新客户端”，而是“把现有 VCPChat 搬进 Android 应用壳里”。

---

## 2. 硬约束

本阶段所有设计都必须遵守以下约束：

1. 不动 `/root/VCPToolBox`
2. 不重新设计一套要求后端配合升级的新协议
3. 不把 `VCPToolBox` 的职责整体搬进 `VCPNative/backend`
4. Android 首版优先保证现有工作流兼容，不追求桌面端一次性全量复刻
5. 所有迁移优先级，以“现有 VCPChat 用户是否还能继续使用同一套后端和同一套 Agent 数据”为准
6. 默认优先复用 `/root/VCPChat` 的现有代码，而不是先写一份新的 Android 版本

---

## 3. 现状判断

从 `/root/VCPChat` 当前代码看，VCPChat 并不只是一个“显示页面的壳”，而是一个带有大量客户端逻辑的 Electron 桌面客户端。

当前已经确认的事实：

- `VCPChat` 本体是 Electron 应用
- `VCPToolBox` 当前被当作既有后端能力中心
- `VCPChat` 本地维护自己的 `AppData`
- 客户端本身承担了很多本来容易被误认为“后端才该做”的逻辑

因此，Android 版不是简单的“把页面改成 Compose”，而是：

- 给现有 VCPChat 找一个 Android 宿主
- 迁移并复用大量现有客户端逻辑
- 继续兼容现有 `VCPToolBox` 接口和事件行为

这里已经预设一条硬约束：Android 宿主壳必须是原生应用。  
但这不等于所有 `VCPChat` 内部代码都要先改写成原生。对于可复用逻辑，仍然优先走迁移路线，而不是为了“更原生”先整体推倒。

---

## 4. 当前客户端实际职责

现有 `VCPChat` 客户端已经承担这些职责：

- 本地设置管理
- Agent 配置读写
- Topic 创建、删除、排序、锁定、未读状态维护
- 聊天历史本地落盘
- Group 配置和群聊本地历史管理
- 附件去重存储
- 附件文本抽取
- 图片、音频、视频的 Base64 化与多模态拼装
- 请求发送前的 system prompt 注入
- 正则规则清洗
- 思维链剥离
- context sanitizer
- context folding
- 群聊编排、邀请发言、重做发言
- SSE 流解析
- 流结束后的本地历史回写
- VCPLog WebSocket 连接

这意味着：

- Android 版必须迁移一大块“客户端业务层”
- 不能把移动端理解成纯展示层
- 在不改 `VCPToolBox` 的前提下，这些逻辑短期内仍然要保留在客户端

---

## 5. 新的架构方向

新的方向应当是：

```text
/root/VCPNative
├── android-app/   # 原生 Android 宿主壳 + VCPChat 迁移代码 + Android bridge
├── shared/        # 协议说明、样例数据、迁移映射、兼容性文档
├── docs/          # 重构基线、阶段规划、风险记录
└── backend/       # 预留目录；本阶段不承担替代 VCPToolBox 的职责
```

新的系统关系不是：

- Android App + 新后端 + 抛弃现有 VCPToolBox

而是：

- Android App（新客户端）
- VCPToolBox（既有后端，保持不动）
- 桌面端 VCPChat（迁移参考和兼容性基准）

---

## 6. 客户端与后端边界

### 6.1 本阶段后端继续负责

- 模型接入
- 工具调用执行
- 异步任务
- 后端推送与日志流
- VCP 协议生态能力
- 既有服务端能力与插件生态

### 6.2 Android 客户端必须负责

- 页面与交互
- 本地设置与本地数据库
- Agent / Topic / Group 本地配置管理
- 聊天历史本地保存
- 附件导入与本地缓存
- 与现有 VCPToolBox 兼容的请求构造
- 流式消息展示
- 本地上下文预处理逻辑
- Android 原生能力接入

### 6.3 暂时不应假设后端会接手的内容

在 `VCPToolBox` 不变的前提下，以下内容不能先验地认为会自动下沉到后端：

- context sanitizer
- context folding
- 本地附件文本抽取
- 群聊编排
- Agent 侧 system prompt 注入
- Topic 本地状态管理

---

## 7. 实施策略

正确的推进顺序应当是：

1. 先冻结现有 `VCPChat` 的兼容契约
2. 再定义 Android 端本地数据模型
3. 先做单聊 MVP
4. 在实施过程中逐模块判断哪些直接抄、哪些必须适配、哪些明确不搬
5. 再逐步补齐群聊和高级模块

不应该做的事情：

- 先起一个新后端再倒逼客户端适配
- 假设 `VCPToolBox` 会立刻配合改接口
- 直接追求桌面端一次性功能对齐
- 把 Electron 桌面特性也原样搬进移动端
- 因为“想要更原生”就先把现有可复用代码全部推倒重写

---

## 8. 阶段拆分

### 8.1 P0：契约冻结

目标：

- 固化现有 VCPChat 的本地数据结构
- 固化现有聊天请求格式
- 固化流式事件格式
- 固化 Group 模式的本地编排逻辑
- 固化迁移原则与宿主约束

产出：

- 迁移映射文档
- 兼容性风险清单
- Android 首版功能边界
- 决策清单

### 8.2 P1：单聊 MVP

首版只做最小闭环：

- 全局设置
- Agent 列表
- Topic 列表
- 读取和保存单聊历史
- 文本发送
- 流式回复
- 中断回复
- 基础图片附件
- 基础消息渲染

P1 的目标不是桌面端等价，而是先拿到“可用的 VCPChat 安卓主链路”。

P1 的实现方式默认应当是：

- 优先迁移现有 `VCPChat` 的业务逻辑和兼容逻辑
- 用原生 Android Compose 宿主壳承接页面、导航和生命周期
- 对强绑定 DOM / Electron 的部分按行为重建，而不是继续桥接桌面前端

### 8.3 P2：兼容性增强

在单聊闭环稳定后补齐：

- 更多模型参数兼容
- `enableVcpToolInjection` 对应的 URL 切换
- 正则规则
- 思维链剥离
- context sanitizer
- context folding
- txt/pdf/docx 等附件文本抽取
- VCPLog

### 8.4 P3：群聊迁移

再接入：

- Group 配置
- Group Topic
- 群聊历史
- sequential / naturerandom / invite_only
- invite / redo
- 群聊话题总结

### 8.5 P4：高级模块筛选迁移

最后再评估是否迁移：

- Notes
- Forum
- Memo
- Music
- Canvas
- Voice Chat
- Assistant Listener
- 其他桌面附属模块

---

## 9. 明确不进入首版的内容

以下内容不应进入 Android 首版范围：

- Electron 窗口体系
- 桌面托盘
- 桌面悬浮窗
- 全局键盘监听
- 桌面文件监听器行为照搬
- Desktop Push 类功能
- VCPHumanToolBox
- 大量仅桌面可用的宿主能力
- 所有“先移植再裁剪”的重壳层思路

---

## 10. 数据迁移原则

Android 版要尽量保持对现有 `VCPChat/AppData` 的兼容理解能力。

原则如下：

1. 以桌面端现有 `AppData` 结构为导入基准
2. Android 内部存储可以重构，但导入导出语义尽量兼容
3. Agent、Topic、History、Group、Attachments 的主字段不要随意改名
4. 不依赖后端来替客户端补齐缺失的本地状态

---

## 11. 当前结论

这次工作不是“做一个新 VCPNative 系统”，而是：

1. 以 `/root/VCPChat` 为功能母体
2. 以 `/root/VCPToolBox` 为现成后端
3. 在 `/root/VCPNative` 中落一个原生 Android 客户端

下一步的正确动作是：

1. 固化兼容契约
2. 列出 Android 必须迁移的客户端职责
3. 先做单聊主链路
4. 再逐步回收桌面端高级能力

本文件从现在起作为 `/root/VCPNative` 的第一份正确基线文档，后续迁移与选型都应以“VCPChat 应用化、VCPToolBox 不动”为前提。
