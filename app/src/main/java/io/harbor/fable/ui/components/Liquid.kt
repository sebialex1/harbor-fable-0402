package io.harbor.fable.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.Motion
import kotlin.math.cos
import kotlin.math.sin

/** True when RenderEffect blur is available (API 31+); below that [Modifier.blur] is a no-op. */
val supportsBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Blur when the platform can; otherwise leave the content alone (soft gradients already read as glass). */
fun Modifier.softBlur(radius: Dp): Modifier =
    if (supportsBlur) blur(radius, BlurredEdgeTreatment.Unbounded) else this

/**
 * Slowly drifting colour blobs behind glass. Three radial gradients orbit the canvas on
 * incommensurate periods so the pattern never visibly repeats; on API 31+ the whole layer is
 * blurred into liquid light, elsewhere the gradients are soft enough on their own.
 *
 * [intensity] scales the alpha so the same backdrop works behind a full setup screen (1f) and
 * faintly behind lists (0.35f).
 */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    animated: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "liquid")
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseA",
    )
    val phaseB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(37_000, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseB",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = Motion.EaseInOut), RepeatMode.Reverse),
        label = "breathe",
    )
    val a = if (animated) phaseA else 0.2f
    val b = if (animated) phaseB else 0.6f
    val scale = if (animated) breathe else 1f

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .softBlur(72.dp),
        ) {
            val w = size.width
            val h = size.height
            val tau = (Math.PI * 2).toFloat()

            fun blob(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha * intensity), color.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }

            // Violet: the brand colour, top-left orbit.
            blob(
                color = FableAccent,
                cx = w * (0.30f + 0.14f * cos(a * tau)),
                cy = h * (0.22f + 0.10f * sin(a * tau)),
                radius = w * 0.62f * scale,
                alpha = 0.55f,
            )
            // Cool blue: lower-right, slower.
            blob(
                color = Color(0xFF3B82F6),
                cx = w * (0.76f + 0.12f * cos(b * tau + 1.3f)),
                cy = h * (0.70f + 0.12f * sin(b * tau + 1.3f)),
                radius = w * 0.58f * scale,
                alpha = 0.40f,
            )
            // Warm magenta highlight that crosses the middle.
            blob(
                color = Color(0xFFE879F9),
                cx = w * (0.55f + 0.26f * sin(a * tau * 0.5f + 0.4f)),
                cy = h * (0.48f + 0.16f * cos(b * tau * 0.8f)),
                radius = w * 0.40f * scale,
                alpha = 0.26f,
            )
            // Light accent that keeps the glass sheen alive.
            blob(
                color = FableAccentLight,
                cx = w * (0.18f + 0.10f * sin(b * tau)),
                cy = h * (0.82f + 0.06f * cos(a * tau)),
                radius = w * 0.34f * scale,
                alpha = 0.22f,
            )
        }
    }
}

/**
 * Entrance helper: fades and rises [content] into place once, [index] * [staggerMs] after the
 * first composition. Use for hero screens where elements should arrive one after another.
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
