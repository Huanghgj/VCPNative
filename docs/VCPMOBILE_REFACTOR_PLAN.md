# VCPNative 重构计划书 — 基于 VCPMobile v0.9.6

> 日期: 2026-04-01
> 目标: 将 VCPMobile (Tauri + Rust + Vue 3) 的架构优势移植到 VCPNative (Android Kotlin)
> 范围: 底层基础设施、交互层、API 请求层全面重构

---

## 目录

1. [总体架构对比](#1-总体架构对比)
2. [重构优先级与分期](#2-重构优先级与分期)
3. [Phase 1: 底层基础设施重构](#3-phase-1-底层基础设施重构)
4. [Phase 2: API 请求层重构](#4-phase-2-api-请求层重构)
5. [Phase 3: 交互层重构](#5-phase-3-交互层重构)
6. [Phase 4: 数据同步体系](#6-phase-4-数据同步体系)
7. [Phase 5: 内容处理管线](#7-phase-5-内容处理管线)
8. [Phase 6: 群聊引擎升级](#8-phase-6-群聊引擎升级)
9. [Phase 7: 模型生态与表情包](#9-phase-7-模型生态与表情包)
10. [Phase 8: 文件管理升级](#10-phase-8-文件管理升级)
11. [风险与缓解](#11-风险与缓解)
12. [完成标准](#12-完成标准)

---

## 1. 总体架构对比

### 1.1 技术栈对照

| 层级 | VCPMobile (参考) | VCPNative (当前) | 重构方向 |
|------|-----------------|-----------------|---------|
| 核心运行时 | Rust + Tokio | Kotlin + Coroutines | 保持 Kotlin，学习 Rust 架构模式 |
| UI 框架 | Vue 3 + Pinia | Jetpack Compose | 保持 Compose，学习 Pinia Store 模式 |
| 数据库 | SQLx + SQLite (WAL) | Room + SQLite | Room 已够用，优化 schema |
| HTTP | reqwest + SSE | OkHttp + SSE | 增强 SSE + 请求管理 |
| IPC | Tauri Commands (50+) | WebView Bridge (150+) | 补全 stub handler |
| 状态管理 | Pinia Stores (11个) | AppContainer 手动DI | 引入 StateHolder 模式 |
| 文件系统 | notify + SHA256 | AppFileStore | 增强原子写、SHA256 |
| 同步 | Manifest + Delta | 基础 AppData 导入 | 引入增量同步 |

### 1.2 VCPMobile 核心优势 (必须学习的)

1. **生命周期管理** — 6阶段启动引导，状态可观测
2. **原子文件操作** — temp→validate→backup→rename 四步写入
3. **设置恢复机制** — 三层降级 (当前→备份→默认) + 指数退避
4. **请求追踪/中断** — DashMap 活跃请求 + tokio::select! 响应式中断
5. **内容处理管线** — content_parser + context_sanitizer + LRU 缓存
6. **增量同步** — 指纹比对 + Delta 计算 + 原子下载
7. **群聊编排** — 5级优先级调度 + 概率调整 + 检查点持久化
8. **模型使用追踪** — 热门模型、收藏夹、5秒防抖写入
9. **文件完整性** — SHA256 内容寻址 + 孤立附件清理
10. **编译正则缓存** — DashMap 缓存编译后的正则表达式

### 1.3 VCPNative 已有优势 (保留不动的)

1. **LLM 适配器注册表** — 9+ Provider 支持 (VCPMobile 没有)
2. **密钥轮换 + 熔断** — LlmKeyManager 三错断路 + 60秒自恢复
3. **Room 迁移体系** — 10个版本迁移，成熟稳定
4. **Compose 导航** — 完整的路由图 + 深度链接
5. **Overlay 无障碍服务** — AiOverlayService (VCPMobile 完全没有)
6. **三通道 WebSocket** — VcpLogClient 多通道监听
7. **桥接兼容层** — 150+ IPC channel 映射

---

## 2. 重构优先级与分期

```
Phase 1 (底层基础设施)  ━━━━━━━━━━━━━━━━ [最高优先级, 其他Phase依赖此]
  │
  ├─→ Phase 2 (API请求层)  ━━━━━━━━━━━━━ [高优先级, 核心用户体验]
  │     │
  │     └─→ Phase 5 (内容处理管线)  ━━━━ [中优先级]
  │
  ├─→ Phase 3 (交互层)  ━━━━━━━━━━━━━━━ [高优先级]
  │
  ├─→ Phase 4 (数据同步)  ━━━━━━━━━━━━━ [中优先级]
  │
  ├─→ Phase 6 (群聊引擎)  ━━━━━━━━━━━━━ [中优先级]
  │
  ├─→ Phase 7 (模型生态)  ━━━━━━━━━━━━━ [低优先级]
  │
  └─→ Phase 8 (文件管理)  ━━━━━━━━━━━━━ [低优先级]
```

**预估工作量**: 8个Phase，每个Phase独立可交付

---

## 3. Phase 1: 底层基础设施重构

### 3.1 应用生命周期管理器

**参考**: VCPMobile `lifecycle_manager.rs`
**目标**: 取代当前 BootstrapRoute 的简单检查，引入可观测的多阶段启动

**新建文件**: `app/lifecycle/AppLifecycleManager.kt`

```kotlin
// 对标 VCPMobile 的 CoreStatus + LifecycleState
enum class CoreStatus { INITIALIZING, LOADING_DATA, CONNECTING, READY, ERROR }

class AppLifecycleManager(private val appContainer: AppContainer) {
    private val _status = MutableStateFlow(CoreStatus.INITIALIZING)
    val status: StateFlow<CoreStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun bootstrap() {
        try {
            // Phase 1: 数据库初始化
            _status.value = CoreStatus.INITIALIZING
            appContainer.database  // 触发 lazy 初始化

            // Phase 2: 加载核心数据 (并行)
            _status.value = CoreStatus.LOADING_DATA
            coroutineScope {
                launch { appContainer.settingsRepository.currentSettings() }
                launch { appContainer.modelCatalog.refresh() }
                launch { appContainer.llmProfileStore.load() }
            }

            // Phase 3: 连接外部服务
            _status.value = CoreStatus.CONNECTING
            // VcpLogClient 自动连接 (已有逻辑)

            // Phase 4: 就绪
            _status.value = CoreStatus.READY

        } catch (e: Exception) {
            _lastError.value = e.message
            _status.value = CoreStatus.ERROR
        }
    }
}
```

**修改文件**:
- `app/AppContainer.kt` — 添加 `lifecycleManager` 属性
- `feature/bootstrap/BootstrapRoute.kt` — 消费 `lifecycleManager.status` 展示启动进度
- `app/VcpNativeApp.kt` — 在 `LaunchedEffect` 中调用 `bootstrap()`

### 3.2 原子文件操作工具

**参考**: VCPMobile `app_settings_manager.rs` 的 `internal_write_app_settings()`
**目标**: 所有文件写入使用 temp→validate→backup→rename 模式，防止断电丢数据

**新建文件**: `data/files/AtomicFileWriter.kt`

```kotlin
object AtomicFileWriter {
    /**
     * 原子写入文件: temp写入 → 内容校验 → 备份原文件 → 原子重命名
     * 对标 VCPMobile 的 atomic write pattern
     */
    suspend fun write(
        target: File,
        content: ByteArray,
        validate: ((ByteArray) -> Boolean)? = null,
    ) {
        val temp = File(target.parent, "${target.name}.tmp.${System.nanoTime()}")
        val backup = File(target.parent, "${target.name}.bak")
        try {
            // Step 1: 写入临时文件
            temp.writeBytes(content)

            // Step 2: 校验临时文件内容 (可选)
            if (validate != null && !validate(temp.readBytes())) {
                throw IOException("Validation failed for ${target.name}")
            }

            // Step 3: 备份当前文件 (如果存在)
            if (target.exists()) {
                target.copyTo(backup, overwrite = true)
            }

            // Step 4: 原子重命名 (Linux 上是原子操作)
            if (!temp.renameTo(target)) {
                // renameTo 在跨文件系统时可能失败，降级为 copy+delete
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /**
     * 写入 JSON 文件，自带 JSON 格式校验
     */
    suspend fun writeJson(target: File, json: String) {
        write(target, json.toByteArray(Charsets.UTF_8)) { bytes ->
            try {
                JSONObject(String(bytes, Charsets.UTF_8)); true
            } catch (_: Exception) {
                try { JSONArray(String(bytes, Charsets.UTF_8)); true }
                catch (_: Exception) { false }
            }
        }
    }
}
```

**改造文件** (将裸 `file.writeText()` 替换为 `AtomicFileWriter`):
- `data/repository/WorkspaceRepository.kt` — compat history 写入
- `data/datastore/SettingsRepository.kt` — compatSettings.json 同步
- `bridge/NotesFileManager.kt` — 笔记文件写入
- `network/llm/LlmProfileStore.kt` — Profile JSON 写入

### 3.3 设置三层恢复机制

**参考**: VCPMobile `app_settings_manager.rs` 三层降级 + 指数退避
**目标**: 设置读取永不崩溃

**修改文件**: `data/datastore/SettingsRepository.kt`

```kotlin
// 新增: 三层降级读取
suspend fun readSettingsWithRecovery(): AppSettings {
    // Layer 1: 正常读取
    return try {
        currentSettings()
    } catch (e: Exception) {
        BridgeLogger.w("Settings", "Primary read failed, trying backup: ${e.message}")
        // Layer 2: 从备份文件恢复
        try {
            val backupFile = File(appFileStore.root, "compat/AppData/settings.json.bak")
            if (backupFile.exists()) {
                val json = JSONObject(backupFile.readText())
                parseSettingsFromJson(json)
            } else throw FileNotFoundException("No backup")
        } catch (e2: Exception) {
            BridgeLogger.w("Settings", "Backup read failed, using defaults: ${e2.message}")
            // Layer 3: 返回默认值
            AppSettings()
        }
    }
}

// 新增: 指数退避重试
private suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelayMs: Long = 50,
    block: suspend () -> T,
): T {
    var delay = initialDelayMs
    repeat(maxRetries - 1) {
        try { return block() } catch (_: Exception) {
            delay(delay)
            delay *= 2
        }
    }
    return block() // 最后一次不 catch
}
```

### 3.4 编译正则缓存

**参考**: VCPMobile `chat_manager.rs` 的 DashMap<String, Regex> 缓存
**目标**: 避免每次消息处理都重新编译正则

**新建文件**: `data/cache/RegexCache.kt`

```kotlin
/**
 * 线程安全的编译正则缓存
 * 对标 VCPMobile 的 DashMap<String, Regex> 缓存策略
 */
object RegexCache {
    private val cache = ConcurrentHashMap<String, Regex>(64)

    fun get(pattern: String): Regex {
        return cache.getOrPut(pattern) {
            try { Regex(pattern) }
            catch (e: PatternSyntaxException) {
                BridgeLogger.w("RegexCache", "Invalid pattern: $pattern")
                Regex(Regex.escape(pattern)) // 降级为字面匹配
            }
        }
    }

    fun clear() = cache.clear()
}
```

**改造文件**:
- `chat/compiler/ChatRequestCompiler.kt` — regex rules 应用时使用缓存
- `bridge/IpcHandlers.kt` — frontend regex 应用时使用缓存

### 3.5 BridgeLogger 增强

**参考**: VCPMobile `vcp_log_service.rs` + 我们已有的 BridgeLogger
**目标**: 增加日志文件轮转上限，防止磁盘占满

**修改文件**: `bridge/BridgeLogger.kt`

```kotlin
// 新增: 日志目录总大小限制
private const val MAX_LOG_DIR_SIZE_BYTES = 10 * 1024 * 1024 // 10MB
private fun pruneOldLogFiles() {
    val logDir = logDirectory ?: return
    val files = logDir.listFiles()?.sortedBy { it.lastModified() } ?: return
    var totalSize = files.sumOf { it.length() }
    for (file in files) {
        if (totalSize <= MAX_LOG_DIR_SIZE_BYTES) break
        totalSize -= file.length()
        file.delete()
    }
}
```

---

## 4. Phase 2: API 请求层重构

### 4.1 活跃请求追踪器

**参考**: VCPMobile `vcp_client.rs` 的 `ActiveRequests: Arc<DashMap<String, Sender<()>>>`
**目标**: 全局追踪所有进行中的 API 请求，支持按 ID 中断

**新建文件**: `network/vcp/ActiveRequestTracker.kt`

```kotlin
/**
 * 全局活跃请求追踪器
 * 对标 VCPMobile 的 ActiveRequests DashMap
 */
class ActiveRequestTracker {

    data class ActiveRequest(
        val requestId: String,
        val call: Call,                      // OkHttp Call (可取消)
        val job: Job,                        // 协程 Job (可取消)
        val startedAt: Long = System.currentTimeMillis(),
        val interrupted: AtomicBoolean = AtomicBoolean(false),
    )

    private val requests = ConcurrentHashMap<String, ActiveRequest>()

    val activeCount: Int get() = requests.size

    fun track(requestId: String, call: Call, job: Job): ActiveRequest {
        val request = ActiveRequest(requestId, call, job)
        requests[requestId] = request
        return request
    }

    fun remove(requestId: String) {
        requests.remove(requestId)
    }

    /**
     * 中断指定请求
     * 对标 VCPMobile 的 interruptRequest()
     * 同时取消 OkHttp Call 和 Coroutine Job
     */
    suspend fun interrupt(requestId: String): Boolean {
        val request = requests.remove(requestId) ?: return false
        request.interrupted.set(true)
        request.call.cancel()       // 取消网络请求
        request.job.cancel()        // 取消协程
        return true
    }

    /**
     * 中断所有活跃请求 (用于 App 进后台或切换话题)
     */
    suspend fun interruptAll() {
        val ids = requests.keys().toList()
        ids.forEach { interrupt(it) }
    }

    /**
     * 清理超时请求 (安全网, 防止泄漏)
     */
    fun cleanupStale(maxAgeMs: Long = 10 * 60 * 1000) {
        val now = System.currentTimeMillis()
        requests.entries.removeIf { (_, req) ->
            (now - req.startedAt > maxAgeMs).also { stale ->
                if (stale) { req.call.cancel(); req.job.cancel() }
            }
        }
    }
}
```

### 4.2 StreamSessionManager 重构

**参考**: VCPMobile `vcp_client.rs` 的 `perform_vcp_request()` + `tokio::select!`
**目标**: 集成 ActiveRequestTracker，增强 SSE 解析鲁棒性

**修改文件**: `chat/session/StreamSessionManager.kt`

核心改动:

```kotlin
class VcpToolBoxStreamSessionManager(
    private val okHttpClient: OkHttpClient,
    private val activeRequestTracker: ActiveRequestTracker,  // 新增
) : StreamSessionManager {

    // 移除内部 activeRequests ConcurrentHashMap，改用 activeRequestTracker

    override fun submit(compiledRequest: CompiledChatRequest): Flow<StreamSessionEvent> = flow {
        emit(StreamSessionEvent.Started)

        val call = okHttpClient.newCall(buildRequest(compiledRequest))

        // 用 activeRequestTracker 追踪
        val job = currentCoroutineContext().job
        val tracked = activeRequestTracker.track(compiledRequest.requestId, call, job)

        try {
            call.execute().use { response ->
                // ... SSE 解析逻辑 ...

                val source = response.body!!.source()
                while (true) {
                    // 对标 VCPMobile tokio::select! 的检查
                    currentCoroutineContext().ensureActive()
                    if (tracked.interrupted.get()) {
                        emit(StreamSessionEvent.Interrupted(partialText))
                        return@flow
                    }

                    val line = source.readUtf8Line() ?: break
                    // ... 解析逻辑 ...
                }
            }
        } finally {
            activeRequestTracker.remove(compiledRequest.requestId)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun interrupt(requestId: String): StreamInterruptResult {
        // 先通知远端
        val remoteResult = sendRemoteInterrupt(requestId)
        // 再本地中断
        activeRequestTracker.interrupt(requestId)
        return remoteResult
    }
}
```

### 4.3 SSE 解析器增强

**参考**: VCPMobile 的 UTF-8 分片处理 + 无硬超时
**目标**: 处理移动网络下 UTF-8 字节被拆分的问题

**新建文件**: `network/vcp/RobustSseParser.kt`

```kotlin
/**
 * 增强版 SSE 解析器
 * 对标 VCPMobile 的移动网络鲁棒性
 * - 处理 UTF-8 字节拆分
 * - 支持多种 SSE 格式 (OpenAI/Anthropic/Google)
 * - [DONE] 信号检测
 */
class RobustSseParser {

    private val buffer = StringBuilder()

    data class SseFrame(
        val event: String? = null,
        val data: String,
        val isDone: Boolean = false,
    )

    /**
     * 消费一行 SSE 数据，返回完整帧或 null
     */
    fun consumeLine(line: String): SseFrame? {
        val trimmed = line.trim()

        // 空行 = 帧结束
        if (trimmed.isEmpty()) {
            if (buffer.isEmpty()) return null
            val data = buffer.toString()
            buffer.clear()
            return parseFrame(data)
        }

        // data: 前缀
        when {
            trimmed.startsWith("data: ") -> buffer.appendLine(trimmed.removePrefix("data: "))
            trimmed.startsWith("data:") -> buffer.appendLine(trimmed.removePrefix("data:"))
            trimmed.startsWith("event: ") -> { /* 可选: 记录事件类型 */ }
            // 忽略 id:, retry: 等其他字段
        }
        return null
    }

    private fun parseFrame(data: String): SseFrame {
        val trimmedData = data.trim()

        // [DONE] 信号 (OpenAI 格式)
        if (trimmedData == "[DONE]") {
            return SseFrame(data = "", isDone = true)
        }

        return SseFrame(data = trimmedData)
    }

    fun reset() = buffer.clear()
}
```

### 4.4 上下文注入系统

**参考**: VCPMobile `vcp_client.rs` 的 context 注入 (音乐状态、UI 格式要求)
**目标**: 在系统消息中注入运行时上下文

**新建文件**: `chat/compiler/ContextInjector.kt`

```kotlin
/**
 * 运行时上下文注入器
 * 对标 VCPMobile 的 context injection
 */
class ContextInjector(
    private val settingsRepository: SettingsRepository,
) {
    /**
     * 构建上下文字符串，注入到系统消息末尾
     */
    suspend fun buildContextString(agentId: String, topicId: String): String {
        val parts = mutableListOf<String>()

        val settings = settingsRepository.currentSettings()

        // 平台信息
        parts += "[平台: Android Mobile]"

        // 当前时间
        parts += "[当前时间: ${java.time.LocalDateTime.now()}]"

        // VCP 工具注入标记 (如果启用)
        if (settings.enableVcpToolInjection) {
            parts += "[VCP工具已启用]"
        }

        return parts.joinToString("\n")
    }
}
```

**修改文件**: `chat/compiler/ChatRequestCompiler.kt` — 在系统消息末尾追加上下文

### 4.5 请求超时策略优化

**参考**: VCPMobile 无硬超时 (支持长思考模型)
**目标**: 为长思考模型 (如 o1, gemini-2.5-pro) 移除读超时

**修改文件**: `app/AppContainer.kt`

```kotlin
// 新增: 无超时 HttpClient (用于流式请求)
val streamingHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)     // 无读超时! 对标 VCPMobile
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

// 保留原有 boundedHttpClient 用于非流式请求
```

---

## 5. Phase 3: 交互层重构

### 5.1 IPC Stub 补全

**参考**: VCPMobile 50+ Tauri Commands 全部实现
**目标**: 将 IpcHandlers.kt 中约 40 个 stub handler 补全

**优先级排序**:

| 优先级 | Channel | 当前状态 | 目标 |
|--------|---------|---------|------|
| P0 | `save-settings` | TODO stub | 完整实现设置写入 |
| P0 | `save-agent-config` | stub | 通过 WorkspaceRepository 保存 |
| P0 | `refresh-models` | stub | 调用 modelCatalog.refresh() |
| P0 | `update-agent-config` | stub | JSON merge + save |
| P1 | `interrupt-vcp-request` | stub | 调用 activeRequestTracker.interrupt() |
| P1 | `delete-agent` | stub | WorkspaceRepository.deleteAgent() |
| P1 | `summarize-topic` | stub | 调用 TopicSummarizer |
| P1 | `toggle-topic-lock` | stub | 数据库更新 |
| P1 | `set-topic-unread` | stub | 数据库更新 |
| P2 | `get-unread-topic-counts` | stub | 数据库聚合查询 |
| P2 | `export-topic-as-markdown` | stub | 格式转换 + 分享 Intent |
| P2 | `import-regex-rules` | stub | JSON 解析 + Room 写入 |
| P2 | `select-avatar` | stub | Android 图片选择器 |
| P2 | `save-avatar` | stub | 文件存储 + 数据库更新 |
| P3 | Canvas 系列 | stub | 可暂缓 |
| P3 | Translator 系列 | stub | 可暂缓 |
| P3 | Voice/TTS 系列 | 部分实现 | 渐进完善 |

**修改文件**: `bridge/IpcHandlers.kt`

### 5.2 事件发射系统增强

**参考**: VCPMobile 的 Tauri Event System (6+ 事件通道)
**目标**: 统一事件发射，支持多订阅者

**新建文件**: `bridge/EventBus.kt`

```kotlin
/**
 * Kotlin → WebView 事件总线
 * 对标 VCPMobile 的 Tauri Event Emission System
 *
 * 事件通道:
 * - vcp-stream-event: AI 流式响应
 * - vcp-file-change: 外部文件变更
 * - vcp-group-turn-finished: 群聊轮次完成
 * - topic-index-updated: 话题索引更新
 * - theme-changed: 主题切换
 * - settings-changed: 设置变更
 */
class EventBus {
    // WebView 事件发射 (JS 侧)
    private var webViewEmitter: ((channel: String, data: Any?) -> Unit)? = null

    // Kotlin 侧 Flow 事件 (Compose UI 消费)
    private val _events = MutableSharedFlow<BusEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BusEvent> = _events.asSharedFlow()

    data class BusEvent(val channel: String, val data: Any?)

    fun setWebViewEmitter(emitter: (String, Any?) -> Unit) {
        webViewEmitter = emitter
    }

    /**
     * 发射事件到 WebView + Kotlin 双通道
     */
    fun emit(channel: String, data: Any? = null) {
        webViewEmitter?.invoke(channel, data)
        _events.tryEmit(BusEvent(channel, data))
    }
}
```

**改造文件**:
- `bridge/IpcDispatcher.kt` — 用 EventBus 替换裸 eventEmitter
- `bridge/VcpModuleHost.kt` — 连接 EventBus 到 WebView
- `app/VcpNativeApp.kt` — 消费 EventBus 的 Kotlin 侧 Flow

### 5.3 流式渲染优化

**参考**: VCPMobile `streamManager.ts` 的 RAF + 自适应步长
**目标**: WebView 侧已有 messageRenderer.js，但 Kotlin→JS 推送需要优化

**修改文件**: `bridge/IpcHandlers.kt` 的 `send-to-vcp` handler

```kotlin
// 当前: 每个 SSE chunk 都立即 emit 到 WebView
// 问题: 高频 evaluateJavascript 调用造成 WebView 卡顿

// 优化: 批量合并 chunk，16ms 一帧推送 (对标 60fps)
val chunkBuffer = StringBuilder()
var lastPushTime = 0L

fun flushChunks(messageId: String) {
    val now = System.currentTimeMillis()
    if (chunkBuffer.isNotEmpty() && (now - lastPushTime >= 16 || /* stream ended */)) {
        val batch = chunkBuffer.toString()
        chunkBuffer.clear()
        lastPushTime = now
        eventBus.emit("vcp-stream-chunk", JSONObject().apply {
            put("messageId", messageId)
            put("chunk", batch)
        })
    }
}
```

### 5.4 IPC 错误处理标准化

**参考**: VCPMobile 所有 Tauri Command 返回 `Result<T, String>`
**目标**: IPC handler 统一错误格式

**修改文件**: `bridge/IpcDispatcher.kt`

```kotlin
// 新增: 标准化错误响应
data class IpcResult(
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null,
)

// 修改 handle 方法: 所有异常转为 IpcResult
suspend fun handle(channel: String, args: JSONArray): Any? {
    val handler = handlers[channel]
    if (handler == null) {
        BridgeLogger.w("IPC", "Unhandled channel: $channel")
        return null
    }
    return try {
        handler.handle(args)
    } catch (e: Exception) {
        BridgeLogger.e("IPC", "Error in $channel: ${e.message}")
        JSONObject().apply {
            put("error", true)
            put("message", e.message ?: "Unknown error")
        }
    }
}
```

---

## 6. Phase 4: 数据同步体系

### 6.1 增量同步引擎

**参考**: VCPMobile `sync_handlers.rs` + `chat_manager.rs` 的 Delta 同步
**目标**: 从"全量导入"升级为"增量同步"

**新建文件**: `data/sync/SyncEngine.kt`

```kotlin
/**
 * 增量同步引擎
 * 对标 VCPMobile 的 Manifest + Delta 同步体系
 */
class SyncEngine(
    private val httpClient: OkHttpClient,
    private val appFileStore: AppFileStore,
) {
    data class SyncConfig(
        val serverIp: String,
        val serverPort: Int,
        val syncToken: String,
    )

    data class RemoteFileInfo(
        val path: String,
        val mtimeMs: Long,
        val size: Long,
        val hash: String? = null,
    )

    /**
     * Step 1: Ping 检查连通性 (5秒超时)
     */
    suspend fun ping(config: SyncConfig): Boolean

    /**
     * Step 2: 获取远端 Manifest
     */
    suspend fun fetchManifest(config: SyncConfig): Map<String, RemoteFileInfo>

    /**
     * Step 3: 获取本地 Manifest
     */
    fun getLocalManifest(): Map<String, LocalFileInfo>

    /**
     * Step 4: 计算 Delta (新增、修改、删除)
     */
    fun computeDelta(
        remote: Map<String, RemoteFileInfo>,
        local: Map<String, LocalFileInfo>,
    ): SyncDelta

    /**
     * Step 5: 执行同步 (顺序下载防 OOM)
     */
    suspend fun executSync(
        config: SyncConfig,
        delta: SyncDelta,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit,
    ): SyncResult

    data class SyncDelta(
        val toDownload: List<String>,    // 新增或修改
        val toDelete: List<String>,      // 远端已删除
        val unchanged: List<String>,     // 未变化
    )

    data class SyncResult(
        val succeeded: Int,
        val failed: List<Pair<String, String>>,  // path → error
    )
}
```

### 6.2 智能 JSON 合并

**参考**: VCPMobile `sync_handlers.rs` 的 settings.json 合并策略
**目标**: 同步设置时保留移动端特有字段

**新建文件**: `data/sync/JsonMerger.kt`

```kotlin
/**
 * 智能 JSON 合并器
 * 对标 VCPMobile 的 settings.json 合并策略
 * 规则: 远端为主，但保留移动端特有字段
 */
object JsonMerger {

    // 移动端特有字段，同步时不被覆盖
    private val MOBILE_ONLY_FIELDS = setOf(
        "overlayApiUrl", "overlayApiKey", "overlayModel",
        "enableFloatingWindow", "lastAgentId", "lastTopicId",
    )

    fun mergeSettings(remote: JSONObject, local: JSONObject): JSONObject {
        val result = JSONObject(remote.toString()) // 以远端为基础

        // 保留移动端特有字段
        for (field in MOBILE_ONLY_FIELDS) {
            if (local.has(field)) {
                result.put(field, local.get(field))
            }
        }
        return result
    }
}
```

### 6.3 话题指纹与 Delta

**参考**: VCPMobile `chat_manager.rs` 的 `TopicFingerprint` + `TopicDelta`
**目标**: 聊天记录增量同步

**新建文件**: `data/sync/TopicFingerprint.kt`

```kotlin
data class TopicFingerprint(
    val topicId: String,
    val messageCount: Int,
    val lastModifiedMs: Long,
    val contentHash: String,  // SHA256 of history JSON
)

data class TopicDelta(
    val added: List<MessageEntity>,
    val updated: List<MessageEntity>,
    val deleted: List<String>,       // message IDs
)

/**
 * 计算话题指纹 (用于快速比对是否需要同步)
 */
fun computeFingerprint(topicId: String, messages: List<MessageEntity>): TopicFingerprint {
    val content = messages.joinToString("|") { "${it.id}:${it.updatedAt}" }
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return TopicFingerprint(
        topicId = topicId,
        messageCount = messages.size,
        lastModifiedMs = messages.maxOfOrNull { it.updatedAt } ?: 0,
        contentHash = hash,
    )
}
```

---

## 7. Phase 5: 内容处理管线

### 7.1 内容解析器 (Kotlin 侧)

**参考**: VCPMobile `content_parser.rs` 的 ContentBlock 体系
**目标**: 将部分内容解析从 JS messageRenderer.js 下沉到 Kotlin

**新建文件**: `chat/content/ContentParser.kt`

```kotlin
/**
 * 内容块解析器
 * 对标 VCPMobile 的 content_parser.rs
 * 将 AI 输出解析为结构化块
 */
object ContentParser {

    sealed class ContentBlock {
        data class Markdown(val text: String) : ContentBlock()
        data class ToolUse(val toolName: String, val content: String) : ContentBlock()
        data class ToolResult(val summary: String, val details: List<Pair<String, String>>) : ContentBlock()
        data class ThoughtChain(val title: String?, val content: String) : ContentBlock()
        data class HtmlPreview(val html: String) : ContentBlock()
        data class DailyNote(val date: String, val folder: String, val content: String) : ContentBlock()
    }

    // 使用 RegexCache 避免重复编译
    private val TOOL_REGEX get() = RegexCache.get("""<<<\[TOOL_REQUEST\]>>>(.*?)<<<\[END_TOOL_REQUEST\]>>>""")
    private val THOUGHT_REGEX get() = RegexCache.get("""\[--- VCP元思考链(?::\s*"([^"]*)")?\s*---\]([\s\S]*?)\[--- 元思考链结束 ---\]""")
    private val NOTE_REGEX get() = RegexCache.get("""<<<DailyNoteStart>>>(.*?)<<<DailyNoteEnd>>>""")

    fun parse(text: String): List<ContentBlock> {
        // 对标 VCPMobile: 扫描最早的标记，按顺序提取块
        // ... 实现逻辑 ...
    }
}
```

### 7.2 上下文清理器 LRU 缓存

**参考**: VCPMobile `context_sanitizer.rs` 的 LRU + TTL 缓存
**目标**: 为 ChatRequestCompiler 的 ContextSanitizer 增加缓存

**修改文件**: `chat/compiler/ChatRequestCompiler.kt`

```kotlin
// 新增: 上下文清理结果缓存
private val sanitizerCache = object : LinkedHashMap<String, Pair<String, Long>>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Pair<String, Long>>): Boolean {
        // LRU: 最多 128 条
        if (size > 128) return true
        // TTL: 5分钟过期
        return System.currentTimeMillis() - eldest.value.second > 5 * 60 * 1000
    }
}

private fun sanitizeWithCache(content: String, depth: Int): String {
    val key = "${content.hashCode()}:$depth"
    val cached = sanitizerCache[key]
    if (cached != null && System.currentTimeMillis() - cached.second < 5 * 60 * 1000) {
        return cached.first
    }
    val result = ContextSanitizer.sanitize(content, depth)
    sanitizerCache[key] = result to System.currentTimeMillis()
    return result
}
```

---

## 8. Phase 6: 群聊引擎升级

### 8.1 5级优先级调度

**参考**: VCPMobile `group_orchestrator.rs` 的 `determine_naturerandom_speakers()`
**目标**: 升级 GroupChatEngine 的发言者选择逻辑

**新建文件**: `feature/groupchat/SpeakerSelector.kt`

```kotlin
/**
 * 5级优先级发言者选择器
 * 对标 VCPMobile 的 determine_naturerandom_speakers()
 *
 * Tier 1: 直接 @提及 (@agentName)
 * Tier 2: 标签匹配 (natural/strict 模式)
 * Tier 3: @所有人 广播
 * Tier 4: 概率响应 (15% 基础 / 85% 有标签)
 * Tier 5: 兜底选择 (确保至少1人发言)
 */
class SpeakerSelector {

    data class SpeakerResult(
        val speakers: List<String>,   // 选中的 agentId 列表
        val tier: Int,                // 命中的层级
        val reason: String,           // 调试信息
    )

    fun selectSpeakers(
        userMessage: String,
        members: List<GroupMember>,
        recentHistory: List<GroupMessage>,  // 最近 8 条
        mode: String,                      // "sequential" | "naturerandom"
        tagMatchMode: String = "natural",  // "natural" | "strict"
    ): SpeakerResult {
        if (mode == "sequential") {
            return SpeakerResult(members.map { it.agentId }, 0, "Sequential mode")
        }

        val selected = mutableSetOf<String>()

        // Tier 1: @提及
        val mentions = extractMentions(userMessage, members)
        if (mentions.isNotEmpty()) {
            return SpeakerResult(mentions, 1, "Direct mention")
        }

        // Tier 2: 标签匹配
        val tagMatches = matchTags(userMessage, members, tagMatchMode)
        if (tagMatches.isNotEmpty()) {
            selected.addAll(tagMatches)
        }

        // Tier 3: @所有人
        if (userMessage.contains("@所有人") || userMessage.contains("@everyone")) {
            return SpeakerResult(members.map { it.agentId }, 3, "Broadcast")
        }

        // Tier 4: 概率响应
        val baseProbability = if (selected.isNotEmpty()) 0.85 else 0.15
        for (member in members) {
            if (member.agentId !in selected) {
                val adjusted = adjustProbability(member, recentHistory, baseProbability)
                if (Math.random() < adjusted) {
                    selected.add(member.agentId)
                }
            }
        }

        // Tier 5: 兜底
        if (selected.isEmpty()) {
            val fallback = selectFallback(members, recentHistory)
            selected.add(fallback.agentId)
            return SpeakerResult(selected.toList(), 5, "Fallback")
        }

        // 去重: 防止同一轮重复发言
        val deduplicated = deduplicateWithHistory(selected.toList(), recentHistory)
        return SpeakerResult(deduplicated, if (tagMatches.isNotEmpty()) 2 else 4, "Selected")
    }

    private fun adjustProbability(
        member: GroupMember,
        history: List<GroupMessage>,
        base: Double,
    ): Double {
        // 对标 VCPMobile: 最近说过话的降低概率
        val recentCount = history.count { it.agentId == member.agentId }
        return base * (1.0 / (1 + recentCount * 0.3))
    }
}
```

### 8.2 检查点持久化

**参考**: VCPMobile `group_handlers.rs` 每个 agent 回复后立即保存
**目标**: 群聊每轮回复后保存，防止长对话丢失

**修改文件**: `feature/groupchat/GroupChatEngine.kt`

```kotlin
// 在 handleGroupMessage() 的每个 agent 回复循环中:
for (speaker in speakers) {
    val response = streamResponse(speaker, history)
    history.add(response)

    // 检查点: 每个 agent 回复后立即持久化
    // 对标 VCPMobile 的 checkpoint persistence
    groupChatRepository.saveHistory(groupId, topicId, history)
}

// 全部完成后发射事件
eventBus.emit("vcp-group-turn-finished", JSONObject().apply {
    put("groupId", groupId)
    put("topicId", topicId)
})
```

---

## 9. Phase 7: 模型生态与表情包

### 9.1 模型使用追踪增强

**参考**: VCPMobile `model_manager.rs` 的使用统计 + 热门排名 + 5秒防抖
**目标**: 增强已有的 ModelUsageTracker

**修改文件**: `data/ModelUsageTracker.kt`

```kotlin
// 新增: 防抖写入 (对标 VCPMobile 的 5秒防抖)
private var isDirty = AtomicBoolean(false)
private val debouncedSave = CoroutineScope(Dispatchers.IO).launch {
    while (true) {
        delay(5000)
        if (isDirty.compareAndSet(true, false)) {
            saveToDisk()
        }
    }
}

fun recordUsage(modelId: String) {
    usageStats.compute(modelId) { _, count -> (count ?: 0) + 1 }
    isDirty.set(true)  // 标记脏，等防抖写入
}

// 新增: 热门模型排名 (对标 VCPMobile 的 get_hot_models)
fun getHotModels(limit: Int = 10): List<String> {
    return usageStats.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}
```

### 9.2 表情包管理器

**参考**: VCPMobile `emoticon_manager.rs` 的模糊匹配 + Levenshtein 距离
**目标**: 实现表情包 URL 修复 (当前 JS 侧有 emoticonUrlFixer，但 Kotlin 侧缺失)

**新建文件**: `data/emoticon/EmoticonManager.kt`

```kotlin
/**
 * 表情包管理器
 * 对标 VCPMobile 的 emoticon_manager.rs
 * - Levenshtein 模糊匹配
 * - 加权相似度 (70% 分类 + 30% 文件名)
 * - 0.6 阈值
 */
class EmoticonManager(private val appFileStore: AppFileStore) {

    data class EmoticonItem(
        val url: String,
        val category: String,
        val filename: String,
        val searchKey: String,
    )

    private val library = mutableListOf<EmoticonItem>()

    fun generateLibrary(): Int {
        library.clear()
        val emoticonDir = File(appFileStore.root, "emoticons")
        if (!emoticonDir.exists()) return 0

        emoticonDir.walkTopDown().filter { it.isFile }.forEach { file ->
            library.add(EmoticonItem(
                url = file.absolutePath,
                category = file.parentFile?.name ?: "",
                filename = file.nameWithoutExtension,
                searchKey = file.nameWithoutExtension,
            ))
        }
        return library.size
    }

    fun fixUrl(brokenUrl: String): String? {
        if (library.isEmpty()) return null

        var bestMatch: EmoticonItem? = null
        var bestScore = 0.0
        val (targetCategory, targetFilename) = extractInfo(brokenUrl)

        for (item in library) {
            val catScore = similarity(targetCategory, item.category)
            val nameScore = similarity(targetFilename, item.filename)
            val score = catScore * 0.7 + nameScore * 0.3
            if (score > bestScore) {
                bestScore = score
                bestMatch = item
            }
        }

        return if (bestScore >= 0.6) bestMatch?.url else null
    }

    // Levenshtein 编辑距离
    private fun editDistance(a: String, b: String): Int { /* ... */ }
    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / maxLen
    }
}
```

---

## 10. Phase 8: 文件管理升级

### 10.1 SHA256 内容寻址存储

**参考**: VCPMobile `file_manager.rs` 的 SHA256 去重
**目标**: 附件按哈希存储，自动去重

**修改文件**: `data/files/AppFileStore.kt` 或新建独立类

```kotlin
/**
 * 计算文件 SHA256 (流式，不全部加载到内存)
 * 对标 VCPMobile 的 streaming hash computation
 */
suspend fun computeSha256(inputStream: InputStream): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024) // 128KB chunks
    var read: Int
    while (inputStream.read(buffer).also { read = it } != -1) {
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 存储附件 (内容寻址: 以 SHA256 为文件名)
 * 如果已存在相同哈希的文件，直接返回已有路径
 */
suspend fun storeAttachment(
    inputStream: InputStream,
    originalName: String,
    mimeType: String,
): AttachmentResult {
    // Step 1: 写入临时文件并计算哈希
    val tempFile = File(attachmentsDir, "tmp_${System.nanoTime()}")
    val hash = computeSha256WhileWriting(inputStream, tempFile)

    // Step 2: 检查是否已存在 (去重)
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
    val targetFile = File(attachmentsDir, "$hash.$ext")
    if (targetFile.exists()) {
        tempFile.delete()
        return AttachmentResult(targetFile, hash, existed = true)
    }

    // Step 3: 原子重命名
    tempFile.renameTo(targetFile)
    return AttachmentResult(targetFile, hash, existed = false)
}
```

### 10.2 孤立附件清理

**参考**: VCPMobile `file_manager.rs` 的 `cleanup_orphaned_attachments()`
**目标**: 清理没有消息引用的附件文件

**新建文件**: `data/files/AttachmentCleaner.kt`

```kotlin
/**
 * 孤立附件清理器
 * 对标 VCPMobile 的 cleanup_orphaned_attachments()
 */
class AttachmentCleaner(
    private val database: AppDatabase,
    private val appFileStore: AppFileStore,
) {
    suspend fun cleanup(): CleanupResult = withContext(Dispatchers.IO) {
        // Step 1: 收集所有被引用的哈希
        val referencedHashes = database.messageAttachmentDao()
            .loadAllHashes()
            .toSet()

        // Step 2: 扫描附件目录
        val attachmentsDir = appFileStore.attachmentsDir
        var deletedCount = 0
        var freedBytes = 0L

        attachmentsDir.listFiles()?.forEach { file ->
            val hash = file.nameWithoutExtension
            if (hash !in referencedHashes && !file.name.startsWith("tmp_")) {
                freedBytes += file.length()
                file.delete()
                deletedCount++
            }
        }

        CleanupResult(deletedCount, freedBytes)
    }

    data class CleanupResult(val deletedFiles: Int, val freedBytes: Long)
}
```

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 大范围重构引入回归 | 高 | 每个 Phase 独立 PR，逐步合并 |
| Room 迁移失败 | 高 | 新增表不影响已有表；加 fallback migration |
| WebView bridge 不兼容 | 中 | 保持 bridge-shim.js 接口不变，只改 Kotlin handler |
| 同步协议与桌面端不兼容 | 中 | 参照 VCPMobile 完全一致的 manifest 格式 |
| 性能劣化 | 低 | 新增缓存和批量推送只会提升性能 |
| 原子文件写入 renameTo 失败 | 低 | 加 copy+delete fallback |

---

## 12. 完成标准

### Phase 1 完成标准
- [ ] AppLifecycleManager 可观测启动进度
- [ ] 所有文件写入使用 AtomicFileWriter
- [ ] 设置读取有三层降级
- [ ] RegexCache 替代裸 Regex 编译
- [ ] BridgeLogger 有日志目录大小限制

### Phase 2 完成标准
- [ ] ActiveRequestTracker 追踪所有进行中请求
- [ ] StreamSessionManager 支持响应式中断
- [ ] RobustSseParser 处理 UTF-8 分片
- [ ] ContextInjector 注入运行时上下文
- [ ] 长思考模型无超时

### Phase 3 完成标准
- [ ] 40 个 stub handler 中 P0/P1 全部实现
- [ ] EventBus 双通道事件发射
- [ ] 流式推送 16ms 帧合并
- [ ] IPC 错误标准化

### Phase 4 完成标准
- [ ] SyncEngine 支持 manifest 增量同步
- [ ] JsonMerger 保留移动端字段
- [ ] TopicFingerprint 支持话题级 delta

### Phase 5 完成标准
- [ ] ContentParser 在 Kotlin 侧解析特殊块
- [ ] ContextSanitizer 有 LRU 缓存

### Phase 6 完成标准
- [ ] SpeakerSelector 5级优先级
- [ ] 群聊检查点持久化

### Phase 7 完成标准
- [ ] ModelUsageTracker 5秒防抖
- [ ] EmoticonManager Levenshtein 匹配

### Phase 8 完成标准
- [ ] SHA256 内容寻址存储
- [ ] 孤立附件自动清理

---

## 附录 A: 新文件清单

```
新建:
  app/lifecycle/AppLifecycleManager.kt
  data/files/AtomicFileWriter.kt
  data/cache/RegexCache.kt
  network/vcp/ActiveRequestTracker.kt
  network/vcp/RobustSseParser.kt
  chat/compiler/ContextInjector.kt
  bridge/EventBus.kt
  data/sync/SyncEngine.kt
  data/sync/JsonMerger.kt
  data/sync/TopicFingerprint.kt
  chat/content/ContentParser.kt
  feature/groupchat/SpeakerSelector.kt
  data/emoticon/EmoticonManager.kt
  data/files/AttachmentCleaner.kt

修改:
  app/AppContainer.kt
  app/VcpNativeApp.kt
  feature/bootstrap/BootstrapRoute.kt
  data/datastore/SettingsRepository.kt
  data/repository/WorkspaceRepository.kt
  bridge/IpcDispatcher.kt
  bridge/IpcHandlers.kt
  bridge/VcpModuleHost.kt
  bridge/BridgeLogger.kt
  bridge/NotesFileManager.kt
  chat/session/StreamSessionManager.kt
  chat/compiler/ChatRequestCompiler.kt
  network/llm/LlmProfileStore.kt
  data/ModelUsageTracker.kt
  feature/groupchat/GroupChatEngine.kt
  data/files/AppFileStore.kt
```

## 附录 B: VCPMobile → VCPNative 概念映射表

| VCPMobile (Rust/Vue) | VCPNative (Kotlin) | 备注 |
|----------------------|-------------------|------|
| `Arc<DashMap<K,V>>` | `ConcurrentHashMap<K,V>` | 直接对应 |
| `Arc<RwLock<T>>` | `StateFlow<T>` / `MutableStateFlow<T>` | Flow 提供更好的 Compose 集成 |
| `Arc<Mutex<T>>` | `Mutex` (kotlinx.coroutines) | 协程 Mutex |
| `tokio::select!` | `currentCoroutineContext().ensureActive()` | 协程取消检查 |
| `DashMap regex cache` | `ConcurrentHashMap<String, Regex>` | RegexCache 单例 |
| `lazy_static!` | `by lazy {}` | Kotlin 惰性初始化 |
| `Tauri invoke()` | `IpcDispatcher.handle()` | 同步/异步 IPC |
| `Tauri emit()` | `EventBus.emit()` | 事件推送 |
| `Pinia Store` | `StateFlow + AppContainer` | 响应式状态 |
| `reqwest` | `OkHttpClient` | HTTP 客户端 |
| `tokio-tungstenite` | `OkHttp WebSocket` | WebSocket |
| `sqlx::Pool<Sqlite>` | `Room Database` | 数据库 |
| `serde_json` | `kotlinx.serialization` / `JSONObject` | JSON 处理 |
| `notify` crate | Android `FileObserver` | 文件监控 |
| `sha2` crate | `java.security.MessageDigest` | 哈希 |
| `image` crate | `android.graphics.Bitmap` | 图片处理 |

---

> 这份计划书覆盖了 VCPMobile v0.9.6 的所有核心架构模式。
> 每个 Phase 独立可交付，可以根据实际需求调整优先级和顺序。
