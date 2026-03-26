# 桌面到 Android 功能对照表

更新时间：2026-03-25

本文把当前桌面版 `VCPChat` 的能力拆成可迁移清单。  
目标不是“列功能大全”，而是明确每项能力在 Android 版里的命运：

- `P1`
  首版必迁
- `P2`
  单聊稳定后补齐
- `P3`
  群聊阶段迁移
- `P4`
  高级模块按需评估
- `Redesign`
  Android 端需要重设计，不适合照搬
- `Drop`
  不进入 Android 路线

执行默认规则再补一条：

- 默认先 `Copy`
- `Copy` 不通时再 `Adapt`
- 只有前两者都不成立时才 `Redesign`

---

## 1. 核心主链路

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| 全局设置 | `settings.json` + 设置界面 | 保留 | `P1` | 先做最小设置集 |
| 连接 VCP 服务 | `vcpServerUrl` + `vcpApiKey` | 保留 | `P1` | 完整兼容 |
| Agent 列表 | 本地 `Agents/<id>/config.json` | 保留 | `P1` | 完整兼容 |
| Topic 列表 | Agent config + 本地 history | 保留 | `P1` | 完整兼容 |
| 打开上次会话 | `lastOpenItem*` | 保留 | `P1` | 完整兼容 |
| 单聊 history 读写 | `UserData/<agent>/topics/<topic>/history.json` | 保留 | `P1` | 语义兼容，存储可重构 |
| 文本发送 | 编译后发给 `VCPToolBox` | 保留 | `P1` | 完整兼容 |
| SSE 流式回复 | `data:` 行流 | 保留 | `P1` | 完整兼容 |
| 中断回复 | 本地 abort + `/v1/interrupt` | 保留 | `P1` | 完整兼容 |
| 基础 Markdown 渲染 | 聊天气泡基础能力 | 保留 | `P1` | 首版只做基础子集 |
| 基础错误态 | 请求错误/流错误/中断提示 | 保留 | `P1` | 完整兼容 |

---

## 2. Agent / Topic / History 管理

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| 创建 Agent | 本地生成目录和默认 config | 保留 | `P2` | 优先兼容已有字段 |
| 删除 Agent | 删除 Agent 和 UserData | 保留 | `P2` | 完整兼容 |
| Agent 排序 | `agentOrder` | 保留 | `P2` | 完整兼容 |
| Topic 创建 | 写入 Agent config + 初始化 history | 保留 | `P1` | 完整兼容 |
| Topic 删除 | 修改 config + 删除 topic history | 保留 | `P2` | 完整兼容 |
| Topic 重命名 | 修改 config | 保留 | `P2` | 完整兼容 |
| Topic 排序 | 修改 config 中 topics 顺序 | 保留 | `P2` | 完整兼容 |
| Topic 锁定 | `locked` 字段 | 保留 | `P2` | 完整兼容 |
| Topic 未读 | `unread` 字段 | 保留 | `P2` | 完整兼容 |
| 自动话题总结 | 基于消息生成标题 | 保留 | `P2` | 可先单聊后群聊 |
| 聊天分支 | 基于现有对话开新 topic | 保留 | `P2` | UI 可简化 |
| 差分历史监听 | 文件变化驱动 UI 差分更新 | `Redesign` | `P4` | Android 不照搬文件监听器模式 |

---

## 3. 请求编译与上下文处理

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| `enableVcpToolInjection` | 切到 `/v1/chatvcp/completions` | 保留 | `P2` | 完整兼容 |
| system prompt 注入 | Agent prompt + 额外注入 | 保留 | `P2` | 完整兼容 |
| 话题创建时间注入 | 发请求前拼入系统上下文 | 保留 | `P2` | 保留语义 |
| history 路径注入 | 注入本地 history 文件路径 | `Redesign` | `P2` | 保留语义，不机械复刻桌面路径 |
| 音乐控制注入 | `点歌台{{VCPMusicController}}` | `P4` | `P4` | 先不进入主链路 |
| `{{VarDivRender}}` 注入 | 气泡主题输出规范 | 保留 | `P2` | 首版只兼容开关，不追全效果 |
| 思维链剥离 | `stripThoughtChains` | 保留 | `P2` | 完整兼容 |
| context sanitizer | HTML 转 Markdown 净化 | 保留 | `P2` | 完整兼容 |
| context folding | 历史压缩摘要 | 保留 | `P2` | 完整兼容 |
| Agent 正则规则 | context/frontend 双作用域 | 保留 | `P2` | 先做 context 侧，再补 frontend |
| Promptmodules | 复杂 prompt 模块系统 | `Redesign` | `P4` | 先保留基本 system prompt，后续再设计移动端交互 |

---

