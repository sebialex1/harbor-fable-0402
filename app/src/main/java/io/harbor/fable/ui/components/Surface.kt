package io.harbor.fable.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.FableSurface
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.GlassBorder
import io.harbor.fable.ui.theme.GlassLight
import io.harbor.fable.ui.theme.GlassSurface
import io.harbor.fable.ui.theme.GlassSurfaceRaised
import io.harbor.fable.ui.theme.GradientBottomStop
import io.harbor.fable.ui.theme.GradientTopStop
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

/**
 * Glass surface: translucent fill with optional blur (API 31+), a gradient overlay for depth,
 * and a brighter hairline border. Used for floating chrome (topbar depth, notification overlays,
 * the ESC menu drawer, the tab bar) where content should show through with a frosted-glass feel.
 *
 * On API < 31 the blur is a no-op; the higher alpha of [GlassSurface] compensates so the
 * surface still reads as a distinct layer.
 */
fun Modifier.glassSurface(
    shape: Shape,
    fill: Color = GlassSurface,
    border: Color? = GlassBorder,
    blurRadius: Int = 24,
    gradient: Boolean = true,
): Modifier {
    var mod = clip(shape).background(fill, shape)
    // Real blur on Android 12+; no-op below.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0) {
        mod = mod.blur(blurRadius.dp)
    }
    // Subtle top-to-bottom gradient for a directional light feel.
    if (gradient) {
        mod = mod.background(
            Brush.verticalGradient(listOf(GradientTopStop, GradientBottomStop)),
            shape,
        )
    }
    return if (border != null) mod.border(HairlineStroke, border, shape) else mod
}

/**
 * A lighter glass surface for small floating elements like notification text overlays:
 * more transparent so text can blend with the topbar.
 */
fun Modifier.glassOverlay(
    shape: Shape,
    fill: Color = GlassLight,
    border: Color? = null,
    blurRadius: Int = 16,
): Modifier = glassSurface(
    shape = shape,
    fill = fill,
    border = border,
    blurRadius = blurRadius,
    gradient = false,
)
