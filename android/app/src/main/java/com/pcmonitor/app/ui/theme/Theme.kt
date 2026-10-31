package com.pcmonitor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF256EEB),
    secondary = Color(0xFF4CD964),
    tertiary = Color(0xFFFF9F0A),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B9BFF),
    secondary = Color(0xFF4CD964),
    tertiary = Color(0xFFFFB340),
    background = Color(0xFF111418),
    surface = Color(0xFF1B2026),
)

@Composable
fun PcMonitorTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
