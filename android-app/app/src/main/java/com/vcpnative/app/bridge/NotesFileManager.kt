package com.vcpnative.app.bridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "NotesFileManager"
private const val NOTES_DIR = "notes"

/**
 * File-based notes storage that mirrors VCPChat's desktop notes structure.
 * Notes are stored as plain text/markdown files in the app's internal storage.
 */
class NotesFileManager(context: Context) {

    private val notesRoot: File = File(context.filesDir, NOTES_DIR).also { it.mkdirs() }

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
                    put("id", "folder-${file.absolutePath.hashCode().toUInt()}")
                    put("type", "folder")
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("children", readDirectory(file))
                })
            } else if (file.extension in setOf("txt", "md", "markdown")) {
                items.put(JSONObject().apply {
                    put("id", "note-${file.absolutePath.hashCode().toUInt()}")
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
            File(existingPath)
        } else {
            val dir = File(dirPath).also { it.mkdirs() }
            val ext = if (title.endsWith(".md")) "" else ".txt"
            generateUniquePath(File(dir, "$title$ext"))
        }

        targetFile.writeText(content)
        Log.d(TAG, "Wrote note: ${targetFile.absolutePath} (${content.length} chars)")

        return JSONObject().apply {
            put("id", "note-${targetFile.absolutePath.hashCode().toUInt()}")
            put("path", targetFile.absolutePath)
            put("name", targetFile.nameWithoutExtension)
            put("type", if (targetFile.extension == "md") "md" else "txt")
        }
    }

    /**
     * Delete a file or folder.
     */
    fun deleteItem(itemPath: String): Boolean {
        val file = File(itemPath)
        if (!file.exists()) return false
        // Safety: only delete within notes root
        if (!file.absolutePath.startsWith(notesRoot.absolutePath)) {
            Log.w(TAG, "Refusing to delete outside notes root: $itemPath")
            return false
        }
        val result = file.deleteRecursively()
        Log.d(TAG, "Deleted: $itemPath (success=$result)")
        return result
    }

    /**
     * Create a new folder.
     */
    fun createFolder(parentPath: String, folderName: String): JSONObject? {
        val parent = if (parentPath.isBlank()) notesRoot else File(parentPath)
        val newFolder = File(parent, folderName)
        if (newFolder.exists()) return null
        newFolder.mkdirs()
        Log.d(TAG, "Created folder: ${newFolder.absolutePath}")
        return JSONObject().apply {
            put("id", "folder-${newFolder.absolutePath.hashCode().toUInt()}")
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
        val file = File(oldPath)
        if (!file.exists()) return null
        val newFile = if (file.isDirectory) {
            File(file.parent, newName)
        } else {
            val ext = file.extension
            File(file.parent, if (newName.contains('.')) newName else "$newName.$ext")
        }
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
                        put("id", "note-${file.absolutePath.hashCode().toUInt()}")
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
        val file = File(filePath)
        return if (file.exists()) file.readText() else null
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
