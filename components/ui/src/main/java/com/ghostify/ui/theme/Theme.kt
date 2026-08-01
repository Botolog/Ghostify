package com.ghostify.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color.White,
    secondary = Color(0xFF121212),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color(0xFF003318),
    secondary = Color(0xFFB9B9B9),
)

@Composable
fun GhostifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
