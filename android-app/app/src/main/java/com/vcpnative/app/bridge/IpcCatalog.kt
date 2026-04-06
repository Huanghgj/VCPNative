package com.vcpnative.app.bridge

/**
 * IPC 管线の统一花名册♡
 *
 * 从桌面端 VCPChat 的 roles.js + catalog.js + ipcContracts.js 移植而来。
 * 所有 IPC channel 集中声明——类型、分类、是否支持并发，一目了然♡
 * 不再让几百个 register 散落在各处，猫娘看着都心疼喵
 */

/** Channel 的通信类型♡ 对应桌面端 apiFactory.js 的三种 IPC 原语 */
enum class IpcChannelType {
    /** 有去无回——fire-and-forget，不等待返回值♡ */
    COMMAND,
    /** 一问一答——invoke 后等待结果返回♡ */
    QUERY,
    /** 持续订阅——注册回调，事件到来时被调用♡ */
    SUBSCRIPTION,
}

/** Channel 的功能分类♡ */
enum class IpcCategory {
    SETTINGS, PLATFORM, AGENT, TOPIC, CHAT_HISTORY,
    CLIPBOARD, NOTES, MODELS, MODULE_CONFIG, FILES,
    VCP_COMM, GROUP_CHAT, EXPORT, VCPLOG, VIEWER,
    TTS, VOICE, CANVAS, THEMES, EMOTICON, TERMINAL,
    DESKTOP, ADMIN, FLOWLOCK, WAREHOUSE, PROMPT, ORDERS,
}

data class IpcChannelMeta(
    val channel: String,
    val type: IpcChannelType = IpcChannelType.QUERY,
    val category: IpcCategory,
    /** 是否支持并发调用♡ false = 同一时间只允许一个飞行中请求（幂等性防护） */
    val supportsConcurrent: Boolean = true,
    /** 是否已在 Android 端实现（false = stub，返回 null） */
    val implemented: Boolean = false,
)

/**
 * 全量 IPC channel 注册表♡
 *
 * 对齐桌面端 preloads/shared/roles.js 中的 CHAT_KEYS + UTILITY_KEYS 列表。
 * 每个 channel 的实现状态、类型、分类、并发策略一目了然喵
 */
object IpcCatalog {

