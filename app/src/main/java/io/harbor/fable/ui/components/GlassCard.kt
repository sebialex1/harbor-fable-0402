package io.harbor.fable.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.harbor.fable.ui.theme.GlassRadius
import io.harbor.fable.ui.theme.Motion

/**
 * The faint top light shared by glass surfaces, kept for callers that layer it on their own
 * fills. New surfaces should use [glassSurface], which draws the sheen and the rim together.
 */
internal val GlassSheen: Brush = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
)

/**
 * A pane of frosted glass: translucent charcoal over the liquid backdrop, a sheen across its upper
 * half and a rim of light that is brightest at the top-left corner and fades out by the bottom.
 * There is no border; see [glassRim]. Everything is drawn on a single node so long lists stay cheap.
 *
 * Pass [onClick] to make the whole card tappable; it then settles slightly while pressed and
 * brightens for a moment. [tint] lights the pane with a colour (an accent-tinted hero card);
 * [level] moves it up or down the depth stack.
 *
 * Rows share one card (separated by `CardDivider`); do not put a card inside another card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius,
    level: GlassLevel = GlassLevel.Card,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedNow = pressed && onClick != null
    val scale by animateFloatAsState(
        targetValue = if (pressedNow) 0.984f else 1f,
        animationSpec = Motion.press(),
        label = "glassCardScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (pressedNow) 1f else 0f,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "glassCardLift",
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
            .glassSurface(
                shape = shape,
                level = level,
                fill = tint?.let { level.fill.compositeOverTint(it) } ?: level.fill,
                sheenColor = tint ?: Color.White,
                // A pressed card catches a little more light instead of darkening like a ripple.
                sheenAlpha = (if (tint != null) 0.16f else level.sheen) + 0.06f * lift,
            )
            .then(clickModifier),
        content = content,
    )
}

/** Mixes a little of [tint] into a glass fill without changing its alpha. */
private fun Color.compositeOverTint(tint: Color, amount: Float = 0.10f): Color = Color(
    red = red + (tint.red - red) * amount,
    green = green + (tint.green - green) * amount,
    blue = blue + (tint.blue - blue) * amount,
    alpha = alpha,
)
