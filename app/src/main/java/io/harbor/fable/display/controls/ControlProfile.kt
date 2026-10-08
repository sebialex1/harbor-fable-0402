package io.harbor.fable.display.controls

import org.json.JSONArray
import org.json.JSONObject

/** Element kinds of a control profile; the names are Winlator's `ControlElement.Type`. */
enum class ControlType {
    BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD,

    /** Newer Winlator kinds. Kept in the file, not drawn (see [ControlElement.isSupported]). */
    MIDI_KEY, RADIAL_MENU,
    ;

    companion object {
        fun fromName(name: String?): ControlType? = entries.firstOrNull { it.name == name }
    }
}

/** Button outlines; Winlator's `ControlElement.Shape`. */
enum class ControlShape {
    CIRCLE, RECT, ROUND_RECT, SQUARE;

    companion object {
        fun fromName(name: String?): ControlShape = entries.firstOrNull { it.name == name } ?: CIRCLE
    }
}

/** Key ranges a [ControlType.RANGE_BUTTON] scrolls through; Winlator's `ControlElement.Range`. */
enum class ControlRange(val keys: List<ControlBinding>) {
    FROM_A_TO_Z(('A'..'Z').map { ControlBinding.valueOf("KEY_$it") }),
    FROM_0_TO_9(('0'..'9').map { ControlBinding.valueOf("KEY_$it") }),
    FROM_F1_TO_F12((1..12).map { ControlBinding.valueOf("KEY_F$it") }),
    FROM_NP0_TO_NP9((0..9).map { ControlBinding.valueOf("KEY_KP_$it") }),
    ;

    companion object {
        fun fromName(name: String?): ControlRange? = entries.firstOrNull { it.name == name }
    }
}

/** A rectangle in view pixels; plain numbers so the layout math is testable off-device. */
data class ControlBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/**
 * One on-screen control, in Winlator's control-profile format:
 *
 * ```json
 * {"type":"BUTTON","shape":"CIRCLE","bindings":["KEY_SPACE","NONE","NONE","NONE"],"scale":1,
 *  "x":0.88,"y":0.87,"toggleSwitch":false,"text":"","iconId":0}
 * ```
 *
 * [x] / [y] are the element's centre as a fraction of the overlay's width / height. Sizes follow
 * Winlator's grid: one unit is 1/100 of the overlay width, a circle button is 6 units across, a
 * rect 8x4, a square 5x5, a d-pad 14x14, a stick or trackpad 12x12, all times [scale]
 * ([boxIn]). [bindings] are what the element sends: a button presses all of them, a d-pad /
 * stick / trackpad uses them as up, right, down, left.
 *
 * Fields Fable doesn't use are kept in [extra] and written back, so a profile survives a round
 * trip through Fable unchanged.
 */
