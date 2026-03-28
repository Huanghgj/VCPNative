package com.vcpnative.app.feature.debug

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vcpnative.app.bridge.BridgeLogger
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogRoute(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val entryCount by BridgeLogger.entryCount.collectAsStateWithLifecycle()
    var enabledState by remember { mutableStateOf(BridgeLogger.enabled) }
    var levelFilter by remember { mutableStateOf<BridgeLogger.Level?>(null) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entryCount) {
        if (entryCount > 0) {
            listState.animateScrollToItem(entryCount - 1)
        }
    }

    val allEntries = remember(entryCount) { BridgeLogger.entries() }
    val filtered = remember(allEntries, levelFilter) {
        if (levelFilter == null) allEntries
        else allEntries.filter { it.level == levelFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge Debug Log") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Share log file
                    IconButton(onClick = {
                        val path = BridgeLogger.logFilePath()
                        if (path != null) {
                            val file = File(path)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share log"))
                        }
                    }) {
                        Icon(Icons.Outlined.Share, "Share")
                    }
                    // Clear
                    IconButton(onClick = {
                        BridgeLogger.clear()
                    }) {
                        Icon(Icons.Outlined.Delete, "Clear")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Controls row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Logging", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = enabledState,
                        onCheckedChange = {
                            enabledState = it
                            BridgeLogger.enabled = it
                        },
                    )
                }
                Text(
                    "$entryCount entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = levelFilter == null,
                    onClick = { levelFilter = null },
                    label = { Text("All") },
                )
                BridgeLogger.Level.entries.forEach { level ->
                    FilterChip(
                        selected = levelFilter == level,
                        onClick = {
                            levelFilter = if (levelFilter == level) null else level
                        },
                        label = { Text(level.name) },
                    )
                }
            }

            // Log entries
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.timestamp }) { entry ->
                    val bgColor = when (entry.level) {
                        BridgeLogger.Level.ERROR -> Color(0x30FF0000)
                        BridgeLogger.Level.WARN -> Color(0x30FFAA00)
                        else -> Color.Transparent
                    }
                    Text(
                        text = entry.formatted(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        ),
                    )
                }
            }
        }
    }
}
