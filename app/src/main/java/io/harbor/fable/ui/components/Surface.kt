package io.harbor.fable.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.FableSurface
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.GlassBorder
import io.harbor.fable.ui.theme.GlassFrost
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
 * Glass surface: translucent fill, a directional sheen and a light-catching hairline edge. Used
 * for floating chrome (top bar, its buttons, notifications, the tab bar) where content should
 * show through with a frosted-glass feel.
 *
 * Compose has no backdrop blur, and `Modifier.blur` blurs the element's *own* content (its icons
 * and text), so it is never applied to the surface itself. Instead, on API 31+ [blurRadius]
 * widens a soft radial "frost" highlight drawn behind the content, which reads as diffused light
 * through glass; below API 31 the highlight is tighter and the higher alpha of [GlassSurface]
 * keeps the layer distinct. Children always stay sharp.
 */
fun Modifier.glassSurface(
    shape: Shape,
    fill: Color = GlassSurface,
    border: Color? = GlassBorder,
    blurRadius: Int = 24,
    gradient: Boolean = true,
): Modifier {
    var mod = clip(shape).background(fill, shape)
    if (blurRadius > 0) {
        val diffusion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0.6f
        mod = mod.drawBehind {
            // Frost: a soft light pooled at the top-left, sized by the blur radius.
            val radius = (size.maxDimension * 0.9f + blurRadius.dp.toPx() * 4f) * diffusion
            drawRect(
                Brush.radialGradient(
                    colors = listOf(GlassFrost, Color.Transparent),
                    center = Offset(size.width * 0.18f, 0f),
                    radius = radius.coerceAtLeast(1f),
                ),
            )
        }
    }
    // Subtle top-to-bottom gradient for a directional light feel.
    if (gradient) {
        mod = mod.background(
            Brush.verticalGradient(listOf(GradientTopStop, GradientBottomStop)),
            shape,
        )
    }
    return if (border != null) {
        // The edge catches light at the top and fades towards the bottom, like a glass rim.
        mod.border(
            HairlineStroke,
            Brush.verticalGradient(listOf(border, border.copy(alpha = border.alpha * 0.35f))),
            shape,
        )
    } else {
        mod
    }
}

/**
 * A lighter glass surface for small floating elements (top-bar buttons, overlays): more
 * transparent so it blends with the bar.
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

/**
 * A tinted gradient tile: two colour stops on a diagonal, a glossy highlight at the top and a
 * hairline rim. Gives tiles (tools, settings icons) their own personality instead of the flat
 * grey square every icon used to sit on.
 */
fun Modifier.gradientTile(
    shape: Shape,
    start: Color,
    end: Color,
    border: Color? = GlassBorder,
): Modifier {
    val base = clip(shape)
        .background(Brush.linearGradient(listOf(start, end)), shape)
        .background(Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color.Transparent)), shape)
    return if (border != null) {
        base.border(HairlineStroke, Brush.verticalGradient(listOf(border, Color.Transparent)), shape)
    } else {
        base
    }
}
