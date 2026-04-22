package com.vcpnative.app.data.taskassistant

import org.json.JSONArray
import org.json.JSONObject

// ── AgentAssistant (AA) ──────────────────────────

data class AAConfig(
    val agents: List<AAAgent>,
    val maxHistoryRounds: Int = 7,
    val contextTtlHours: Int = 24,
    val globalSystemPrompt: String = "",
    /** Preserve unknown fields for round-trip fidelity. */
    val extraJson: JSONObject? = null,
)

data class AAAgent(
    val chineseName: String = "",
    val baseName: String = "",
    val modelId: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val maxOutputTokens: Int = 8000,
    val temperature: Double = 0.7,
    /** Preserve unknown fields for round-trip fidelity. */
    val extraJson: JSONObject? = null,
)

// ── TaskAssistant (FA) ───────────────────────────

data class FAConfig(
    val globalEnabled: Boolean = false,
    val tasks: List<FATask> = emptyList(),
    val maxHistory: Int = 200,
)

data class FATask(
    val id: String,
    val name: String = "",
    val type: TaskType = TaskType.CUSTOM_PROMPT,
    val enabled: Boolean = false,
    val schedule: FASchedule = FASchedule(),
    val targets: List<String> = emptyList(),
    val taskDelegation: Boolean = false,
    val promptTemplate: String = "",
    val includeForumPostList: Boolean = true,
    val forumListPlaceholder: String = "{{forum_post_list}}",
    val maxPosts: Int = 200,
)

enum class TaskType(val apiValue: String) {
    FORUM_PATROL("forum_patrol"),
    CUSTOM_PROMPT("custom_prompt");

    companion object {
        fun fromApi(value: String): TaskType =
            entries.firstOrNull { it.apiValue == value } ?: CUSTOM_PROMPT
    }
}

data class FASchedule(
    val mode: ScheduleMode = ScheduleMode.MANUAL,
    val intervalMinutes: Int? = null,
    val cronValue: String? = null,
    val runAt: String? = null,
)

enum class ScheduleMode(val apiValue: String) {
    INTERVAL("interval"),
    CRON("cron"),
    MANUAL("manual"),
    ONCE("once");

    companion object {
        fun fromApi(value: String): ScheduleMode =
            entries.firstOrNull { it.apiValue == value } ?: MANUAL
    }
}

data class FAStatus(
    val globalEnabled: Boolean = false,
    val activeTimerCount: Int = 0,
    val history: List<FAHistoryEntry> = emptyList(),
)

data class FAHistoryEntry(
    val taskId: String,
    val taskName: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val success: Boolean,
)

// ── JSON Parsing ─────────────────────────────────

fun JSONObject.toAAConfig(): AAConfig {
    val agents = optJSONArray("agents")?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it).toAAAgent() }
    } ?: emptyList()
    return AAConfig(
        agents = agents,
        maxHistoryRounds = optInt("maxHistoryRounds", 7),
        contextTtlHours = optInt("contextTtlHours", 24),
        globalSystemPrompt = optString("globalSystemPrompt", ""),
        extraJson = this,
    )
}

fun JSONObject.toAAAgent(): AAAgent = AAAgent(
    chineseName = optString("chineseName", ""),
    baseName = optString("baseName", ""),
    modelId = optString("modelId", ""),
    description = optString("description", ""),
    systemPrompt = optString("systemPrompt", ""),
    maxOutputTokens = optInt("maxOutputTokens", 8000),
    temperature = optDouble("temperature", 0.7),
    extraJson = this,
)

fun JSONObject.toFAConfig(): FAConfig {
    // Handle both nested { config: { tasks: [...] } } and flat { tasks: [...] }
    val tasksArr = optJSONObject("config")?.optJSONArray("tasks")
        ?: optJSONArray("tasks")
        ?: JSONArray()
    val tasks = (0 until tasksArr.length()).map { tasksArr.getJSONObject(it).toFATask() }
    return FAConfig(
        globalEnabled = optBoolean("globalEnabled", false),
        tasks = tasks,
        maxHistory = optJSONObject("settings")?.optInt("maxHistory", 200) ?: 200,
    )
}

