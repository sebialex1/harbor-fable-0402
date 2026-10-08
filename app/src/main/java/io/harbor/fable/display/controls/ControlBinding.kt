package io.harbor.fable.display.controls

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * What an on-screen control element sends, named exactly like Winlator's `Binding` values so a
 * Winlator control profile (`*.icp`) reads as-is: `KEY_*` keys, `MOUSE_*` buttons / wheel /
 * movement, and `GAMEPAD_*` controller inputs.
 *
 * Fable has no virtual XInput controller, so a `GAMEPAD_*` binding is played as the keyboard or
 * mouse input most PC games bind to that control ([gamepadFallback]): the left stick is WASD, the
 * right stick moves the mouse, the d-pad is the arrow keys, A / B / X / Y are Enter / Escape /
 * Space / Shift (as on Fable's old built-in pad), the triggers are the mouse buttons, and so on.
 * Unknown names (a newer Winlator) read as [NONE] and do nothing.
 */
enum class ControlBinding {
    NONE,
    MOUSE_LEFT_BUTTON, MOUSE_MIDDLE_BUTTON, MOUSE_RIGHT_BUTTON,
    MOUSE_MOVE_LEFT, MOUSE_MOVE_RIGHT, MOUSE_MOVE_UP, MOUSE_MOVE_DOWN,
    MOUSE_SCROLL_UP, MOUSE_SCROLL_DOWN,
    KEY_UP, KEY_RIGHT, KEY_DOWN, KEY_LEFT, KEY_ENTER, KEY_ESC, KEY_BKSP, KEY_DEL, KEY_INSERT, KEY_TAB, KEY_SPACE,
    KEY_CTRL_L, KEY_CTRL_R, KEY_SHIFT_L, KEY_SHIFT_R, KEY_ALT_L, KEY_ALT_R,
    KEY_HOME, KEY_PRTSCN, KEY_PG_UP, KEY_PG_DOWN, KEY_END, KEY_CAPS_LOCK, KEY_NUM_LOCK,
    KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9,
    KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J, KEY_K, KEY_L, KEY_M,
    KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T, KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z,
    KEY_BRACKET_LEFT, KEY_BRACKET_RIGHT, KEY_BACKSLASH, KEY_SLASH, KEY_SEMICOLON, KEY_COMMA, KEY_PERIOD,
    KEY_APOSTROPHE, KEY_KP_ADD, KEY_MINUS,
    KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6, KEY_F7, KEY_F8, KEY_F9, KEY_F10, KEY_F11, KEY_F12,
    KEY_KP_0, KEY_KP_1, KEY_KP_2, KEY_KP_3, KEY_KP_4, KEY_KP_5, KEY_KP_6, KEY_KP_7, KEY_KP_8, KEY_KP_9,
    GAMEPAD_BUTTON_A, GAMEPAD_BUTTON_B, GAMEPAD_BUTTON_X, GAMEPAD_BUTTON_Y,
    GAMEPAD_BUTTON_L1, GAMEPAD_BUTTON_R1, GAMEPAD_BUTTON_SELECT, GAMEPAD_BUTTON_START,
    GAMEPAD_BUTTON_L3, GAMEPAD_BUTTON_R3, GAMEPAD_BUTTON_L2, GAMEPAD_BUTTON_R2,
    GAMEPAD_LEFT_THUMB_UP, GAMEPAD_LEFT_THUMB_RIGHT, GAMEPAD_LEFT_THUMB_DOWN, GAMEPAD_LEFT_THUMB_LEFT,
    GAMEPAD_RIGHT_THUMB_UP, GAMEPAD_RIGHT_THUMB_RIGHT, GAMEPAD_RIGHT_THUMB_DOWN, GAMEPAD_RIGHT_THUMB_LEFT,
    GAMEPAD_DPAD_UP, GAMEPAD_DPAD_RIGHT, GAMEPAD_DPAD_DOWN, GAMEPAD_DPAD_LEFT,
    KEY_VOL_UP, KEY_VOL_DOWN,
    ;

    val isKeyboard: Boolean get() = name.startsWith("KEY_")
    val isMouse: Boolean get() = name.startsWith("MOUSE_")
    val isGamepad: Boolean get() = name.startsWith("GAMEPAD_")
    val isMouseMove: Boolean
        get() = this == MOUSE_MOVE_LEFT || this == MOUSE_MOVE_RIGHT || this == MOUSE_MOVE_UP || this == MOUSE_MOVE_DOWN

    /**
     * The binding actually played: itself, or for a `GAMEPAD_*` binding the keyboard / mouse
     * stand-in described on the class.
     */
    val effective: ControlBinding get() = gamepadFallback[this] ?: this

    /** The X key this binding presses, or null when it is not a key (or has no X key). */
    val xKeycode: XKeycode?
        get() {
            val binding = effective
            if (!binding.isKeyboard) return null
            val keyName = when (binding) {
                KEY_PG_UP -> "KEY_PRIOR"
                KEY_PG_DOWN -> "KEY_NEXT"
                else -> binding.name
            }
            return runCatching { XKeycode.valueOf(keyName) }.getOrNull()?.takeIf { it != XKeycode.KEY_NONE }
        }

    /** The X pointer button this binding presses (mouse buttons and the wheel), or null. */
    val pointerButton: Pointer.Button?
        get() = when (effective) {
            MOUSE_LEFT_BUTTON -> Pointer.Button.BUTTON_LEFT
            MOUSE_MIDDLE_BUTTON -> Pointer.Button.BUTTON_MIDDLE
            MOUSE_RIGHT_BUTTON -> Pointer.Button.BUTTON_RIGHT
            MOUSE_SCROLL_UP -> Pointer.Button.BUTTON_SCROLL_UP
            MOUSE_SCROLL_DOWN -> Pointer.Button.BUTTON_SCROLL_DOWN
            else -> null
        }

    /** Short label drawn on a button: "W", "SPACE", "L SHIFT", "LMB", "A". */
    val label: String
        get() = when (this) {
            NONE -> ""
            MOUSE_LEFT_BUTTON -> "LMB"
            MOUSE_MIDDLE_BUTTON -> "MMB"
            MOUSE_RIGHT_BUTTON -> "RMB"
            MOUSE_SCROLL_UP -> "WHEEL \u25B2"
            MOUSE_SCROLL_DOWN -> "WHEEL \u25BC"
            KEY_SHIFT_L -> "L SHIFT"
            KEY_SHIFT_R -> "R SHIFT"
            KEY_CTRL_L -> "L CTRL"
            KEY_CTRL_R -> "R CTRL"
            KEY_ALT_L -> "L ALT"
            KEY_ALT_R -> "R ALT"
            KEY_UP -> "\u25B2"
            KEY_DOWN -> "\u25BC"
            KEY_LEFT -> "\u25C0"
            KEY_RIGHT -> "\u25B6"
            KEY_BRACKET_LEFT -> "["
            KEY_BRACKET_RIGHT -> "]"
            KEY_BACKSLASH -> "\\"
            KEY_SLASH -> "/"
            KEY_SEMICOLON -> ";"
            KEY_COMMA -> ","
            KEY_PERIOD -> "."
            KEY_APOSTROPHE -> "'"
            KEY_MINUS -> "-"
            KEY_KP_ADD -> "+"
            KEY_VOL_UP -> "VOL +"
            KEY_VOL_DOWN -> "VOL -"
            else -> name.replace(PREFIX, "").replace("KP_", "NUM ").replace("BUTTON_", "").replace('_', ' ')
        }

    companion object {
        private val PREFIX = Regex("^(MOUSE_|KEY_|GAMEPAD_)")

        /** Keyboard / mouse stand-ins for gamepad inputs (Fable has no virtual controller). */
        val gamepadFallback: Map<ControlBinding, ControlBinding> = mapOf(
            GAMEPAD_BUTTON_A to KEY_ENTER,
            GAMEPAD_BUTTON_B to KEY_ESC,
            GAMEPAD_BUTTON_X to KEY_SPACE,
            GAMEPAD_BUTTON_Y to KEY_SHIFT_L,
            GAMEPAD_BUTTON_L1 to KEY_Q,
            GAMEPAD_BUTTON_R1 to KEY_E,
            GAMEPAD_BUTTON_L2 to MOUSE_RIGHT_BUTTON,
            GAMEPAD_BUTTON_R2 to MOUSE_LEFT_BUTTON,
            GAMEPAD_BUTTON_L3 to KEY_CTRL_L,
            GAMEPAD_BUTTON_R3 to MOUSE_MIDDLE_BUTTON,
            GAMEPAD_BUTTON_SELECT to KEY_TAB,
            GAMEPAD_BUTTON_START to KEY_ESC,
            GAMEPAD_LEFT_THUMB_UP to KEY_W,
            GAMEPAD_LEFT_THUMB_RIGHT to KEY_D,
            GAMEPAD_LEFT_THUMB_DOWN to KEY_S,
            GAMEPAD_LEFT_THUMB_LEFT to KEY_A,
            GAMEPAD_RIGHT_THUMB_UP to MOUSE_MOVE_UP,
            GAMEPAD_RIGHT_THUMB_RIGHT to MOUSE_MOVE_RIGHT,
            GAMEPAD_RIGHT_THUMB_DOWN to MOUSE_MOVE_DOWN,
            GAMEPAD_RIGHT_THUMB_LEFT to MOUSE_MOVE_LEFT,
            GAMEPAD_DPAD_UP to KEY_UP,
            GAMEPAD_DPAD_RIGHT to KEY_RIGHT,
            GAMEPAD_DPAD_DOWN to KEY_DOWN,
            GAMEPAD_DPAD_LEFT to KEY_LEFT,
        )

        /**
         * Reads a binding name the way Winlator does (`KEY_CTRL` / `KEY_SHIFT` / `KEY_ALT` are
         * the left keys); anything unknown is [NONE].
         */
        fun fromName(name: String?): ControlBinding = when (name) {
            null -> NONE
            "KEY_CTRL" -> KEY_CTRL_L
            "KEY_SHIFT" -> KEY_SHIFT_L
            "KEY_ALT" -> KEY_ALT_L
            else -> entries.firstOrNull { it.name == name } ?: NONE
        }
    }
}