## 4. 附件与多模态

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| 图片附件 | 选择/粘贴/拖放/预览 | 保留 | `P1` | 首版先做图片 |
| 文本附件 | 粘贴长文本转 txt 文件 | 保留 | `P2` | 完整兼容 |
| 文件去重存储 | `sha256` + attachments 目录 | 保留 | `P2` | 完整兼容 |
| txt 提取 | 直接读取文本 | 保留 | `P2` | 完整兼容 |
| pdf 提取 | 文本抽取 + 扫描版转图片 | 保留 | `P2` | 先文本抽取，后补扫描版策略 |
| docx 提取 | `mammoth` | 保留 | `P2` | Android 需换实现 |
| 图片 Base64 化 | 转 data URL | 保留 | `P1` | 完整兼容 |
| 音频 Base64 化 | 当前塞进 `image_url` 风格结构 | 保留 | `P2` | 先兼容现有后端行为 |
| 视频 Base64 化 | 当前塞进 `image_url` 风格结构 | 保留 | `P2` | 先兼容现有后端行为 |
| 高级图片查看器 | 独立窗口预览 | `Redesign` | `P2` | 改成 Android Viewer/Activity |
| 拖放附件 | Electron 拖放 | `Redesign` | `P4` | Android 不照搬桌面拖放入口 |

---

## 5. 群聊

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| Group 列表 | `AgentGroups/<groupId>/config.json` | 保留 | `P3` | 完整兼容 |
| Group 配置 | 名称、头像、成员、模式等 | 保留 | `P3` | 完整兼容 |
| Group Topic | 独立话题与 history | 保留 | `P3` | 完整兼容 |
| sequential | 顺序发言 | 保留 | `P3` | 完整兼容 |
| naturerandom | 标签和自然触发 | 保留 | `P3` | 完整兼容 |
| invite_only | 用户邀请发言 | 保留 | `P3` | 完整兼容 |
| invitePrompt | 模板驱动发言邀请 | 保留 | `P3` | 完整兼容 |
| unifiedModel | 群统一模型开关 | 保留 | `P3` | 完整兼容 |
| redo group message | 删除并重发某个 AI 消息 | 保留 | `P3` | 完整兼容 |
| 群聊 topic 总结 | 自动重命名群话题 | 保留 | `P3` | 完整兼容 |
| 群文件/工作区 | README 定义很重 | `Redesign` | `P4` | 不能先验假设有现成移动端形态 |

---

## 6. 渲染与交互

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| 基础聊天气泡 | 用户/AI/系统消息 | 保留 | `P1` | 完整兼容 |
| 工具调用气泡 | 展示工具请求与结果 | 保留 | `P2` | 先做简化版 |
| 富文本渲染 | Markdown/KaTeX/Mermaid 等 | `Redesign` | `P2` | 首版做核心子集 |
| DIV 流式渲染 | 超复杂 HTML/DIV 动态气泡 | `Redesign` | `P4` | 不适合作为首版目标 |
| 高级阅读模式 | 独立阅读器 + 代码块工具 | `Redesign` | `P4` | 移动端需重新设计 |
| 气泡评论 | 右键添加评论 | `Redesign` | `P4` | 改成长按菜单后再评估 |
| 转发消息 | 右键转发到其他会话 | 保留 | `P4` | Android 可以做，但不阻塞主链路 |
| 收藏到笔记 | 保存到 Notes | `P4` | `P4` | 依赖 Notes 模块 |
| AI 气泡按钮交互 | HTML 内按钮回调 | `Redesign` | `P4` | 需要明确安全模型 |
| 表情包 URL 修复器 | URL 模糊修复 | 保留 | `P2` | 可作为渲染兼容层 |

---

## 7. 桌面扩展与宿主能力

| 桌面能力 | 当前形态 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| Desktopmodules | 桌面画布窗口 + `DESKTOP_PUSH` | `Drop` | `Drop` | 不进入 Android 主线 |
| 托盘 | Electron tray | `Drop` | `Drop` | 不迁移 |
| 多窗口体系 | Electron BrowserWindow | `Redesign` | `P4` | Android 按页面/Activity 重建 |
| 全局键盘监听 | Electron/桌面能力 | `Drop` | `Drop` | 不迁移 |
| 文件系统监听器 | 本地文件变化驱动 UI | `Redesign` | `P4` | Android 不照搬 |
| VchatManager | 直接编辑本地数据的一致性工具 | `Drop` | `Drop` | Android 不需要同形态工具 |

---

## 8. 高级模块

| 模块 | 当前定位 | Android 目标 | 阶段 | 策略 |
| --- | --- | --- | --- | --- |
| Flowlock | 自动续写循环 | 保留 | `P4` | 先等单聊和流状态机稳定 |
| Notes | 本地笔记树 | `P4` | `P4` | 可后置 |
| Forum | 论坛前端 | `P4` | `P4` | 可后置 |
| Memo | 记忆/工作台 | `P4` | `P4` | 可后置 |
| Music | 音乐播放器和音频引擎 | `Drop` | `Drop` | 不进入当前 Android 主线 |
| Voice Chat | 语音窗口与识别 | `P4` | `P4` | 移动端需重设计 |
| Canvas | 协同工作区 | `Redesign` | `P4` | 不适合作为首版目标 |
| Translator | 辅助窗口 | `P4` | `P4` | 可后置 |
| RAG Observer | 观察器窗口 | `Drop` | `Drop` | 不进入移动端主线 |
| VCPHumanToolBox | 人类工具箱/GUI 生成 | `Drop` | `Drop` | 当前路线不纳入 |

---

## 9. 当前结论

真正进入 Android 主线的，只有三类：

1. 单聊主链路
2. 客户端本地数据与请求编译能力
3. 后续需要单独迁移的群聊能力

其余大量桌面模块，不是“以后一定做”，而是“以后再判断值不值得做”。
