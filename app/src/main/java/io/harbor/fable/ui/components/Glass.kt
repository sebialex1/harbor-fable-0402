package io.harbor.fable.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableGlass
import io.harbor.fable.ui.theme.FableGlassDeep
import io.harbor.fable.ui.theme.FableGlassRaised
import io.harbor.fable.ui.theme.FableGlassSheet
import io.harbor.fable.ui.theme.FableGlassShadow
import io.harbor.fable.ui.theme.FableGlassShadowSpot
import io.harbor.fable.ui.theme.GlassEdgeStroke
import io.harbor.fable.ui.theme.GlassShadow

/**
 * How far a glass surface sits from the canvas. Each level is a little more opaque, catches a
 * little more light on its rim and casts a softer, longer shadow than the one below it, so glass
 * on glass (a dialog over a sheet over a list) reads as separate panes instead of one flat tone.
 */
@Immutable
enum class GlassLevel(
    /** Base fill. */
    val fill: Color,
    /** Peak alpha of the rim light at the top-left corner. */
    val rim: Float,
    /** Peak alpha of the specular line along the top edge. */
    val specular: Float,
    /** Alpha of the vertical sheen that lightens the upper part of the pane. */
    val sheen: Float,
    val shadow: Dp,
) {
    /** Cards and rows resting on the canvas. */
    Card(fill = FableGlass, rim = 0.12f, specular = 0.22f, sheen = 0.045f, shadow = GlassShadow.Card),

    /** Controls sitting on a card: buttons, chips, icon buttons, tiles. No shadow of their own. */
    Control(fill = FableGlassRaised, rim = 0.14f, specular = 0.26f, sheen = 0.05f, shadow = 0.dp),

    /** The dock and other chrome floating over scrolling content. */
    Floating(fill = FableGlassDeep, rim = 0.16f, specular = 0.30f, sheen = 0.06f, shadow = GlassShadow.Floating),

    /** Modal sheets: a heavier pane that covers most of the screen. */
    Sheet(fill = FableGlassSheet, rim = 0.18f, specular = 0.34f, sheen = 0.06f, shadow = GlassShadow.Sheet),

    /** Dialogs and snackbars, the topmost pane. */
    Overlay(fill = FableGlassSheet, rim = 0.22f, specular = 0.40f, sheen = 0.07f, shadow = GlassShadow.Overlay),
}

/**
 * The light on a pane of glass, drawn over its content: a rim that is brightest at the top-left
 * corner and gone by the bottom-right, plus a thin specular highlight along the top edge. There is
 * no border; the edge is only where the light catches it.
 *
 * Draw-only, so it can wrap components that paint their own surface (Material sheets, dialogs).
 */
fun Modifier.glassRim(shape: Shape, level: GlassLevel = GlassLevel.Card, tint: Color = Color.White): Modifier =
    drawWithContent {
        drawContent()
        val stroke = GlassEdgeStroke.toPx()
        inset(stroke / 2f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                brush = Brush.linearGradient(
                    colors = listOf(
                        tint.copy(alpha = level.rim),
                        tint.copy(alpha = level.rim * 0.35f),
                        tint.copy(alpha = 0f),
                        tint.copy(alpha = level.rim * 0.18f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                style = Stroke(stroke),
            )
            // Specular: a short bright run along the straight part of the top edge only, fading
            // out on both sides. It starts past the corner radius so it never bends round it.
            val cornerRadius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            val startX = cornerRadius + stroke
            val endX = size.width * 0.72f
            if (endX > startX + stroke) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            tint.copy(alpha = 0f),
                            tint.copy(alpha = level.specular),
                            tint.copy(alpha = 0f),
                        ),
                        startX = startX,
                        endX = endX,
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(endX, 0f),
                    strokeWidth = stroke,
                )
            }
        }
    }

/**
 * Sheen and rim without fill, clip or shadow: the light on a pane whose surface is painted by a
 * Material container (a bottom sheet, a dialog). Apply it to the content that fills the pane.
 */
fun Modifier.glassLight(shape: Shape, level: GlassLevel, sheenColor: Color = Color.White): Modifier = this
    .background(
        Brush.verticalGradient(
            0f to sheenColor.copy(alpha = level.sheen),
            0.4f to sheenColor.copy(alpha = level.sheen * 0.25f),
            1f to sheenColor.copy(alpha = 0f),
        ),
    )
    .glassRim(shape, level)

/**
 * A full pane of glass: soft shadow, clip, translucent fill, sheen and [glassRim]. The one
 * modifier every glass component builds on, so changing the material changes it everywhere.
 *
 * [fill] overrides the level's fill (tinted panes); [sheenColor] is the colour of the light
 * falling on the upper half (white by default, the accent for emphasised panes); [rimColor] is
 * the colour of the edge light. [shadow] can switch the shadow off for panes inside a scrolling
 * list where many shadows would stack.
 */
fun Modifier.glassSurface(
    shape: Shape,
    level: GlassLevel = GlassLevel.Card,
    fill: Color = level.fill,
    sheenColor: Color = Color.White,
    sheenAlpha: Float = level.sheen,
    rimColor: Color = Color.White,
    shadow: Boolean = true,
): Modifier {
    val shadowed = if (shadow && level.shadow > 0.dp) {
        shadow(
            elevation = level.shadow,
            shape = shape,
            clip = false,
            ambientColor = FableGlassShadow,
            spotColor = FableGlassShadowSpot,
        )
    } else {
        this
    }
    return shadowed
        .clip(shape)
        .background(fill)
        .background(
            Brush.verticalGradient(
                0f to sheenColor.copy(alpha = sheenAlpha),
                0.45f to sheenColor.copy(alpha = sheenAlpha * 0.25f),
                1f to sheenColor.copy(alpha = 0f),
            ),
        )
        .glassRim(shape, level, rimColor)
}
