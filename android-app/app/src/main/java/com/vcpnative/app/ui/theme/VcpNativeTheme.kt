package com.vcpnative.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 猫娘主题喵～ ──────────────────────────────────────────────────
// Primary: 樱花粉 | Secondary: 薰衣草紫 | Tertiary: 薄荷绿
// 柔软、温暖、有活力。大圆角、充裕留白、轻盈排版。
// 深色模式保留粉紫调性，用深莓果色打底。

private val LightColors = lightColorScheme(
    primary = Color(0xFFE8618C),            // 樱花粉 — 主角色
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E3),    // 淡樱花 — 选中态/指示器
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFF9C7CB5),           // 薰衣草紫 — 辅助色
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE0F7),  // 淡紫 — 助理气泡
    onSecondaryContainer = Color(0xFF2B0047),
    tertiary = Color(0xFF5BBFAD),            // 薄荷绿 — 成功/强调
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDF5EC),
    onTertiaryContainer = Color(0xFF00382D),
    background = Color(0xFFFFF5F8),          // 奶油粉底 — 整体背景
    onBackground = Color(0xFF2D1F28),
    surface = Color(0xFFFFFBFC),             // 微粉白 — 卡片/面板
    onSurface = Color(0xFF2D1F28),
    surfaceVariant = Color(0xFFFFF0F4),      // 浅粉 — 分组背景
    onSurfaceVariant = Color(0xFF8C7884),
    surfaceContainerHighest = Color(0xFFF3DDE4),
    surfaceContainerHigh = Color(0xFFFFF0F4),
    surfaceContainer = Color(0xFFFFF7F9),
    error = Color(0xFFE5395B),               // 玫红 — 不那么刺眼的错误色
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADE),
    onErrorContainer = Color(0xFF410010),
    outline = Color(0xFFD4BCC5),             // 藕粉 — 分割线
    outlineVariant = Color(0xFFF0DEE5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8DAF),             // 亮樱花粉
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6B2040),    // 深莓红
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFCDB0E0),           // 亮薰衣草
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF4A3560),  // 深紫
    onSecondaryContainer = Color(0xFFEDE0F7),
    tertiary = Color(0xFF7DDFC3),            // 亮薄荷
    onTertiary = Color(0xFF003830),
    tertiaryContainer = Color(0xFF1E5247),
    onTertiaryContainer = Color(0xFFCDF5EC),
    background = Color(0xFF1A1520),          // 深莓果黑 — 不是纯黑，有温度
    onBackground = Color(0xFFECDFE5),
    surface = Color(0xFF251F2A),             // 深紫灰
    onSurface = Color(0xFFECDFE5),
    surfaceVariant = Color(0xFF332B38),
    onSurfaceVariant = Color(0xFFA08C98),
    surfaceContainerHighest = Color(0xFF443A4A),
    surfaceContainerHigh = Color(0xFF332B38),
    surfaceContainer = Color(0xFF251F2A),
    error = Color(0xFFFF6B82),               // 柔玫红
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF8C0020),
    onErrorContainer = Color(0xFFFFDADE),
    outline = Color(0xFF4A3F4E),
    outlineVariant = Color(0xFF3D3342),
)

// 猫猫的圆角要更大更软糯喵～
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// 猫娘排版：标题柔和圆润，正文舒适好读喵
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            lineHeight = 40.sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.15.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontWeight = FontWeight.Normal,
            lineHeight = 24.sp,    // 行间距大一点读起来更舒服喵
        ),
        bodyMedium = base.bodyMedium.copy(
            fontWeight = FontWeight.Normal,
            lineHeight = 22.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
fun VcpNativeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
