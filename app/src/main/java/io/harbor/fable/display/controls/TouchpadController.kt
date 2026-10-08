package io.harbor.fable.display.controls

import com.winlator.xserver.Pointer
import kotlin.math.hypot

/**
 * The X screen's laptop-style trackpad, fed one finger at a time so it can share the screen
 * with on-screen controls: [InputControlsView] gives it every finger that didn't land on a
 * control element, and only those.
 *
 * - One finger dragging moves the cursor relatively ([pixelScale] converts screen pixels to X
 *   pixels; sub-pixel remainders carry over so slow drags still move).
 * - A quick tap (shorter than [tapTimeoutMs], within [touchSlopPx]) is a left click.
 * - A second *trackpad* finger going down is a right click. Fingers on controls never count,
 *   so holding a d-pad or button no longer turns a tap into a right click.
 * - When the moving finger lifts with another trackpad finger still down, tracking moves to
 *   that finger without a jump.
 *
 * Not thread-safe: call it from the UI thread.
 */
class TouchpadController(
    private val target: InputTarget,
    private val touchSlopPx: Float,
    private val tapTimeoutMs: Long,
    /** Screen pixels -> X pixels for cursor motion; 0 or less ignores motion (no screen yet). */
    private val pixelScale: () -> Float,
) {
    private val fingers = LinkedHashMap<Int, FloatArray>()
    private var trackedId = NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tapCandidate = false
    private var carryX = 0f
    private var carryY = 0f

    /** Fingers currently on the trackpad. */
    val fingerCount: Int get() = fingers.size

    /** Whether finger [pointerId] belongs to the trackpad. */
    fun owns(pointerId: Int): Boolean = pointerId in fingers

    fun pointerDown(pointerId: Int, x: Float, y: Float, time: Long) {
        fingers[pointerId] = floatArrayOf(x, y)
        if (fingers.size == 1) {
            // First finger: remember where it landed; the cursor stays put.
            trackedId = pointerId
            downX = x
            downY = y
            downTime = time
            carryX = 0f
            carryY = 0f
            tapCandidate = true
        } else {
            tapCandidate = false
            if (fingers.size == 2) {
                target.buttonDown(Pointer.Button.BUTTON_RIGHT)
                target.buttonUp(Pointer.Button.BUTTON_RIGHT)
            }
        }
    }

    fun pointerMove(pointerId: Int, x: Float, y: Float) {
        val last = fingers[pointerId] ?: return
        if (pointerId == trackedId && fingers.size == 1) {
            if (tapCandidate && hypot(x - downX, y - downY) > touchSlopPx) tapCandidate = false
            val scale = pixelScale()
            if (scale > 0f) {
                carryX += (x - last[0]) * scale
                carryY += (y - last[1]) * scale
                val dx = carryX.toInt()
                val dy = carryY.toInt()
                if (dx != 0 || dy != 0) {
                    carryX -= dx
                    carryY -= dy
                    target.moveBy(dx, dy)
                }
            }
        }
        last[0] = x
        last[1] = y
    }

    fun pointerUp(pointerId: Int, x: Float, y: Float, time: Long) {
        if (fingers.remove(pointerId) == null) return
        if (fingers.isEmpty()) {
            val isTap = tapCandidate && time - downTime < tapTimeoutMs && hypot(x - downX, y - downY) <= touchSlopPx
            if (isTap) {
                target.buttonDown(Pointer.Button.BUTTON_LEFT)
                target.buttonUp(Pointer.Button.BUTTON_LEFT)
            }
            tapCandidate = false
            trackedId = NONE
        } else if (pointerId == trackedId) {
            // Keep tracking whichever finger remains (its last position is already stored).
            trackedId = fingers.keys.first()
        }
    }

    /** Forgets every finger without clicking (gesture cancelled, overlay torn down). */
    fun cancel() {
        fingers.clear()
        trackedId = NONE
        tapCandidate = false
    }

    private companion object {
        const val NONE = -1
    }
}
