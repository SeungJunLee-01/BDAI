package com.example.sleepsync

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF256ABF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE2FB),
    onPrimaryContainer = Color(0xFF0D366B),
    secondaryContainer = Color(0xFFE6EEF9),
    onSecondaryContainer = Color(0xFF104281),
    background = Color(0xFFEFEFEB),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFEDECE8),
    onSurfaceVariant = Color(0xFF52514E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFB),
    surfaceContainer = Color(0xFFF6F6F3),
    surfaceContainerHigh = Color(0xFFFCFCFB),
    surfaceContainerHighest = Color(0xFFEDECE8),
    outline = Color(0xFFC3C2B7),
    outlineVariant = Color(0xFFE1E0D9),
    error = Color(0xFFD03B3B),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86B6EF),
    onPrimary = Color(0xFF0D366B),
    primaryContainer = Color(0xFF184F95),
    onPrimaryContainer = Color(0xFFCDE2FB),
    secondaryContainer = Color(0xFF1C2B3F),
    onSecondaryContainer = Color(0xFFB7D3F6),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19),
    onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF2C2C2A),
    onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF1A1A19),
    surfaceContainer = Color(0xFF1F1F1E),
    surfaceContainerHigh = Color(0xFF242423),
    surfaceContainerHighest = Color(0xFF2C2C2A),
    outline = Color(0xFF55544F),
    outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFE66767),
    onError = Color(0xFF0B0B0B),
)

@Composable
fun SleepTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
