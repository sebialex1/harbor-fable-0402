package io.harbor.fable.display

import io.harbor.fable.data.models.HudPosition
import io.harbor.fable.data.models.HudSettings
import kotlin.math.hypot

/**
 * Where the performance HUD sits, as fractions of the free space around it: x 0 = against the
 * left margin, 1 = against the right one; y likewise top to bottom. Fractions rather than pixels
 * so the HUD stays on screen and keeps its side when the screen rotates or its text gets wider
 * (a right-hand HUD grows to the left).
 */
data class HudAnchor(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "anchor out of range: $x, $y" }
    }

    /** Top-left pixel of a [width] × [height] HUD in a [parentWidth] × [parentHeight] screen. */
    fun toPixels(width: Int, height: Int, parentWidth: Int, parentHeight: Int, margin: Int): Pair<Float, Float> {
        val freeX = (parentWidth - width - 2 * margin).coerceAtLeast(0)
        val freeY = (parentHeight - height - 2 * margin).coerceAtLeast(0)
        return (margin + x * freeX) to (margin + y * freeY)
    }

    companion object {
        /** The anchor of a HUD whose top-left pixel is ([left], [top]); off-screen values are pulled in. */
        fun fromPixels(left: Float, top: Float, width: Int, height: Int, parentWidth: Int, parentHeight: Int, margin: Int): HudAnchor {
            val freeX = (parentWidth - width - 2 * margin).coerceAtLeast(0)
            val freeY = (parentHeight - height - 2 * margin).coerceAtLeast(0)
            fun fraction(pos: Float, free: Int) = if (free == 0) 0f else ((pos - margin) / free).coerceIn(0f, 1f)
            return HudAnchor(snap(fraction(left, freeX)), snap(fraction(top, freeY)))
        }

        /** The corner presets as anchors. */
        fun of(position: HudPosition): HudAnchor = when (position) {
            HudPosition.TOP_START -> HudAnchor(0f, 0f)
            HudPosition.TOP_END -> HudAnchor(1f, 0f)
            HudPosition.BOTTOM_START -> HudAnchor(0f, 1f)
            HudPosition.BOTTOM_END -> HudAnchor(1f, 1f)
        }

        /** Where [hud] goes: where the user dragged it, else its corner. */
        fun of(hud: HudSettings): HudAnchor {
            val x = hud.customX
            val y = hud.customY
            return if (x != null && y != null) HudAnchor(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else of(hud.position)
        }

        /** Dropped within 3 % of an edge counts as on it, so "against the edge" survives a resize. */
        private fun snap(f: Float): Float = when {
            f < EDGE_SNAP -> 0f
            f > 1f - EDGE_SNAP -> 1f
            else -> f
        }

        private const val EDGE_SNAP = 0.03f
    }
}

/**
 * Tells a tap on the HUD from a drag of it, for one finger at a time (a second finger on the HUD
 * is ignored, and fingers elsewhere never reach it: the HUD only gets touches that land on it, so
 * the touchpad and on-screen controls keep theirs). A finger becomes a drag once it has moved
 * [slopPx] from where it landed; until then lifting it is a tap. Coordinates are screen (raw)
 * pixels so moving the view under the finger doesn't feed back into the deltas.
 */
class HudDragTracker(private val slopPx: Float) {
    sealed interface Result {
        /** Lifted without moving past the slop. */
        data object Tap : Result

        /** Dragged; the HUD's top-left ended at ([left], [top]). */
        data class Moved(val left: Float, val top: Float) : Result

        /** Not a gesture of ours (another finger, cancelled). */
        data object None : Result
    }

    private var pointerId = NO_POINTER
    private var downRawX = 0f
    private var downRawY = 0f
    private var startLeft = 0f
    private var startTop = 0f

    /** True once the current finger is dragging. */
    var dragging = false
        private set

    val active: Boolean get() = pointerId != NO_POINTER

    /** A finger landed on the HUD, whose top-left is at ([left], [top]). */
    fun down(id: Int, rawX: Float, rawY: Float, left: Float, top: Float) {
        if (active) return
        pointerId = id
        downRawX = rawX
        downRawY = rawY
        startLeft = left
        startTop = top
        dragging = false
    }

    /** The HUD's new top-left while dragging, or null while it is still a possible tap. */
    fun move(id: Int, rawX: Float, rawY: Float): Pair<Float, Float>? {
        if (id != pointerId) return null
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        if (!dragging && hypot(dx, dy) < slopPx) return null
        dragging = true
        return (startLeft + dx) to (startTop + dy)
    }

    fun up(id: Int, rawX: Float, rawY: Float): Result {
        if (id != pointerId) return Result.None
        val result = move(id, rawX, rawY)?.let { (left, top) -> Result.Moved(left, top) } ?: Result.Tap
        reset()
        return result
    }

    fun cancel() = reset()

    private fun reset() {
        pointerId = NO_POINTER
        dragging = false
    }

    private companion object {
        const val NO_POINTER = -1
    }
}
