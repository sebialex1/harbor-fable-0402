package io.harbor.fable.display.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws a [ControlProfile]'s elements over the X screen and plays them through a
 * [ControlInput]: Fable's take on Winlator's input-controls overlay.
 *
 * Every finger is tracked on its own: the element a finger lands on owns it until it lifts, so
 * a d-pad, a stick and buttons can all be held at once. Element behaviour:
 *
 * - **Button**: holds its bindings while pressed; a toggle button latches on one tap and lets
 *   go on the next.
 * - **D-pad**: holds up / right / down / left (diagonals hold two) once the finger is a third
 *   of the way out from the centre.
 * - **Stick**: the same for key bindings, past a small dead zone; mouse-move bindings move
 *   the cursor faster the further it is pushed. The thumb follows the finger.
 * - **Trackpad**: mouse-move bindings move the cursor with the finger, like a laptop
 *   touchpad; other bindings act like a stick centred where the finger landed.
 * - **Range button**: a strip of keys from its range (A-Z, 0-9, F1-F12, keypad 0-9); a tap
 *   types the key under the finger, a drag along the strip scrolls it.
 *
 * MIDI keys and radial menus (newer Winlator kinds) are not drawn. Winlator's icons aren't
 * shipped either: an element with an icon shows its text or binding label instead.
 */
@SuppressLint("ViewConstructor")
class InputControlsView(context: Context, private val input: ControlInput) : View(context) {
    private var profile: ControlProfile? = null
    private var elements: List<ControlElement> = emptyList()
    // Keyed by identity: two identical elements in a profile are still two controls.
    private val states = java.util.IdentityHashMap<ControlElement, ElementState>()
    private val pointerOwners = HashMap<Int, ControlElement>()

    private val density = resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        color = STROKE_COLOR
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private var elementAlpha = 255
    private val path = Path()

    private val ticker = object : Runnable {
        override fun run() {
            input.tick()
            if (input.isMoving) postOnAnimation(this)
        }
    }

    /** Per-element touch state. */
    private class ElementState {
        var pointerId = -1
        var pressed = false
        var latched = false
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var thumbX = 0f
        var thumbY = 0f
        val held = BooleanArray(4)
        var rangeOffset = 0
        var rangeScrolled = false
        var rangeCarry = 0f
    }

    /** The profile being shown, or null for none. */
    val currentProfile: ControlProfile? get() = profile

    /** Shows [newProfile] (null clears the overlay), letting go of anything still held. */
    fun setProfile(newProfile: ControlProfile?) {
        releaseAll()
        profile = newProfile
        elements = newProfile?.elements?.filter { it.isSupported }.orEmpty()
        states.clear()
        elements.forEach { states[it] = ElementState() }
        input.cursorSpeed = newProfile?.cursorSpeed ?: 1f
        invalidate()
    }

