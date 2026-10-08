package io.harbor.fable.display.controls

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Winlator profile compatibility, the built-in presets, and the control input plumbing. */
class ControlProfileTest {
    /** A profile in the shape Winlator writes (`controls-<id>.icp`), with fields Fable ignores. */
    private val winlatorProfile = """
        {"id":7,"name":"Skyrim","cursorSpeed":1.5,"disableMouseInput":false,"controllers":[],
         "elements":[
          {"type":"D_PAD","shape":"CIRCLE","bindings":["KEY_W","KEY_D","KEY_S","KEY_A"],"scale":1,"x":0.1,"y":0.7,"toggleSwitch":false,"text":"","iconId":0},
          {"type":"BUTTON","shape":"ROUND_RECT","bindings":["KEY_SHIFT","NONE","NONE","NONE"],"scale":0.85,"x":0.5,"y":0.9,"toggleSwitch":true,"text":"Run","iconId":3},
          {"type":"STICK","shape":"CIRCLE","bindings":["MOUSE_MOVE_UP","MOUSE_MOVE_RIGHT","MOUSE_MOVE_DOWN","MOUSE_MOVE_LEFT"],"scale":1,"x":0.8,"y":0.7,"toggleSwitch":false,"text":"","iconId":0},
          {"type":"RANGE_BUTTON","shape":"CIRCLE","bindings":["NONE","NONE","NONE","NONE","NONE"],"scale":1,"x":0.5,"y":0.1,"toggleSwitch":false,"text":"","iconId":0,"range":"FROM_F1_TO_F12","orientation":1},
          {"type":"RADIAL_MENU","shape":"CIRCLE","bindings":["KEY_1"],"scale":1,"x":0.3,"y":0.3,"toggleSwitch":false,"text":"","iconId":0},
          {"type":"SOMETHING_NEW","x":0.3,"y":0.3},
          {"type":"BUTTON","bindings":["KEY_FUTURE_KEY"],"x":0.4,"y":0.4,"mouseMoveMode":"x"}
         ]}
    """.trimIndent()

    @Test
    fun readsWinlatorProfiles() {
        val profile = ControlProfile.parse(winlatorProfile)
        assertNotNull(profile)
        profile!!
        assertEquals(7, profile.id)
        assertEquals("Skyrim", profile.name)
        assertEquals(1.5f, profile.cursorSpeed, 0.0001f)
        // Unknown element types are dropped; known-but-unplayed ones are kept but not drawn.
        assertEquals(6, profile.elements.size)
        val dpad = profile.elements[0]
        assertEquals(ControlType.D_PAD, dpad.type)
        assertEquals(listOf(ControlBinding.KEY_W, ControlBinding.KEY_D, ControlBinding.KEY_S, ControlBinding.KEY_A), dpad.bindings)
        val run = profile.elements[1]
        assertEquals(ControlBinding.KEY_SHIFT_L, run.bindings[0])
        assertTrue(run.toggleSwitch)
        assertEquals("Run", run.displayLabel)
        assertEquals(ControlShape.ROUND_RECT, run.shape)
        assertEquals(0.85f, run.scale, 0.0001f)
        val range = profile.elements[3]
        assertEquals(ControlRange.FROM_F1_TO_F12, range.range)
        assertEquals(1, range.orientation)
        assertFalse(profile.elements[4].isSupported)
        // An unknown binding reads as NONE rather than failing the profile.
        assertEquals(ControlBinding.NONE, profile.elements[5].bindings[0])
    }

    @Test
    fun roundTripKeepsWinlatorFields() {
        val profile = ControlProfile.parse(winlatorProfile)!!
        val again = ControlProfile.parse(profile.toJson().toString())!!
        assertEquals(profile.name, again.name)
        assertEquals(profile.elements.map { it.bindings }, again.elements.map { it.bindings })
        val json = again.toJson()
        assertTrue(json.has("controllers"))
        assertTrue(json.has("disableMouseInput"))
        assertEquals("x", json.getJSONArray("elements").getJSONObject(5).getString("mouseMoveMode"))
    }

    @Test
    fun rejectsDocumentsThatAreNotProfiles() {
        assertNull(ControlProfile.parse("not json"))
        assertNull(ControlProfile.parse("""{"id":1,"name":"x"}"""))
    }

    @Test
    fun builtInPresetsParseAndFitOnScreen() {
        val dir = File("src/main/assets/inputcontrols/profiles")
        val files = dir.listFiles().orEmpty().filter { ControlProfileRepository.isProfileFile(it.name) }
        assertTrue("built-in presets missing from $dir", files.size >= 4)
        val names = mutableSetOf<String>()
        for (file in files) {
            val profile = ControlProfile.parse(file.readText())
            assertNotNull(file.name, profile)
            profile!!
            assertTrue("${file.name} has no elements", profile.elements.isNotEmpty())
            assertEquals("${file.name} id must match its file name", "controls-${profile.id}.icp", file.name)
            assertTrue("duplicate name ${profile.name}", names.add(profile.name))
            // On a 20:9 phone every element stays on screen.
            for (element in profile.elements) {
                val box = element.boxIn(2400f, 1080f)
                assertTrue("${profile.name}: ${element.type} off screen ($box)", box.left >= 0f && box.top >= 0f && box.right <= 2400f && box.bottom <= 1080f)
            }
        }
        assertTrue(File(dir, ControlProfileRepository.DEFAULT_FILE).isFile)
    }

