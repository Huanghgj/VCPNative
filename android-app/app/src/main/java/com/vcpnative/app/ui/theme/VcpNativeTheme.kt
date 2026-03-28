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

// ── Apple-inspired clean design ──────────────────────────────────────
// Primary: System Blue | Secondary: System Green | Tertiary: System Purple
// Clean, confident, understated. Generous whitespace, precise typography.

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF34C759),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4F5DD),
    onSecondaryContainer = Color(0xFF002110),
    tertiary = Color(0xFF5856D6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E0FF),
    onTertiaryContainer = Color(0xFF140064),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    surfaceContainerHigh = Color(0xFFF2F2F7),
    surfaceContainer = Color(0xFFF9F9FB),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF003A75),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF30D158),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF004D25),
    onSecondaryContainer = Color(0xFFD4F5DD),
    tertiary = Color(0xFF7D7AFF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF3B3799),
    onTertiaryContainer = Color(0xFFE4E0FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE5E5E5),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE5E5E5),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainer = Color(0xFF1C1C1E),
    error = Color(0xFFFF453A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF48484A),
)

// Restrained, precise corner radii
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Clean, tight typography — confidence without shouting
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.25).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.Medium,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontWeight = FontWeight.Normal,
            lineHeight = 22.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontWeight = FontWeight.Normal,
            lineHeight = 20.sp,
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
