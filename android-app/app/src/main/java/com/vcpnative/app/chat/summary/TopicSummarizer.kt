package com.vcpnative.app.chat.summary

import com.vcpnative.app.data.datastore.SettingsRepository
import com.vcpnative.app.data.repository.WorkspaceRepository
import com.vcpnative.app.data.room.MessageEntity
import com.vcpnative.app.network.vcp.toServiceConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * AI-powered automatic topic title summarization.
 *
 * Reference: /root/VCPChat/modules/topicSummarizer.js
 */
class TopicSummarizer(
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val okHttpClient: OkHttpClient,
) {
    /**
     * Attempt to summarize a topic title based on recent messages.
     * Returns the new title if successful, null otherwise.
     *
     * Conditions:
     * - At least 4 messages (2 conversation rounds)
     * - Current title still looks like a placeholder
     */
    suspend fun trySummarize(
        topicId: String,
        agentName: String,
    ): String? = withContext(Dispatchers.IO) {
        val topic = workspaceRepository.findTopic(topicId) ?: return@withContext null

        // Skip if title doesn't look like a placeholder
        if (!isPlaceholderTitle(topic.title)) return@withContext null

        val messages = workspaceRepository.loadMessages(topicId)
        if (messages.size < MIN_MESSAGES_FOR_SUMMARY) return@withContext null

        val settings = settingsRepository.currentSettings()
        if (!settings.isConfigured) return@withContext null

        val serviceConfig = settings.toServiceConfig()
        val recentMessages = messages.takeLast(4)

        val title = requestAiSummary(
            messages = recentMessages,
            agentName = agentName,
            userName = "用户",
            endpoint = serviceConfig.chatUrl(false),
            apiKey = serviceConfig.apiKey,
            model = settings.topicSummaryModel,
        ) ?: fallbackTitle(messages)

        if (title != null) {
            workspaceRepository.renameTopic(topicId, title)
        }
        title
    }

    private fun requestAiSummary(
        messages: List<MessageEntity>,
        agentName: String,
        userName: String,
        endpoint: String,
        apiKey: String,
        model: String,
    ): String? {
        val conversationText = messages.joinToString("\n") { msg ->
            val speaker = if (msg.role == "user") userName else agentName
            val content = msg.content.take(500)
            "$speaker: $content"
        }

        val prompt = "[待总结聊天记录: $conversationText]\n" +
            "请根据以上对话内容，仅返回一个简洁的话题标题。要求：" +
            "1. 标题长度控制在10个汉字以内。" +
            "2. 标题本身不能包含任何标点符号、数字编号或任何非标题文字。" +
            "3. 直接给出标题文字，不要添加任何解释或前缀。"

        val body = JSONObject().apply {
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            put("model", model)
            put("temperature", 0.3)
            put("max_tokens", 100)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body.string())
                val rawTitle = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?: return@use null

                cleanTitle(rawTitle)
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun cleanTitle(rawTitle: String): String? {
        var cleaned = rawTitle.split("\n").first().trim()
        cleaned = cleaned.replace(CLEAN_NON_CJK_REGEX, "")
        cleaned = cleaned.replace(CLEAN_PREFIX_NUMBER_REGEX, "")
        cleaned = cleaned.replace(CLEAN_PREFIX_LABEL_REGEX, "")
        cleaned = cleaned.replace(CLEAN_WHITESPACE_REGEX, "")
        if (cleaned.length > 12) {
            cleaned = cleaned.take(12)
        }
        return cleaned.ifBlank { null }
    }

    private fun fallbackTitle(messages: List<MessageEntity>): String? {
        val lastUserContent = messages
            .lastOrNull { it.role == "user" }
            ?.content
            ?.trim()
            ?.replace(FALLBACK_NEWLINE_REGEX, " ")
            ?.replace(CLEAN_WHITESPACE_REGEX, " ")
            ?.take(15)
            ?: return null
        return lastUserContent.ifBlank { null }
    }

    companion object {
        private const val MIN_MESSAGES_FOR_SUMMARY = 4

        private val PLACEHOLDER_PATTERN = Regex("""^新话题\s*\d+\s*·""")
        private val CLEAN_NON_CJK_REGEX = Regex("[^\\u4e00-\\u9fa5a-zA-Z\\s]")
        private val CLEAN_PREFIX_NUMBER_REGEX = Regex("^\\s*\\d+\\s*[.．\\s]\\s*")
        private val CLEAN_PREFIX_LABEL_REGEX = Regex("^(标题|总结|Topic)[:：\\s]*", RegexOption.IGNORE_CASE)
        private val CLEAN_WHITESPACE_REGEX = Regex("\\s+")
        private val FALLBACK_NEWLINE_REGEX = Regex("[\\n\\r\\t]+")

        fun isPlaceholderTitle(title: String): Boolean =
            PLACEHOLDER_PATTERN.containsMatchIn(title)
    }
}
