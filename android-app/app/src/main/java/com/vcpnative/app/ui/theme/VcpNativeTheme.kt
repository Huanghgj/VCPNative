package com.vcpnative.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── Precision Cyber-Minimalism ──────────────────────────────────────
// Primary: Amethyst Purple  |  Secondary: Electric Blue  |  Tertiary: Rose

private val LightColors = lightColorScheme(
    primary = Color(0xFF4B00D1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0D4FF),
    onPrimaryContainer = Color(0xFF160046),
    secondary = Color(0xFF00639A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE5FF),
    onSecondaryContainer = Color(0xFF001D32),
    tertiary = Color(0xFF984061),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E001D),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4A7FF),
    onPrimary = Color(0xFF240071),
    primaryContainer = Color(0xFF3700A0),
    onPrimaryContainer = Color(0xFFE0D4FF),
    secondary = Color(0xFF96CCFF),
    onSecondary = Color(0xFF003353),
    secondaryContainer = Color(0xFF004A75),
    onSecondaryContainer = Color(0xFFCEE5FF),
    tertiary = Color(0xFFFFB1C8),
    onTertiary = Color(0xFF5E1133),
    tertiaryContainer = Color(0xFF7B2949),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454E),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun VcpNativeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
