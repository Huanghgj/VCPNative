package com.vcpnative.app.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.vcpnative.app.data.files.AppFileStore
import com.vcpnative.app.model.ChatAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

interface ChatAttachmentManager {
    suspend fun importAttachment(uri: Uri): ChatAttachment
}

class AndroidChatAttachmentManager(
    context: Context,
    private val fileStore: AppFileStore,
) : ChatAttachmentManager {
    private val appContext = context.applicationContext

    override suspend fun importAttachment(uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val metadata = loadMetadata(uri)
        val originalName = metadata.name.ifBlank { "attachment.bin" }
        val mimeType = normalizeMimeType(
            originalName = originalName,
            reportedMimeType = resolver.getType(uri),
        )
        val extension = resolveExtension(
            originalName = originalName,
            mimeType = mimeType,
        )
        val tempFile = File.createTempFile("incoming_", extension.ifBlank { ".tmp" }, fileStore.rootDir)
        val digest = MessageDigest.getInstance("SHA-256")
        val copiedSize = resolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val readCount = inputStream.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    digest.update(buffer, 0, readCount)
                    outputStream.write(buffer, 0, readCount)
                    total += readCount
                }
                total
            }
        } ?: throw IOException("Unable to open attachment input stream")

        val hash = digest.digest().toHexString()
        val targetFile = File(fileStore.attachmentsDir, "$hash$extension")
        if (!targetFile.exists()) {
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } else {
            tempFile.delete()
        }

        val createdAt = System.currentTimeMillis()
        val pdfRenderResult = if (mimeType == PDF_MIME_TYPE) {
            renderPdfToFrames(targetFile)
        } else {
            PdfRenderResult()
        }
        val extractedText = if (pdfRenderResult.frames.isNotEmpty()) {
            buildPdfSummary(
                originalName = originalName,
                totalPages = pdfRenderResult.frames.size,
                wasTruncated = pdfRenderResult.wasTruncated,
            )
        } else {
            extractTextIfSupported(
                file = targetFile,
                mimeType = mimeType,
                originalName = originalName,
            )
        }

        ChatAttachment(
            id = "draft_${UUID.randomUUID()}",
            fileId = "attachment_$hash",
            name = originalName,
            mimeType = mimeType,
            size = metadata.size ?: copiedSize,
            src = targetFile.absolutePath,
            internalFileName = targetFile.name,
            internalPath = "file://${targetFile.absolutePath}",
            hash = hash,
            createdAt = createdAt,
            extractedText = extractedText,
            imageFrames = pdfRenderResult.frames,
        )
    }

    private fun loadMetadata(uri: Uri): AttachmentMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        var size: Long? = null

        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex).orEmpty().ifBlank { displayName }
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        return AttachmentMetadata(
            name = displayName.ifBlank { "attachment.bin" },
            size = size,
        )
    }

    private fun normalizeMimeType(
        originalName: String,
        reportedMimeType: String?,
    ): String {
        if (originalName.endsWith(".mp3", ignoreCase = true)) {
            return "audio/mpeg"
        }

        val normalizedReported = reportedMimeType?.trim().orEmpty()
        if (normalizedReported.isNotBlank() && normalizedReported != "application/octet-stream") {
            return normalizedReported
        }

        return when (originalName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "txt", "md", "bat", "sh", "py", "java", "c", "cpp", "h", "hpp", "cs", "go",
            "rb", "php", "swift", "kt", "kts", "ts", "tsx", "jsx", "vue", "yml", "yaml",
            "toml", "ini", "log", "sql", "jsonc", "rs", "dart", "lua", "r", "pl", "ex",
            "exs", "zig", "hs", "scala", "groovy", "d", "nim", "cr" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "html" -> "text/html"
            "css" -> "text/css"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "aiff" -> "audio/aiff"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun resolveExtension(
        originalName: String,
        mimeType: String,
    ): String {
        val explicitExtension = originalName
            .substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.US)
        if (explicitExtension != null) {
            return ".$explicitExtension"
        }

        val mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.US)
            ?: return ""
        return ".$mimeExtension"
    }

    private fun extractTextIfSupported(
        file: File,
        mimeType: String,
        originalName: String,
    ): String? {
        val extension = originalName.substringAfterLast('.', "").lowercase(Locale.US)
        val shouldReadAsText = mimeType.startsWith("text/") || extension in TEXT_EXTRACTABLE_EXTENSIONS
        if (!shouldReadAsText) {
            return null
        }
        return runCatching { file.readText() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun renderPdfToFrames(file: File): PdfRenderResult =
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    PdfRenderResult(
                        frames = buildList {
                            val pageCount = minOf(renderer.pageCount, MAX_PDF_RENDER_PAGES)
                            for (pageIndex in 0 until pageCount) {
                                renderer.openPage(pageIndex).use { page ->
                                    val baseEdge = maxOf(page.width, page.height).coerceAtLeast(1)
                                    val renderScale = minOf(
                                        PDF_RENDER_SCALE.toFloat(),
                                        MAX_PDF_RENDER_EDGE.toFloat() / baseEdge.toFloat(),
                                    )
                                    val bitmap = Bitmap.createBitmap(
                                        (page.width * renderScale).roundToInt().coerceAtLeast(1),
                                        (page.height * renderScale).roundToInt().coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888,
                                    )
                                    Canvas(bitmap).drawColor(Color.WHITE)
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                    ByteArrayOutputStream().use { output ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                                        add(Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                                    }
                                    bitmap.recycle()
                                }
                            }
                        },
                        wasTruncated = renderer.pageCount > MAX_PDF_RENDER_PAGES,
                    )
                }
            }
        }.getOrDefault(PdfRenderResult())

    private fun buildPdfSummary(
        originalName: String,
        totalPages: Int,
        wasTruncated: Boolean,
    ): String {
        val suffix = if (wasTruncated) {
            " Only the first $MAX_PDF_RENDER_PAGES pages were prepared on Android."
        } else {
            ""
        }
        return "[VChat Auto-summary: This is a PDF named \"$originalName\". The content is displayed as images. Pages prepared: $totalPages.$suffix]"
    }

    private data class AttachmentMetadata(
        val name: String,
        val size: Long?,
    )

    private data class PdfRenderResult(
        val frames: List<String> = emptyList(),
        val wasTruncated: Boolean = false,
    )

    private companion object {
        const val JPEG_QUALITY = 82
        const val MAX_PDF_RENDER_PAGES = 12
        const val PDF_RENDER_SCALE = 2
        const val MAX_PDF_RENDER_EDGE = 1600
        const val PDF_MIME_TYPE = "application/pdf"
        val TEXT_EXTRACTABLE_EXTENSIONS = setOf(
            "txt",
            "md",
            "json",
            "xml",
            "csv",
            "html",
            "css",
            "js",
            "mjs",
            "bat",
            "sh",
            "py",
            "java",
            "c",
            "cpp",
            "h",
            "hpp",
            "cs",
            "go",
            "rb",
            "php",
            "swift",
            "kt",
            "kts",
            "ts",
            "tsx",
            "jsx",
            "vue",
            "yml",
            "yaml",
            "toml",
            "ini",
            "log",
            "sql",
            "jsonc",
            "rs",
            "dart",
            "lua",
            "r",
            "pl",
            "ex",
            "exs",
            "zig",
            "hs",
            "scala",
            "groovy",
            "d",
            "nim",
            "cr",
        )
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
