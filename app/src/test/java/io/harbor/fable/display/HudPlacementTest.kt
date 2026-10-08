package io.harbor.fable.display

import io.harbor.fable.data.models.HudPosition
import io.harbor.fable.data.models.HudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudPlacementTest {
    // A 100 x 40 HUD on a 1000 x 500 screen with an 8 px margin: 784 x 444 px of free space.
    private val w = 100
    private val h = 40
    private val pw = 1000
    private val ph = 500
    private val m = 8

    @Test fun cornersMapToTheMargins() {
        assertEquals(8f to 8f, HudAnchor.of(HudPosition.TOP_START).toPixels(w, h, pw, ph, m))
        assertEquals(892f to 452f, HudAnchor.of(HudPosition.BOTTOM_END).toPixels(w, h, pw, ph, m))
    }

    @Test fun customPositionWinsOverTheCorner() {
        val hud = HudSettings(position = HudPosition.BOTTOM_END, customX = 0.5f, customY = 0.25f)
        assertEquals(HudAnchor(0.5f, 0.25f), HudAnchor.of(hud))
        assertEquals(HudAnchor.of(HudPosition.TOP_END), HudAnchor.of(HudSettings(position = HudPosition.TOP_END)))
    }

    @Test fun pixelsRoundTripAndOffscreenDropsArePulledIn() {
        val anchor = HudAnchor.fromPixels(400f, 230f, w, h, pw, ph, m)
        val (x, y) = anchor.toPixels(w, h, pw, ph, m)
        assertEquals(400f, x, 0.01f)
        assertEquals(230f, y, 0.01f)
        assertEquals(HudAnchor(1f, 0f), HudAnchor.fromPixels(5000f, -300f, w, h, pw, ph, m))
    }

    @Test fun nearEdgeSnapsSoARightHandHudStaysRightAfterRotation() {
        val anchor = HudAnchor.fromPixels(885f, 10f, w, h, pw, ph, m)
        assertEquals(HudAnchor(1f, 0f), anchor)
        // Portrait: still against the right edge.
        assertEquals(492f to 8f, anchor.toPixels(w, h, 600, 1000, m))
    }

    @Test fun hudLargerThanTheScreenSticksToTheMargin() {
        assertEquals(8f to 8f, HudAnchor(1f, 1f).toPixels(2000, 900, pw, ph, m))
        assertEquals(HudAnchor(0f, 0f), HudAnchor.fromPixels(300f, 300f, 2000, 900, pw, ph, m))
    }

    @Test fun shortTouchIsATap() {
        val drag = HudDragTracker(slopPx = 10f)
        drag.down(0, 100f, 100f, left = 8f, top = 8f)
        assertNull(drag.move(0, 104f, 103f))
        assertEquals(HudDragTracker.Result.Tap, drag.up(0, 105f, 104f))
        assertTrue(!drag.active)
    }

    @Test fun movingPastTheSlopDragsFromWhereItStarted() {
        val drag = HudDragTracker(slopPx = 10f)
        drag.down(3, 100f, 100f, left = 8f, top = 8f)
        assertEquals(58f to 28f, drag.move(3, 150f, 120f))
        assertTrue(drag.dragging)
        // Coming back inside the slop is still a drag, not a tap.
        assertEquals(10f to 8f, drag.move(3, 102f, 100f))
        assertEquals(HudDragTracker.Result.Moved(10f, 8f), drag.up(3, 102f, 100f))
    }

    @Test fun otherFingersAreIgnored() {
        val drag = HudDragTracker(slopPx = 10f)
        drag.down(0, 100f, 100f, left = 0f, top = 0f)
        drag.down(1, 500f, 500f, left = 0f, top = 0f) // second finger on the HUD: ignored
        assertNull(drag.move(1, 900f, 900f))
        assertEquals(HudDragTracker.Result.None, drag.up(1, 900f, 900f))
        assertEquals(HudDragTracker.Result.Tap, drag.up(0, 100f, 100f))
    }

    @Test fun cancelEndsTheGesture() {
        val drag = HudDragTracker(slopPx = 10f)
        drag.down(0, 0f, 0f, 0f, 0f)
        drag.move(0, 50f, 0f)
        drag.cancel()
        assertTrue(!drag.active && !drag.dragging)
        assertEquals(HudDragTracker.Result.None, drag.up(0, 50f, 0f))
    }
}
