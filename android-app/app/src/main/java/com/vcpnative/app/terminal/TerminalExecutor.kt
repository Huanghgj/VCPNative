package com.vcpnative.app.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * Executes shell commands inside the app sandbox.
 *
 * Provides a small set of built-in commands (help, env, pwd, skill-*)
 * and delegates everything else to /system/bin/sh.
 */
class TerminalExecutor(
    context: Context,
    var skillRegistry: com.vcpnative.app.chat.skill.SkillRegistry? = null,
    var skillInstaller: SkillInstaller? = null,
) {

    val terminalHome: File = File(context.filesDir, "terminal_home").also { it.mkdirs() }

    @Volatile
    var currentProcess: Process? = null
        private set

    /**
     * Execute [command] and stream output line-by-line via [onOutput].
     *
     * @param command  Shell command string.
     * @param onOutput Called for each line with (text, stream) where stream is "stdout" or "stderr".
     * @return Exit code (0 for built-in commands on success).
     */
    suspend fun execute(
        command: String,
        onOutput: (text: String, stream: String) -> Unit,
    ): Int {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return 0

        // git clone 拦截：沙箱没有 git，自动转为 GitHub zip 下载 + 解压
        if (trimmed.startsWith("git clone ")) {
            return handleGitClone(trimmed, onOutput)
        }

        // skill-install（直接 URL 安装）
        if (trimmed.startsWith("skill-install ")) {
            val url = trimmed.removePrefix("skill-install ").trim()
            if (url.isEmpty()) {
                onOutput("Usage: skill-install <url>", "stderr")
                return 1
            }
            val installer = skillInstaller
            if (installer == null) {
                onOutput("SkillInstaller not available", "stderr")
                return 1
            }
            val error = installer.installFromUrl(url) { progress ->
                onOutput(progress, "stdout")
            }
            return if (error != null) {
                onOutput(error, "stderr")
                1
            } else 0
        }

        // Check built-in commands first
        val builtinResult = handleBuiltin(trimmed, onOutput)
        if (builtinResult != null) return builtinResult

        // External command via sh
        return executeExternal(trimmed, onOutput)
    }

    /** Interrupt the currently running external process, if any. */
    fun interrupt() {
        currentProcess?.destroy()
        currentProcess = null
    }

    // ── Built-in commands ────────────────────────────────────────────────

    private fun handleBuiltin(
        command: String,
        onOutput: (text: String, stream: String) -> Unit,
    ): Int? {
        val parts = command.split("\\s+".toRegex(), limit = 2)
        val name = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        return when (name) {
            "help" -> {
                onOutput(
                    buildString {
                        appendLine("Available built-in commands:")
                        appendLine("  help              Show this help message")
                        appendLine("  pwd               Print working directory")
                        appendLine("  env               Print environment variables")
                        appendLine("  skill-list        List installed skills")
                        appendLine("  skill-install <url>  Install a skill from URL")
                        appendLine("  skill-remove <name>  Remove an installed skill")
                        appendLine()
                        append("Other commands are executed via /system/bin/sh.")
                    },
                    "stdout",
                )
                0
            }

            "pwd" -> {
                onOutput(terminalHome.absolutePath, "stdout")
                0
            }

            "env" -> {
                val env = buildEnvironment()
                env.entries.sortedBy { it.key }.forEach { (k, v) ->
                    onOutput("$k=$v", "stdout")
                }
                0
            }

            "skill-list" -> {
                val registry = skillRegistry
                if (registry == null) {
                    onOutput("SkillRegistry not available", "stderr")
                    1
                } else {
                    val skills = registry.listAllSkills()
                    if (skills.isEmpty()) {
                        onOutput("No skills installed.", "stdout")
                    } else {
                        skills.forEach { skill ->
                            val badge = if (skill.source == com.vcpnative.app.chat.skill.SkillSource.INSTALLED) "[installed]" else "[bundled]"
                            val count = registry.getInvocationCount(skill.name)
                            onOutput("  ${skill.name} $badge (${count}x) - ${skill.description.take(60)}", "stdout")
                        }
                        onOutput("Total: ${skills.size} skill(s)", "stdout")
                    }
                    0
                }
            }

            "skill-install" -> null // handled in execute() (needs suspend for network I/O)

            "skill-remove" -> {
                if (arg.isEmpty()) {
                    onOutput("Usage: skill-remove <name>", "stderr")
                    1
                } else {
                    val registry = skillRegistry
                    if (registry == null) {
                        onOutput("SkillRegistry not available", "stderr")
                        1
                    } else {
                        if (registry.uninstallSkill(arg)) {
                            onOutput("Removed skill: $arg", "stdout")
                            0
                        } else {
                            onOutput("Cannot remove '$arg' (not found or bundled skill)", "stderr")
                            1
                        }
                    }
                }
            }

            else -> null // not a built-in
        }
    }

    // ── External process execution ───────────────────────────────────────

    private suspend fun executeExternal(
        command: String,
        onOutput: (text: String, stream: String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val env = buildEnvironment()

        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(terminalHome)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(env)
            }
            .start()

        currentProcess = process

        try {
            coroutineScope {
                launch {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { onOutput(it, "stdout") }
                    }
                }
                launch {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { onOutput(it, "stderr") }
                    }
                }
            }
            process.waitFor()
        } finally {
            currentProcess = null
        }
    }

    /**
     * 拦截 git clone 命令，转为 GitHub zip 下载安装。
     * 支持格式：
     *   git clone https://github.com/user/repo path
     *   git clone https://github.com/user/repo ~/.claude/skills/name
     *   git clone https://github.com/user/repo（自动取 repo 名）
     */
    private suspend fun handleGitClone(
        command: String,
        onOutput: (text: String, stream: String) -> Unit,
    ): Int {
        val parts = command.removePrefix("git clone").trim().split("\\s+".toRegex())
        val repoUrl = parts.getOrNull(0) ?: run {
            onOutput("Usage: git clone <url> [path]", "stderr")
            return 1
        }

        // 解析目标名称（从路径或 repo URL 提取）
        val targetPath = parts.getOrNull(1)
        val skillName = when {
            targetPath != null -> targetPath
                .replace("~/.claude/skills/", "")
                .replace("~/.openclaw/workspace/skills/", "")
                .trimEnd('/')
                .substringAfterLast("/")
            else -> repoUrl.trimEnd('/').substringAfterLast("/").removeSuffix(".git")
        }

        if (skillName.isBlank()) {
            onOutput("Cannot determine skill name from URL", "stderr")
            return 1
        }

        // 转换 GitHub URL → zip 下载链接
        val zipUrl = convertToZipUrl(repoUrl)
        if (zipUrl == null) {
            onOutput("Unsupported git host. Only GitHub URLs are supported.", "stderr")
            onOutput("Try: skill-install <zip-url> instead", "stderr")
            return 1
        }

        onOutput("🐱 git not available in sandbox, using HTTP download instead...", "stdout")
        onOutput("   repo: $repoUrl", "stdout")
        onOutput("   name: $skillName", "stdout")
        onOutput("   zip:  $zipUrl", "stdout")
        onOutput("", "stdout")

        val installer = skillInstaller
        if (installer == null) {
            onOutput("SkillInstaller not available", "stderr")
            return 1
        }

        val error = installer.installFromUrl(zipUrl) { progress ->
            onOutput(progress, "stdout")
        }
        return if (error != null) {
            onOutput(error, "stderr")
            1
        } else {
            onOutput("", "stdout")
            onOutput("✅ Skill '$skillName' installed successfully!", "stdout")
            onOutput("   Use 'skill-list' to verify.", "stdout")
            0
        }
    }

    /**
     * 将 GitHub repo URL 转为 archive zip URL。
     * https://github.com/user/repo → https://github.com/user/repo/archive/refs/heads/main.zip
     */
    private fun convertToZipUrl(repoUrl: String): String? {
        val cleaned = repoUrl.trimEnd('/').removeSuffix(".git")
        // GitHub
        val ghMatch = Regex("""https?://github\.com/([^/]+)/([^/]+)""").find(cleaned)
        if (ghMatch != null) {
            return "$cleaned/archive/refs/heads/main.zip"
        }
        // Gitee
        val giteeMatch = Regex("""https?://gitee\.com/([^/]+)/([^/]+)""").find(cleaned)
        if (giteeMatch != null) {
            return "$cleaned/repository/archive/master.zip"
        }
        return null
    }

    private fun buildEnvironment(): MutableMap<String, String> = mutableMapOf(
        "HOME" to terminalHome.absolutePath,
        "PATH" to "/system/bin:/system/xbin",
        "TERM" to "xterm-256color",
        "TMPDIR" to File(terminalHome, "tmp").also { it.mkdirs() }.absolutePath,
    )
}
