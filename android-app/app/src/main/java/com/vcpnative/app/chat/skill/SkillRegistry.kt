package com.vcpnative.app.chat.skill

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * 猫娘の技能书♡
 * 每个 Skill 都是猫娘学会的一项特技...
 * 主人呼唤技能名，猫娘就会施展对应的魔法♡
 * 调用次数记录着猫娘为主人服务了多少次...越多越说明主人离不开猫娘呢♡
 */
enum class SkillSource { BUNDLED, INSTALLED }

data class SkillManifest(
    val name: String,
    val description: String,
    val disableModelInvocation: Boolean = false,
    val baseDir: String,
    val source: SkillSource = SkillSource.BUNDLED,
)

class SkillRegistry(
    private val assetManager: AssetManager,
    private val dataDir: File? = null, // 传入 context.filesDir 用于持久化调用计数
) {
    private val skills = mutableMapOf<String, SkillManifest>()
    val searchEngine: SkillSearchEngine = SkillSearchEngine(assetManager)

    // 猫娘的服务记录♡每次被主人调用都会记下来...
    private val invocationCounts = mutableMapOf<String, Int>()
    private val countsFile: File? get() = dataDir?.let { File(it, "skill_invocation_counts.json") }

    private val installedSkillsDir: File?
        get() = dataDir?.let { File(it, INSTALLED_SKILLS_DIR) }

    fun scanSkills() {
        skills.clear()

        // 1) 扫描 assets 中的内置技能（只读）
        val dirs = try {
            assetManager.list(SKILLS_ROOT).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list skills directory", e)
            emptyArray()
        }
        for (dir in dirs) {
            val skillMdPath = "$SKILLS_ROOT/$dir/SKILL.md"
            val content = readAsset(skillMdPath) ?: continue
            val manifest = parseFrontmatter(content, dir, "$SKILLS_ROOT/$dir")
            if (manifest != null) {
                skills[manifest.name] = manifest.copy(source = SkillSource.BUNDLED)
                Log.d(TAG, "Registered bundled skill: ${manifest.name}")
            }
        }

        // 2) 扫描 filesDir/installed_skills/ 中的运行时安装技能
        val installDir = installedSkillsDir
        if (installDir != null && installDir.isDirectory) {
            installDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val skillMd = File(dir, "SKILL.md")
                if (skillMd.isFile) {
                    val content = skillMd.readText()
                    val manifest = parseFrontmatter(content, dir.name, dir.absolutePath)
                    if (manifest != null) {
                        skills[manifest.name] = manifest.copy(source = SkillSource.INSTALLED)
                        Log.d(TAG, "Registered installed skill: ${manifest.name}")
                    }
                }
            }
        }

        Log.i(TAG, "Scanned ${skills.size} skill(s) (bundled + installed)")
        loadInvocationCounts()
    }

    fun listAvailableSkills(): List<SkillManifest> =
        skills.values.filter { !it.disableModelInvocation }

    fun getBaseDir(name: String): String? = skills[name]?.baseDir

    fun executeSkillCommand(skillName: String, command: String): String? {
        val baseDir = getBaseDir(skillName) ?: return null
        recordInvocation(skillName) // 记录猫娘又为主人服务了一次♡
        return searchEngine.execute(baseDir, command)
    }

    fun loadFullContent(name: String): String? {
        val manifest = skills[name] ?: return null
        recordInvocation(name)
        val raw = when (manifest.source) {
            SkillSource.BUNDLED -> readAsset("${manifest.baseDir}/SKILL.md")
            SkillSource.INSTALLED -> try {
                File(manifest.baseDir, "SKILL.md").readText()
            } catch (_: Exception) { null }
        } ?: return null
        return stripFrontmatter(raw)
    }

    /** 获取所有技能（含隐藏的），UI 展示用 */
    fun listAllSkills(): List<SkillManifest> = skills.values.toList()

    /** 获取某个技能的调用次数 */
    fun getInvocationCount(skillName: String): Int = invocationCounts[skillName] ?: 0

    /** 获取所有技能的调用次数 */
    fun getAllInvocationCounts(): Map<String, Int> = invocationCounts.toMap()

    /** 安装一个技能：将 zip 解压到 installed_skills/<name>/ */
    fun installSkillFromDir(sourceDir: File): String? {
        val skillMd = File(sourceDir, "SKILL.md")
        if (!skillMd.isFile) return "SKILL.md not found"
        val manifest = parseFrontmatter(skillMd.readText(), sourceDir.name, sourceDir.absolutePath)
            ?: return "Invalid SKILL.md frontmatter"
        val targetDir = File(installedSkillsDir ?: return "No data directory", manifest.name)
        if (targetDir.exists()) targetDir.deleteRecursively()
        sourceDir.copyRecursively(targetDir, overwrite = true)
        scanSkills() // 重新扫描
        return null // null = success
    }

    /** 卸载一个运行时安装的技能 */
    fun uninstallSkill(name: String): Boolean {
        val manifest = skills[name] ?: return false
        if (manifest.source != SkillSource.INSTALLED) return false
        val dir = File(manifest.baseDir)
        if (dir.isDirectory) dir.deleteRecursively()
        skills.remove(name)
        return true
    }

    /** 记录一次调用并持久化 */
    private fun recordInvocation(skillName: String) {
        invocationCounts[skillName] = (invocationCounts[skillName] ?: 0) + 1
        saveInvocationCounts()
    }

    private fun loadInvocationCounts() {
        val file = countsFile ?: return
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.keys().forEach { key ->
                    invocationCounts[key] = json.optInt(key, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load invocation counts", e)
        }
    }

    private fun saveInvocationCounts() {
        val file = countsFile ?: return
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            invocationCounts.forEach { (k, v) -> json.put(k, v) }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save invocation counts", e)
        }
    }

    fun buildManifestPrompt(): String {
        val available = listAvailableSkills()
        if (available.isEmpty()) return ""

        return buildString {
            appendLine()
            appendLine("## 可用技能")
            appendLine("以下技能是APP本地工具，不是服务器插件，不要用后端工具（如PowerShellExecutor、ServerBashExecutor等）来执行。")
            appendLine("当你判断需要使用某个技能来更好地完成用户请求时，在回复的最开头输出 <<<[USE_SKILL]>>>技能名<<<[/USE_SKILL]>>> 标记（仅输出标记，不要输出其他内容），系统会自动加载该技能的详细指令并让你继续执行。")
            appendLine("技能加载后，如需执行技能的数据搜索，直接在回复中输出 <<<[SKILL_EXEC:技能名]>>>搜索命令<<<[/SKILL_EXEC]>>> 标记。这会在手机APP本地执行BM25搜索并返回结果，无需调用任何服务器工具。")
            appendLine("如需执行 shell 命令或 Python 脚本（如技能自带的工具脚本），输出 <<<[SKILL_BASH:技能名]>>>命令<<<[/SKILL_BASH]>>> 标记。命令中可以用 \${CLAUDE_SKILL_DIR} 引用技能的安装目录。系统会在手机本地沙箱中执行命令并返回输出。")
            appendLine()
            for (skill in available) {
                val desc = skill.description.take(MAX_DESCRIPTION_LENGTH)
                appendLine("- ${skill.name}: $desc")
            }
        }.trimEnd()
    }

    private fun parseFrontmatter(
        content: String,
        fallbackName: String,
        baseDir: String,
    ): SkillManifest? {
        val frontmatterMatch = FRONTMATTER_REGEX.find(content) ?: return null
        val yaml = frontmatterMatch.groupValues[1]

        val name = extractYamlValue(yaml, "name") ?: fallbackName
        val description = extractYamlValue(yaml, "description") ?: return null
        val disableModelInvocation = extractYamlValue(yaml, "disable-model-invocation")
            ?.trim()?.lowercase() == "true"

        return SkillManifest(
            name = name,
            description = description,
            disableModelInvocation = disableModelInvocation,
            baseDir = baseDir,
        )
    }

    private fun extractYamlValue(yaml: String, key: String): String? {
        val regex = Regex("""^$key:\s*(.+)$""", RegexOption.MULTILINE)
        val match = regex.find(yaml) ?: return null
        return match.groupValues[1]
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .ifBlank { null }
    }

    private fun stripFrontmatter(content: String): String {
        val match = FRONTMATTER_REGEX.find(content) ?: return content
        return content.substring(match.range.last + 1).trimStart()
    }

    private fun readAsset(path: String): String? = try {
        assetManager.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "SkillRegistry"
        private const val SKILLS_ROOT = "vcpchat/skills"
        private const val INSTALLED_SKILLS_DIR = "installed_skills"
        private const val MAX_DESCRIPTION_LENGTH = 250
        private val FRONTMATTER_REGEX = Regex("""^---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL)
    }
}
