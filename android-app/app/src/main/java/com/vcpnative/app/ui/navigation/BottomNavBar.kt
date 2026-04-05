package com.vcpnative.app.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// 猫娘风格的底部标签喵～标签文字可爱一点
enum class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("tab/home", "窝窝~", Icons.Outlined.Home),
    Chat("tab/chat", "聊天喵", Icons.Outlined.ChatBubbleOutline),
    Tools("tab/tools", "百宝箱", Icons.Outlined.Construction),
    Settings("tab/settings", "设定~", Icons.Outlined.Settings),
}

@Composable
fun VcpBottomNavBar(
    currentRoute: String?,
    unreadCount: Int = 0,
    onTabSelected: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 猫娘导航栏：去掉生硬分割线，用微妙的阴影代替喵
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
        )
        NavigationBar(
            modifier = Modifier.navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            tonalElevation = 0.dp,
        ) {
            BottomTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route

                // 选中时图标柔和放大，像猫猫慢慢竖起耳朵喵
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                    label = "neko-tab-bounce",
                )

                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        if (tab == BottomTab.Chat && unreadCount > 0) {
                            BadgedBox(badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                                }
                            }) {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.scale(iconScale),
                                )
                            }
                        } else {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.scale(iconScale),
                            )
                        }
                    },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}
