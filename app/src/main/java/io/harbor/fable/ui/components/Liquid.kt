package io.harbor.fable.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.FableLiquidBlue
import io.harbor.fable.ui.theme.FableLiquidMagenta
import io.harbor.fable.ui.theme.FableLiquidTeal
import io.harbor.fable.ui.theme.Motion
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** True when RenderEffect blur is available (API 31+); below that [Modifier.blur] is a no-op. */
val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Blur when the platform can; otherwise leave the content alone (soft gradients already read as glass). */
fun Modifier.softBlur(radius: Dp): Modifier =
    if (supportsBlur) blur(radius, BlurredEdgeTreatment.Unbounded) else this

/**
 * The liquid light behind the glass: five large colour fields that overlap and drift on
 * incommensurate periods, so the pattern never visibly repeats, under a soft vignette that keeps
 * the corners dark. Each field is a multi-stop radial gradient, which is smooth on its own; with
 * [blurred] (API 31+) the whole layer is additionally blurred into one continuous wash.
 *
 * [intensity] scales the alpha so the same backdrop works behind a full setup screen (1f) and
 * faintly behind lists (0.4f). [animated] moves the fields; a static backdrop costs nothing per
 * frame, which is what the main shell uses. The blur is only worth its per-frame cost while the
 * fields move, hence the default.
 */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    animated: Boolean = true,
    blurred: Boolean = animated,
) {
    val a: Float
    val b: Float
    val c: Float
    val scale: Float
    if (animated) {
        val transition = rememberInfiniteTransition(label = "liquid")
        val phaseA by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(32_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phaseA",
        )
        val phaseB by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(47_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phaseB",
        )
        val phaseC by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(61_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phaseC",
        )
        val breathe by transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(11_000, easing = Motion.EaseInOut), RepeatMode.Reverse),
            label = "breathe",
        )
        a = phaseA
        b = phaseB
        c = phaseC
        scale = breathe
    } else {
        a = 0.18f
        b = 0.62f
        c = 0.37f
        scale = 1f
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (blurred) Modifier.softBlur(80.dp) else Modifier),
        ) {
            val w = size.width
            val h = size.height
            val tau = (Math.PI * 2).toFloat()
            val span = max(w, h)

            // Violet: the brand colour, high and to the left.
            liquidField(
                color = FableAccent,
                center = Offset(w * (0.28f + 0.14f * cos(a * tau)), h * (0.20f + 0.10f * sin(a * tau))),
                radius = span * 0.46f * scale,
                alpha = 0.58f * intensity,
            )
            // Cool blue: low and to the right, slower.
            liquidField(
                color = FableLiquidBlue,
                center = Offset(w * (0.78f + 0.12f * cos(b * tau + 1.3f)), h * (0.72f + 0.12f * sin(b * tau + 1.3f))),
                radius = span * 0.42f * scale,
                alpha = 0.42f * intensity,
            )
            // Magenta: crosses the middle, where glass panes pick it up as warmth.
            liquidField(
                color = FableLiquidMagenta,
                center = Offset(w * (0.56f + 0.26f * sin(a * tau * 0.5f + 0.4f)), h * (0.46f + 0.16f * cos(b * tau * 0.8f))),
                radius = span * 0.30f * scale,
                alpha = 0.24f * intensity,
            )
            // Teal: a small cool note top-right that keeps the palette from going flat.
            liquidField(
                color = FableLiquidTeal,
                center = Offset(w * (0.86f + 0.08f * sin(c * tau)), h * (0.14f + 0.08f * cos(c * tau + 0.9f))),
                radius = span * 0.22f * scale,
                alpha = 0.14f * intensity,
            )
            // Light violet: bottom-left, keeps the sheen alive on the lower cards.
            liquidField(
                color = FableAccentLight,
                center = Offset(w * (0.16f + 0.10f * sin(b * tau)), h * (0.86f + 0.06f * cos(a * tau))),
                radius = span * 0.26f * scale,
                alpha = 0.22f * intensity,
            )
            // Vignette: the corners fall back into the canvas so the light reads as depth.
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.42f),
                    center = Offset(w * 0.5f, h * 0.45f),
                    radius = span * 0.75f,
                ),
            )
        }
    }
}

/** One soft field of light: a radial gradient with enough stops to fade out without banding. */
private fun DrawScope.liquidField(color: Color, center: Offset, radius: Float, alpha: Float) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.28f to color.copy(alpha = alpha * 0.62f),
            0.58f to color.copy(alpha = alpha * 0.22f),
            0.82f to color.copy(alpha = alpha * 0.05f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * Entrance helper: fades and rises [content] into place once, [index] * [staggerMs] after the
 * first composition. Use for hero screens where elements should arrive one after another.
 * For lazy lists use [rememberLiquidAppear] with [Modifier.liquidAppear] instead, which
 * survives scrolling and state restoration.
 */
@Composable
fun Staggered(
    index: Int,
    modifier: Modifier = Modifier,
    staggerMs: Int = 70,
    initialOffsetDp: Float = 18f,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, Motion.enter(duration = Motion.Entrance, delay = index * staggerMs))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * initialOffsetDp * density
        },
    ) {
        content()
    }
}

/**
 * One screen's entrance. [shown] flips to true right after the first frame, so every item
 * composed in that frame animates from hidden to visible; anything composed later (scrolled
 * into view, inserted by a data change) starts visible and leaves the motion to `animateItem`.
 * The flag is saved, so returning to a tab with restored state does not replay the entrance.
 */
@Stable
class LiquidAppearState internal constructor(shown: Boolean) {
    var shown by mutableStateOf(shown)
        internal set
}

/** Remembers the entrance state for a screen; call once at the top of the screen's content. */
@Composable
fun rememberLiquidAppear(): LiquidAppearState {
    var shown by rememberSaveable { mutableStateOf(false) }
    val state = remember { LiquidAppearState(shown) }
    LaunchedEffect(state) {
        shown = true
        state.shown = true
    }
    return state
}

/** How far apart consecutive items arrive, and the point past which they arrive together. */
private const val LiquidAppearStaggerMs = 55
private const val LiquidAppearMaxStagger = 8
private const val LiquidAppearRiseDp = 14f

/**
 * Fades and rises this element into place as part of [state]'s entrance, [index] steps after the
 * first. Composable so it can drive an `animateFloatAsState`; chain it after `animateItem()`.
 */
@Composable
fun Modifier.liquidAppear(state: LiquidAppearState, index: Int): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (state.shown) 1f else 0f,
        animationSpec = Motion.enter(
            duration = Motion.Entrance,
            delay = min(index, LiquidAppearMaxStagger) * LiquidAppearStaggerMs,
        ),
        label = "liquidAppear",
    )
    return if (progress >= 1f) {
        this
    } else {
        graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * LiquidAppearRiseDp * density
        }
    }
}