fun JSONObject.toFATask(): FATask {
    val schedule = optJSONObject("schedule")
    val targets = optJSONObject("targets")?.optJSONArray("agents")
    val payload = optJSONObject("payload")
    val dispatch = optJSONObject("dispatch")
    return FATask(
        id = optString("id", ""),
        name = optString("name", ""),
        type = TaskType.fromApi(optString("type", "custom_prompt")),
        enabled = optBoolean("enabled", false),
        schedule = FASchedule(
            mode = ScheduleMode.fromApi(schedule?.optString("mode", "manual") ?: "manual"),
            intervalMinutes = schedule?.takeIf { it.has("intervalMinutes") }?.optInt("intervalMinutes"),
            cronValue = schedule?.optString("cronValue")?.takeIf { it.isNotEmpty() },
            runAt = schedule?.optString("runAt")?.takeIf { it.isNotEmpty() },
        ),
        targets = targets?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList(),
        taskDelegation = dispatch?.optBoolean("taskDelegation", false) ?: false,
        promptTemplate = payload?.optString("promptTemplate", "") ?: "",
        includeForumPostList = payload?.optBoolean("includeForumPostList", true) ?: true,
        forumListPlaceholder = payload?.optString("forumListPlaceholder", "{{forum_post_list}}") ?: "{{forum_post_list}}",
        maxPosts = payload?.optInt("maxPosts", 200) ?: 200,
    )
}

fun JSONObject.toFAStatus(): FAStatus {
    val history = optJSONArray("history")?.let { arr ->
        (0 until arr.length()).map { i ->
            val h = arr.getJSONObject(i)
            FAHistoryEntry(
                taskId = h.optString("taskId", ""),
                taskName = h.optString("taskName", ""),
                startedAt = h.optString("startedAt").takeIf { it.isNotEmpty() } ?: h.optString("time").takeIf { it.isNotEmpty() },
                finishedAt = h.optString("finishedAt").takeIf { it.isNotEmpty() },
                success = h.optBoolean("success", h.optString("status") == "success"),
            )
        }
    } ?: emptyList()
    return FAStatus(
        globalEnabled = optBoolean("globalEnabled", false),
        activeTimerCount = optInt("activeTimerCount", 0),
        history = history,
    )
}

// ── JSON Serialization ───────────────────────────

fun AAConfig.toJson(): JSONObject {
    val base = extraJson?.let { JSONObject(it.toString()) } ?: JSONObject()
    base.put("agents", JSONArray().apply {
        agents.forEach { put(it.toJson()) }
    })
    base.put("maxHistoryRounds", maxHistoryRounds)
    base.put("contextTtlHours", contextTtlHours)
    base.put("globalSystemPrompt", globalSystemPrompt)
    return base
}

fun AAAgent.toJson(): JSONObject {
    val base = extraJson?.let { JSONObject(it.toString()) } ?: JSONObject()
    base.put("chineseName", chineseName)
    base.put("baseName", baseName)
    base.put("modelId", modelId)
    base.put("description", description)
    base.put("systemPrompt", systemPrompt)
    base.put("maxOutputTokens", maxOutputTokens)
    base.put("temperature", temperature)
    return base
}

fun FAConfig.toJson(): JSONObject = JSONObject().apply {
    put("globalEnabled", globalEnabled)
    put("tasks", JSONArray().apply {
        tasks.forEach { put(it.toJson()) }
    })
    put("settings", JSONObject().put("maxHistory", maxHistory))
}

fun FATask.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("type", type.apiValue)
    put("enabled", enabled)
    put("schedule", JSONObject().apply {
        put("mode", schedule.mode.apiValue)
        schedule.intervalMinutes?.let { put("intervalMinutes", it) }
        schedule.cronValue?.let { put("cronValue", it) }
        schedule.runAt?.let { put("runAt", it) }
    })
    put("targets", JSONObject().put("agents", JSONArray().apply {
        targets.forEach { put(it) }
    }))
    put("dispatch", JSONObject().put("taskDelegation", taskDelegation))
    put("payload", JSONObject().apply {
        put("promptTemplate", promptTemplate)
        if (type == TaskType.FORUM_PATROL) {
            put("includeForumPostList", includeForumPostList)
            put("forumListPlaceholder", forumListPlaceholder)
            put("maxPosts", maxPosts)
        }
    })
}
