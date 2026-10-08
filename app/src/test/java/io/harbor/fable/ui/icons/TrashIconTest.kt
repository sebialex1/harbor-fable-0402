package io.harbor.fable.ui.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry of [FableIcons.TRASH_PATH]: inside the 256-unit viewport (nothing clipped at any icon
 * size) and mirror-symmetric about x = 128, bars included. The old glyph's bars sat at x 112–128
 * and 160–176, centred on 144, which is what made the delete button look lopsided.
 */
class TrashIconTest {
    private data class Box(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val centerX: Float get() = (minX + maxX) / 2f
    }

    /** Bounding box of every subpath, from the end points of each segment (arcs add their radius). */
    private fun subpathBounds(data: String): List<Box> {
        val boxes = mutableListOf<Box>()
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var open = false
        fun add(px: Float, py: Float, pad: Float = 0f) {
            minX = minOf(minX, px - pad); maxX = maxOf(maxX, px + pad)
            minY = minOf(minY, py - pad); maxY = maxOf(maxY, py + pad)
            open = true
        }
        fun flush() {
            if (open) boxes += Box(minX, minY, maxX, maxY)
            minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
            open = false
        }
        for (node in addPathNodes(data)) {
            when (node) {
                is PathNode.MoveTo -> { flush(); x = node.x; y = node.y; startX = x; startY = y; add(x, y) }
                is PathNode.RelativeMoveTo -> { flush(); x += node.dx; y += node.dy; startX = x; startY = y; add(x, y) }
                is PathNode.LineTo -> { x = node.x; y = node.y; add(x, y) }
                is PathNode.RelativeLineTo -> { x += node.dx; y += node.dy; add(x, y) }
                is PathNode.HorizontalTo -> { x = node.x; add(x, y) }
                is PathNode.RelativeHorizontalTo -> { x += node.dx; add(x, y) }
                is PathNode.VerticalTo -> { y = node.y; add(x, y) }
                is PathNode.RelativeVerticalTo -> { y += node.dy; add(x, y) }
                is PathNode.ArcTo -> {
                    val midX = (x + node.arcStartX) / 2f
                    val midY = (y + node.arcStartY) / 2f
                    x = node.arcStartX; y = node.arcStartY
                    add(x, y); add(midX, midY, node.horizontalEllipseRadius / 2f)
                }
                is PathNode.RelativeArcTo -> {
                    val midX = x + node.arcStartDx / 2f
                    val midY = y + node.arcStartDy / 2f
                    x += node.arcStartDx; y += node.arcStartDy
                    add(x, y); add(midX, midY, node.horizontalEllipseRadius / 2f)
                }
                is PathNode.Close -> { x = startX; y = startY }
                else -> error("unexpected path node $node")
            }
        }
        flush()
        return boxes
    }

    @Test fun glyphStaysInsideTheViewport() {
        val all = subpathBounds(FableIcons.TRASH_PATH)
        assertTrue(all.isNotEmpty())
        all.forEach { box ->
            assertTrue("$box", box.minX >= 0f && box.minY >= 0f && box.maxX <= 256f && box.maxY <= 256f)
        }
    }

    @Test fun glyphIsCentredAndBarsAreSymmetric() {
        val boxes = subpathBounds(FableIcons.TRASH_PATH)
        // Outline (lid + body) is centred.
        assertEquals(128f, boxes.first().centerX, 0.5f)
        // The two bars are the last two subpaths: mirror images about x = 128.
        val (left, right) = boxes.takeLast(2).sortedBy { it.centerX }
        assertEquals(128f, (left.centerX + right.centerX) / 2f, 0.5f)
        assertEquals(left.maxX - left.minX, right.maxX - right.minX, 0.5f)
        assertEquals(left.minY, right.minY, 0.5f)
        assertEquals(left.maxY, right.maxY, 0.5f)
    }
}
