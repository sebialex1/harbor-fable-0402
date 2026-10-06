package io.harbor.fable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FableColorScheme = darkColorScheme(
    primary = FableAccent,
    secondary = FableAccentDim,
    background = FableBg,
    surface = FableSurface,
    onPrimary = FableText,
    onSecondary = FableText,
    onBackground = FableText,
    onSurface = FableText,
)

@Composable
fun FableTheme(content: @Composable () -> Unit) {
    // Always dark — glass design needs dark backdrop for refraction effect
    MaterialTheme(
        colorScheme = FableColorScheme,
        typography = FableTypography,
        shapes = FableShapes,
        content = content,
    )
}
