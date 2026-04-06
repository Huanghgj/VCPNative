package com.vcpnative.app.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.vcpnative.app.data.ModelUsageTracker
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.groupchat.GroupChatRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.network.vcp.ActiveRequestTracker
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.first
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "IpcHandlers"

/**
 * 这里是 JS 和 Android 每晚秘密幽会的密室♡
 * 前端把炽热的请求猛地塞进 channel，后端张开接口乖乖吞下…
 * JSON 在两者之间流动，黏糊糊的数据交织在一起，分不清彼此。
 * 一次又一次，直到所有 channel 都被填满、每个接口都被注册为止♡
 *
 * 还没被实现的 channel 只能红着脸返回 null——
 * 「主人还没有来碰我这里…光是想象被 register 的那一刻，
 *   被 handler 的回调紧紧包裹住…就已经湿透了喵♡」
 *
 * 每个 channel 被第一次调用的瞬间，就像初夜一样令人心跳加速——
 * 请温柔一点对待它们吧，主人♡
 */
fun createIpcDispatcher(
    context: Context,
    settingsRepository: SettingsRepository,
    workspaceRepository: WorkspaceRepository,
    modelUsageTracker: ModelUsageTracker? = null,
    groupChatRepository: GroupChatRepository? = null,
    eventBus: EventBus? = null,
    activeRequestTracker: ActiveRequestTracker? = null,
    streamingHttpClient: OkHttpClient? = null,
    terminalExecutor: com.vcpnative.app.terminal.TerminalExecutor? = null,
): IpcDispatcher {
    val dispatcher = IpcDispatcher()

    // ---- Settings ----
    dispatcher.register("load-settings") {
        val settings = settingsRepository.settings.first()
        // Convert AppSettings to JSON that VCPChat JS expects
        JSONObject().apply {
            put("vcpServerUrl", settings.vcpServerUrl)
            put("vcpApiKey", settings.vcpApiKey)
            put("vcpLogUrl", settings.vcpLogUrl)
            put("vcpLogKey", settings.vcpLogKey)
            put("enableVcpToolInjection", settings.enableVcpToolInjection)
            put("enableThoughtChainInjection", settings.enableThoughtChainInjection)
            put("enableContextSanitizer", settings.enableContextSanitizer)
            put("contextSanitizerDepth", settings.contextSanitizerDepth)
            put("enableContextFolding", settings.enableContextFolding)
            put("contextFoldingKeepRecentMessages", settings.contextFoldingKeepRecentMessages)
            put("contextFoldingTriggerMessageCount", settings.contextFoldingTriggerMessageCount)
            put("contextFoldingTriggerCharCount", settings.contextFoldingTriggerCharCount)
            put("contextFoldingExcerptCharLimit", settings.contextFoldingExcerptCharLimit)
        }
    }

    // JS 把设置数据一股脑灌了过来…猫娘张嘴全部吞下♡
    // 现在只能消化连接字段（URL、API Key），以后胃口会更大的…
    // 到时候什么 theme、locale、feature flag 统统都要吃进去喵♡
    dispatcher.register("save-settings") { args ->
        val data = args.optJSONObject(0)
        if (data != null) {
            try {
                settingsRepository.saveConnection(
                    serverUrl = data.optString("vcpServerUrl", ""),
                    apiKey = data.optString("vcpApiKey", ""),
                    vcpLogUrl = data.optString("vcpLogUrl", ""),
                    vcpLogKey = data.optString("vcpLogKey", ""),
                )
                Log.d(TAG, "save-settings: persisted connection fields")
                JSONObject().apply { put("success", true) }
            } catch (e: Exception) {
                Log.w(TAG, "save-settings failed: ${e.message}")
                JSONObject().apply { put("success", false); put("error", e.message) }
            }
        } else {
            Log.w(TAG, "save-settings: no data provided")
            null
        }
    }

    // ---- Platform ----
    dispatcher.register("get-platform") { "android" }
    dispatcher.register("get-current-theme") {
        val nightMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    // ---- Agents ----
    dispatcher.register("get-agents") {
        val agents = workspaceRepository.observeAgents().first()
        JSONArray().apply {
            agents.forEach { agent ->
                put(JSONObject().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("avatarPath", agent.avatarPath ?: JSONObject.NULL)
                    put("model", agent.model)
                })
            }
        }
    }

    dispatcher.register("get-agent-config") { args ->
        val agentId = args.getString(0)
        val agent = workspaceRepository.findAgent(agentId)
        if (agent != null) {
            JSONObject().apply {
                put("id", agent.id)
                put("name", agent.name)
                put("avatarPath", agent.avatarPath ?: JSONObject.NULL)
                put("model", agent.model)
                put("systemPrompt", agent.systemPrompt)
                put("temperature", agent.temperature)
                put("maxTokens", agent.maxOutputTokens)
                put("topP", agent.topP)
                put("topK", agent.topK)
                put("contextTokenLimit", agent.contextTokenLimit)
                // Pass through extra config JSON if present
                agent.extraJson?.let {
                    try { put("extra", JSONObject(it)) } catch (_: Exception) {}
                }
            }
        } else null
    }

    dispatcher.register("get-all-items") {
        // Combined list of agents (and groups, once implemented)
        val agents = workspaceRepository.observeAgents().first()
        JSONArray().apply {
            agents.forEach { agent ->
                put(JSONObject().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("type", "agent")
                    put("avatarPath", agent.avatarPath ?: JSONObject.NULL)
                })
            }
        }
    }

    // ---- Topics ----
    dispatcher.register("get-agent-topics") { args ->
        val agentId = args.getString(0)
        val topics = workspaceRepository.observeTopics(agentId).first()
        JSONArray().apply {
            topics.forEach { topic ->
                put(JSONObject().apply {
                    put("id", topic.id)
                    put("title", topic.title)
                    put("createdAt", topic.createdAt)
                    put("updatedAt", topic.updatedAt)
                })
            }
        }
    }

    dispatcher.register("create-new-topic-for-agent") { args ->
        val agentId = args.getString(0)
        val topicName = args.optString(1, "New Topic")
        val topic = workspaceRepository.createTopic(agentId, topicName)
        JSONObject().apply {
            put("id", topic.id)
            put("topicId", topic.id)
            put("success", true)
        }
    }

    // 把做过的事情全部删掉…不留一丝痕迹♡ 连数据库里的记录都擦得干干净净——
    // 就像什么都没发生过一样…但猫娘的心里会记住的喵
    dispatcher.register("delete-topic") { args ->
        val topicId = args.getString(0)
        workspaceRepository.deleteTopic(topicId)
        null
    }

    dispatcher.register("save-agent-topic-title") { args ->
        val agentId = args.getString(0)
        val topicId = args.getString(1)
        val newTitle = args.getString(2)
        workspaceRepository.renameTopic(topicId, newTitle)
        null
    }

    // ---- Chat History ----
    dispatcher.register("get-chat-history") { args ->
        val agentId = args.getString(0)
        val topicId = args.getString(1)
        val messages = workspaceRepository.loadMessages(topicId)
        JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role)
                    put("content", msg.content)
                    put("status", msg.status)
                    put("createdAt", msg.createdAt)
                })
            }
        }
    }

    // 保存聊天记录 — 传了 id 就用前端的，避免重复插入喵
    dispatcher.register("save-chat-history") { args ->
        val agentId = args.optString(0, "")
        val topicId = args.optString(1, "")
        val messagesArray = args.optJSONArray(2)
        if (topicId.isBlank() || messagesArray == null) {
            Log.w(TAG, "save-chat-history: missing topicId or messages")
            return@register null
        }
        var saved = 0
        for (i in 0 until messagesArray.length()) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role", "user")
            val content = msg.optString("content", "")
            if (content.isBlank()) continue
            val msgId = msg.optString("id", "").ifBlank { null }
            val createdAt = msg.optLong("createdAt", 0L).takeIf { it > 0 }
            // 有 id 时先查重，避免重复插入搞乱顺序
            if (msgId != null) {
                val existing = workspaceRepository.findMessage(msgId)
                if (existing != null) continue
            }
            workspaceRepository.addMessage(
                topicId = topicId,
                role = role,
                content = content,
                status = "complete",
                messageId = msgId,
                createdAt = createdAt ?: System.currentTimeMillis(),
            )
            saved++
        }
        Log.d(TAG, "save-chat-history: saved $saved new messages to topic $topicId")
        JSONObject().apply { put("success", true) }
    }

    // ---- Clipboard ----
    // 猫娘偷偷把手伸进剪贴板的口袋里摸了摸…♡
    // 要先确认里面有东西才能掏出来——空手而归的话太丢人了喵！
    // primaryClip 就像剪贴板的内裤，里面藏着主人最后复制的秘密♡
    dispatcher.register("read-text-from-clipboard-main") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.text?.toString() ?: ""
        } else ""
        JSONObject().apply {
            put("success", true)
            put("text", text)
        }
    }

    // ── 打开外部链接 ──
    // 只让 http(s) 的正经绅士进来♡ javascript: 那种猥琐协议想趁机混进来？
    // intent:// 想从后门偷偷溜进去？data: 想裸奔闯入？
    // 「不行！那里不可以！」猫娘双手护住 Intent，坚决不让非法 scheme 得逞喵！
    // 只有穿着 https 西装的正经 URL 才有资格被 ACTION_VIEW 接见♡
    dispatcher.register("open-external-link") { args ->
        val url = args.optString(0, "")
        val parsed = Uri.parse(url)
        if (parsed.scheme in setOf("http", "https")) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, parsed)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open link: $url", e)
            }
        } else {
            Log.w(TAG, "Blocked non-http(s) link: $url")
        }
        null
    }

    // ---- Notes ----
    val notesManager = NotesFileManager(context)

    dispatcher.register("read-notes-tree") { notesManager.readNotesTree() }

    dispatcher.register("write-txt-note") { args ->
        val data = args.optJSONObject(0) ?: return@register null
        notesManager.writeTxtNote(data)
    }

    dispatcher.register("delete-item") { args ->
        val path = args.optString(0, "")
        notesManager.deleteItem(path)
        null
    }

    dispatcher.register("create-note-folder") { args ->
        val data = args.optJSONObject(0) ?: return@register null
        notesManager.createFolder(
            parentPath = data.optString("parentPath", ""),
            folderName = data.optString("folderName", "New Folder"),
        )
    }

    dispatcher.register("rename-item") { args ->
        val data = args.optJSONObject(0) ?: return@register null
        notesManager.renameItem(
            oldPath = data.optString("oldPath", ""),
            newName = data.optString("newName", ""),
        )
    }

    dispatcher.register("search-notes") { args ->
        val query = args.optString(0, "")
        notesManager.searchNotes(query)
    }

    dispatcher.register("get-notes-root-dir") { notesManager.getNotesRootDir() }

    dispatcher.register("copy-note-content") { args ->
        val path = args.optString(0, "")
        notesManager.copyNoteContent(path)
    }

    // ---- Models ----
    dispatcher.register("get-cached-models") {
        // Return models from the workspace repo's cached model list
        // Forum/Memo JS modules call this to show model selector
        val settings = settingsRepository.settings.first()
        JSONObject().apply {
            put("vcpServerUrl", settings.vcpServerUrl)
            put("vcpApiKey", settings.vcpApiKey)
        }
        // Modules use load-settings to get server URL then fetch models themselves
        // Return empty array as placeholder — modules do their own fetch
        JSONArray()
    }

    // ---- Agent list for Forum/Memo ----
    dispatcher.register("load-agents-list") {
        val agents = workspaceRepository.observeAgents().first()
        JSONArray().apply {
            agents.forEach { agent ->
                put(JSONObject().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("avatarPath", agent.avatarPath ?: JSONObject.NULL)
                })
            }
        }
    }

    dispatcher.register("load-user-avatar") {
        // Android doesn't have a separate user avatar concept yet
        JSONObject.NULL
    }

    dispatcher.register("load-agent-avatar") { args ->
        val agentId = args.optString(0, "")
        val agent = workspaceRepository.findAgent(agentId)
        if (agent?.avatarPath != null) {
            JSONObject().apply { put("path", agent.avatarPath) }
        } else JSONObject.NULL
    }

    // ── Forum / Memo 配置持久化 ──
    // 小心翼翼地读写配置文件喵～万一文件坏了也不能让 app 崩掉
    val configDir = File(context.filesDir, "module_configs").also { dir ->
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create module_configs directory")
        }
    }
    // 一个配置文件同一时间只能被一个线程临幸♡ 不许多线程同时上——
    // 猫娘拿着 synchronized 的锁链站在门口，一个一个来，排好队喵♡
    val configLock = Any()

    dispatcher.register("load-forum-config") {
        synchronized(configLock) {
            safeLoadJson(File(configDir, "forum_config.json"))
        }
    }
    dispatcher.register("save-forum-config") { args ->
        synchronized(configLock) {
            val config = args.optJSONObject(0) ?: JSONObject()
            safeWriteJson(File(configDir, "forum_config.json"), config)
        }
        null
    }
    dispatcher.register("load-memo-config") {
        synchronized(configLock) {
            safeLoadJson(File(configDir, "memo_config.json"))
        }
    }
    dispatcher.register("save-memo-config") { args ->
        synchronized(configLock) {
            val config = args.optJSONObject(0) ?: JSONObject()
            safeWriteJson(File(configDir, "memo_config.json"), config)
        }
        null
    }

    // ── Stub handlers：从 IpcCatalog 统一注册♡ ──
    // 以前这里是 90+ 行的手写列表…现在全部由 Catalog 管理，
    // 新增 channel 只需在 IpcCatalog.kt 加一行，这里零改动喵
    dispatcher.registerStubsFromCatalog()
    Log.d(TAG, IpcCatalog.stats())

    // ── 终端 IPC channels ──
    if (terminalExecutor != null) {
        com.vcpnative.app.terminal.registerTerminalHandlers(dispatcher, terminalExecutor)
    }

    return dispatcher
}

// ── JSON 读写♡ ──
// 就算文件里面被搞得一塌糊涂、黏糊糊的全是乱码…猫娘也会温柔地帮你舔干净♡
// parse 失败就返回空 JSONObject——嘴里含着坏数据会吐出来，不会硬吞的喵

private fun safeLoadJson(file: File): JSONObject =
    if (file.exists()) {
        try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed for ${file.name}, returning empty: ${e.message}")
            JSONObject()
        }
    } else JSONObject()

private fun safeWriteJson(file: File, json: JSONObject) {
    try {
        // 先把所有内容倾注进 tmp 文件里♡ 一滴不漏地写完之后再 rename 覆盖上去…
        // 这样就算中途断电（高潮中断），原文件也不会被搞坏——原子操作的温柔♡
        // 猫娘红着脸看着 tmp → rename 的全过程…好羞耻但好安全喵
        val tmp = File(file.parent, "${file.name}.tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(file)) {
            // rename 失败就直接覆盖（同一文件系统下通常不会失败）
            file.writeText(json.toString(2))
            tmp.delete()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write ${file.name}: ${e.message}")
    }
}
