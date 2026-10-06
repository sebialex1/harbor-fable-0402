package io.harbor.fable.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FableColorScheme = darkColorScheme(
    primary = FableAccent,
    onPrimary = FableOnAccent,
    primaryContainer = FableSurfaceRaised,
    onPrimaryContainer = FableText,
    secondary = FableTextDim,
    onSecondary = FableBg,
    secondaryContainer = FableSurfaceRaised,
    onSecondaryContainer = FableText,
    tertiary = FableTextDim,
    onTertiary = FableBg,
    background = FableBg,
    onBackground = FableText,
    surface = FableBg,
    onSurface = FableText,
    surfaceVariant = FableSurface,
    onSurfaceVariant = FableTextDim,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = FableBg,
    surfaceContainerLow = FableSurface,
    surfaceContainer = FableSurface,
    surfaceContainerHigh = FableSurfaceRaised,
    surfaceContainerHighest = FableSurfaceHigh,
    inverseSurface = FableText,
    inverseOnSurface = FableBg,
    inversePrimary = FableBg,
    outline = FableOutline,
    outlineVariant = FableDivider,
    error = FableError,
    onError = FableBg,
    errorContainer = FableSurfaceRaised,
    onErrorContainer = FableText,
    scrim = Color.Black,
)

@Composable
fun FableTheme(content: @Composable () -> Unit) {
    // Always dark: solid gray surfaces on a pure black canvas.
    MaterialTheme(
        colorScheme = FableColorScheme,
        typography = FableTypography,
        shapes = FableShapes,
        content = content,
    )
}
