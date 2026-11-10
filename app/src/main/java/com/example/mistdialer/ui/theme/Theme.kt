package com.example.mistdialer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MistDarkColorScheme = darkColorScheme(
    primary = MistRed,
    secondary = MistEmerald,
    background = MistBlack,
    surface = MistSurface,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun MistDialerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MistDarkColorScheme,
        typography = Typography,
        content = content
    )
}
