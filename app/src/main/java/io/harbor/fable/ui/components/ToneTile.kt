package io.harbor.fable.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import io.harbor.fable.ui.theme.TileTone

/**
 * The leading tile with personality: [icon] in a light tint of [tone] on that tone's deep
 * diagonal gradient, with a glossy top and a hairline rim ([gradientTile]). Replaces the flat
 * grey [IconTile] where a row deserves an identity (settings groups, launch rows, menus), while
 * keeping the same footprint so rows still line up.
 *
 * [shape] defaults to a squircle-ish rounded square scaled with [size]; pass another shape for
 * varied corner treatments.
 */
@Composable
fun ToneIconTile(
    icon: ImageVector,
    tone: TileTone,
    modifier: Modifier = Modifier,
    size: Dp = RowIconSize,
    shape: Shape = RoundedCornerShape(size * 0.3f),
    dimmed: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .gradientTile(shape = shape, start = tone.start, end = tone.end),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (dimmed) tone.glyph.copy(alpha = 0.45f) else tone.glyph,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

/**
 * A typographic tile: a short monogram ("Wi", "DX", "VK") set tight on the [tone] gradient,
 * for things the icon set has no good glyph for (asset kinds). Mixed with [ToneIconTile] it keeps
 * lists from being a column of identical outline icons.
 */
@Composable
fun MonogramTile(
    text: String,
    tone: TileTone,
    modifier: Modifier = Modifier,
    size: Dp = RowIconSize,
    shape: Shape = RoundedCornerShape(size * 0.3f),
) {
    Box(
        modifier
            .size(size)
            .gradientTile(shape = shape, start = tone.start, end = tone.end),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tone.glyph,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = (size.value * if (text.length > 2) 0.32f else 0.4f).sp,
                lineHeight = (size.value * 0.44f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
            ),
        )
    }
}