    val channels: List<IpcChannelMeta> = listOf(
        // ── Settings ──
        IpcChannelMeta("load-settings", IpcChannelType.QUERY, IpcCategory.SETTINGS, implemented = true),
        IpcChannelMeta("save-settings", IpcChannelType.QUERY, IpcCategory.SETTINGS, supportsConcurrent = false, implemented = true),

        // ── Platform ──
        IpcChannelMeta("get-platform", IpcChannelType.QUERY, IpcCategory.PLATFORM, implemented = true),
        IpcChannelMeta("get-current-theme", IpcChannelType.QUERY, IpcCategory.PLATFORM, implemented = true),

        // ── Agents ──
        IpcChannelMeta("get-agents", IpcChannelType.QUERY, IpcCategory.AGENT, implemented = true),
        IpcChannelMeta("get-agent-config", IpcChannelType.QUERY, IpcCategory.AGENT, implemented = true),
        IpcChannelMeta("get-all-items", IpcChannelType.QUERY, IpcCategory.AGENT, implemented = true),
        IpcChannelMeta("save-agent-config", IpcChannelType.QUERY, IpcCategory.AGENT, supportsConcurrent = false),
        IpcChannelMeta("create-agent", IpcChannelType.QUERY, IpcCategory.AGENT),
        IpcChannelMeta("delete-agent", IpcChannelType.QUERY, IpcCategory.AGENT, supportsConcurrent = false),
        IpcChannelMeta("save-avatar", IpcChannelType.QUERY, IpcCategory.AGENT),
        IpcChannelMeta("save-user-avatar", IpcChannelType.QUERY, IpcCategory.AGENT),
        IpcChannelMeta("select-avatar", IpcChannelType.QUERY, IpcCategory.AGENT),
        IpcChannelMeta("save-avatar-color", IpcChannelType.QUERY, IpcCategory.AGENT),
        IpcChannelMeta("update-agent-config", IpcChannelType.QUERY, IpcCategory.AGENT),
        IpcChannelMeta("load-agents-list", IpcChannelType.QUERY, IpcCategory.AGENT, implemented = true),
        IpcChannelMeta("load-user-avatar", IpcChannelType.QUERY, IpcCategory.AGENT, implemented = true),
        IpcChannelMeta("load-agent-avatar", IpcChannelType.QUERY, IpcCategory.AGENT, implemented = true),

        // ── Topics ──
        IpcChannelMeta("get-agent-topics", IpcChannelType.QUERY, IpcCategory.TOPIC, implemented = true),
        IpcChannelMeta("create-new-topic-for-agent", IpcChannelType.QUERY, IpcCategory.TOPIC, supportsConcurrent = false, implemented = true),
        IpcChannelMeta("delete-topic", IpcChannelType.QUERY, IpcCategory.TOPIC, supportsConcurrent = false, implemented = true),
        IpcChannelMeta("save-agent-topic-title", IpcChannelType.QUERY, IpcCategory.TOPIC, implemented = true),
        IpcChannelMeta("get-unread-topic-counts", IpcChannelType.QUERY, IpcCategory.TOPIC),
        IpcChannelMeta("toggle-topic-lock", IpcChannelType.QUERY, IpcCategory.TOPIC),
        IpcChannelMeta("set-topic-unread", IpcChannelType.QUERY, IpcCategory.TOPIC),

        // ── Chat History ──
        IpcChannelMeta("get-chat-history", IpcChannelType.QUERY, IpcCategory.CHAT_HISTORY, implemented = true),
        IpcChannelMeta("save-chat-history", IpcChannelType.QUERY, IpcCategory.CHAT_HISTORY, supportsConcurrent = false, implemented = true),
        IpcChannelMeta("get-original-message-content", IpcChannelType.QUERY, IpcCategory.CHAT_HISTORY),

        // ── Clipboard ──
        IpcChannelMeta("read-text-from-clipboard-main", IpcChannelType.QUERY, IpcCategory.CLIPBOARD, implemented = true),
        IpcChannelMeta("read-image-from-clipboard-main", IpcChannelType.QUERY, IpcCategory.CLIPBOARD),

        // ── External Link ──
        IpcChannelMeta("open-external-link", IpcChannelType.COMMAND, IpcCategory.PLATFORM, implemented = true),

        // ── Notes ──
        IpcChannelMeta("read-notes-tree", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("write-txt-note", IpcChannelType.QUERY, IpcCategory.NOTES, supportsConcurrent = false, implemented = true),
        IpcChannelMeta("delete-item", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("create-note-folder", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("rename-item", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("search-notes", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("get-notes-root-dir", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("copy-note-content", IpcChannelType.QUERY, IpcCategory.NOTES, implemented = true),
        IpcChannelMeta("notes:move-items", IpcChannelType.QUERY, IpcCategory.NOTES),
        IpcChannelMeta("save-pasted-image-to-file", IpcChannelType.QUERY, IpcCategory.NOTES),
        IpcChannelMeta("scan-network-notes", IpcChannelType.COMMAND, IpcCategory.NOTES),
        IpcChannelMeta("get-cached-network-notes", IpcChannelType.QUERY, IpcCategory.NOTES),
        IpcChannelMeta("open-notes-window", IpcChannelType.QUERY, IpcCategory.NOTES),
        IpcChannelMeta("open-notes-with-content", IpcChannelType.QUERY, IpcCategory.NOTES),

        // ── Models ──
        IpcChannelMeta("get-cached-models", IpcChannelType.QUERY, IpcCategory.MODELS, implemented = true),
        IpcChannelMeta("refresh-models", IpcChannelType.COMMAND, IpcCategory.MODELS),
        IpcChannelMeta("get-hot-models", IpcChannelType.QUERY, IpcCategory.MODELS),
        IpcChannelMeta("get-favorite-models", IpcChannelType.QUERY, IpcCategory.MODELS),
        IpcChannelMeta("toggle-favorite-model", IpcChannelType.QUERY, IpcCategory.MODELS),

        // ── Module Config ──
        IpcChannelMeta("load-forum-config", IpcChannelType.QUERY, IpcCategory.MODULE_CONFIG, implemented = true),
        IpcChannelMeta("save-forum-config", IpcChannelType.QUERY, IpcCategory.MODULE_CONFIG, supportsConcurrent = false, implemented = true),
        IpcChannelMeta("load-memo-config", IpcChannelType.QUERY, IpcCategory.MODULE_CONFIG, implemented = true),
        IpcChannelMeta("save-memo-config", IpcChannelType.QUERY, IpcCategory.MODULE_CONFIG, supportsConcurrent = false, implemented = true),

        // ── Prompt ──
        IpcChannelMeta("load-preset-prompts", IpcChannelType.QUERY, IpcCategory.PROMPT),
        IpcChannelMeta("load-preset-content", IpcChannelType.QUERY, IpcCategory.PROMPT),
        IpcChannelMeta("select-directory", IpcChannelType.QUERY, IpcCategory.PROMPT),
        IpcChannelMeta("get-active-system-prompt", IpcChannelType.QUERY, IpcCategory.PROMPT),
        IpcChannelMeta("programmatic-set-prompt-mode", IpcChannelType.QUERY, IpcCategory.PROMPT),

        // ── Orders ──
        IpcChannelMeta("save-agent-order", IpcChannelType.QUERY, IpcCategory.ORDERS),
        IpcChannelMeta("save-topic-order", IpcChannelType.QUERY, IpcCategory.ORDERS),
        IpcChannelMeta("save-combined-item-order", IpcChannelType.QUERY, IpcCategory.ORDERS),

        // ── Files ──
        IpcChannelMeta("handle-file-paste", IpcChannelType.QUERY, IpcCategory.FILES),
        IpcChannelMeta("select-files-to-send", IpcChannelType.QUERY, IpcCategory.FILES),
        IpcChannelMeta("get-file-as-base64", IpcChannelType.QUERY, IpcCategory.FILES),
        IpcChannelMeta("get-text-content", IpcChannelType.QUERY, IpcCategory.FILES),
        IpcChannelMeta("handle-text-paste-as-file", IpcChannelType.QUERY, IpcCategory.FILES),
        IpcChannelMeta("handle-file-drop", IpcChannelType.QUERY, IpcCategory.FILES),

        // ── VCP Communication ──
        IpcChannelMeta("send-to-vcp", IpcChannelType.QUERY, IpcCategory.VCP_COMM, supportsConcurrent = false),
        IpcChannelMeta("interrupt-vcp-request", IpcChannelType.QUERY, IpcCategory.VCP_COMM),

        // ── Group Chat ──
        IpcChannelMeta("create-agent-group", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("get-agent-groups", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("get-agent-group-config", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("save-agent-group-config", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("delete-agent-group", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("save-agent-group-avatar", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("get-group-topics", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("create-new-topic-for-group", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("delete-group-topic", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("save-group-topic-title", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("get-group-chat-history", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("save-group-chat-history", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("send-group-chat-message", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("save-group-topic-order", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("search-topics-by-content", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("inviteAgentToSpeak", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("redo-group-chat-message", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),
        IpcChannelMeta("interrupt-group-request", IpcChannelType.QUERY, IpcCategory.GROUP_CHAT),

        // ── Export ──
        IpcChannelMeta("export-topic-as-markdown", IpcChannelType.QUERY, IpcCategory.EXPORT),

        // ── VCPLog ──
        IpcChannelMeta("connect-vcplog", IpcChannelType.COMMAND, IpcCategory.VCPLOG),
        IpcChannelMeta("disconnect-vcplog", IpcChannelType.COMMAND, IpcCategory.VCPLOG),
        IpcChannelMeta("send-vcplog-message", IpcChannelType.COMMAND, IpcCategory.VCPLOG),

        // ── Viewer ──
        IpcChannelMeta("show-image-context-menu", IpcChannelType.COMMAND, IpcCategory.VIEWER),
        IpcChannelMeta("open-image-viewer", IpcChannelType.COMMAND, IpcCategory.VIEWER),
        IpcChannelMeta("display-text-content-in-viewer", IpcChannelType.QUERY, IpcCategory.VIEWER),

        // ── TTS ──
        IpcChannelMeta("sovits-get-models", IpcChannelType.QUERY, IpcCategory.TTS),
        IpcChannelMeta("sovits-speak", IpcChannelType.COMMAND, IpcCategory.TTS),
        IpcChannelMeta("sovits-stop", IpcChannelType.COMMAND, IpcCategory.TTS),

        // ── Emoticon ──
        IpcChannelMeta("get-emoticon-library", IpcChannelType.QUERY, IpcCategory.EMOTICON),

        // ── Voice ──
        IpcChannelMeta("open-voice-chat-window", IpcChannelType.COMMAND, IpcCategory.VOICE),
        IpcChannelMeta("start-speech-recognition", IpcChannelType.COMMAND, IpcCategory.VOICE),
        IpcChannelMeta("stop-speech-recognition", IpcChannelType.COMMAND, IpcCategory.VOICE),

        // ── Canvas ──
        IpcChannelMeta("open-canvas-window", IpcChannelType.QUERY, IpcCategory.CANVAS),
        IpcChannelMeta("create-new-canvas", IpcChannelType.COMMAND, IpcCategory.CANVAS),
        IpcChannelMeta("load-canvas-file", IpcChannelType.COMMAND, IpcCategory.CANVAS),
        IpcChannelMeta("save-canvas-file", IpcChannelType.COMMAND, IpcCategory.CANVAS),
        IpcChannelMeta("rename-canvas-file", IpcChannelType.QUERY, IpcCategory.CANVAS),
        IpcChannelMeta("copy-canvas-file", IpcChannelType.COMMAND, IpcCategory.CANVAS),
        IpcChannelMeta("delete-canvas-file", IpcChannelType.COMMAND, IpcCategory.CANVAS),
        IpcChannelMeta("get-latest-canvas-content", IpcChannelType.QUERY, IpcCategory.CANVAS),
        IpcChannelMeta("watcher:start", IpcChannelType.QUERY, IpcCategory.CANVAS),
        IpcChannelMeta("watcher:stop", IpcChannelType.QUERY, IpcCategory.CANVAS),

        // ── Themes ──
        IpcChannelMeta("open-themes-window", IpcChannelType.COMMAND, IpcCategory.THEMES),
        IpcChannelMeta("get-themes", IpcChannelType.QUERY, IpcCategory.THEMES),
        IpcChannelMeta("apply-theme", IpcChannelType.COMMAND, IpcCategory.THEMES),
        IpcChannelMeta("set-theme", IpcChannelType.COMMAND, IpcCategory.THEMES),
        IpcChannelMeta("set-theme-mode", IpcChannelType.COMMAND, IpcCategory.THEMES),
        IpcChannelMeta("get-wallpaper-thumbnail", IpcChannelType.QUERY, IpcCategory.THEMES),

        // ── Warehouse ──
        IpcChannelMeta("get-global-warehouse", IpcChannelType.QUERY, IpcCategory.WAREHOUSE),
        IpcChannelMeta("save-global-warehouse", IpcChannelType.QUERY, IpcCategory.WAREHOUSE),
        IpcChannelMeta("import-regex-rules", IpcChannelType.QUERY, IpcCategory.WAREHOUSE),

        // ── Flowlock ──
        IpcChannelMeta("flowlock-response", IpcChannelType.COMMAND, IpcCategory.FLOWLOCK),

        // ── Desktop ──
        IpcChannelMeta("desktop-push", IpcChannelType.COMMAND, IpcCategory.DESKTOP),
        IpcChannelMeta("open-desktop-window", IpcChannelType.QUERY, IpcCategory.DESKTOP),

        // ── Admin ──
        IpcChannelMeta("open-admin-panel", IpcChannelType.QUERY, IpcCategory.ADMIN),
        IpcChannelMeta("open-dev-tools", IpcChannelType.COMMAND, IpcCategory.ADMIN),
        IpcChannelMeta("toggle-notifications-sidebar", IpcChannelType.COMMAND, IpcCategory.ADMIN),

        // ── Forum / Memo windows ──
        IpcChannelMeta("open-forum-window", IpcChannelType.COMMAND, IpcCategory.MODULE_CONFIG),
        IpcChannelMeta("open-memo-window", IpcChannelType.COMMAND, IpcCategory.MODULE_CONFIG),
        IpcChannelMeta("open-translator-window", IpcChannelType.COMMAND, IpcCategory.VIEWER),
        IpcChannelMeta("open-dice-window", IpcChannelType.QUERY, IpcCategory.VIEWER),
    )

    private val byChannel: Map<String, IpcChannelMeta> = channels.associateBy { it.channel }

    /** 查询某个 channel 的元数据♡ */
    fun getMeta(channel: String): IpcChannelMeta? = byChannel[channel]

    /** 获取所有未实现的 stub channel 列表♡ */
    fun stubChannels(): List<String> = channels.filter { !it.implemented }.map { it.channel }

    /** 获取不支持并发的 channel 列表（需要幂等性防护）♡ */
    fun nonConcurrentChannels(): Set<String> = channels
        .filter { !it.supportsConcurrent }
        .map { it.channel }
        .toSet()

    /** 统计信息♡ */
    fun stats(): String {
        val total = channels.size
        val impl = channels.count { it.implemented }
        return "IPC Catalog: $total channels ($impl implemented, ${total - impl} stubs)"
    }
}
