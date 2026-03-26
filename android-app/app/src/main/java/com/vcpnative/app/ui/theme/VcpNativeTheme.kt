package com.vcpnative.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B68),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF76F7F1),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF7D5700),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEAA),
    onSecondaryContainer = Color(0xFF271900),
    background = Color(0xFFF4F6F5),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFFBFDFC),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFD9E5E2),
    onSurfaceVariant = Color(0xFF3D4947),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF55DBD5),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFF76F7F1),
    secondary = Color(0xFFF1BE62),
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5D4100),
    onSecondaryContainer = Color(0xFFFFDEAA),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3D4947),
    onSurfaceVariant = Color(0xFFBCC9C6),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun VcpNativeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