    /** Lets go of every element and binding (overlay hidden, profile switched, screen closing). */
    fun releaseAll() {
        pointerOwners.clear()
        states.values.forEach { state ->
            state.pointerId = -1
            state.pressed = false
            state.latched = false
            state.held.fill(false)
            state.thumbX = 0f
            state.thumbY = 0f
        }
        input.releaseAll()
        removeCallbacks(ticker)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    /** The topmost element under ([x], [y]), or null. */
    fun elementAt(x: Float, y: Float): ControlElement? {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return null
        return elements.lastOrNull { it.boxIn(w, h).contains(x, y) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val element = elementAt(event.getX(index), event.getY(index))
                if (element == null) {
                    // Nothing here: the first finger falls through to the X screen underneath.
                    return event.actionMasked != MotionEvent.ACTION_DOWN
                }
                pointerDown(element, event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val element = pointerOwners[event.getPointerId(i)] ?: continue
                    pointerMove(element, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                pointerOwners.remove(event.getPointerId(index))?.let { pointerUp(it, event.getX(index), event.getY(index)) }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerOwners.values.toList().forEach { element -> states[element]?.let { cancel(element, it) } }
                pointerOwners.clear()
            }
        }
        return true
    }

    /** Starts [element]'s gesture for finger [pointerId]; false when the element is already held. */
    private fun pointerDown(element: ControlElement, pointerId: Int, x: Float, y: Float): Boolean {
        val state = states[element] ?: return false
        if (state.pointerId != -1) return false
        state.pointerId = pointerId
        pointerOwners[pointerId] = element
        state.downX = x
        state.downY = y
        state.lastX = x
        state.lastY = y
        when (element.type) {
            ControlType.BUTTON -> {
                if (element.toggleSwitch) {
                    state.latched = !state.latched
                    if (state.latched) pressAll(element) else releaseButton(element)
                    state.pressed = state.latched
                } else {
                    state.pressed = true
                    pressAll(element)
                }
            }
            ControlType.D_PAD, ControlType.STICK -> updateDirections(element, state, x, y)
            ControlType.TRACKPAD -> state.pressed = true
            ControlType.RANGE_BUTTON -> {
                state.pressed = true
                state.rangeScrolled = false
                state.rangeCarry = 0f
            }
            else -> Unit
        }
        invalidate()
        return true
    }

    private fun pointerMove(element: ControlElement, x: Float, y: Float) {
        val state = states[element] ?: return
        when (element.type) {
            ControlType.D_PAD, ControlType.STICK -> updateDirections(element, state, x, y)
            ControlType.TRACKPAD -> {
                if (element.bindings.any { it.effective.isMouseMove }) {
                    input.moveCursor((x - state.lastX) * TRACKPAD_SPEED, (y - state.lastY) * TRACKPAD_SPEED)
                } else {
                    updateDirections(element, state, x, y, centreX = state.downX, centreY = state.downY)
                }
            }
            ControlType.RANGE_BUTTON -> scrollRange(element, state, x, y)
            else -> Unit
        }
        state.lastX = x
        state.lastY = y
        invalidate()
    }

    private fun pointerUp(element: ControlElement, x: Float, y: Float) {
        val state = states[element] ?: return
        state.pointerId = -1
        when (element.type) {
            ControlType.BUTTON -> if (!element.toggleSwitch) {
                state.pressed = false
                releaseButton(element)
            }
            ControlType.RANGE_BUTTON -> {
                if (!state.rangeScrolled) rangeKeyAt(element, state, x, y)?.let { key ->
                    input.press(key, state)
                    input.release(key, state)
                }
                state.pressed = false
            }
            else -> clearDirections(element, state)
        }
        invalidate()
    }

    private fun cancel(element: ControlElement, state: ElementState) {
        state.pointerId = -1
        if (element.type == ControlType.BUTTON) {
            if (!element.toggleSwitch) {
                state.pressed = false
                releaseButton(element)
            }
        } else {
            clearDirections(element, state)
            state.pressed = false
        }
        invalidate()
    }

    private fun pressAll(element: ControlElement) {
        val owner = states[element] ?: return
        element.bindings.forEach { input.press(it, owner) }
        startTickerIfMoving()
    }

    private fun releaseButton(element: ControlElement) {
        val owner = states[element] ?: return
        element.bindings.forEach { input.release(it, owner) }
    }

    /** Re-evaluates which directions a d-pad / stick / trackpad holds for a finger at ([x], [y]). */
    private fun updateDirections(
        element: ControlElement,
        state: ElementState,
        x: Float,
        y: Float,
        centreX: Float? = null,
        centreY: Float? = null,
    ) {
        val box = element.boxIn(width.toFloat(), height.toFloat())
        val half = min(box.width, box.height) / 2f
        if (half <= 0f) return
        val nx = (x - (centreX ?: box.centerX)) / half
        val ny = (y - (centreY ?: box.centerY)) / half
        val isDpad = element.type == ControlType.D_PAD
        val held = ControlGeometry.directions(nx, ny, if (isDpad) ControlGeometry.DPAD_DEAD_ZONE else ControlGeometry.STICK_DEAD_ZONE)
        val strengths = if (isDpad) null else ControlGeometry.strengths(nx, ny)
        for (dir in 0 until 4) {
            val binding = element.bindingAt(dir)
            val owner = DirectionOwner(state, dir)
            if (binding.effective.isMouseMove && strengths != null) {
                // Analog: strength follows the push; re-pressing updates it.
                if (strengths[dir] > 0f) input.press(binding, owner, strengths[dir]) else input.release(binding, owner)
                state.held[dir] = strengths[dir] > 0f
                continue
            }
            if (held[dir] != state.held[dir]) {
                if (held[dir]) input.press(binding, owner) else input.release(binding, owner)
                state.held[dir] = held[dir]
            }
        }
        val (tx, ty) = ControlGeometry.clampToRim(nx, ny)
        state.thumbX = tx
        state.thumbY = ty
        state.pressed = true
        startTickerIfMoving()
    }

    private fun clearDirections(element: ControlElement, state: ElementState) {
        for (dir in 0 until 4) {
            if (state.held[dir]) input.release(element.bindingAt(dir), DirectionOwner(state, dir))
            state.held[dir] = false
        }
        state.thumbX = 0f
        state.thumbY = 0f
        state.pressed = false
    }

    private fun startTickerIfMoving() {
        if (input.isMoving) {
            removeCallbacks(ticker)
            postOnAnimation(ticker)
        }
    }

    private fun rangeKeys(element: ControlElement): List<ControlBinding> = (element.range ?: ControlRange.FROM_A_TO_Z).keys

    private fun rangeCells(element: ControlElement): Int = element.bindings.size.coerceAtLeast(1)

    private fun rangeKeyAt(element: ControlElement, state: ElementState, x: Float, y: Float): ControlBinding? {
        val box = element.boxIn(width.toFloat(), height.toFloat())
        if (!box.contains(x, y)) return null
        val cells = rangeCells(element)
        val vertical = element.orientation == 1
        val fraction = if (vertical) (y - box.top) / box.height else (x - box.left) / box.width
        val cell = (fraction * cells).toInt().coerceIn(0, cells - 1)
        val keys = rangeKeys(element)
        return keys[Math.floorMod(state.rangeOffset + cell, keys.size)]
    }

    private fun scrollRange(element: ControlElement, state: ElementState, x: Float, y: Float) {
        val box = element.boxIn(width.toFloat(), height.toFloat())
        val vertical = element.orientation == 1
        val cellSize = (if (vertical) box.height else box.width) / rangeCells(element)
        if (cellSize <= 0f) return
        val delta = if (vertical) y - state.lastY else x - state.lastX
        val travelled = if (vertical) y - state.downY else x - state.downX
        if (!state.rangeScrolled && abs(travelled) < cellSize / 2f) return
        state.rangeScrolled = true
        state.rangeCarry += delta
        val steps = (state.rangeCarry / cellSize).toInt()
        if (steps != 0) {
            state.rangeCarry -= steps * cellSize
            // Dragging towards the end reveals earlier keys, like scrolling a list.
            state.rangeOffset = Math.floorMod(state.rangeOffset - steps, rangeKeys(element).size)
        }
    }

    // ---- Drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (element in elements) {
            val state = states[element] ?: continue
            val box = element.boxIn(w, h)
            elementAlpha = ((element.opacity ?: 1f) * 255).toInt().coerceIn(0, 255)
            strokePaint.alpha = STROKE_ALPHA * elementAlpha / 255
            textPaint.alpha = elementAlpha
            fill(if (state.pressed) PRESSED_FILL else IDLE_FILL)
            when (element.type) {
                ControlType.BUTTON -> drawButton(canvas, element, box)
                ControlType.D_PAD -> drawDpad(canvas, element, state, box)
                ControlType.STICK -> drawStick(canvas, state, box)
                ControlType.TRACKPAD -> drawTrackpad(canvas, box)
                ControlType.RANGE_BUTTON -> drawRange(canvas, element, state, box)
                else -> Unit
            }
        }
    }

