package com.zygabad.worktimerecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Green80,
    secondary = Blue80
)
private val LightColors = lightColorScheme(
    primary = Green40,
    secondary = Blue40
)

@Composable
fun WorkTimeRecorderTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
