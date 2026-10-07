package io.harbor.fable.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.harbor.fable.ui.theme.ControlHeight
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.GlassBorder
import io.harbor.fable.ui.theme.GlassSurfaceRaised
import io.harbor.fable.ui.theme.Motion

/**
 * True inside floating chrome (the top bar and the large-title row): icon buttons there default
 * to the glass material instead of the solid grey disc.
 */
val LocalGlassControls = staticCompositionLocalOf { false }

/**
 * Circular icon button for top-bar actions (back, refresh, add) and compact inline actions such
 * as the download and play buttons on rows: a neutral grey disc that dims while pressed. Pass
 * [containerColor] for a filled variant (white play buttons); [bordered] is kept for callers
 * that want the disc without the default fill.
 */
@Composable
fun FableIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ControlHeight.Icon,
    tint: Color = FableText,
    containerColor: Color = SurfaceLevel.Control.fill,
    bordered: Boolean = true,
    enabled: Boolean = true,
    glass: Boolean = LocalGlassControls.current,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.6f else 1f,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "iconButtonPress",
    )
    val surface = if (glass && containerColor == SurfaceLevel.Control.fill) {
        // Floating chrome (top bar): a frosted disc with a light-catching rim, so the button
        // reads as glass lifted off whatever scrolls beneath the bar.
        Modifier.glassSurface(shape = CircleShape, fill = GlassSurfaceRaised, border = GlassBorder, blurRadius = 20)
    } else if (bordered) {
        Modifier.solidSurface(shape = CircleShape, level = SurfaceLevel.Control, fill = containerColor)
    } else {
        Modifier
            .clip(CircleShape)
            .background(containerColor)
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = (if (enabled) 1f else 0.35f) * pressedAlpha }
            .then(surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}
