package com.vcpnative.app.feature.attachment

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.room.MessageAttachmentEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentViewerScreen(
    appContainer: AppContainer,
    attachmentId: String,
    onNavigateBack: () -> Unit,
) {
    val uiState by produceState<AttachmentViewerUiState>(
        initialValue = AttachmentViewerUiState.Loading,
        key1 = attachmentId,
    ) {
        value = withContext(Dispatchers.IO) {
            val attachment = appContainer.workspaceRepository.findMessageAttachment(attachmentId)
                ?: return@withContext AttachmentViewerUiState.Missing
            AttachmentViewerUiState.Ready(
                attachment = attachment,
                textPreview = buildTextPreview(attachment, appContainer),
                imagePreview = buildImagePreview(attachment, appContainer),
                pdfFramePreviews = buildPdfPreviews(attachment),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val state = uiState) {
                            AttachmentViewerUiState.Loading -> "附件"
                            AttachmentViewerUiState.Missing -> "未找到附件"
                            is AttachmentViewerUiState.Ready -> state.attachment.name
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            AttachmentViewerUiState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "正在加载附件…",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }

            AttachmentViewerUiState.Missing -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "未找到该附件。",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "attachmentId: $attachmentId",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            is AttachmentViewerUiState.Ready -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AttachmentMetaCard(attachment = state.attachment)

                    state.imagePreview?.let { imagePreview ->
                        PreviewCard(title = "图片预览") {
                            Image(
                                bitmap = imagePreview,
                                contentDescription = state.attachment.name,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                    }

                    if (state.pdfFramePreviews.isNotEmpty()) {
                        PreviewCard(title = "PDF 页面预览") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.pdfFramePreviews.forEachIndexed { index, frame ->
                                    Text(
                                        text = "第 ${index + 1} 页",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Image(
                                        bitmap = frame,
                                        contentDescription = "${state.attachment.name} 第 ${index + 1} 页",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.FillWidth,
                                    )
                                }
                            }
                        }
                    }

                    state.textPreview?.let { textPreview ->
                        PreviewCard(title = "文本预览") {
                            Text(
                                text = textPreview,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    if (state.imagePreview == null && state.pdfFramePreviews.isEmpty() && state.textPreview == null) {
                        PreviewCard(title = "预览") {
                            Text(
                                text = "当前附件类型还没有内置预览，已保留元数据与发送兼容链路。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentMetaCard(
    attachment: MessageAttachmentEntity,
) {
    PreviewCard(title = "附件信息") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetadataLine(label = "名称", value = attachment.name)
            MetadataLine(label = "MIME", value = attachment.mimeType)
            MetadataLine(label = "大小", value = formatAttachmentSize(attachment.size))
            MetadataLine(label = "文件名", value = attachment.internalFileName.ifBlank { "-" })
            MetadataLine(label = "Hash", value = attachment.hash.ifBlank { "-" })
            MetadataLine(
                label = "路径",
                value = attachment.src
                    .ifBlank { attachment.internalPath.removePrefix("file://") }
                    .ifBlank { "-" },
            )
        }
    }
}

@Composable
private fun PreviewCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun MetadataLine(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private suspend fun buildTextPreview(
    attachment: MessageAttachmentEntity,
    appContainer: AppContainer,
): String? {
    attachment.extractedText?.takeIf { it.isNotBlank() }?.let { extractedText ->
        return extractedText.take(MAX_TEXT_PREVIEW_CHARS)
    }

    if (!attachment.mimeType.startsWith("text/")) {
        return null
    }

    val file = resolveAttachmentFile(attachment, appContainer) ?: return null
    return runCatching { file.readText() }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(MAX_TEXT_PREVIEW_CHARS)
}

private fun buildImagePreview(
    attachment: MessageAttachmentEntity,
    appContainer: AppContainer,
): ImageBitmap? {
    if (!attachment.mimeType.startsWith("image/")) {
        return null
    }
    val file = resolveAttachmentFile(attachment, appContainer) ?: return null
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    return bitmap.asImageBitmap()
}

private fun buildPdfPreviews(
    attachment: MessageAttachmentEntity,
): List<ImageBitmap> {
    if (attachment.imageFramesJson.isNullOrBlank()) {
        return emptyList()
    }

    return runCatching {
        val frames = JSONArray(attachment.imageFramesJson)
        buildList {
            val previewCount = minOf(frames.length(), MAX_PDF_PREVIEW_PAGES)
            for (index in 0 until previewCount) {
                val encoded = frames.optString(index)
                if (encoded.isBlank()) {
                    continue
                }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                add(bitmap.asImageBitmap())
            }
        }
    }.getOrElse { error ->
        if (error is JSONException) {
            emptyList()
        } else {
            emptyList()
        }
    }
}

private fun resolveAttachmentFile(
    attachment: MessageAttachmentEntity,
    appContainer: AppContainer,
): File? {
    if (attachment.src.isNotBlank()) {
        File(attachment.src).takeIf(File::isFile)?.let { return it }
    }
    if (attachment.internalFileName.isNotBlank()) {
        File(appContainer.fileStore.attachmentsDir, attachment.internalFileName).takeIf(File::isFile)?.let { return it }
    }
    val internalPath = attachment.internalPath.removePrefix("file://")
    if (internalPath.isNotBlank()) {
        File(internalPath).takeIf(File::isFile)?.let { return it }
    }
    return null
}

private fun formatAttachmentSize(size: Long): String =
    when {
        size >= 1024L * 1024L -> String.format("%.1f MB", size.toDouble() / (1024.0 * 1024.0))
        size >= 1024L -> String.format("%.1f KB", size.toDouble() / 1024.0)
        size > 0L -> "$size B"
        else -> "-"
    }

private sealed interface AttachmentViewerUiState {
    data object Loading : AttachmentViewerUiState

    data object Missing : AttachmentViewerUiState

    data class Ready(
        val attachment: MessageAttachmentEntity,
        val textPreview: String?,
        val imagePreview: ImageBitmap?,
        val pdfFramePreviews: List<ImageBitmap>,
    ) : AttachmentViewerUiState
}

private const val MAX_TEXT_PREVIEW_CHARS = 12_000
private const val MAX_PDF_PREVIEW_PAGES = 8
