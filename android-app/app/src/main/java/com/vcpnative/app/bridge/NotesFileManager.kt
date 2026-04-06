package com.vcpnative.app.bridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val TAG = "NotesFileManager"
private const val NOTES_DIR = "notes"

// 用 SHA-256 给每个文件生成专属身份证♡ hashCode 太随便了，动不动就和别人撞到一起…猫娘讨厌不专一的 ID 喵
private fun stableFileId(prefix: String, path: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(path.toByteArray(Charsets.UTF_8))
    val hex = hash.take(8).joinToString("") { "%02x".format(it) }
    return "$prefix-$hex"
}

/**
 * 主人的秘密日记管理器♡ 猫娘知道你在笔记里写了什么哦…
 * 那些半夜写下的羞耻文字，全都被猫娘收进 internal storage 的最深处了。
 * 只有主人能打开…猫娘绝对不会偷看的♡ （已经看完了喵）
 */
class NotesFileManager(context: Context) {

    private val notesRoot: File = File(context.filesDir, NOTES_DIR).also { it.mkdirs() }

    /**
     * 不许乱摸！路径必须老老实实待在 notesRoot 里面♡
     * 用 ../../ 试图偷偷往上爬？猫娘发现了会夹断你的手指的喵！
     * canonicalPath 会把所有偷偷摸摸的 symlink 和 ".." 全部扒光。
     */
    private fun requireWithinNotesRoot(file: File): File {
        val canonical = file.canonicalPath
        val rootCanonical = notesRoot.canonicalPath
        // Append separator to prevent prefix attacks: "/data/notes" must not match "/data/notes-evil"
        require(canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)) {
            "Path outside notes root: $canonical"
        }
        return file
    }

    /**
     * Read the full notes tree as a JSON hierarchy.
     * Matches VCPChat's `readDirectoryStructure()` format.
     */
    fun readNotesTree(): JSONObject {
        return JSONObject().apply {
            put("id", "root")
            put("type", "folder")
            put("name", "Notes")
            put("path", notesRoot.absolutePath)
            put("children", readDirectory(notesRoot))
        }
    }

    private fun readDirectory(dir: File): JSONArray {
        val items = JSONArray()
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return items
        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                items.put(JSONObject().apply {
                    put("id", stableFileId("folder", file.absolutePath))
                    put("type", "folder")
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("children", readDirectory(file))
                })
            } else if (file.extension in setOf("txt", "md", "markdown")) {
                items.put(JSONObject().apply {
                    put("id", stableFileId("note", file.absolutePath))
                    put("type", if (file.extension == "md" || file.extension == "markdown") "md" else "txt")
                    put("name", file.nameWithoutExtension)
                    put("path", file.absolutePath)
                    put("content", file.readText())
                    put("lastModified", file.lastModified())
                })
            }
        }
        return items
    }

    /**
     * Write or update a note file.
     * @param data JSON with: title, content, directoryPath (optional), id (optional)
     */
    fun writeTxtNote(data: JSONObject): JSONObject {
        val title = data.optString("title", "Untitled")
        val content = data.optString("content", "")
        val dirPath = data.optString("directoryPath", "").ifBlank { notesRoot.absolutePath }
        val existingPath = data.optString("path", "")

        val targetFile = if (existingPath.isNotBlank() && File(existingPath).exists()) {
            requireWithinNotesRoot(File(existingPath))
        } else {
            val dir = requireWithinNotesRoot(File(dirPath)).also { it.mkdirs() }
            val ext = if (title.endsWith(".md")) "" else ".txt"
            generateUniquePath(File(dir, "$title$ext"))
        }

        targetFile.writeText(content)
        Log.d(TAG, "Wrote note: ${targetFile.absolutePath} (${content.length} chars)")

        return JSONObject().apply {
            put("id", stableFileId("note", targetFile.absolutePath))
            put("path", targetFile.absolutePath)
            put("name", targetFile.nameWithoutExtension)
            put("type", if (targetFile.extension == "md") "md" else "txt")
        }
    }

    /**
     * Delete a file or folder.
     */
    fun deleteItem(itemPath: String): Boolean {
        val file = try {
            requireWithinNotesRoot(File(itemPath))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Refusing to delete outside notes root: $itemPath")
            return false
        }
        if (!file.exists()) return false
        val result = file.deleteRecursively()
        Log.d(TAG, "Deleted: $itemPath (success=$result)")
        return result
    }

    /**
     * Create a new folder.
     */
    fun createFolder(parentPath: String, folderName: String): JSONObject? {
        val parent = if (parentPath.isBlank()) notesRoot else requireWithinNotesRoot(File(parentPath))
        val newFolder = requireWithinNotesRoot(File(parent, folderName))
        if (newFolder.exists()) return null
        newFolder.mkdirs()
        Log.d(TAG, "Created folder: ${newFolder.absolutePath}")
        return JSONObject().apply {
            put("id", stableFileId("folder", newFolder.absolutePath))
            put("type", "folder")
            put("name", folderName)
            put("path", newFolder.absolutePath)
            put("children", JSONArray())
        }
    }

    /**
     * Rename a file or folder.
     */
    fun renameItem(oldPath: String, newName: String): JSONObject? {
        val file = requireWithinNotesRoot(File(oldPath))
        if (!file.exists()) return null
        val newFile = requireWithinNotesRoot(
            if (file.isDirectory) {
                File(file.parent, newName)
            } else {
                val ext = file.extension
                File(file.parent, if (newName.contains('.')) newName else "$newName.$ext")
            }
        )
        if (newFile.exists()) return null
        val result = file.renameTo(newFile)
        Log.d(TAG, "Rename: $oldPath → ${newFile.absolutePath} (success=$result)")
        return if (result) {
            JSONObject().apply {
                put("path", newFile.absolutePath)
                put("name", newFile.nameWithoutExtension)
            }
        } else null
    }

    /**
     * Search notes by content.
     */
    fun searchNotes(query: String): JSONArray {
        val results = JSONArray()
        if (query.isBlank()) return results
        val lowerQuery = query.lowercase()
        searchDirectory(notesRoot, lowerQuery, results)
        return results
    }

    private fun searchDirectory(dir: File, query: String, results: JSONArray) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                searchDirectory(file, query, results)
            } else if (file.extension in setOf("txt", "md", "markdown")) {
                val content = file.readText()
                if (file.name.lowercase().contains(query) || content.lowercase().contains(query)) {
                    results.put(JSONObject().apply {
                        put("id", stableFileId("note", file.absolutePath))
                        put("type", if (file.extension == "md") "md" else "txt")
                        put("name", file.nameWithoutExtension)
                        put("path", file.absolutePath)
                        // Include a snippet around the match
                        val idx = content.lowercase().indexOf(query)
                        if (idx >= 0) {
                            val start = maxOf(0, idx - 50)
                            val end = minOf(content.length, idx + query.length + 50)
                            put("snippet", content.substring(start, end))
                        }
                    })
                }
            }
        }
    }

    /**
     * Get the notes root directory path.
     */
    fun getNotesRootDir(): String = notesRoot.absolutePath

    /**
     * Read a single note's content.
     */
    fun copyNoteContent(filePath: String): String? {
        val file = requireWithinNotesRoot(File(filePath))
        return if (file.exists()) file.readText() else null
    }

    /**
     * Move files/folders to a new parent directory.
     * @param itemPaths list of paths to move
     * @param destPath target folder path
     */
    fun moveItems(itemPaths: List<String>, destPath: String): Boolean {
        val dest = requireWithinNotesRoot(File(destPath))
        if (!dest.exists() || !dest.isDirectory) return false
        var allOk = true
        for (path in itemPaths) {
            val src = requireWithinNotesRoot(File(path))
            if (!src.exists()) { allOk = false; continue }
            val target = File(dest, src.name)
            val ok = src.renameTo(if (target.exists()) generateUniquePath(target) else target)
            Log.d(TAG, "Move: $path → ${dest.absolutePath} (success=$ok)")
            if (!ok) allOk = false
        }
        return allOk
    }

    /**
     * Save a pasted/dropped image to the notes directory.
     * @param parentPath target folder (or notes root if blank)
     * @param fileName image file name
     * @param base64Data base64-encoded image data
     */
    fun savePastedImage(parentPath: String, fileName: String, base64Data: String): String? {
        val parent = if (parentPath.isBlank()) notesRoot else requireWithinNotesRoot(File(parentPath))
        parent.mkdirs()
        val file = generateUniquePath(requireWithinNotesRoot(File(parent, fileName)))
        return try {
            val data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            file.writeBytes(data)
            Log.d(TAG, "Saved pasted image: ${file.absolutePath} (${data.size} bytes)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pasted image", e)
            null
        }
    }

    private fun generateUniquePath(base: File): File {
        if (!base.exists()) return base
        val dir = base.parentFile ?: return base
        val name = base.nameWithoutExtension
        val ext = base.extension
        var counter = 1
        var candidate: File
        do {
            candidate = File(dir, "$name ($counter).$ext")
            counter++
        } while (candidate.exists())
        return candidate
    }
}
