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
import io.harbor.fable.ui.theme.Motion

/**
 * Circular glass icon button used for top-bar actions (back, refresh, add) and compact inline
 * actions such as the download and play buttons on rows. With [bordered] the button is a small
 * pane of glass (sheen and rim light); without it, a flat disc of [containerColor] for filled
 * accent buttons.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ControlHeight.Icon,
    tint: Color = FableText,
    containerColor: Color = GlassLevel.Control.fill,
    bordered: Boolean = true,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedNow = pressed && enabled
    val scale by animateFloatAsState(
        targetValue = if (pressedNow) 0.9f else 1f,
        animationSpec = Motion.press(),
        label = "iconButtonScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (pressedNow) 1f else 0f,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "iconButtonLift",
    )
    val surface = if (bordered) {
        Modifier.glassSurface(
            shape = CircleShape,
            level = GlassLevel.Control,
            fill = containerColor,
            sheenAlpha = GlassLevel.Control.sheen + 0.08f * lift,
        )
    } else {
        Modifier
            .clip(CircleShape)
            .background(containerColor)
            .background(GlassSheen)
            .background(Color.White.copy(alpha = 0.14f * lift))
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
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
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
