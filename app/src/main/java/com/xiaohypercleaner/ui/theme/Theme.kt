package com.xiaohypercleaner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Blue500,
    onPrimary = White,
    secondary = Teal200,
    background = Color.White,
    surface = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    onPrimary = White,
    secondary = Teal200,
    background = DarkBackground,
    surface = DarkSurface
)

@Composable
fun XiaoHyperCleanerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}