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
import io.harbor.fable.ui.theme.FableGlass
import io.harbor.fable.ui.theme.FableGlassBorder
import io.harbor.fable.ui.theme.GlassRadius

/**
 * The faint top light shared by glass surfaces: a little brighter at the top edge, gone by
 * the bottom. It is what stops a flat charcoal panel from looking like a flat charcoal panel.
 */
internal val GlassSheen: Brush = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.04f), Color.Transparent),
)

/**
 * Frosted glass panel: charcoal at 85% opacity, a hairline edge of white at 6%, and the sheen
 * above, all drawn on a single node so long lists stay cheap. Pass [onClick] to make the whole
 * card tappable; it then settles slightly while pressed.
 *
 * Rows share one card (separated by `CardDivider`); do not put a card inside another card.
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
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
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
            .background(GlassSheen)
            .border(Dp.Hairline, FableGlassBorder, shape)
            .then(clickModifier),
        content = content,
    )
}
