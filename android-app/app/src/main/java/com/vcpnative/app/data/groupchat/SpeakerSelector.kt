package com.vcpnative.app.data.groupchat

import com.vcpnative.app.data.cache.RegexCache
import kotlin.random.Random

/**
 * 5-tier speaker selection engine for group chat.
 *
 * Ported from VCPMobile `group_orchestrator.rs` determine_naturerandom_speakers().
 *
 * Tier 1: Direct @mentions (@agentName)
 * Tier 2: Tag matching (natural/strict mode)
 * Tier 3: @everyone broadcast
 * Tier 4: Probabilistic response (15% base / 85% with tags)
 * Tier 5: Fallback (ensure at least 1 speaker)
 */
class SpeakerSelector {

    data class SpeakerResult(
        val speakers: List<String>,   // selected agentIds
        val tier: Int,
        val reason: String,
    )

    data class GroupMember(
        val agentId: String,
        val name: String,
        val tags: List<String> = emptyList(),
    )

    data class HistoryMessage(
        val agentId: String?,
        val content: String,
    )

    /**
     * Select speakers for the next group chat turn.
     *
     * @param userMessage  The user's message text
     * @param members      All group members
     * @param recentHistory Last 8 messages for context
     * @param mode         "sequential" or "naturerandom"
     * @param tagMatchMode "natural" (probabilistic) or "strict" (deterministic)
     */
    fun selectSpeakers(
        userMessage: String,
        members: List<GroupMember>,
        recentHistory: List<HistoryMessage> = emptyList(),
        mode: String = "sequential",
        tagMatchMode: String = "natural",
    ): SpeakerResult {
        if (mode == "sequential") {
            return SpeakerResult(members.map { it.agentId }, 0, "Sequential: all members speak")
        }

        if (members.isEmpty()) {
            return SpeakerResult(emptyList(), 5, "No members")
        }

        // Tier 1: Direct @mentions
        val mentions = extractMentions(userMessage, members)
        if (mentions.isNotEmpty()) {
            return SpeakerResult(mentions, 1, "Direct @mention")
        }

        // Tier 3: @everyone
        if (userMessage.contains("@所有人") || userMessage.contains("@everyone") || userMessage.contains("@all")) {
            return SpeakerResult(members.map { it.agentId }, 3, "@everyone broadcast")
        }

        // Tier 2: Tag matching
        val tagMatches = matchTags(userMessage, members, tagMatchMode)
        val selected = tagMatches.toMutableSet()

        // Tier 4: Probabilistic responses
        val baseProbability = if (selected.isNotEmpty()) 0.85 else 0.15
        for (member in members) {
            if (member.agentId !in selected) {
                val adjusted = adjustProbability(member, recentHistory, baseProbability)
                if (Random.nextDouble() < adjusted) {
                    selected.add(member.agentId)
                }
            }
        }

        // Tier 5: Fallback — ensure at least 1 speaker
        if (selected.isEmpty()) {
            val fallback = selectFallback(members, recentHistory)
            return SpeakerResult(listOf(fallback.agentId), 5, "Fallback selection")
        }

        val tier = if (tagMatches.isNotEmpty()) 2 else 4
        return SpeakerResult(selected.toList(), tier, "Selected ${selected.size} speakers")
    }

    /** Extract @agentName mentions from message. */
    private fun extractMentions(message: String, members: List<GroupMember>): List<String> {
        return members.filter { member ->
            message.contains("@${member.name}")
        }.map { it.agentId }
    }

    /** Match members by tags present in the message. */
    private fun matchTags(
        message: String,
        members: List<GroupMember>,
        mode: String,
    ): Set<String> {
        val matched = mutableSetOf<String>()
        for (member in members) {
            for (tag in member.tags) {
                if (tag.isBlank()) continue
                if (message.contains(tag, ignoreCase = true)) {
                    matched.add(member.agentId)
                    if (mode == "strict") break // Strict: one match is enough
                }
            }
        }
        return matched
    }

    /**
     * Adjust probability based on recent speaking history.
     * Members who spoke recently get lower probability to encourage variety.
     * Ported from VCPMobile's dynamic probability adjustment.
     */
    private fun adjustProbability(
        member: GroupMember,
        history: List<HistoryMessage>,
        base: Double,
    ): Double {
        // Count how many times this member spoke in recent 8 messages
        val recentCount = history.takeLast(CONTEXT_WINDOW)
            .count { it.agentId == member.agentId }
        return base * (1.0 / (1 + recentCount * 0.3))
    }

    /** Select fallback speaker (least recently spoken). */
    private fun selectFallback(
        members: List<GroupMember>,
        history: List<HistoryMessage>,
    ): GroupMember {
        val recentSpeakers = history.takeLast(CONTEXT_WINDOW)
            .mapNotNull { it.agentId }
            .toSet()
        // Prefer members who haven't spoken recently
        return members.firstOrNull { it.agentId !in recentSpeakers }
            ?: members.random()
    }

    companion object {
        /** Context window for speaker history analysis. */
        private const val CONTEXT_WINDOW = 8
    }
}