    private fun drawButton(canvas: Canvas, element: ControlElement, box: ControlBox) {
        rect.set(box.left, box.top, box.right, box.bottom)
        when (element.shape) {
            ControlShape.CIRCLE -> {
                val r = min(box.width, box.height) / 2f
                canvas.drawCircle(box.centerX, box.centerY, r, fillPaint)
                canvas.drawCircle(box.centerX, box.centerY, r, strokePaint)
            }
            ControlShape.ROUND_RECT -> {
                val r = box.height / 2f
                canvas.drawRoundRect(rect, r, r, fillPaint)
                canvas.drawRoundRect(rect, r, r, strokePaint)
            }
            ControlShape.RECT, ControlShape.SQUARE -> {
                val r = min(box.width, box.height) * 0.12f
                canvas.drawRoundRect(rect, r, r, fillPaint)
                canvas.drawRoundRect(rect, r, r, strokePaint)
            }
        }
        drawLabel(canvas, element.displayLabel, box.centerX, box.centerY, box.width, box.height)
    }

    private fun drawDpad(canvas: Canvas, element: ControlElement, state: ElementState, box: ControlBox) {
        // A plus sign: three-unit-wide arms reaching the element's edge.
        val arm = box.width / 3f
        path.reset()
        val l = box.left
        val t = box.top
        val r = box.right
        val b = box.bottom
        path.moveTo(l + arm, t)
        path.lineTo(r - arm, t)
        path.lineTo(r - arm, t + arm)
        path.lineTo(r, t + arm)
        path.lineTo(r, b - arm)
        path.lineTo(r - arm, b - arm)
        path.lineTo(r - arm, b)
        path.lineTo(l + arm, b)
        path.lineTo(l + arm, b - arm)
        path.lineTo(l, b - arm)
        path.lineTo(l, t + arm)
        path.lineTo(l + arm, t + arm)
        path.close()
        fill(IDLE_FILL)
        canvas.drawPath(path, fillPaint)
        // Held arms light up.
        fill(PRESSED_FILL)
        val cx = box.centerX
        val cy = box.centerY
        val arms = arrayOf(
            floatArrayOf(l + arm, t, r - arm, t + arm),
            floatArrayOf(r - arm, t + arm, r, b - arm),
            floatArrayOf(l + arm, b - arm, r - arm, b),
            floatArrayOf(l, t + arm, l + arm, b - arm),
        )
        for (dir in 0 until 4) {
            if (state.held[dir]) canvas.drawRect(arms[dir][0], arms[dir][1], arms[dir][2], arms[dir][3], fillPaint)
        }
        canvas.drawPath(path, strokePaint)
        // Labels: what each arm sends (arrows for arrow keys, letters for WASD).
        val centres = arrayOf(cx to t + arm / 2f, r - arm / 2f to cy, cx to b - arm / 2f, l + arm / 2f to cy)
        for (dir in 0 until 4) {
            val label = element.bindingAt(dir).effective.label
            drawLabel(canvas, label, centres[dir].first, centres[dir].second, arm, arm)
        }
    }