data class ControlElement(
    val type: ControlType,
    val shape: ControlShape = ControlShape.CIRCLE,
    val bindings: List<ControlBinding> = listOf(ControlBinding.NONE),
    val scale: Float = 1f,
    val x: Float,
    val y: Float,
    val toggleSwitch: Boolean = false,
    val text: String = "",
    val iconId: Int = 0,
    val range: ControlRange? = null,
    /** 0 = horizontal, 1 = vertical (range buttons). */
    val orientation: Int = 0,
    val opacity: Float? = null,
    val extra: JSONObject? = null,
) {
    /** False for kinds Fable doesn't play yet (MIDI keys, radial menus): they are not drawn. */
    val isSupported: Boolean get() = type != ControlType.MIDI_KEY && type != ControlType.RADIAL_MENU

    /** The binding for direction [index] (0 up, 1 right, 2 down, 3 left), or [ControlBinding.NONE]. */
    fun bindingAt(index: Int): ControlBinding = bindings.getOrNull(index) ?: ControlBinding.NONE

    /** The text drawn on the element: its own [text], else what its first binding is. */
    val displayLabel: String
        get() = text.ifBlank {
            bindings.filter { it != ControlBinding.NONE }.joinToString(" + ") { it.label }
        }

    /** Where the element sits in a [width] x [height] overlay. */
    fun boxIn(width: Float, height: Float): ControlBox {
        val unit = width / 100f
        val (halfW, halfH) = when (type) {
            ControlType.BUTTON, ControlType.MIDI_KEY -> when (shape) {
                ControlShape.RECT, ControlShape.ROUND_RECT -> 4f to 2f
                ControlShape.SQUARE -> 2.5f to 2.5f
                ControlShape.CIRCLE -> 3f to 3f
            }
            ControlType.D_PAD -> 7f to 7f
            ControlType.STICK, ControlType.TRACKPAD -> 6f to 6f
            ControlType.RANGE_BUTTON -> {
                val along = bindings.size.coerceAtLeast(1) * 4f / 2f
                if (orientation == 1) 2f to along else along to 2f
            }
            ControlType.RADIAL_MENU -> 3f to 3f
        }
        val cx = x * width
        val cy = y * height
        val hw = halfW * unit * scale
        val hh = halfH * unit * scale
        return ControlBox(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    fun toJson(): JSONObject {
        val obj = extra?.let { JSONObject(it.toString()) } ?: JSONObject()
        obj.put("type", type.name)
        obj.put("shape", shape.name)
        obj.put("bindings", JSONArray().apply { bindings.forEach { put(it.name) } })
        obj.put("scale", scale.toDouble())
        obj.put("x", x.toDouble())
        obj.put("y", y.toDouble())
        obj.put("toggleSwitch", toggleSwitch)
        obj.put("text", text)
        obj.put("iconId", iconId)
        range?.let { obj.put("range", it.name) }
        if (orientation != 0) obj.put("orientation", orientation)
        opacity?.let { obj.put("opacity", it.toDouble()) }
        return obj
    }

    companion object {
        private val KNOWN_KEYS = setOf(
            "type", "shape", "bindings", "scale", "x", "y", "toggleSwitch", "text", "iconId", "range", "orientation", "opacity",
        )

        /** Reads one element; null when its type is unknown or its position is missing. */
        fun fromJson(obj: JSONObject): ControlElement? {
            val type = ControlType.fromName(obj.optString("type")) ?: return null
            if (!obj.has("x") || !obj.has("y")) return null
            val bindingsJson = obj.optJSONArray("bindings")
            val bindings = if (bindingsJson != null) {
                (0 until bindingsJson.length()).map { ControlBinding.fromName(bindingsJson.optString(it)) }
            } else {
                listOf(ControlBinding.NONE)
            }
            val extra = JSONObject().also { rest ->
                obj.keys().forEach { key -> if (key !in KNOWN_KEYS) rest.put(key, obj.get(key)) }
            }.takeIf { it.length() > 0 }
            return ControlElement(
                type = type,
                shape = ControlShape.fromName(obj.optString("shape")),
                bindings = bindings,
                scale = obj.optDouble("scale", 1.0).toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f,
                x = obj.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                y = obj.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                toggleSwitch = obj.optBoolean("toggleSwitch", false),
                text = obj.optString("text", ""),
                iconId = obj.optInt("iconId", 0),
                range = ControlRange.fromName(obj.optString("range").ifBlank { null }),
                orientation = obj.optInt("orientation", 0),
                opacity = if (obj.has("opacity")) obj.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f) else null,
                extra = extra,
            )
        }
    }
}

/**
 * A named set of on-screen controls: a Winlator control profile (`controls-<id>.icp`), so
 * profiles exported from Winlator load as they are and Fable's are readable by Winlator.
 *
 * ```json
 * {"id":3,"name":"Virtual Gamepad","cursorSpeed":1,"elements":[ … ]}
 * ```
 *
 * [cursorSpeed] scales mouse movement from trackpad elements and mouse-move bindings. Unknown
 * top-level fields (Winlator's `controllers`, `disableMouseInput`, …) are kept in [extra].
 */
data class ControlProfile(
    val id: Int,
    val name: String,
    val cursorSpeed: Float = 1f,
    val elements: List<ControlElement>,
    val extra: JSONObject? = null,
) {
    /** Winlator treats profiles named "… template …" as starting points, not for play. */
    val isTemplate: Boolean get() = name.contains("template", ignoreCase = true)

    fun toJson(): JSONObject {
        val obj = extra?.let { JSONObject(it.toString()) } ?: JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("cursorSpeed", cursorSpeed.toDouble())
        obj.put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })
        return obj
    }

    companion object {
        private val KNOWN_KEYS = setOf("id", "name", "cursorSpeed", "elements")

        /**
         * Parses a profile document. Elements that can't be read are skipped; null when the
         * document is not a profile at all (not JSON, or no `elements` array).
         */
        fun parse(text: String): ControlProfile? {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val elementsJson = obj.optJSONArray("elements") ?: return null
            val elements = (0 until elementsJson.length()).mapNotNull { index ->
                elementsJson.optJSONObject(index)?.let(ControlElement::fromJson)
            }
            val extra = JSONObject().also { rest ->
                obj.keys().forEach { key -> if (key !in KNOWN_KEYS) rest.put(key, obj.get(key)) }
            }.takeIf { it.length() > 0 }
            return ControlProfile(
                id = obj.optInt("id", 0),
                name = obj.optString("name").ifBlank { "Unnamed" },
                cursorSpeed = obj.optDouble("cursorSpeed", 1.0).toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f,
                elements = elements,
                extra = extra,
            )
        }
    }
}
