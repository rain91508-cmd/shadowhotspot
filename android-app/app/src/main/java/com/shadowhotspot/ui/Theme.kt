package com.shadowhotspot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    secondary = Color(0xFF80DEEA),
    background = Color(0xFF0D1B2A),
    surface = Color(0xFF152A3E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00838F),
    secondary = Color(0xFF0097A7),
)

@Composable
fun ShadowHotspotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
