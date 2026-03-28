package com.vcpnative.app.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.first
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "IpcHandlers"

/**
 * Creates an [IpcDispatcher] pre-loaded with handlers that bridge VCPChat's
 * Electron IPC channels to Android's data layer.
 *
 * Channels that are not yet implemented return null (the JS side handles
 * null results gracefully). This allows us to incrementally implement
 * handlers without breaking the modules.
 */
fun createIpcDispatcher(
    context: Context,
    settingsRepository: SettingsRepository,
    workspaceRepository: WorkspaceRepository,
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

    dispatcher.register("save-settings") { args ->
        // TODO: Implement full settings save from JSON when needed
        Log.d(TAG, "save-settings called (stub)")
        null
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
        JSONObject().apply { put("id", topic.id) }
    }

    dispatcher.register("delete-topic") { args ->
        val topicId = args.getString(1)
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

    dispatcher.register("save-chat-history") { args ->
        // TODO: Implement full history replacement when needed
        // For now this is a stub — individual message CRUD is handled natively
        Log.d(TAG, "save-chat-history called (stub)")
        null
    }

    // ---- Clipboard ----
    dispatcher.register("read-text-from-clipboard-main") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        JSONObject().apply {
            put("success", true)
            put("text", text)
        }
    }

    // ---- External Links ----
    dispatcher.register("open-external-link") { args ->
        val url = args.getString(0)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open link: $url", e)
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

    // ---- Forum / Memo config (simple JSON persistence) ----
    val configDir = File(context.filesDir, "module_configs").also { it.mkdirs() }

    dispatcher.register("load-forum-config") {
        val file = File(configDir, "forum_config.json")
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }
    dispatcher.register("save-forum-config") { args ->
        val config = args.optJSONObject(0) ?: JSONObject()
        File(configDir, "forum_config.json").writeText(config.toString())
        null
    }
    dispatcher.register("load-memo-config") {
        val file = File(configDir, "memo_config.json")
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }
    dispatcher.register("save-memo-config") { args ->
        val config = args.optJSONObject(0) ?: JSONObject()
        File(configDir, "memo_config.json").writeText(config.toString())
        null
    }

    // ---- Stub handlers for channels not yet implemented ----
    // These return null so the JS modules don't crash.
    // Implement incrementally as needed.

    val stubChannels = listOf(
        // Agent CRUD (write operations)
        "save-agent-config", "create-agent", "delete-agent",
        "save-avatar", "save-user-avatar", "select-avatar",
        "save-avatar-color", "update-agent-config",
        // Models
        "get-cached-models", "refresh-models", "get-hot-models",
        "get-favorite-models", "toggle-favorite-model",
        // Prompt
        "load-preset-prompts", "load-preset-content", "select-directory",
        "get-active-system-prompt", "programmatic-set-prompt-mode",
        // Orders
        "save-agent-order", "save-topic-order", "save-combined-item-order",
        // Topic extras
        "get-unread-topic-counts", "toggle-topic-lock", "set-topic-unread",
        "get-original-message-content",
        // Files
        "handle-file-paste", "select-files-to-send", "get-file-as-base64",
        "get-text-content", "handle-text-paste-as-file", "handle-file-drop",
        // Notes (remaining stubs)
        "notes:move-items",
        "save-pasted-image-to-file",
        "scan-network-notes",
        "get-cached-network-notes",
        "open-notes-window", "open-notes-with-content",
        // VCP Communication
        "send-to-vcp", "interrupt-vcp-request",
        // Group Chat
        "create-agent-group", "get-agent-groups", "get-agent-group-config",
        "save-agent-group-config", "delete-agent-group", "save-agent-group-avatar",
        "get-group-topics", "create-new-topic-for-group", "delete-group-topic",
        "save-group-topic-title", "get-group-chat-history", "save-group-chat-history",
        "send-group-chat-message", "save-group-topic-order",
        "search-topics-by-content", "inviteAgentToSpeak",
        "redo-group-chat-message", "interrupt-group-request",
        // Export
        "export-topic-as-markdown",
        // VCPLog
        "connect-vcplog", "disconnect-vcplog", "send-vcplog-message",
        // Image/Text viewer
        "show-image-context-menu", "open-image-viewer",
        "display-text-content-in-viewer",
        // Clipboard (image)
        "read-image-from-clipboard-main",
        // Translator
        "open-translator-window",
        // Dice
        "open-dice-window",
        // TTS
        "sovits-get-models", "sovits-speak", "sovits-stop",
        // Emoticons
        "get-emoticon-library",
        // Voice
        "open-voice-chat-window",
        "start-speech-recognition", "stop-speech-recognition",
        // Forum / Memo (remaining stubs)
        "open-forum-window", "open-memo-window",
        "load-agents-list", "load-user-avatar", "load-agent-avatar",
        // Canvas
        "open-canvas-window", "create-new-canvas", "load-canvas-file",
        "save-canvas-file", "rename-canvas-file", "copy-canvas-file",
        "delete-canvas-file", "get-latest-canvas-content",
        "watcher:start", "watcher:stop",
        // Themes
        "open-themes-window", "get-themes", "apply-theme",
        "set-theme", "set-theme-mode", "get-wallpaper-thumbnail",
        // Global warehouse
        "get-global-warehouse", "save-global-warehouse",
        "import-regex-rules",
        // Flowlock
        "flowlock-response",
        // Desktop push
        "desktop-push", "open-desktop-window",
        // Admin
        "open-admin-panel", "open-dev-tools",
        "toggle-notifications-sidebar",
    )
    stubChannels.forEach { channel ->
        if (!dispatcher.hasHandler(channel)) {
            dispatcher.register(channel) { null }
        }
    }

    return dispatcher
}
