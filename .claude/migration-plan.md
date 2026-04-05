# VCPChat → VCPNative 功能迁移清单

> 生成时间: 2026-03-28
> 对比基准: VCPChat 4.4.2 vs VCPNative (isolate/local-code-20260327)

---

## 已实现 (无需迁移)

以下功能 VCPNative 已完整实现，无需改动：

- 核心聊天 (发送/接收/SSE流式/中断)
- Agent CRUD + 配置 (model/temperature/topP/topK/maxTokens)
- Topic 管理 (创建/重命名/删除/分支)
- 消息编辑/删除/重试
- 附件 (导入/SHA256去重/PDF提取/Base64)
- Context Folding + Context Sanitizer + ThoughtChainStripper
- Topic 自动摘要
- Room 数据库 (5实体/10迁移)
- DataStore 设置
- 导入/导出 (zip/目录)
- VCPLog 双通道 WebSocket (通知/审批/RAG)
- 9个 HTML 模块 (Notes/Memo/Forum/Canvas/Translator/Dice/Themes/VoiceChat/RAGObserver)
- IPC Bridge (40+ channel)
- 正则规则
- Preset Prompt
- DOMPurify HTML 净化
- 硬件加速 WebView

---

## 可迁移功能 (按优先级排列)

### P0 — 高价值 + 低成本 (建议立即做)

#### 1. 消息渲染增强：Mermaid 图表
- **VCPChat**: `messageRenderer.js:99-146` 用 mermaid.js 渲染 ```mermaid 代码块
- **VCPNative 现状**: `vendor/mermaid.min.js` 已打包但未使用
- **迁移方案**: chat.html 的 `md()` 函数中检测 ```mermaid 代码块，调用 mermaid.render()
- **工作量**: 小 (30-50行JS)

#### 2. 消息渲染增强：KaTeX 数学公式
- **VCPChat**: `contentProcessor.js:799-806` 用 renderMathInElement 渲染 $...$ 和 $$...$$
- **VCPNative 现状**: `vendor/katex.min.js` + `vendor/auto-render.min.js` 已打包，ChatMessageRenderer.kt 有原生 KaTeX 但仅限 Compose 渲染
- **迁移方案**: chat.html 加载 katex + auto-render，在消息渲染后调用 renderMathInElement
- **工作量**: 小 (10-20行JS + CSS引入)

#### 3. 消息渲染增强：代码高亮
- **VCPChat**: `contentProcessor.js:816-827` 用 highlight.js
- **VCPNative 现状**: `vendor/highlight.min.js` 已打包，ChatMessageRenderer.kt 有原生高亮
- **迁移方案**: chat.html 的代码块渲染后调用 hljs.highlightElement()
- **工作量**: 小 (10-15行JS + CSS引入)

#### 4. CSS Scoping (防止 AI HTML 样式污染聊天页面)
- **VCPChat**: `contentProcessor.js:838-888` scopeCss() 给所有 CSS 选择器加唯一前缀
- **VCPNative 现状**: 无，AI 生成的 `<style>` 会影响整个页面
- **迁移方案**: processHtmlPreviews 中对 .html-render 内的 style 做 scoping
- **工作量**: 中 (50-80行JS)

#### 5. 全文搜索
- **VCPChat**: `searchManager.js` 用 flexsearch 做客户端全文搜索
- **VCPNative 现状**: Room 数据库有基础 SQL LIKE 查询
- **迁移方案**: Room FTS (Full-Text Search) 虚拟表，或 Kotlin 侧实现搜索 UI
- **工作量**: 中 (需要数据库迁移 + UI)

#### 6. 模型使用统计 / 热门模型 / 收藏模型
- **VCPChat**: `modelUsageTracker.js` 记录每个模型使用次数，`get-hot-models` / `toggle-favorite-model`
- **VCPNative 现状**: 仅展示模型列表，无统计/收藏
- **迁移方案**: DataStore 或 Room 存储使用计数和收藏标记
- **工作量**: 中

---

### P1 — 中等价值 (建议近期做)

#### 7. 语音输入 (Speech Recognition)
- **VCPChat**: `speechRecognizer.js` 用 Web Speech API
- **VCPNative 现状**: VoiceChat 模块存在但语音输入未集成到主聊天
- **迁移方案**: Android SpeechRecognizer API + Compose UI
- **工作量**: 中

#### 8. TTS 语音播报
- **VCPChat**: `SovitsTTS.js` 调用 Sovits TTS 服务
- **VCPNative 现状**: ChatRoute.kt 有 BubbleSpeechController 用 Android TextToSpeech
- **迁移方案**: 已有基础实现，可增强为支持 Sovits 远程 TTS
- **工作量**: 中

#### 9. 输入增强 (Input Enhancer)
- **VCPChat**: `inputEnhancer.js` 自动补全、快捷操作
- **VCPNative 现状**: 无
- **迁移方案**: Compose TextField 自定义行为
- **工作量**: 中

