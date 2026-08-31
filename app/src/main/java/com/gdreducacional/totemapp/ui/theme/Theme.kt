package com.gdreducacional.totemapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AcciosDarkScheme = darkColorScheme(
    primary = AcciosColors.detecting,
    onPrimary = Color.White,
    secondary = AcciosColors.gradientEnd,
    onSecondary = Color.White,
    tertiary = AcciosColors.success,
    onTertiary = Color.White,
    error = AcciosColors.error,
    onError = Color.White,
    background = AcciosColors.gradientStart,
    onBackground = Color.White,
    surface = AcciosColors.surface,
    onSurface = Color.White,
    surfaceVariant = AcciosColors.glassCard,
    onSurfaceVariant = AcciosColors.textSecondary,
    outline = AcciosColors.glassBorder,
    outlineVariant = AcciosColors.divider
)

@Composable
fun AcciosTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AcciosDarkScheme,
        typography = Typography,
        content = content
    )
}