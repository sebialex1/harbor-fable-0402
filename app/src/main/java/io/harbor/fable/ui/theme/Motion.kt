package io.harbor.fable.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion vocabulary shared by every animation in the app. Pick a duration and an easing from
 * here instead of inventing one per call site, so transitions feel like one system.
 */
object Motion {
    // Durations (ms).
    const val Fast = 160
    const val Quick = 220
    const val Standard = 320
    const val Slow = 440
    const val Entrance = 400

    /** Decelerating "settle" for things entering the screen. */
    val EaseOut: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Accelerating exit. */
    val EaseIn: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Symmetric ease for state changes in place. */
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Liquid overshoot for emphasis (badges appearing, selection). */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    fun <T> enter(duration: Int = Standard, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = EaseOut)

    fun <T> exit(duration: Int = Quick, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = EaseIn)

    fun <T> inPlace(duration: Int = Quick, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = EaseInOut)

    /** Press feedback: quick, barely any bounce. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)

    /** Soft, non-bouncy spring for layout and position changes. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

    /** Gentle spring for things popping into view; no visible wobble. */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
}
