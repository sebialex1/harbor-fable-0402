package io.harbor.fable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.FableSurface
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.HairlineStroke
import io.harbor.fable.ui.theme.TabBarBg

/**
 * Which solid fill a surface uses. Depth comes from a lighter gray per level, never from
 * translucency, blur or shadows.
 */
@Immutable
enum class SurfaceLevel(val fill: Color) {
    /** Cards and grouped rows on the canvas. */
    Card(FableSurface),

    /** Controls sitting on a card: buttons, chips, icon buttons, tiles. */
    Control(FableSurfaceRaised),

    /** The tab bar and other chrome over scrolling content. */
    Bar(TabBarBg),

    /** Sheets, dialogs and snackbars. */
    Sheet(FableSurface),
}

/**
 * A plain, solid surface: clip, opaque fill and an optional hairline border. The one modifier
 * every card and control builds on, so changing the material changes it everywhere.
 */
fun Modifier.solidSurface(
    shape: Shape,
    level: SurfaceLevel = SurfaceLevel.Card,
    fill: Color = level.fill,
    border: Color? = FableBorder,
): Modifier {
    val clipped = clip(shape).background(fill, shape)
    return if (border != null) clipped.border(HairlineStroke, border, shape) else clipped
}
