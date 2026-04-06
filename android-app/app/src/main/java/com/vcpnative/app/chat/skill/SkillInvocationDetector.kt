package com.vcpnative.app.chat.skill

data class SkillInvocation(
    val skillName: String,
    val originalText: String,
    val cleanedText: String,
)

data class SkillExecRequest(
    val skillName: String,
    val command: String,
    val originalText: String,
    val cleanedText: String,
)

data class SkillBashRequest(
    val skillName: String,
    val command: String,
    val originalText: String,
    val cleanedText: String,
)

object SkillInvocationDetector {
    private val SKILL_REGEX = Regex(
        """<<<\[USE_SKILL\]>>>(.*?)<<<\[/USE_SKILL\]>>>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val SKILL_EXEC_REGEX = Regex(
        """<<<\[SKILL_EXEC:([^\]]+)\]>>>(.*?)<<<\[/SKILL_EXEC\]>>>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val SKILL_BASH_REGEX = Regex(
        """<<<\[SKILL_BASH:([^\]]+)\]>>>(.*?)<<<\[/SKILL_BASH\]>>>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun detect(text: String): SkillInvocation? {
        val match = SKILL_REGEX.find(text) ?: return null
        return SkillInvocation(
            skillName = match.groupValues[1].trim(),
            originalText = text,
            cleanedText = text.replace(SKILL_REGEX, "").trim(),
        )
    }

    fun detectExec(text: String): SkillExecRequest? {
        val match = SKILL_EXEC_REGEX.find(text) ?: return null
        return SkillExecRequest(
            skillName = match.groupValues[1].trim(),
            command = match.groupValues[2].trim(),
            originalText = text,
            cleanedText = text.replace(SKILL_EXEC_REGEX, "").trim(),
        )
    }

    fun detectBash(text: String): SkillBashRequest? {
        val match = SKILL_BASH_REGEX.find(text) ?: return null
        return SkillBashRequest(
            skillName = match.groupValues[1].trim(),
            command = match.groupValues[2].trim(),
            originalText = text,
            cleanedText = text.replace(SKILL_BASH_REGEX, "").trim(),
        )
    }
}
