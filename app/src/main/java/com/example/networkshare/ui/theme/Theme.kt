package com.example.networkshare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlue,
    background = BackgroundDark,
    surface = BackgroundDark,
    surfaceVariant = BoxDark,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    background = BackgroundLight,
    surface = BackgroundLight,
    surfaceVariant = BoxLight,
    onSurface = Color(0xFF1C1B1F)
)
@Suppress("UNUSED_PARAMETER")
@Composable
fun NetworkShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}