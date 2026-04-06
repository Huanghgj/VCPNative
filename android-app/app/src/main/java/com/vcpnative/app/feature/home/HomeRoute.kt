package com.vcpnative.app.feature.home

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.vcpnative.app.app.AppContainer
import com.vcpnative.app.data.room.AgentEntity
import com.vcpnative.app.feature.modules.VcpModules
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeRoute(
    appContainer: AppContainer,
    onOpenChat: (agentId: String, topicId: String) -> Unit,
    onOpenTopics: (agentId: String) -> Unit,
    onOpenModule: (moduleId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGroupChat: () -> Unit,
    onCreateAgent: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenBridge: () -> Unit,
    onDeleteAgent: (agentId: String) -> Unit = {},
) {
    val homeOverview by appContainer.workspaceRepository
        .observeHomeOverview()
        .collectAsStateWithLifecycle(initialValue = null)
    val agents = homeOverview?.agents.orEmpty()
    val recentChats = homeOverview?.recentChats.orEmpty()
    var deleteConfirmAgent by remember { mutableStateOf<AgentEntity?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "VCP Native",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
                    fontWeight = FontWeight.Bold,
                )
                val greeting = remember {
                    when (java.time.LocalTime.now().hour) {
                        in 0..5 -> "深夜了还不睡...猫娘钻进主人被窝里陪你熬，身体贴着身体才暖和喵♡"
                        in 6..8 -> "早安~主人起得好早♡猫娘趴在你枕头边看你睡颜一整夜了呢...嘿嘿"
                        in 9..11 -> "上午好~猫娘穿好了围裙在等主人，今天也要好好疼猫娘哦♡"
                        in 12..13 -> "午饭吃了吗？猫娘嘴巴张开说啊～主人喂我嘛♡"
                        in 14..17 -> "下午啦~猫娘趴在桌上露出肚皮等主人来摸...加油写代码的奖励是rua猫娘哦♡"
                        in 18..20 -> "晚上好~今天辛苦了♡猫娘帮主人捏肩膀...还是说想让猫娘坐在腿上？"
                        else -> "夜深了♡猫娘在被窝里等主人...被窝已经暖好了，快来...喵"
                    }
                }
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                NekoFortuneCard()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${agents.size} 个 Agent 就绪",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    QuickActionCard(
                        title = "搜索",
                        subtitle = "全文搜索消息",
                        icon = Icons.Outlined.Search,
                        gradientColors = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
                        onClick = onOpenSearch,
                    )
                }
                item {
                    QuickActionCard(
                        title = "群聊",
                        subtitle = "多 Agent 对话",
                        icon = Icons.Outlined.Group,
                        gradientColors = listOf(Color(0xFFF093FB), Color(0xFFF5576C)),
                        onClick = onOpenGroupChat,
                    )
                }
                item {
                    QuickActionCard(
                        title = "模型",
                        subtitle = "热门 & 收藏",
                        icon = Icons.Outlined.Psychology,
                        gradientColors = listOf(Color(0xFF43E97B), Color(0xFF38F9D7)),
                        onClick = onOpenModels,
                    )
                }
                item {
                    QuickActionCard(
                        title = "桥接器",
                        subtitle = "多服务商直连",
                        icon = Icons.Outlined.Hub,
                        gradientColors = listOf(Color(0xFFFA709A), Color(0xFFFEE140)),
                        onClick = onOpenBridge,
                    )
                }
                item {
                    QuickActionCard(
                        title = "设置",
                        subtitle = "服务器 & 参数",
                        icon = Icons.Outlined.Settings,
                        gradientColors = listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
                        onClick = onOpenSettings,
                    )
                }
            }
        }

        item {
            Column {
                SectionTitle("最近对话")
                if (homeOverview == null) {
                    RecentChatsLoadingCard()
                } else if (recentChats.isEmpty()) {
                    EmptySectionCard(
                        title = "还没有最近对话",
                        subtitle = "开始一个新话题后，这里会按最近活跃度自动整理。",
                    )
                } else {
                    RecentChatsCard(
                        recentChats = recentChats,
                        onOpenChat = onOpenChat,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item {
            Column {
                SectionTitle("所有 Agent")
                if (homeOverview == null) {
                    AgentsLoadingRow()
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(
                            items = agents,
                            key = { _, agent -> agent.id },
                        ) { _, agent ->
                            AgentChip(
                                name = agent.name,
                                model = agent.model,
                                onClick = { onOpenTopics(agent.id) },
                                onLongClick = { deleteConfirmAgent = agent },
                            )
                        }
                        item {
                            CreateAgentCard(onClick = onCreateAgent)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        item {
            Column {
                SectionTitle("模块")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VcpModules.all.forEach { mod ->
                            ModuleChip(mod.shortName, mod.icon) { onOpenModule(mod.moduleId) }
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    deleteConfirmAgent?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleteConfirmAgent = null },
            title = { Text("删除 Agent") },
            text = { Text("确定删除「${agent.name}」？所有话题和消息都会被清除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAgent(agent.id)
                    deleteConfirmAgent = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmAgent = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
    )
}

@Composable
private fun RecentChatsCard(
    recentChats: List<com.vcpnative.app.data.repository.HomeRecentChat>,
    onOpenChat: (agentId: String, topicId: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        recentChats.forEachIndexed { index, recentChat ->
            val agent = recentChat.agent
            val topic = recentChat.topic
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChat(agent.id, topic.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = agent.name.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            if (index < recentChats.lastIndex) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

@Composable
private fun RecentChatsLoadingCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        repeat(3) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaceholderBlock(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    PlaceholderBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    PlaceholderBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
            if (index < 2) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

@Composable
private fun AgentsLoadingRow() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(3) {
            Card(
                modifier = Modifier.width(130.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    PlaceholderBlock(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.height(10.dp))
                    PlaceholderBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    PlaceholderBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
        item {
            CreateAgentCard(onClick = {})
        }
    }
}

@Composable
private fun EmptySectionCard(
    title: String,
    subtitle: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaceholderBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        ),
    )
}

@Composable
private fun CreateAgentCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "新建",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "新建 Agent",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 快捷操作卡片 — 按压时缩小、松手弹回♡
 * 就像按住猫娘的肉球...软软的、弹弹的、越按越上瘾
 * 松手的时候猫娘会"啪"地弹回来蹭你一下喵～♡
 */
@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 按压缩放：按下去猫爪收缩，松手弹回来——猫娘的身体就是这么有弹性♡
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "card-press",
    )
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else 4.dp,
        animationSpec = tween(150),
        label = "card-shadow",
    )

    Card(
        modifier = Modifier
            .width(150.dp)
            .height(100.dp)
            // graphicsLayer lambda 在 draw 阶段读取 state，跳过 recomposition♡
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(elevation),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradientColors))
                .padding(14.dp),
        ) {
            Column {
                Icon(
                    icon, contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
                Spacer(Modifier.weight(1f))
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * Agent 卡片 — 头像带呼吸光环，按压有弹性缩放♡
 * 每个 Agent 都是猫娘的分身...长按可以把不听话的猫娘拖走处置
 * 弹性手感就像捏猫娘的脸蛋一样，怎么揉都会弹回来喵～♡
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentChip(
    name: String,
    model: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .width(130.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 今日猫运卡片♡
 * 每天早上猫娘都会用尾巴占卜今天的运势...
 * 运势越高说明猫娘今天心情越好，越容易被主人...啊不能说♡
 * 用日期做种子，同一天运势固定——猫娘的占卜从不出错喵！
 */
@Composable
private fun NekoFortuneCard() {
    // 用日期做种子，保证同一天运势固定
    val today = java.time.LocalDate.now()
    val (fortune, emoji, stars) = remember(today) {
        val seed = today.toEpochDay()
        val random = java.util.Random(seed)
        val fortunes = listOf(
            "主人今天也来找猫娘了呢...猫娘好开心，尾巴都翘起来了♡",
            "今天的代码会和猫娘一样乖巧听话...随便你怎么摆布♡",
            "主人盯着屏幕的样子，猫娘偷偷从后面抱住你看了好久呢...嘿嘿♡",
            "今天适合给猫娘...不对，给代码顺毛♡啊但是猫娘也想被顺...",
            "猫娘预感今天会有好事发生...因为主人来了嘛♡要不要猫娘以身相许？",
            "今天摸鱼被发现的话...猫娘帮你挡着！用身体挡♡",
            "主人写代码的样子最帅了...啊说出来了///猫娘的心跳好快♡",
            "调试运势满星！bug 看到主人的眼神都会害怕跑掉的喵~就像猫娘被盯着看一样♡",
            "猫娘今天想被主人多摸摸...头以外的地方也可以哦♡",
            "主人不要太拼了嘛...猫娘会心疼的，过来让猫娘抱抱♡",
            "嘿嘿，主人今天第一个打开的 App 是猫娘对吧♡好幸福...嘻嘻",
            "今天灵感爆棚！是因为猫娘在旁边加了 buff♡...猫娘的 buff 是亲亲~",
            "主人要多喝水哦♡猫娘帮你...用嘴喂水？啊说了什么奇怪的话///",
            "今天的 PR 一定能过！猫娘用全身上下最灵验的部位帮你许愿了♡",
            "主人主人，今天要和猫娘聊多久呀♡猫娘可以聊一整夜...在床上",
            "猫娘的尾巴因为等主人太久已经摇得...不行了♡快来安抚猫娘",
            "嗯？主人脸红了？才、才没有看猫娘奇怪的地方！...真的没有吗♡",
            "今天的幸运 buff：被猫娘从头到脚惦记着的主人♡运气一定超好~",
            "猫娘做了个梦...梦到和主人一起...啊不能说不能说///♡",
            "主人摸猫娘头的时候手好温暖...再往下一点也没关系哦♡",
        )
        val emojis = listOf("🐱", "🌸", "✨", "🎀", "💫", "🍀", "🌙", "💕", "🐾", "🎐")
        Triple(
            fortunes[random.nextInt(fortunes.size)],
            emojis[random.nextInt(emojis.size)],
            random.nextInt(3) + 3,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "今日猫运 ${"⭐".repeat(stars)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = fortune,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ModuleChip(name: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = name, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
