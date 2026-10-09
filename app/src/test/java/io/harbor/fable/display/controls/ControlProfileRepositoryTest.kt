package io.harbor.fable.display.controls

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Managing control presets: id/name allocation, selection upkeep, built-in delete policy, imports. */
class ControlProfileRepositoryTest {
    private class MapStore : ProfileStore {
        val values = mutableMapOf<String, Any>()
        override fun getString(key: String) = values[key] as? String
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String) = values[key] as? Set<String> ?: emptySet()
        override fun keys() = values.keys.toSet()
        override fun edit(block: ProfileStore.Editor.() -> Unit) {
            object : ProfileStore.Editor {
                override fun putString(key: String, value: String) { values[key] = value }
                override fun putInt(key: String, value: Int) { values[key] = value }
                override fun putStringSet(key: String, value: Set<String>) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
            }.block()
        }
    }

    private class FakeBuiltins(val docs: Map<String, String>) : BuiltinSource {
        override fun names() = docs.keys.toList()
        override fun open(name: String): InputStream = ByteArrayInputStream(docs.getValue(name).toByteArray())
    }

    private fun doc(id: Int, name: String) =
        """{"id":$id,"name":"$name","cursorSpeed":1,"elements":[{"type":"BUTTON","shape":"CIRCLE","bindings":["KEY_SPACE"],"scale":1,"x":0.5,"y":0.5}]}"""

    private lateinit var root: File
    private lateinit var importDir: File
    private lateinit var store: MapStore
    private lateinit var repo: ControlProfileRepository

    private val builtinDocs = mapOf(
        "controls-1.icp" to doc(1, "Fable Pad"),
        "controls-2.icp" to doc(2, "Minimal"),
    )

    @Before fun setUp() {
        root = Files.createTempDirectory("profiles-test").toFile()
        importDir = File(root, "import").also { it.mkdirs() }
        store = MapStore()
        repo = ControlProfileRepository(
            profilesDir = File(root, "profiles"),
            store = store,
            builtins = FakeBuiltins(builtinDocs),
            importDirsProvider = { listOf(importDir) },
            exportDirsProvider = { listOf(File(root, "export")) },
        )
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun names() = repo.load().map { it.profile.name }
    private fun ok(result: ProfileResult<StoredProfile>): StoredProfile = (result as ProfileResult.Ok).value
    private fun failure(result: ProfileResult<StoredProfile>): String = (result as ProfileResult.Failed).message

    // --- allocation -------------------------------------------------------------------------

    @Test fun nextIdSitsAboveEverythingAndBuiltIns() {
        assertEquals(100, ProfileNames.nextId(emptyList()) { false })
        assertEquals(100, ProfileNames.nextId(listOf(1, 2, 5)) { false })
        assertEquals(106, ProfileNames.nextId(listOf(1, 105)) { false })
    }

    @Test fun nextIdSkipsIdsWhoseFileExists() {
        assertEquals(102, ProfileNames.nextId(listOf(1)) { it < 102 })
    }

    @Test fun copyNamesAreNumberedAndCaseInsensitive() {
        assertEquals("Pad copy", ProfileNames.copyName("Pad", listOf("Pad")))
        assertEquals("Pad copy 2", ProfileNames.copyName("Pad", listOf("Pad", "pad COPY")))
        assertEquals("Pad copy 3", ProfileNames.copyName("Pad", listOf("Pad", "Pad copy", "Pad copy 2")))
    }

    @Test fun createGivesBlankPresetWithFreshIdAndFile() {
        repo.load()
        val a = ok(repo.create("  Mine  "))
        val b = ok(repo.create("Mine 2"))
        assertEquals("Mine", a.profile.name)
        assertTrue(a.profile.elements.isEmpty())
        assertEquals(100, a.profile.id)
        assertEquals(101, b.profile.id)
        assertEquals("controls-100.icp", a.file.name)
        assertFalse(a.builtin)
        assertTrue(names().containsAll(listOf("Mine", "Mine 2")))
    }

    @Test fun createRejectsBlankAndTakenNames() {
        repo.load()
        assertEquals("Give the preset a name", failure(repo.create("   ")))
        assertTrue(failure(repo.create("minimal")).contains("already exists"))
        assertEquals(2, repo.load().size)
    }

    @Test fun createFromTemplateCopiesElements() {
        val pad = repo.load().first { it.profile.name == "Fable Pad" }
        val copy = ok(repo.create("Pad+", pad))
        assertEquals(1, copy.profile.elements.size)
        assertEquals(100, copy.profile.id)
    }

    @Test fun duplicateMakesAnEditableCopyWithItsOwnFile() {
        val pad = repo.load().first { it.builtin && it.profile.name == "Fable Pad" }
        val copy = ok(repo.duplicate(pad))
        assertEquals("Fable Pad copy", copy.profile.name)
        assertFalse(copy.builtin)
        assertTrue(copy.file.name != pad.file.name)
        assertEquals(pad.profile.elements, copy.profile.elements)
        assertEquals("Fable Pad copy 2", ok(repo.duplicate(pad)).profile.name)
    }

    // --- rename -----------------------------------------------------------------------------

    @Test fun renameChangesJsonNameAndKeepsFileAndSelection() {
        repo.load()
        val mine = ok(repo.create("Mine"))
        repo.select(mine, "c1")
        val renamed = ok(repo.rename(mine, "Better"))
        assertEquals("Better", renamed.profile.name)
        assertEquals(mine.file, renamed.file)
        assertEquals("Better", ControlProfile.parse(mine.file.readText())!!.name)
        val loaded = repo.load()
        assertEquals("controls-100.icp", repo.selected(loaded, "c1")!!.file.name)
        assertEquals("Better", repo.selected(loaded, "c1")!!.profile.name)
        assertEquals("controls-100.icp", store.getString("selected"))
    }

    @Test fun renameRefusesBuiltinsDuplicatesAndBlank() {
        val loaded = repo.load()
        val builtin = loaded.first { it.profile.name == "Minimal" }
        assertTrue(failure(repo.rename(builtin, "X")).contains("Built-in"))
        val a = ok(repo.create("A"))
        ok(repo.create("B"))
        assertTrue(failure(repo.rename(a, "b")).contains("already exists"))
        assertEquals("Give the preset a name", failure(repo.rename(a, " ")))
        // Same name, different case-free spelling of itself, is fine.
        assertEquals("A", ok(repo.rename(a, "A")).profile.name)
    }

    // --- delete -----------------------------------------------------------------------------

    @Test fun deleteRemovesFileAndEveryStaleSelection() {
        repo.load()
        val mine = ok(repo.create("Mine"))
        val other = ok(repo.create("Other"))
        repo.select(mine, "c1")
        repo.select(mine, "c2")
        repo.select(other, "c3") // global is now Other
        repo.select(mine, null)
        assertTrue(repo.delete(mine))
        assertFalse(mine.file.exists())
        assertNull(store.getString("selected"))
        assertNull(store.getString("selectedc1"))
        assertNull(store.getString("selectedc2"))
        assertEquals("controls-101.icp", store.getString("selectedc3"))
        // Selection falls back instead of pointing nowhere.
        assertEquals(DEFAULT, repo.selected(repo.load(), "c1")!!.file.name)
    }

    @Test fun deletedBuiltinStaysGoneAcrossVersionRefresh() {
        val minimal = repo.load().first { it.profile.name == "Minimal" }
        assertTrue(repo.delete(minimal))
        assertEquals(setOf("controls-2.icp"), repo.hiddenBuiltins())
        assertFalse(names().contains("Minimal"))
        // An app update bumps the built-in version: every built-in is refreshed, except hidden ones.
        store.values.remove("builtin_version")
        assertEquals(listOf("Fable Pad"), names())
    }

    @Test fun restoreBuiltinsBringsBackOnlyMissingOnes() {
        val loaded = repo.load()
        repo.delete(loaded.first { it.profile.name == "Minimal" })
        val pad = loaded.first { it.profile.name == "Fable Pad" }
        pad.file.writeText(doc(1, "Fable Pad").replace("0.5", "0.25")) // a local tweak survives
        repo.restoreBuiltins()
        assertTrue(repo.hiddenBuiltins().isEmpty())
        val after = repo.load()
        assertEquals(setOf("Fable Pad", "Minimal"), after.map { it.profile.name }.toSet())
        assertEquals(0.25f, after.first { it.profile.name == "Fable Pad" }.profile.elements.single().x, 0.0001f)
    }

    // --- imports ----------------------------------------------------------------------------

    @Test fun importedPresetStaysDeletedUntilImportNow() {
        File(importDir, "skyrim.icp").writeText(doc(7, "Skyrim"))
        val imported = repo.load().first { it.profile.name == "Skyrim" }
        assertEquals(100, imported.profile.id)
        repo.delete(imported)
        assertFalse(names().contains("Skyrim"))
        assertTrue(repo.importNow().any { it.profile.name == "Skyrim" })
    }

    @Test fun renamingAnImportedPresetDoesNotReimportTheOriginal() {
        File(importDir, "skyrim.icp").writeText(doc(7, "Skyrim"))
        val imported = repo.load().first { it.profile.name == "Skyrim" }
        ok(repo.rename(imported, "Skyrim mine"))
        assertEquals(1, names().count { it.startsWith("Skyrim") })
    }

    @Test fun changedImportFileReplacesSameNamedPreset() {
        File(importDir, "skyrim.icp").writeText(doc(7, "Skyrim"))
        repo.load()
        File(importDir, "skyrim.icp").writeText(doc(7, "Skyrim").replace("KEY_SPACE", "KEY_A"))
        val updated = repo.load().single { it.profile.name == "Skyrim" }
        assertEquals(ControlBinding.KEY_A, updated.profile.elements.single().bindings.single())
        assertEquals(100, updated.profile.id)
    }

    private companion object {
        const val DEFAULT = "controls-1.icp"
    }
}