    @Test
    fun elementSizesFollowWinlatorsGrid() {
        val circle = ControlElement(ControlType.BUTTON, ControlShape.CIRCLE, x = 0.5f, y = 0.5f)
        val box = circle.boxIn(1000f, 500f)
        // 1 unit = width / 100 = 10 px; a circle is 6 units across.
        assertEquals(60f, box.width, 0.01f)
        assertEquals(60f, box.height, 0.01f)
        assertEquals(500f, box.centerX, 0.01f)
        assertEquals(250f, box.centerY, 0.01f)
        val rect = ControlElement(ControlType.BUTTON, ControlShape.RECT, x = 0.5f, y = 0.5f, scale = 0.5f).boxIn(1000f, 500f)
        assertEquals(40f, rect.width, 0.01f)
        assertEquals(20f, rect.height, 0.01f)
        assertEquals(140f, ControlElement(ControlType.D_PAD, x = 0.5f, y = 0.5f).boxIn(1000f, 500f).width, 0.01f)
        val strip = ControlElement(ControlType.RANGE_BUTTON, bindings = List(5) { ControlBinding.NONE }, x = 0.5f, y = 0.5f).boxIn(1000f, 500f)
        assertEquals(200f, strip.width, 0.01f)
        assertEquals(40f, strip.height, 0.01f)
    }

    @Test
    fun bindingsMapToXInput() {
        assertEquals(XKeycode.KEY_W, ControlBinding.KEY_W.xKeycode)
        assertEquals(XKeycode.KEY_PRIOR, ControlBinding.KEY_PG_UP.xKeycode)
        assertEquals(XKeycode.KEY_NEXT, ControlBinding.KEY_PG_DOWN.xKeycode)
        assertEquals(Pointer.Button.BUTTON_RIGHT, ControlBinding.MOUSE_RIGHT_BUTTON.pointerButton)
        assertNull(ControlBinding.KEY_VOL_UP.xKeycode)
        // Gamepad inputs fall back to keyboard / mouse.
        assertEquals(XKeycode.KEY_ENTER, ControlBinding.GAMEPAD_BUTTON_A.xKeycode)
        assertEquals(XKeycode.KEY_W, ControlBinding.GAMEPAD_LEFT_THUMB_UP.xKeycode)
        assertEquals(Pointer.Button.BUTTON_LEFT, ControlBinding.GAMEPAD_BUTTON_R2.pointerButton)
        assertTrue(ControlBinding.GAMEPAD_RIGHT_THUMB_LEFT.effective.isMouseMove)
        // Every Winlator binding name resolves; aliases too.
        assertEquals(ControlBinding.KEY_CTRL_L, ControlBinding.fromName("KEY_CTRL"))
        assertEquals(ControlBinding.NONE, ControlBinding.fromName("NOPE"))
        for (binding in ControlBinding.entries) {
            if (binding.effective.isKeyboard && binding != ControlBinding.KEY_VOL_UP && binding != ControlBinding.KEY_VOL_DOWN) {
                assertNotNull("$binding has no X key", binding.xKeycode)
            }
        }
    }

    @Test
    fun dpadDirectionsAndStickStrength() {
        assertArrayEquals(booleanArrayOf(true, false, false, false), ControlGeometry.directions(0f, -0.8f))
        assertArrayEquals(booleanArrayOf(true, true, false, false), ControlGeometry.directions(0.5f, -0.5f))
        assertArrayEquals(booleanArrayOf(false, false, false, false), ControlGeometry.directions(0.1f, 0.2f))
        val pushed = ControlGeometry.strengths(2f, 0f)
        assertEquals(1f, pushed[1], 0.001f)
        assertEquals(0f, pushed[3], 0.001f)
        assertEquals(0f, ControlGeometry.strengths(0.1f, 0f)[1], 0.001f)
    }

    private class Recorder : InputTarget {
        val events = mutableListOf<String>()
        override fun keyDown(key: XKeycode) { events += "down $key" }
        override fun keyUp(key: XKeycode) { events += "up $key" }
        override fun buttonDown(button: Pointer.Button) { events += "press $button" }
        override fun buttonUp(button: Pointer.Button) { events += "release $button" }
        override fun moveBy(dx: Int, dy: Int) { events += "move $dx,$dy" }
    }

    @Test
    fun sharedKeysAreReferenceCounted() {
        val recorder = Recorder()
        val input = ControlInput(recorder)
        val dpad = Any()
        val button = Any()
        input.press(ControlBinding.KEY_W, dpad)
        input.press(ControlBinding.KEY_W, button)
        input.release(ControlBinding.KEY_W, dpad)
        assertEquals(listOf("down KEY_W"), recorder.events)
        input.release(ControlBinding.KEY_W, button)
        assertEquals(listOf("down KEY_W", "up KEY_W"), recorder.events)
    }

    @Test
    fun mouseMoveBindingsMoveTheCursorPerTick() {
        val recorder = Recorder()
        val input = ControlInput(recorder)
        val owner = Any()
        input.press(ControlBinding.MOUSE_MOVE_RIGHT, owner, 0.5f)
        assertTrue(input.isMoving)
        input.tick()
        assertEquals(listOf("move 5,0"), recorder.events)
        input.release(ControlBinding.MOUSE_MOVE_RIGHT, owner)
        assertFalse(input.isMoving)
        input.press(ControlBinding.MOUSE_RIGHT_BUTTON, owner)
        input.press(ControlBinding.KEY_A, owner)
        input.releaseAll()
        assertTrue(recorder.events.containsAll(listOf("release BUTTON_RIGHT", "up KEY_A")))
    }
}
