package com.vcpnative.app.data.sync

import com.vcpnative.app.data.room.MessageEntity
import java.security.MessageDigest

/**
 * Fast change detection for topic synchronization.
 *
 * Ported from VCPMobile `chat_manager.rs` TopicFingerprint + TopicDelta.
 * Allows comparing topics without loading full message content.
 */
data class TopicFingerprint(
    val topicId: String,
    val messageCount: Int,
    val lastModifiedMs: Long,
    val contentHash: String,
)

data class TopicDelta(
    val added: List<MessageEntity>,
    val updated: List<MessageEntity>,
    val deletedIds: List<String>,
)

/**
 * Compute a fingerprint for a topic's messages.
 * The hash is based on message IDs and timestamps, not full content,
 * so it's cheap to compute while still detecting changes.
 */
fun computeFingerprint(topicId: String, messages: List<MessageEntity>): TopicFingerprint {
    val content = messages.joinToString("|") { "${it.id}:${it.updatedAt}" }
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    return TopicFingerprint(
        topicId = topicId,
        messageCount = messages.size,
        lastModifiedMs = messages.maxOfOrNull { it.updatedAt } ?: 0L,
        contentHash = hash,
    )
}

/**
 * Compute delta between two message lists (by ID).
 */
fun computeDelta(
    local: List<MessageEntity>,
    remote: List<MessageEntity>,
): TopicDelta {
    val localById = local.associateBy { it.id }
    val remoteById = remote.associateBy { it.id }

    val added = remote.filter { it.id !in localById }
    val updated = remote.filter { msg ->
        val existing = localById[msg.id]
        existing != null && existing.updatedAt < msg.updatedAt
    }
    val deletedIds = local.filter { it.id !in remoteById }.map { it.id }

    return TopicDelta(added, updated, deletedIds)
}
