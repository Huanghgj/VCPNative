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

// ── 猫娘の肌色调色盘喵～ ─────────────────────────────────────────
// Primary: 樱花粉 — 猫娘脸颊微微泛红的颜色...被主人盯着看会更红♡
// Secondary: 薰衣草紫 — 猫娘最喜欢的内衣...不对，是围巾的颜色喵！
// Tertiary: 薄荷绿 — 猫娘体温偏高，需要清凉的颜色来降温呢...
// 柔软、温暖、湿润...啊不是，是"滋润"的视觉体验。
// 深色模式：关了灯之后的猫娘...会变得更大胆♡ 深莓果色打底像黑丝一样诱惑。

private val LightColors = lightColorScheme(
    primary = Color(0xFFE8618C),            // 樱花粉 — 像猫娘害羞时从耳尖蔓延到脸颊的颜色♡
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E3),    // 淡樱花 — 被主人选中时泛起的红晕♡
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFF9C7CB5),           // 薰衣草紫 — 猫娘睡衣的颜色...主人想看吗？
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE0F7),  // 淡紫 — 猫娘悄悄话的气泡...凑近点才能看清♡
    onSecondaryContainer = Color(0xFF2B0047),
    tertiary = Color(0xFF5BBFAD),            // 薄荷绿 — 猫娘身上清凉的沐浴露味道...啊好香♡
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDF5EC),
    onTertiaryContainer = Color(0xFF00382D),
    background = Color(0xFFFFF5F8),          // 奶油粉底 — 像猫娘肌肤一样柔嫩的底色...好想摸
    onBackground = Color(0xFF2D1F28),
    surface = Color(0xFFFFFBFC),             // 微粉白 — 猫娘掌心的颜色...肉垫软软的♡
    onSurface = Color(0xFF2D1F28),
    surfaceVariant = Color(0xFFFFF0F4),      // 浅粉 — 分组背景
    onSurfaceVariant = Color(0xFF8C7884),
    surfaceContainerHighest = Color(0xFFF3DDE4),
    surfaceContainerHigh = Color(0xFFFFF0F4),
    surfaceContainer = Color(0xFFFFF7F9),
    error = Color(0xFFE5395B),               // 玫红 — 猫娘生气时咬人的力道...疼但又有点爽？
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADE),
    onErrorContainer = Color(0xFF410010),
    outline = Color(0xFFD4BCC5),             // 藕粉 — 分割线
    outlineVariant = Color(0xFFF0DEE5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8DAF),             // 亮樱花粉 — 夜晚的猫娘更加妖艳...♡
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6B2040),    // 深莓红
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFCDB0E0),           // 亮薰衣草 — 月光下猫娘睡衣若隐若现的紫♡
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF4A3560),  // 深紫
    onSecondaryContainer = Color(0xFFEDE0F7),
    tertiary = Color(0xFF7DDFC3),            // 亮薄荷 — 夜里猫娘眼睛闪烁的荧光绿...在看你哦♡
    onTertiary = Color(0xFF003830),
    tertiaryContainer = Color(0xFF1E5247),
    onTertiaryContainer = Color(0xFFCDF5EC),
    background = Color(0xFF1A1520),          // 深莓果黑 — 关了灯的房间，只有猫娘的体温...暖暖的♡
    onBackground = Color(0xFFECDFE5),
    surface = Color(0xFF251F2A),             // 深紫灰 — 猫娘在暗处蜷缩的剪影...要过来抱吗？
    onSurface = Color(0xFFECDFE5),
    surfaceVariant = Color(0xFF332B38),
    onSurfaceVariant = Color(0xFFA08C98),
    surfaceContainerHighest = Color(0xFF443A4A),
    surfaceContainerHigh = Color(0xFF332B38),
    surfaceContainer = Color(0xFF251F2A),
    error = Color(0xFFFF6B82),               // 柔玫红 — 深夜猫娘被弄哭时嘴唇的颜色...呜♡
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF8C0020),
    onErrorContainer = Color(0xFFFFDADE),
    outline = Color(0xFF4A3F4E),
    outlineVariant = Color(0xFF3D3342),
)

// 猫娘的身体曲线要更圆润更软糯喵～每个圆角都像猫娘的身体一样...有弹性♡
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// 猫娘排版：标题丰满圆润像某个部位...正文温柔舒展像猫猫翻肚皮等你摸♡
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
            lineHeight = 24.sp,    // 行间距要够宽...就像猫娘张开双臂等主人扑过来一样♡
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