    private fun drawStick(canvas: Canvas, state: ElementState, box: ControlBox) {
        val r = min(box.width, box.height) / 2f
        fill(IDLE_FILL)
        canvas.drawCircle(box.centerX, box.centerY, r, fillPaint)
        canvas.drawCircle(box.centerX, box.centerY, r, strokePaint)
        val thumbR = r * 0.45f
        val reach = r - thumbR
        val tx = box.centerX + state.thumbX * reach
        val ty = box.centerY + state.thumbY * reach
        fill(if (state.pressed) PRESSED_FILL else THUMB_FILL)
        canvas.drawCircle(tx, ty, thumbR, fillPaint)
        canvas.drawCircle(tx, ty, thumbR, strokePaint)
    }

    private fun drawTrackpad(canvas: Canvas, box: ControlBox) {
        rect.set(box.left, box.top, box.right, box.bottom)
        val r = box.width * 0.15f
        canvas.drawRoundRect(rect, r, r, fillPaint)
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }

    private fun drawRange(canvas: Canvas, element: ControlElement, state: ElementState, box: ControlBox) {
        rect.set(box.left, box.top, box.right, box.bottom)
        val vertical = element.orientation == 1
        val radius = (if (vertical) box.width else box.height) / 2f
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        val cells = rangeCells(element)
        val keys = rangeKeys(element)
        val cellW = if (vertical) box.width else box.width / cells
        val cellH = if (vertical) box.height / cells else box.height
        for (i in 0 until cells) {
            val key = keys[Math.floorMod(state.rangeOffset + i, keys.size)]
            val cx = if (vertical) box.centerX else box.left + cellW * (i + 0.5f)
            val cy = if (vertical) box.top + cellH * (i + 0.5f) else box.centerY
            if (i > 0) {
                if (vertical) {
                    canvas.drawLine(box.left, box.top + cellH * i, box.right, box.top + cellH * i, strokePaint)
                } else {
                    canvas.drawLine(box.left + cellW * i, box.top, box.left + cellW * i, box.bottom, strokePaint)
                }
            }
            drawLabel(canvas, key.label, cx, cy, cellW, cellH)
        }
    }

    /** Sets the fill colour, scaled by the element's opacity. */
    private fun fill(color: Int) {
        fillPaint.color = color
        fillPaint.alpha = (color ushr 24) * elementAlpha / 255
    }

    /** Draws [label] (may have line breaks) centred at ([cx], [cy]), sized to fit [w] x [h]. */
    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, w: Float, h: Float) {
        if (label.isBlank()) return
        val lines = label.split('\n')
        var size = min(h * 0.42f / lines.size.coerceAtLeast(1) * if (lines.size > 1) 1.6f else 1f, MAX_TEXT_DP * density)
        textPaint.textSize = size
        val widest = lines.maxOf { textPaint.measureText(it) }
        if (widest > w * 0.82f && widest > 0f) {
            size *= w * 0.82f / widest
            textPaint.textSize = size
        }
        val lineHeight = size * 1.15f
        val first = cy - lineHeight * (lines.size - 1) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        lines.forEachIndexed { i, line -> canvas.drawText(line, cx, first + i * lineHeight, textPaint) }
    }

    /** Identifies one direction of an element for [ControlInput]'s reference counting. */
    private data class DirectionOwner(val state: ElementState, val direction: Int)

    companion object {
        private const val STROKE_DP = 1.5f
        private const val STROKE_COLOR = 0x99FFFFFF.toInt()
        private const val STROKE_ALPHA = 0x99
        private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val IDLE_FILL = 0x66000000
        private const val PRESSED_FILL = 0x66FFFFFF
        private const val THUMB_FILL = 0x99000000.toInt()
        private const val MAX_TEXT_DP = 18f

        /** Trackpad elements: X pixels of cursor travel per screen pixel of finger travel. */
        private const val TRACKPAD_SPEED = 1.5f
    }
}
