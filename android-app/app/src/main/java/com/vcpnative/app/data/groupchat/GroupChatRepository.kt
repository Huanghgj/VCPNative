package com.vcpnative.app.data.groupchat

import android.util.Log
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.data.files.AtomicFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * File-based repository for group chat data.
 * Aligns with VCPChat's storage layout:
 *   AppData/AgentGroups/{groupId}/config.json
 *   UserData/{groupId}/topics/{topicId}/history.json
 */
class GroupChatRepository(
    private val fileStore: AppFileStore,
) {
    private val groupsDir: File get() = File(fileStore.compatAppDataDir(), "AgentGroups")
    private val userDataDir: File get() = fileStore.compatUserDataDir()
    // Mutex per topic to prevent concurrent read-modify-write on history files.
    // Entries are evicted when topics/groups are deleted to prevent unbounded growth.
    private val historyMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    // ── Group CRUD ──────────────────────────────────

    suspend fun getGroups(): List<AgentGroup> = withContext(Dispatchers.IO) {
        val dir = groupsDir
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles()?.mapNotNull { folder ->
            val config = File(folder, "config.json")
            if (config.exists()) {
                runCatching { AgentGroup.fromJson(JSONObject(config.readText())) }.getOrNull()
            } else null
        }?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    suspend fun getGroupConfig(groupId: String): AgentGroup? = withContext(Dispatchers.IO) {
        val config = File(groupsDir, "$groupId/config.json")
        if (!config.exists()) return@withContext null
        runCatching { AgentGroup.fromJson(JSONObject(config.readText())) }.getOrNull()
    }

    suspend fun createGroup(name: String, members: List<String> = emptyList()): AgentGroup =
        withContext(Dispatchers.IO) {
            val sanitizedName = name.take(10).replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "")
            // Fallback to "group" if name is all special chars; UUID suffix prevents collision on same-millisecond creates
            val namePart = sanitizedName.ifBlank { "group" }
            val id = "${namePart}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
            val group = AgentGroup(
                id = id,
                name = name,
                members = members,
            )
            saveGroupConfig(group)
            group
        }

    suspend fun saveGroupConfig(group: AgentGroup): Unit = withContext(Dispatchers.IO) {
        val dir = File(groupsDir, group.id)
        dir.mkdirs()
        AtomicFileWriter.writeJson(File(dir, "config.json"), group.toJson().toString(2))
    }

    suspend fun deleteGroup(groupId: String): Unit = withContext(Dispatchers.IO) {
        File(groupsDir, groupId).deleteRecursively()
        File(userDataDir, groupId).deleteRecursively()
        // Evict all mutexes belonging to this group to prevent memory leak
        historyMutexes.keys.removeAll { it.startsWith("$groupId/") }
    }

    // ── Topics ──────────────────────────────────────

    suspend fun createTopic(groupId: String, name: String = "新话题"): GroupTopic? =
        withContext(Dispatchers.IO) {
            val group = getGroupConfig(groupId) ?: return@withContext null
            val topic = GroupTopic(
                id = "topic_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                name = name,
            )
            val updated = group.copy(
                topics = group.topics + topic,
                updatedAt = System.currentTimeMillis(),
            )
            saveGroupConfig(updated)
            // Initialize empty history
            val historyFile = historyFile(groupId, topic.id)
            historyFile.parentFile?.mkdirs()
            AtomicFileWriter.writeJson(historyFile, "[]")
            topic
        }

    suspend fun deleteTopic(groupId: String, topicId: String): Unit = withContext(Dispatchers.IO) {
        val group = getGroupConfig(groupId) ?: return@withContext
        val updated = group.copy(
            topics = group.topics.filterNot { it.id == topicId },
            updatedAt = System.currentTimeMillis(),
        )
        saveGroupConfig(updated)
        File(userDataDir, "$groupId/topics/$topicId").deleteRecursively()
        historyMutexes.remove("$groupId/$topicId")
    }

    suspend fun renameTopic(groupId: String, topicId: String, newTitle: String): Unit =
        withContext(Dispatchers.IO) {
            val group = getGroupConfig(groupId) ?: return@withContext
            val updated = group.copy(
                topics = group.topics.map {
                    if (it.id == topicId) it.copy(name = newTitle) else it
                },
                updatedAt = System.currentTimeMillis(),
            )
            saveGroupConfig(updated)
        }

    // ── History ─────────────────────────────────────

    suspend fun loadHistory(groupId: String, topicId: String): JSONArray =
        withContext(Dispatchers.IO) {
            val file = historyFile(groupId, topicId)
            if (!file.exists() || file.length() == 0L) return@withContext JSONArray()
            val text = file.readText().trim()
            if (text.startsWith("[")) {
                // 旧 JSONArray 格式
                runCatching { JSONArray(text) }.getOrDefault(JSONArray())
            } else {
                // JSONL 格式：每行一个 JSON 对象
                val result = JSONArray()
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    runCatching { result.put(JSONObject(line)) }
                }
                result
            }
        }

    suspend fun saveHistory(groupId: String, topicId: String, history: JSONArray): Unit =
        withContext(Dispatchers.IO) {
            val file = historyFile(groupId, topicId)
            file.parentFile?.mkdirs()
            // 统一写为 JSONL 格式，避免 appendMessage 每次都要迁移格式喵
            val jsonl = buildString {
                for (i in 0 until history.length()) {
                    appendLine(history.getJSONObject(i).toString())
                }
            }
            AtomicFileWriter.writeJson(file, jsonl)
        }

    suspend fun appendMessage(groupId: String, topicId: String, message: JSONObject): Unit {
        val key = "$groupId/$topicId"
        val mutex = historyMutexes.computeIfAbsent(key) { Mutex() }
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = historyFile(groupId, topicId)
                file.parentFile?.mkdirs()
                // 追加写入：如果文件已有内容且不以 [ 开头（旧格式 JSONArray），
                // 先迁移为 JSONL 再追加；否则直接追加一行 JSON——
                // 避免每条消息都全量读写整个历史文件，省 IO 省电喵
                if (file.exists() && file.length() > 0) {
                    val firstByte = file.inputStream().use { it.read().toChar() }
                    if (firstByte == '[') {
                        // 旧 JSONArray 格式 → 原子迁移为 JSONL
                        val oldHistory = runCatching { JSONArray(file.readText()) }
                            .getOrDefault(JSONArray())
                        val jsonl = buildString {
                            for (i in 0 until oldHistory.length()) {
                                appendLine(oldHistory.getJSONObject(i).toString())
                            }
                        }
                        AtomicFileWriter.writeJson(file, jsonl)
                    }
                }
                file.appendText(message.toString() + "\n")
            }
        }
    }

    suspend fun removeMessage(groupId: String, topicId: String, messageId: String): Unit {
        val key = "$groupId/$topicId"
        val mutex = historyMutexes.computeIfAbsent(key) { Mutex() }
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val history = loadHistory(groupId, topicId)
                val filtered = JSONArray()
                for (i in 0 until history.length()) {
                    val msg = history.optJSONObject(i) ?: continue
                    if (msg.optString("id") != messageId) {
                        filtered.put(msg)
                    }
                }
                saveHistory(groupId, topicId, filtered)
            }
        }
    }

    private fun historyFile(groupId: String, topicId: String): File =
        File(userDataDir, "$groupId/topics/$topicId/history.json")

    companion object {
        private const val TAG = "GroupChatRepo"
    }
}
