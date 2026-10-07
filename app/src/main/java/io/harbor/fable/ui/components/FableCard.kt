package io.harbor.fable.ui.components

import androidx.compose.animation.animateColorAsState
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
import io.harbor.fable.ui.theme.GlassBorder
import io.harbor.fable.ui.theme.TileTone
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.CardRadius
import io.harbor.fable.ui.theme.Motion

/**
 * An inset grouped section, as in iOS Settings: an opaque grey surface with rounded corners on
 * the black canvas. Rows inside it are separated by `CardDivider` hairlines; do not put a card
 * inside another card.
 *
 * Pass [onClick] to make the whole section tappable; it highlights to the next grey while
 * pressed, like a table cell, instead of scaling or glowing. [level] picks the material.
 */
@Composable
fun FableCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardRadius,
    level: SurfaceLevel = SurfaceLevel.Card,
    onClick: (() -> Unit)? = null,
    glow: TileTone? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed && onClick != null) FableSurfaceRaised else level.fill,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "cardFill",
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .cardSurface(shape = shape, level = level, fill = fill)
            .then(if (glow != null) Modifier.toneGlow(glow) else Modifier)
            .then(clickModifier),
        content = content,
    )
}

/**
 * The card material shared by every screen: the level's opaque fill (content behind cards is
 * the black canvas, so there is nothing to see through), finished like the glass chrome — a
 * faint top sheen and a rim that catches light at the top edge and fades towards the bottom.
 * Cards and the glass bar read as one material family.
 */
internal fun Modifier.cardSurface(shape: Shape, level: SurfaceLevel, fill: Color): Modifier =
    if (level == SurfaceLevel.Card || level == SurfaceLevel.Sheet) {
        glassSurface(shape = shape, fill = fill, border = GlassBorder, blurRadius = 0, gradient = true)
    } else {
        solidSurface(shape = shape, level = level, fill = fill)
    }

/**
 * A soft pool of [tone] light in the top-start corner of a card: hero cards (the active driver,
 * the Vulkan device) take on their tone without becoming coloured slabs.
 */
internal fun Modifier.toneGlow(tone: TileTone): Modifier = drawBehind {
    drawRect(
        Brush.radialGradient(
            colors = listOf(tone.start.copy(alpha = 0.45f), Color.Transparent),
            center = Offset(0f, 0f),
            radius = size.maxDimension * 0.75f,
        ),
    )
}

