package com.vcpnative.app.terminal

import android.util.Log
import com.vcpnative.app.chat.skill.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 猫娘の技能安装器♡
 * 从网上下载新技能包，解压验证后注册到 SkillRegistry。
 * 就像猫娘在偷偷学新的讨好主人的姿势...啊不是，技能♡
 */
class SkillInstaller(
    private val skillRegistry: SkillRegistry,
    private val httpClient: OkHttpClient,
    private val cacheDir: File,
) {
    /**
     * 从 URL 下载并安装 Skill 包。
     * @return null 表示成功，否则返回错误信息
     */
    suspend fun installFromUrl(
        url: String,
        onProgress: (String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 1) 下载
            onProgress("正在下载: $url")
            val zipFile = File(cacheDir, "skill_download_${System.currentTimeMillis()}.zip")
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext "下载失败: HTTP ${response.code}"
                }
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(zipFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext "下载失败: 空响应体"
                onProgress("下载完成 (${zipFile.length() / 1024}KB)")

                // 2) 解压到临时目录
                onProgress("正在解压...")
                val extractDir = File(cacheDir, "skill_extract_${System.currentTimeMillis()}")
                extractDir.mkdirs()
                unzip(zipFile, extractDir)

                // 3) 查找 SKILL.md — 可能在根目录或子目录中
                val skillDir = findSkillDir(extractDir)
                    ?: return@withContext "无效的技能包: 未找到 SKILL.md"

                onProgress("正在安装: ${skillDir.name}")

                // 4) 安装
                val error = skillRegistry.installSkillFromDir(skillDir)
                if (error != null) return@withContext "安装失败: $error"

                // 5) 清理
                extractDir.deleteRecursively()
                onProgress("安装成功!")
                null
            } finally {
                zipFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Skill install failed", e)
            "安装异常: ${e.message}"
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // 防止 zip slip 攻击
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip slip detected: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    /** 递归查找包含 SKILL.md 的目录 */
    private fun findSkillDir(dir: File): File? {
        if (File(dir, "SKILL.md").isFile) return dir
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val found = findSkillDir(sub)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "SkillInstaller"
    }
}
