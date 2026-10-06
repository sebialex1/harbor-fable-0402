package io.harbor.fable.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableGlass
import io.harbor.fable.ui.theme.FableGlassDeep
import io.harbor.fable.ui.theme.FableGlassRaised
import io.harbor.fable.ui.theme.FableGlassShadow
import io.harbor.fable.ui.theme.FableGlassShadowSpot
import io.harbor.fable.ui.theme.FableGlassSheet
import io.harbor.fable.ui.theme.GlassEdgeStroke
import io.harbor.fable.ui.theme.GlassShadow

/**
 * The material a surface is made of, after the iOS system materials: grouped sections are
 * opaque grey, controls are a neutral lift of white, and only chrome that floats over moving
 * content is translucent. No level is tinted; the only light is an optional hairline [edge],
 * a little brighter at the top where a real pane would catch it.
 */
@Immutable
enum class GlassLevel(
    /** Base fill. */
    val fill: Color,
    /** Alpha of the hairline edge at the top; it fades to a quarter of that at the bottom. */
    val edge: Float,
    val shadow: Dp,
) {
    /** Grouped sections and rows resting on the canvas. Opaque, no edge, no shadow. */
    Card(fill = FableGlass, edge = 0f, shadow = GlassShadow.Card),

    /** Controls on a surface: buttons, chips, icon buttons, tiles. */
    Control(fill = FableGlassRaised, edge = 0f, shadow = 0.dp),

    /** The tab bar and other chrome floating over scrolling content. */
    Floating(fill = FableGlassDeep, edge = 0.14f, shadow = GlassShadow.Floating),

    /** Modal sheets. */
    Sheet(fill = FableGlassSheet, edge = 0.08f, shadow = GlassShadow.Sheet),

    /** Dialogs and snackbars, the topmost pane. */
    Overlay(fill = FableGlassSheet, edge = 0.10f, shadow = GlassShadow.Overlay),
}

/**
 * The edge of a pane: a hairline that is brightest along the top and fades towards the bottom,
 * the way light catches the rim of a sheet of glass held under a ceiling light. Nothing else; no
 * glow, no specular streak. Draw-only, so it can wrap components that paint their own surface.
 */
fun Modifier.glassRim(shape: Shape, level: GlassLevel = GlassLevel.Card, tint: Color = Color.White): Modifier {
    if (level.edge <= 0f) return this
    return drawWithContent {
        drawContent()
        val stroke = GlassEdgeStroke.toPx()
        inset(stroke / 2f) {
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                brush = Brush.verticalGradient(
                    0f to tint.copy(alpha = level.edge),
                    0.35f to tint.copy(alpha = level.edge * 0.4f),
                    1f to tint.copy(alpha = level.edge * 0.25f),
                ),
                style = Stroke(stroke),
            )
        }
    }
}

/**
 * The edge for a pane whose surface is painted by a Material container (a bottom sheet, a
 * dialog). Apply it to the content that fills the pane.
 */
fun Modifier.glassLight(shape: Shape, level: GlassLevel): Modifier = glassRim(shape, level)

/**
 * A full pane: optional soft shadow, clip, fill and [glassRim]. Every surface builds on it, so
 * changing the material changes it everywhere. [fill] overrides the level's fill; [shadow] can
 * switch the shadow off where it would stack.
 */
fun Modifier.glassSurface(
    shape: Shape,
    level: GlassLevel = GlassLevel.Card,
    fill: Color = level.fill,
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
        .glassRim(shape, level)
}
