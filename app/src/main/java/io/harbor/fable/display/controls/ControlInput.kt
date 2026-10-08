package io.harbor.fable.display.controls

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/** Where on-screen controls send input: the X server, or a recorder in tests. */
interface InputTarget {
    fun keyDown(key: XKeycode)
    fun keyUp(key: XKeycode)
    fun buttonDown(button: Pointer.Button)
    fun buttonUp(button: Pointer.Button)
    fun moveBy(dx: Int, dy: Int)
}

/**
 * Turns binding presses from any number of on-screen elements into X input.
 *
 * Presses are reference counted per key / button, so two elements holding the same key (a
 * d-pad and a button both bound to `KEY_W`) only release it when both let go. Wheel bindings
 * click once per press. Mouse-move bindings don't press anything: each held one adds a
 * direction (with a strength from 0 to 1, set by sticks) to a movement vector that [tick]
 * turns into cursor motion, carrying sub-pixel remainders so slow pushes still move.
 *
 * Not thread-safe: use it from the UI thread.
 */
class ControlInput(private val target: InputTarget) {
    private val keyCounts = HashMap<XKeycode, Int>()
    private val buttonCounts = HashMap<Pointer.Button, Int>()
    private val moves = HashMap<Any, Pair<Float, Float>>()
    private var carryX = 0f
    private var carryY = 0f

    /** Cursor speed multiplier from the active profile. */
    var cursorSpeed = 1f

    /** True while any mouse-move binding is held (the owner keeps calling [tick]). */
    val isMoving: Boolean get() = moves.values.any { it.first != 0f || it.second != 0f }

    /** Presses [binding] on behalf of the element (or element part) [owner]. */
    fun press(binding: ControlBinding, owner: Any, strength: Float = 1f) {
        val effective = binding.effective
        when {
            effective == ControlBinding.NONE -> Unit
            effective.isMouseMove -> setMove(effective, owner, strength)
            effective == ControlBinding.MOUSE_SCROLL_UP || effective == ControlBinding.MOUSE_SCROLL_DOWN -> {
                val button = effective.pointerButton ?: return
                target.buttonDown(button)
                target.buttonUp(button)
            }
            else -> {
                effective.pointerButton?.let { button ->
                    val count = buttonCounts[button] ?: 0
                    buttonCounts[button] = count + 1
                    if (count == 0) target.buttonDown(button)
                }
                effective.xKeycode?.let { key ->
                    val count = keyCounts[key] ?: 0
                    keyCounts[key] = count + 1
                    if (count == 0) target.keyDown(key)
                }
            }
        }
    }

    /** Releases a [press] made by [owner]. */
    fun release(binding: ControlBinding, owner: Any) {
        val effective = binding.effective
        when {
            effective == ControlBinding.NONE -> Unit
            effective.isMouseMove -> moves.remove(moveKey(effective, owner))
            effective == ControlBinding.MOUSE_SCROLL_UP || effective == ControlBinding.MOUSE_SCROLL_DOWN -> Unit
            else -> {
                effective.pointerButton?.let { button ->
                    val count = buttonCounts[button] ?: return@let
                    if (count <= 1) {
                        buttonCounts.remove(button)
                        target.buttonUp(button)
                    } else {
                        buttonCounts[button] = count - 1
                    }
                }
                effective.xKeycode?.let { key ->
                    val count = keyCounts[key] ?: return@let
                    if (count <= 1) {
                        keyCounts.remove(key)
                        target.keyUp(key)
                    } else {
                        keyCounts[key] = count - 1
                    }
                }
            }
        }
    }

    /** Moves the cursor directly, in X pixels (trackpad elements), scaled by [cursorSpeed]. */
    fun moveCursor(dx: Float, dy: Float) {
        carryX += dx * cursorSpeed
        carryY += dy * cursorSpeed
        flushMotion()
    }

    /** Applies one frame of held mouse-move bindings. */
    fun tick() {
        var vx = 0f
        var vy = 0f
        moves.values.forEach { (x, y) -> vx += x; vy += y }
        if (vx == 0f && vy == 0f) return
        carryX += vx * MOVE_PIXELS_PER_TICK * cursorSpeed
        carryY += vy * MOVE_PIXELS_PER_TICK * cursorSpeed
        flushMotion()
    }

    /** Lets go of everything still held (profile switch, overlay hidden, screen closing). */
    fun releaseAll() {
        keyCounts.keys.toList().forEach(target::keyUp)
        buttonCounts.keys.toList().forEach(target::buttonUp)
        keyCounts.clear()
        buttonCounts.clear()
        moves.clear()
        carryX = 0f
        carryY = 0f
    }

    private fun setMove(binding: ControlBinding, owner: Any, strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        val vector = when (binding) {
            ControlBinding.MOUSE_MOVE_LEFT -> -s to 0f
            ControlBinding.MOUSE_MOVE_RIGHT -> s to 0f
            ControlBinding.MOUSE_MOVE_UP -> 0f to -s
            ControlBinding.MOUSE_MOVE_DOWN -> 0f to s
            else -> return
        }
        moves[moveKey(binding, owner)] = vector
    }

    private fun moveKey(binding: ControlBinding, owner: Any): Any = binding to owner

    private fun flushMotion() {
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        if (dx == 0 && dy == 0) return
        carryX -= dx
        carryY -= dy
        target.moveBy(dx, dy)
    }

    companion object {
        /** Cursor travel per frame (~60 Hz) for a fully held mouse-move binding. */
        const val MOVE_PIXELS_PER_TICK = 10f
    }
}
