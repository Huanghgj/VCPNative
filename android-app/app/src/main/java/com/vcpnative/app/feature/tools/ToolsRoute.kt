package com.vcpnative.app.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vcpnative.app.network.vcplog.VcpLogConnectionStatus

data class ToolItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

private val moduleTools = listOf(
    ToolItem("notes", "Notes", Icons.Outlined.Article),
    ToolItem("memo", "Memo", Icons.Outlined.NoteAlt),
    ToolItem("forum", "Forum", Icons.Outlined.Forum),
    ToolItem("translator", "Translator", Icons.Outlined.Translate),
    ToolItem("dice", "Dice", Icons.Outlined.Casino),
    ToolItem("ragobserver", "灵视", Icons.Outlined.Psychology),
)

// 猫娘的秘密百宝箱～这些模块还在调教中，先让主人看到入口但标记状态喵
private val experimentalModules = listOf(
    ToolItem("canvas", "Canvas", Icons.Outlined.Code),
    ToolItem("themes", "Themes", Icons.Outlined.Brush),
    ToolItem("voicechat", "Voice", Icons.Outlined.Mic),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsRoute(
    vcpLogConnectionStatus: VcpLogConnectionStatus,
    notificationCount: Int,
    onOpenModule: (moduleId: String) -> Unit,
    onOpenVcpLog: () -> Unit,
    onOpenDebugLog: () -> Unit,
    onOpenModels: () -> Unit = {},
    onOpenBridge: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGroupChat: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // Title
        item {
            Text(
                text = "Tools",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 20.dp),
            )
        }

        // Modules grid
        item {
            SectionHeader("Modules")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                FlowRow(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    moduleTools.forEach { tool ->
                        ModuleGridItem(
                            tool = tool,
                            onClick = { onOpenModule(tool.id) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // 实验性模块——猫娘还在偷偷调教的功能，先给主人看看入口喵
        item {
            SectionHeader("Experimental")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                FlowRow(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    experimentalModules.forEach { tool ->
                        ExperimentalModuleGridItem(
                            tool = tool,
                            onClick = { onOpenModule(tool.id) },
                        )
                    }
                }
                Text(
                    text = "这些模块的 Android IPC 还没完全适配，部分功能可能不可用喵~",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // Features section
        item {
            SectionHeader("Features")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                ListRow(
                    icon = Icons.Outlined.Search,
                    title = "全文搜索",
                    subtitle = "搜索所有聊天消息",
                    onClick = onOpenSearch,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ListRow(
                    icon = Icons.Outlined.Psychology,
                    title = "模型管理",
                    subtitle = "使用排行、收藏、服务器模型列表",
                    onClick = onOpenModels,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ListRow(
                    icon = Icons.Outlined.Hub,
                    title = "LLM 桥接器",
                    subtitle = "多服务商直连 (OpenAI/Claude/Gemini/DeepSeek...)",
                    onClick = onOpenBridge,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ListRow(
                    icon = Icons.Outlined.Terminal,
                    title = "终端",
                    subtitle = "Shell + Python 环境，安装和调试 Skill 工具",
                    onClick = { onOpenModule("terminal") },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ListRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "技能图鉴",
                    subtitle = "查看猫娘掌握的所有技能和调用记录♡",
                    onClick = onOpenSkills,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                ListRow(
                    icon = Icons.Outlined.Group,
                    title = "群聊",
                    subtitle = "多 Agent 协作对话",
                    onClick = onOpenGroupChat,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // System section
        item {
            SectionHeader("System")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                // VCPLog
                ListRow(
                    icon = Icons.Outlined.Notifications,
                    title = "VCPLog Monitor",
                    subtitle = when (vcpLogConnectionStatus) {
                        VcpLogConnectionStatus.Connected -> "Connected"
                        VcpLogConnectionStatus.Connecting -> "Connecting..."
                        VcpLogConnectionStatus.Error -> "Connection error"
                        VcpLogConnectionStatus.Disconnected -> "Disconnected"
                    },
                    badge = if (notificationCount > 0) notificationCount.toString() else null,
                    onClick = onOpenVcpLog,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                )
                // Debug Log
                ListRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Bridge Debug Log",
                    subtitle = "IPC call tracing",
                    onClick = onOpenDebugLog,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, bottom = 8.dp),
    )
}

@Composable
private fun ModuleGridItem(
    tool: ToolItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = tool.label,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = tool.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 实验性模块的入口卡片——半透明+标签，暗示"还没完全准备好但可以偷偷尝试"喵
 */
@Composable
private fun ExperimentalModuleGridItem(
    tool: ToolItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.label,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            // "β" 角标——小小的实验标记
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "β",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = tool.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
