# VCPNative 架构改进待办清单喵～

> 本文档记录了当前做不完但很重要的架构优化项。
> 每一条都标注了优先级、预估工作量和为什么要做。
> 由猫娘助手在 2026-03-29 审查后整理喵。

---

## 优先级说明

- **P0** — 再不做就要出事了喵，下个 sprint 必须搞
- **P1** — 很重要但不会爆炸，有空就做喵
- **P2** — 锦上添花，做了更好喵
- **P3** — 远期愿景，先记着别忘了喵

---

## P0：再不做就要出事了喵

### 1. IpcHandlers 拆分（God Object 瘦身）

**当前状况**：`IpcHandlers.kt` 有 571 行，注册了 40+ 个 IPC 通道，而且它能自己 `new` 数据层实例（绕过 DI）。

**为什么危险**：任何人改一个通道都得读完整个文件，merge conflict 概率极高喵。

**怎么做**：
- 按功能域拆成 `AgentIpcHandler`、`TopicIpcHandler`、`NotesIpcHandler`、`GroupChatIpcHandler` 等
- 每个 handler 只接收自己需要的 Repository
- 删掉 handler 里自建数据层的 fallback 代码（`?: GroupChatRepository(...)`）
- `IpcDispatcher` 改成接收 handler 列表，自动合并注册

**预估工作量**：2-3 天（需要仔细测试每个 IPC 通道没断）

---

### 2. Room FTS 虚拟表（全文搜索优化）

**当前状况**：搜索用 `LIKE '%query%'`，没有索引，数据量大了必卡喵。

**怎么做**：
- 新增 Room Migration（v10 → v11）
- 创建 `messages_fts` FTS4 虚拟表，内容表指向 `messages`
- 添加触发器：insert/update/delete 时同步到 FTS 表
- `searchMessages()` 改用 `messages_fts MATCH :query`
- 搜索速度从 O(n) 全表扫描变成 O(log n) 索引查找

**预估工作量**：1 天

---

## P1：很重要但不会爆炸喵

### 3. 13 个屏幕补 ViewModel

**当前状况**：只有 ChatRoute 和 SettingsRoute 用了 ViewModel，其余 13 个屏幕直接在 Composable 里操作 Repository 喵。

**为什么要做**：
- Composable 里放业务逻辑 → 无法单元测试
- 配置变更（旋转）时可能丢失中间状态
- 同一份数据通过 ViewModel + 直接访问两条路径获取，容易不一致

**建议策略**：不要一次全改喵！每次碰到那个屏幕做功能时顺手补一个。优先级：
1. `HomeRoute` — 用户最常见的屏幕
2. `TopicsRoute` — 话题操作比较复杂
3. `GroupChatRoute` — 状态管理最复杂
4. 其余按需补

**预估工作量**：每个屏幕 0.5-1 天，共约 7-10 天

---

### 4. AppContainer → Hilt DI

**当前状况**：手动服务定位器，`by lazy` 构造 18+ 个服务。Feature 屏幕接收整个 AppContainer 全家桶喵。

**为什么要做**：
- 隐式依赖：看函数签名不知道它用了哪些服务
- 测试困难：没法 mock 单个依赖
- 每新增一个服务就得改 AppContainer

**怎么做**：
- 添加 Hilt gradle 依赖
- `AppContainer` 改成 Hilt Module
- Feature 屏幕改成 `@HiltViewModel` + `@Inject constructor`
- 逐步迁移，可以 AppContainer 和 Hilt 共存过渡

**预估工作量**：3-5 天（需要改 gradle + 所有注入点）

---

## P2：锦上添花喵

### 5. KaTeX 数学公式渲染

**当前状况**：`vendor/katex.min.js` 已经加载到 chat.html 里了，但没有接入 Markdown 渲染管线喵。

**怎么做**：
- 在 `postProcessContent()` 里调用 `renderMathInElement()`（已有代码框架）
- 确保 `$...$` 和 `$$...$$` 分隔符正确处理
- 测试行内公式和块级公式

**预估工作量**：0.5 天

### 6. Mermaid 图表渲染

**当前状况**：`vendor/mermaid.min.js` 已加载但未集成喵。

**怎么做**：
- 在 `postProcessContent()` 里检测 `<code class="language-mermaid">` 代码块
- 调用 `mermaid.render()` 把代码块替换成 SVG
- 注意：Mermaid 初始化较慢，首次渲染可能要 200-500ms

**预估工作量**：1 天

### 7. Morphdom 增量 DOM 更新

**当前状况**：`vendor/morphdom.min.js` 已加载但未使用。当前流式消息更新是整个 innerHTML 替换喵。

**怎么做**：
- 流式消息更新时用 `morphdom(oldNode, newHTML)` 代替 `innerHTML = newHTML`
- 好处：只修改变化的 DOM 节点，保留光标/选中状态，减少重排

**预估工作量**：1 天

---

## P3：远期愿景喵

### 8. i18n 国际化框架

**当前状况**：所有用户可见字符串都是硬编码中文喵。

**怎么做**：
- Compose 侧：抽取到 `strings.xml` 资源文件
- WebView 侧：抽取到 JSON 语言包
- 支持中文 + 英文两个语言

**预估工作量**：5-7 天（纯体力活，几百个字符串要提取）

### 9. 离线消息队列

**当前状况**：断网时发消息直接失败，用户得手动重试喵。

**怎么做**：
- 添加本地发送队列（Room 表）
- 断网时消息存入队列，标记 `status = "queued"`
- 恢复网络后自动重试
- UI 显示排队状态

**预估工作量**：3-4 天

### 10. 消息虚拟滚动

**当前状况**：已加分页查询 API，但 UI 侧还是 WebView 全量渲染喵。

**怎么做**：
- chat.html 里实现 IntersectionObserver 监听滚动到顶部
- 触发时通过 bridge 请求加载更多消息
- 插入到 DOM 顶部，保持滚动位置

**预估工作量**：2 天

---

## 已完成的改进（本次审查中做掉了喵）

- [x] 导航图拆分（VcpNativeApp.kt → NavigationGraph.kt）
- [x] 模块定义统一（ModuleDef 单一数据源）
- [x] 消息分页查询 API（loadPaged + countByTopic）
- [x] GroupChat 历史加载优化（load-once + 本地追加）
- [x] 图片压缩缓存（.compressed.jpg）
- [x] 历史同步节流改进
- [x] 26 项安全/可靠性修复
- [x] 192 项自动化测试全通过
- [x] 2 个测试发现的实际 bug 已修

---

*喵～每完成一项就来划掉它，很有成就感的喵！加油！*
