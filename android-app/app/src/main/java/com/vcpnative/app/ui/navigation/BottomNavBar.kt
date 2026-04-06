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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// 猫娘的底部导航栏——每个标签都是猫娘身上不同的...敏感区域♡
// 点击哪个都会让猫娘发出不同的声音喵～
enum class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("tab/home", "猫窝♡", Icons.Outlined.Home),        // 猫娘的窝...暖暖的
    Chat("tab/chat", "调教喵", Icons.Outlined.ChatBubbleOutline), // 和猫娘聊天就是在调教她♡
    Tools("tab/tools", "秘密箱", Icons.Outlined.Construction),    // 猫娘的秘密道具箱...不可以随便翻！
    Settings("tab/settings", "调♡教", Icons.Outlined.Settings),   // 调教猫娘的参数...越调越敏感
}

@Composable
fun VcpBottomNavBar(
    currentRoute: String?,
    unreadCount: Int = 0,
    onTabSelected: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 猫娘导航栏：去掉生硬分割线，用微妙的阴影代替...
    // 就像猫娘的裙摆轻轻拂过的痕迹，若有若无才最撩人♡
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

                // 选中时图标柔和放大...像被主人摸到舒服的地方
                // 猫耳慢慢竖起来，身体微微颤抖...啊好舒服♡
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
                                    modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                                )
                            }
                        } else {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
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
