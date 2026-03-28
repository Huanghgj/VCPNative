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

// ── Kawaii Twilight ─────────────────────────────────────────────────
// Primary: Lavender Dream  |  Secondary: Mint Soda  |  Tertiary: Honey Gold
// No pink. Cute but not saccharine. Anime vibes with soft pastels.

private val LightColors = lightColorScheme(
    primary = Color(0xFF7C5CFC),           // Lavender Dream
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E0FF),   // Soft lilac cloud
    onPrimaryContainer = Color(0xFF21005E),
    secondary = Color(0xFF2BB89E),          // Mint Soda
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFF5E8), // Mint foam
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFE09830),           // Honey Gold
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE4B8),  // Warm honey glow
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFFF9F2),         // Cream paper
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFCF7),            // Warm white
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0E8F5),     // Lavender mist
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFE53935),              // Soft red (anime warning)
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
    outline = Color(0xFF9E8FB8),            // Soft purple-gray
    outlineVariant = Color(0xFFD6C8E8),     // Light purple border
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8A4FF),            // Moonlit lavender
    onPrimary = Color(0xFF381E8F),
    primaryContainer = Color(0xFF4F35C0),   // Deep amethyst
    onPrimaryContainer = Color(0xFFE8E0FF),
    secondary = Color(0xFF6EE7B7),          // Neon mint
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF005140), // Deep sea green
    onSecondaryContainer = Color(0xFFBFF5E8),
    tertiary = Color(0xFFFFD580),           // Starlight amber
    onTertiary = Color(0xFF462B00),
    tertiaryContainer = Color(0xFF654000),  // Deep amber
    onTertiaryContainer = Color(0xFFFFE4B8),
    background = Color(0xFF1A1B2E),         // Anime night sky
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1F33),            // Twilight surface
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2D2E45),     // Soft night panel
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFF7A7585),
    outlineVariant = Color(0xFF3D3C50),
)

// Bubbly, rounded shapes — anime-cute feel
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

// Slightly softer typography weights for a friendlier feel
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
