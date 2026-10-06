package io.harbor.fable.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableGlass
import io.harbor.fable.ui.theme.FableGlassBorder
import io.harbor.fable.ui.theme.GlassRadius

/** Specular highlight shared by every glass surface (top-left light refraction). */
internal val GlassSpecular: Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.07f),
        Color.Transparent,
        Color.White.copy(alpha = 0.02f),
    ),
)

/**
 * Refraction glass surface — the core design element.
 *
 * Translucent fill, specular gradient and a frosted 1dp edge, drawn on a single node so
 * long lists of cards stay cheap. Pass [onClick] to make the whole card tappable; it then
 * scales down slightly while pressed.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "glassCardScale",
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(FableGlass)
            .background(GlassSpecular)
            .border(1.dp, FableGlassBorder, shape)
            .then(clickModifier),
        content = content,
    )
}
