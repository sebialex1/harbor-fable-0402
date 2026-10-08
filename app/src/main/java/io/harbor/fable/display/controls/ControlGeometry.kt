package io.harbor.fable.display.controls

import kotlin.math.hypot

/**
 * The direction math of d-pads and sticks, separate from the view so it can be tested.
 * Offsets are the finger's position relative to the element's centre divided by its half
 * size, so the rim is at 1.
 */
object ControlGeometry {
    /** A d-pad direction needs this much push (Winlator's DPAD_DEAD_ZONE). */
    const val DPAD_DEAD_ZONE = 0.3f

    /** A stick ignores pushes below this (Winlator's STICK_DEAD_ZONE). */
    const val STICK_DEAD_ZONE = 0.15f

    /**
     * Which d-pad / stick directions are held for a push of ([nx], [ny]): up, right, down, left
     * (the order of an element's bindings). Diagonals hold two.
     */
    fun directions(nx: Float, ny: Float, deadZone: Float = DPAD_DEAD_ZONE): BooleanArray =
        booleanArrayOf(ny <= -deadZone, nx >= deadZone, ny >= deadZone, nx <= -deadZone)

    /** Clamps a push to the rim: returns the thumb position (each component within -1..1). */
    fun clampToRim(nx: Float, ny: Float): Pair<Float, Float> {
        val length = hypot(nx, ny)
        return if (length <= 1f) nx to ny else (nx / length) to (ny / length)
    }

    /**
     * How strongly each direction (up, right, down, left) of a stick pushed to ([nx], [ny]) is
     * held, 0 to 1, after the dead zone. Used for mouse-move bindings, which move faster the
     * further the stick is pushed.
     */
    fun strengths(nx: Float, ny: Float, deadZone: Float = STICK_DEAD_ZONE): FloatArray {
        val (cx, cy) = clampToRim(nx, ny)
        fun scaled(v: Float) = if (v <= deadZone) 0f else ((v - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        return floatArrayOf(scaled(-cy), scaled(cx), scaled(cy), scaled(-cx))
    }
}
