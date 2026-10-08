package io.harbor.fable.display.controls

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trackpad's click and motion rules when it shares the screen with on-screen controls. */
class TouchpadControllerTest {
    private val events = mutableListOf<String>()
    private val target = object : InputTarget {
        override fun keyDown(key: XKeycode) { events += "down $key" }
        override fun keyUp(key: XKeycode) { events += "up $key" }
        override fun buttonDown(button: Pointer.Button) { events += "press $button" }
        override fun buttonUp(button: Pointer.Button) { events += "release $button" }
        override fun moveBy(dx: Int, dy: Int) { events += "move $dx,$dy" }
    }
    private val pad = TouchpadController(target, touchSlopPx = 10f, tapTimeoutMs = 200L) { 1f }

    @Test
    fun tapIsALeftClick() {
        pad.pointerDown(0, 100f, 100f, 0L)
        pad.pointerUp(0, 102f, 101f, 100L)
        assertEquals(listOf("press BUTTON_LEFT", "release BUTTON_LEFT"), events)
    }

    @Test
    fun dragMovesWithoutClicking() {
        pad.pointerDown(0, 100f, 100f, 0L)
        pad.pointerMove(0, 130f, 90f)
        pad.pointerUp(0, 130f, 90f, 100L)
        assertEquals(listOf("move 30,-10"), events)
    }

    @Test
    fun secondTrackpadFingerIsOneRightClick() {
        pad.pointerDown(0, 100f, 100f, 0L)
        pad.pointerDown(1, 300f, 100f, 20L)
        pad.pointerUp(1, 300f, 100f, 60L)
        pad.pointerUp(0, 100f, 100f, 80L)
        assertEquals(listOf("press BUTTON_RIGHT", "release BUTTON_RIGHT"), events)
    }

    @Test
    fun trackingMovesToTheRemainingFingerWithoutAJump() {
        pad.pointerDown(0, 100f, 100f, 0L)
        pad.pointerDown(1, 300f, 300f, 10L)
        events.clear()
        pad.pointerUp(0, 100f, 100f, 50L)
        pad.pointerMove(1, 305f, 300f)
        assertEquals(listOf("move 5,0"), events)
    }

    /**
     * The view only hands the trackpad fingers that missed every control, so a finger holding a
     * button (never passed here) can't turn the trackpad finger's tap into a right click, and the
     * trackpad finger keeps steering while the button is held.
     */
    @Test
    fun controlFingersDontReachTheTrackpad() {
        pad.pointerDown(5, 100f, 100f, 0L)
        pad.pointerMove(5, 120f, 100f)
        pad.pointerUp(5, 120f, 100f, 300L)
        assertEquals(listOf("move 20,0"), events)
        assertTrue(!pad.owns(5) && pad.fingerCount == 0)
    }
}
