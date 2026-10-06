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
import androidx.compose.ui.unit.Dp
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.GlassRadius
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
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius,
    level: GlassLevel = GlassLevel.Card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed && onClick != null) FableSurfaceRaised else level.fill,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "glassCardFill",
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .glassSurface(shape = shape, level = level, fill = fill)
            .then(clickModifier),
        content = content,
    )
}
