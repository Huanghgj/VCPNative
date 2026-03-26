# Android Chat Renderer Handoff

日期：2026-03-25

## 当前状态

- Android 原生单聊主链路已经接到真实 VCPToolBox HTTP、SSE、`/v1/interrupt`。
- request compiler 已按 VCPChat 语义迁移过一版，包含 regex rules、history 兼容处理、附件 `content[]` 组装。
- 附件导入、Room 持久化、导出 ZIP 主链路都已完成，之前已验证 `:app:assembleDebug` 可通过。
- 当前优先级切到聊天渲染：先把 Android 端的流式可读性和最终态渲染拉到可用，再继续补其余业务语义。

## 本轮目标

- 先做聊天，不继续展开文档/导入语义。
- Android 端补一个临时可维护的 VCPChat 对齐渲染层：
  - 区分 `streaming` 和 `final` 两条预处理路径。
  - 优先迁移 VCPChat 的轻量预处理和特殊块语义。
  - 在 Compose 里直接渲染结构化块，不照搬桌面的 DOM/morphdom 实现。

## 迁移参考

- `/root/VCPChat/modules/renderer/contentProcessor.js`
- `/root/VCPChat/modules/renderer/streamManager.js`
- `/root/VCPChat/modules/messageRenderer.js`

## 本轮收口范围

- 流式阶段：
  - 以“累计全文”持续重渲染，而不是只看最新 delta。
  - 轻预处理对齐 VCPChat：代码围栏换行、错误缩进修正、speaker tag 清理等。
- 完成阶段：
  - 更完整的预处理。
  - Markdown 子集渲染：段落、标题、列表、引用、代码块、粗体、行内代码。
- VCP 特殊块：
  - `<<<[TOOL_REQUEST]>>>`
  - `[[VCP调用结果信息汇总: ... VCP调用结果结束]]`
  - `<<<DailyNoteStart>>>`
  - `[--- VCP元思考链 ---]`
  - `<think>...</think>`
  - `<<<[DESKTOP_PUSH]>>>`

## 暂不做

- Mermaid 真正渲染。
- HTML/CSS 注入执行。
- AI 生成 button 的交互执行。
- 远程图片真正加载与排版优化。

这些能力后面继续按 VCPChat 补，但不阻塞当前单聊主链路可用性。