#### 10. 消息流式渲染优化 (morphdom)
- **VCPChat**: `streamManager.js` 用 morphdom 做增量 DOM 更新，避免闪烁
- **VCPNative 现状**: chat.html 直接 innerHTML 替换
- **迁移方案**: 引入 `vendor/morphdom.min.js`（已打包），流式更新时用 morphdom patch
- **工作量**: 中 (需要改 chat.html 的 updateMessage 逻辑)

#### 11. Emoji/表情包管理
- **VCPChat**: `emoticonManager.js` + `emoticonHandlers.js` 自定义表情
- **VCPNative 现状**: 无
- **迁移方案**: IPC handler + 文件存储 + UI 选择器
- **工作量**: 大

#### 12. 群聊 (Group Chat)
- **VCPChat**: `Groupmodules/groupchat.js` + `groupChatHandlers.js` 多 Agent 群聊
- **VCPNative 现状**: IPC stub 返回空，UI 未实现
- **迁移方案**: 需要新的数据模型 + 轮询调度 + UI
- **工作量**: 大

---

### P2 — 特色功能 (按需做)

#### 13. FlowLock 心流锁 (自动续写)
- **VCPChat**: `Flowlockmodules/flowlock.js` AI 自动连续生成
- **VCPNative 现状**: 无
- **迁移方案**: Kotlin 侧实现续写逻辑，HTML 模块已打包
- **工作量**: 中

#### 14. 音乐播放器 (Rust Audio Engine)
- **VCPChat**: 完整 HIFI 播放器，Rust 引擎，EQ/频谱/WebDAV
- **VCPNative 现状**: 无
- **迁移方案**: 用 Android MediaPlayer/ExoPlayer 实现基础版，或编译 Rust for Android
- **工作量**: 极大 (建议先不做)

#### 15. 桌面助手 (Rust Assistant Engine)
- **VCPChat**: 屏幕捕获、文本选择监听
- **VCPNative 现状**: 无
- **迁移方案**: Android Accessibility Service (完全不同的实现)
- **工作量**: 极大

#### 16. 主题/壁纸管理
- **VCPChat**: `Themesmodules/themes.html` 主题切换和壁纸
- **VCPNative 现状**: Themes 模块已打包，基础深色/浅色支持
- **迁移方案**: IPC handler 实现主题持久化
- **工作量**: 中

#### 17. WebDAV 文件管理
- **VCPChat**: `webdavManager.js` 远程文件同步
- **VCPNative 现状**: 无
- **迁移方案**: OkHttp WebDAV 客户端
- **工作量**: 大

#### 18. Pyodide (浏览器内 Python)
- **VCPChat**: `vendor/pyodide.js` 在浏览器执行 Python
- **VCPNative 现状**: `vendor/pyodide.js` 已打包但未使用
- **迁移方案**: 在 iframe 中加载 pyodide，执行 AI 生成的 Python 代码
- **工作量**: 中 (主要是内存管理)

---

### 不建议迁移 (Electron 专有)

| 功能 | 原因 |
|------|------|
| 系统托盘 | Android 无此概念 |
| 多窗口管理 | Android Activity 模型不同 |
| 文件监听 (chokidar) | 移动端文件系统不适合 |
| DevTools 打开 | WebView 无此功能 |
| 全局快捷键 | Android 无此概念 |
| 窗口最小化/最大化 | Android 无此概念 |

---

## 实施进度 (截至 2026-03-28)

| # | 功能 | 状态 | 提交 |
|---|------|------|------|
| 1 | KaTeX 数学公式 | ✅ 完成 | dc60222 |
| 2 | 代码高亮 highlight.js | ✅ 完成 | dc60222 |
| 3 | Mermaid 图表 | ✅ 完成 | dc60222 |
| 4 | CSS Scoping | ✅ 完成 | dc60222 |
| 5 | morphdom 流式渲染 | ✅ 完成 | dc60222 |
| 6 | 全文搜索 | ✅ 完成 | dc60222 (DAO + IPC) |
| 7 | 模型统计/热门/收藏 | ✅ 完成 | dc60222 (ModelUsageTracker) |
| 8 | 语音输入 | ✅ 完成 | dc60222 (SpeechRecognizer) |
| 9 | TTS 增强 (Sovits) | ⏸️ 搁置 | 需远程TTS服务器，已有原生TTS |
| 10 | 输入增强 | ✅ 不需要 | Android原生已覆盖 |
| 11 | 表情包管理 | 📋 待做 | 需文件存储+UI面板 |
| 12 | 群聊 | 📋 待做 | 需新数据模型+调度+UI |
| 13 | FlowLock | 📋 待做 | HTML模块已有，需Kotlin调度 |
| 14 | 主题/壁纸 | 📋 待做 | HTML模块已有，需IPC持久化 |
| 15 | Pyodide Python | 📋 待做 | vendor已有，需iframe集成 |
