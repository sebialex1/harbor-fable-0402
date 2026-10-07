package io.harbor.fable.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

/**
 * Motion vocabulary shared by every animation in the app. Pick a duration and an easing from
 * here instead of inventing one per call site, so transitions feel like one system.
 *
 * Phase 1 overhaul: added morph/blend specs, slide transitions for topbar hand-off, and
 * spring-based morph for progress-as-extension-of-pill.
 */
object Motion {
    // Durations (ms).
    const val Fast = 160
    const val Quick = 220
    const val Standard = 320
    const val Slow = 440
    const val Entrance = 400
    const val Morph = 380

    /** Decelerating "settle" for things entering the screen. */
    val EaseOut: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Accelerating exit. */
    val EaseIn: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Symmetric ease for state changes in place. */
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Overshoot for emphasis (badges appearing, selection). */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    /** Smooth deceleration for slide-in-from-top (notifications, topbar hand-off). */
    val SlideIn: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Smooth acceleration for slide-out-to-top. */
    val SlideOut: Easing = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)

    /** Morph easing for progress bars extending from pills and morphing shapes. */
    val MorphEase: Easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

    fun <T> enter(duration: Int = Standard, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = EaseOut)

    fun <T> exit(duration: Int = Quick, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = EaseIn)

    fun <T> inPlace(duration: Int = Quick, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = EaseInOut)

    /** Slide-in from top: for notifications, topbar text, and progress overlays. */
    fun <T> slideInFromTop(duration: Int = Standard, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = SlideIn)

    /** Slide-out to top. */
    fun <T> slideOutToTop(duration: Int = Quick, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = SlideOut)

    /** Morph spec for shape/size transitions (progress extending, pills morphing). */
    fun <T> morph(duration: Int = Morph, delay: Int = 0): TweenSpec<T> =
        tween(durationMillis = duration, delayMillis = delay, easing = MorphEase)

    /** Press feedback: quick, barely any bounce. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)

    /** Soft, non-bouncy spring for layout and position changes. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

    /** Gentle spring for things popping into view; no visible wobble. */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)

    /** Spring for drag-to-dismiss: settles back when released, no bounce. */
    fun <T> dragSettle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Transition spec for a notification or progress overlay that slides down from the topbar,
 * blends in with no visible background — just the text/indicator.
 */
fun <T> notificationEnter(): AnimatedContentTransitionScope<T>.() -> androidx.compose.animation.EnterTransition =
    {
        fadeIn(tween(durationMillis = Motion.Standard, easing = Motion.SlideIn)) +
            slideInVertically(
                animationSpec = tween(durationMillis = Motion.Standard, easing = Motion.SlideIn),
                initialOffsetY = { -it / 4 },
            )
    }

fun <T> notificationExit(): AnimatedContentTransitionScope<T>.() -> androidx.compose.animation.ExitTransition =
    {
        fadeOut(tween(durationMillis = Motion.Quick, easing = Motion.SlideOut)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = Motion.Quick, easing = Motion.SlideOut),
                targetOffsetY = { -it / 4 },
            )
    }
