package com.vcpnative.app.data.groupchat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Group chat data models — aligns with VCPChat's Groupmodules/groupchat.js
 */
data class AgentGroup(
    val id: String,
    val name: String,
    val members: List<String> = emptyList(),
    val mode: String = "sequential", // "sequential" | "naturerandom" | "invite_only"
    val tagMatchMode: String = "strict", // "strict" | "natural"
    val memberTags: Map<String, String> = emptyMap(), // agentId -> comma-separated tags
    val groupPrompt: String = "",
    val invitePrompt: String = "",
    val useUnifiedModel: Boolean = false,
    val unifiedModel: String = "",
    val avatarPath: String? = null,
    val topics: List<GroupTopic> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("members", JSONArray(members))
        put("mode", mode)
        put("tagMatchMode", tagMatchMode)
        put("memberTags", JSONObject(memberTags))
        put("groupPrompt", groupPrompt)
        put("invitePrompt", invitePrompt)
        put("useUnifiedModel", useUnifiedModel)
        put("unifiedModel", unifiedModel)
        put("avatarPath", avatarPath ?: JSONObject.NULL)
        put("topics", JSONArray().apply {
            topics.forEach { put(it.toJson()) }
        })
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentGroup {
            val membersArr = json.optJSONArray("members") ?: JSONArray()
            val members = (0 until membersArr.length()).map { membersArr.getString(it) }

            val tagsObj = json.optJSONObject("memberTags") ?: JSONObject()
            val tags = mutableMapOf<String, String>()
            tagsObj.keys().forEach { tags[it] = tagsObj.optString(it, "") }

            val topicsArr = json.optJSONArray("topics") ?: JSONArray()
            val topics = (0 until topicsArr.length()).map { GroupTopic.fromJson(topicsArr.getJSONObject(it)) }

            return AgentGroup(
                id = json.optString("id"),
                name = json.optString("name"),
                members = members,
                mode = json.optString("mode", "sequential"),
                tagMatchMode = json.optString("tagMatchMode", "strict"),
                memberTags = tags,
                groupPrompt = json.optString("groupPrompt", ""),
                invitePrompt = json.optString("invitePrompt", ""),
                useUnifiedModel = json.optBoolean("useUnifiedModel", false),
                unifiedModel = json.optString("unifiedModel", ""),
                avatarPath = if (json.isNull("avatarPath")) null else json.optString("avatarPath").takeIf { it.isNotBlank() },
                topics = topics,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}

data class GroupTopic(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = GroupTopic(
            id = json.optString("id"),
            name = json.optString("name"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}
