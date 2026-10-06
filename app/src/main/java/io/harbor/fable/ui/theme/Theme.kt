package io.harbor.fable.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FableColorScheme = darkColorScheme(
    primary = FableAccent,
    secondary = FableAccentDim,
    background = FableBg,
    surface = FableSurface,
    surfaceTint = Color.Transparent,
    surfaceVariant = FableSurfaceRaised,
    onPrimary = Color.Black,
    onSecondary = FableText,
    onBackground = FableText,
    onSurface = FableText,
    onSurfaceVariant = FableTextDim,
    outline = FableOutline,
    outlineVariant = FableGlassBorder,
    error = FableError,
)

@Composable
fun FableTheme(content: @Composable () -> Unit) {
    // Always dark: grey grouped surfaces on a true-black OLED canvas.
    MaterialTheme(
        colorScheme = FableColorScheme,
        typography = FableTypography,
        shapes = FableShapes,
        content = content,
    )
}
