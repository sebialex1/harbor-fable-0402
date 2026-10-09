package io.harbor.fable.display.controls

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * On-screen control presets, stored the way Winlator stores them so its profiles can be copied
 * straight in: one JSON document per preset, `files/profiles/controls-<id>.icp`.
 *
 * - **Built-ins** ship in `assets/inputcontrols/profiles` and are copied into the profiles
 *   directory on first use (and again when [BUILTIN_VERSION] changes, overwriting only those
 *   files).
 * - **Import**: every `*.icp` / `*.json` profile found in [importDirs] — the app's external files
 *   folder `Android/data/<package>/files/profiles` and Winlator's export folder
 *   `Download/Winlator/profiles` — is copied in when the profiles load. A profile whose name is
 *   already present replaces that preset (Winlator's import does the same); files are not
 *   removed from the import folders. Each import file is remembered by content ([seenImports]),
 *   so a preset the user deleted or renamed is not brought back by the next scan; [importNow]
 *   forgets that and rescans, which is the explicit "bring it back" gesture.
 * - **Export** writes a preset to `Download/Fable/profiles/<name>.icp` (falls back to the app's
 *   external files folder when Downloads isn't writable).
 * - **Manage** ([create], [duplicate], [rename], [delete]): see each method. File names are
 *   always `controls-<id>.icp` — Winlator's scheme — and never change; the preset's name lives
 *   only in the JSON, so renaming can't strand a stored selection.
 *
 * The chosen preset is remembered per container, with a global fallback.
 *
 * **Built-in policy.** Built-ins can't be renamed (the next [BUILTIN_VERSION] refresh would
 * overwrite the name). They *can* be deleted: the file name goes into [hiddenBuiltins], which
 * [installBuiltins] respects, so a deleted built-in stays gone across app updates until
 * [restoreBuiltins] clears the list. To customise one, duplicate it.
 *
 * Storage is injected ([ProfileStore], [BuiltinSource], directories) so the logic runs in plain
 * JVM tests; the `Context` constructor wires the real ones.
 */
class ControlProfileRepository(
    val profilesDir: File,
    private val store: ProfileStore,
    private val builtins: BuiltinSource,
    private val importDirsProvider: () -> List<File>,
    private val exportDirsProvider: () -> List<File>,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    constructor(context: Context) : this(
        profilesDir = File(context.applicationContext.filesDir, "profiles"),
        store = SharedPreferencesProfileStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        builtins = AssetBuiltinSource(context.applicationContext.assets),
        importDirsProvider = {
            listOfNotNull(
                context.applicationContext.getExternalFilesDir("profiles"),
                downloads("Winlator/profiles"),
            )
        },
        exportDirsProvider = {
            listOfNotNull(
                downloads("Fable/profiles"),
                context.applicationContext.getExternalFilesDir("exported-profiles"),
            )
        },
        log = { message, error -> if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message) },
    )

    /** Folders scanned for profiles to import (see the class comment). */
    val importDirs: List<File> get() = importDirsProvider()

    /** All presets, built-ins first then by name. Copies built-ins and imports first. */
    @Synchronized
    fun load(): List<StoredProfile> {
        profilesDir.mkdirs()
        runCatching { installBuiltins() }.onFailure { log("Couldn't copy built-in presets", it) }
        var stored = readDir()
        runCatching { stored = importFrom(importDirs, stored) }.onFailure { log("Profile import failed", it) }
        return stored.sortedWith(compareBy<StoredProfile>({ !it.builtin }, { it.profile.name.lowercase() }))
    }

    /** The preset last chosen for [containerId] (or globally), else the first non-template one. */
    fun selected(profiles: List<StoredProfile>, containerId: String?): StoredProfile? {
        val wanted = containerId?.let { store.getString(KEY_SELECTED + it) } ?: store.getString(KEY_SELECTED)
        return profiles.firstOrNull { it.file.name == wanted }
            ?: profiles.firstOrNull { it.file.name == DEFAULT_FILE }
            ?: profiles.firstOrNull { !it.profile.isTemplate }
    }

    /** Remembers [profile] as the preset for [containerId] (when given) and the global default. */
    fun select(profile: StoredProfile, containerId: String?) {
        store.edit {
            putString(KEY_SELECTED, profile.file.name)
            if (containerId != null) putString(KEY_SELECTED + containerId, profile.file.name)
        }
    }

    /** Copies [profile] to the export folder; returns the written file or null. */
    fun export(profile: StoredProfile): File? {
        val safeName = profile.profile.name.replace(UNSAFE_NAME, "_").ifBlank { "profile" }
        for (dir in exportDirsProvider()) {
            val written = runCatching {
                dir.mkdirs()
                File(dir, "$safeName.icp").also { it.writeText(profile.profile.toJson().toString()) }
            }.getOrNull()
            if (written != null && written.isFile) return written
        }
        return null
    }

    // --- Managing presets -------------------------------------------------------------------

    /**
     * A new preset called [name]: empty, or a copy of [template]'s elements. Gets the next free
     * id (>= [USER_ID_BASE]) and file. Fails when the name is blank or already used.
     */
    @Synchronized
    fun create(name: String, template: StoredProfile? = null): ProfileResult<StoredProfile> {
        val existing = readDir()
        ProfileNames.problem(name, existing.map { it.profile.name })?.let { return ProfileResult.Failed(it) }
        val id = ProfileNames.nextId(existing.map { it.profile.id }) { File(profilesDir, fileNameFor(it)).exists() }
        val profile = (template?.profile ?: ControlProfile(id = id, name = "", elements = emptyList()))
            .copy(id = id, name = name.trim())
        return write(profile)
    }

    /** A copy of [source] named "<name> copy" (numbered if taken), as a normal, editable preset. */
    @Synchronized
    fun duplicate(source: StoredProfile): ProfileResult<StoredProfile> {
        val existing = readDir()
        val name = ProfileNames.copyName(source.profile.name, existing.map { it.profile.name })
        return create(name, source)
    }

    /**
     * Renames [profile] (the JSON `name`; the file keeps its `controls-<id>.icp` name, so
     * selections stay valid). Fails for built-ins, blank names and names already used.
     */
    @Synchronized
    fun rename(profile: StoredProfile, newName: String): ProfileResult<StoredProfile> {
        if (profile.builtin) return ProfileResult.Failed("Built-in presets can't be renamed. Duplicate it first.")
        val others = readDir().filter { it.file.name != profile.file.name }
        ProfileNames.problem(newName, others.map { it.profile.name })?.let { return ProfileResult.Failed(it) }
        if (newName.trim() == profile.profile.name) return ProfileResult.Ok(profile)
        return write(profile.profile.copy(name = newName.trim()), profile.file)
    }

    /**
     * Deletes [profile]'s file and forgets any selection (global or per-container) that pointed
     * at it, so nothing keeps naming a file that is gone. A built-in is also added to
     * [hiddenBuiltins] so the next [installBuiltins] doesn't bring it back; a user preset that
     * came from an import folder is already in [seenImports], so a rescan doesn't either.
     */
    @Synchronized
    fun delete(profile: StoredProfile): Boolean {
        val name = profile.file.name
        if (profile.file.exists() && !profile.file.delete()) return false
        store.edit {
            if (profile.builtin) putStringSet(KEY_HIDDEN_BUILTINS, hiddenBuiltins() + name)
            for (key in store.keys()) {
                if (key.startsWith(KEY_SELECTED) && store.getString(key) == name) remove(key)
            }
        }
        return true
    }

    /** File names of built-ins the user deleted; they are not reinstalled. */
    fun hiddenBuiltins(): Set<String> = store.getStringSet(KEY_HIDDEN_BUILTINS)

    /** Un-deletes every built-in: reinstalls the ones that are missing, keeps the rest as they are. */
    @Synchronized
    fun restoreBuiltins() {
        store.edit { remove(KEY_HIDDEN_BUILTINS) }
        profilesDir.mkdirs()
        runCatching { installBuiltins() }.onFailure { log("Couldn't restore built-in presets", it) }
    }

    /**
     * Rescans the import folders, bringing back files the user deleted or renamed away from
     * (those are otherwise skipped, see the class comment). Returns the presets afterwards.
     */
    @Synchronized
    fun importNow(): List<StoredProfile> {
        store.edit { remove(KEY_SEEN_IMPORTS) }
        return load()
    }

    private fun fileNameFor(id: Int) = "controls-$id.icp"

    /** Writes [profile] to [file] (default `controls-<id>.icp`) via a temp file; returns what was written. */
    private fun write(profile: ControlProfile, file: File = File(profilesDir, fileNameFor(profile.id))): ProfileResult<StoredProfile> {
        return try {
            profilesDir.mkdirs()
            val json = profile.toJson().toString()
            val tmp = File(profilesDir, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
            ProfileResult.Ok(StoredProfile(profile, file, builtin = false))
        } catch (e: java.io.IOException) {
            log("Couldn't write ${file.name}", e)
            ProfileResult.Failed("Couldn't save the preset")
        }
    }

    private fun readDir(): List<StoredProfile> {
        val builtinNames = runCatching { builtins.names().toSet() }.getOrNull().orEmpty()
        return profilesDir.listFiles().orEmpty()
            .filter { it.isFile && isProfileFile(it.name) }
            .mapNotNull { file ->
                val profile = runCatching { ControlProfile.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
                StoredProfile(profile, file, builtin = file.name in builtinNames)
            }
    }

    private fun installBuiltins() {
        val hidden = hiddenBuiltins()
        val names = builtins.names().filter(::isProfileFile).filter { it !in hidden }
        val refresh = store.getInt(KEY_BUILTIN_VERSION, 0) != BUILTIN_VERSION
        for (name in names) {
            val target = File(profilesDir, name)
            if (target.isFile && !refresh) continue
            builtins.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        if (refresh) store.edit { putInt(KEY_BUILTIN_VERSION, BUILTIN_VERSION) }
    }

    private fun importFrom(dirs: List<File>, current: List<StoredProfile>): List<StoredProfile> {
        val result = current.toMutableList()
        val seen = store.getStringSet(KEY_SEEN_IMPORTS).toMutableSet()
        val seenBefore = seen.size
        for (dir in dirs) {
            val files = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            for (file in files) {
                if (!file.isFile || !isProfileFile(file.name) || !file.canRead()) continue
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                val parsed = ControlProfile.parse(text) ?: continue
                // Already consumed once (and maybe deleted or renamed since): leave it alone.
                if (!seen.add(ProfileNames.contentKey(parsed))) continue
                val existing = result.indexOfFirst { it.profile.name == parsed.name }
                if (existing >= 0) {
                    val old = result[existing]
                    val replacement = parsed.copy(id = old.profile.id)
                    if (replacement.toJson().toString() == old.profile.toJson().toString()) continue
                    old.file.writeText(replacement.toJson().toString())
                    result[existing] = StoredProfile(replacement, old.file, builtin = false)
                } else {
                    val id = ProfileNames.nextId(result.map { it.profile.id }) { File(profilesDir, fileNameFor(it)).exists() }
                    val profile = parsed.copy(id = id)
                    val target = File(profilesDir, fileNameFor(id))
                    target.writeText(profile.toJson().toString())
                    result += StoredProfile(profile, target, builtin = false)
                }
                log("Imported control profile '${parsed.name}' from ${file.name}", null)
            }
        }
        if (seen.size != seenBefore) store.edit { putStringSet(KEY_SEEN_IMPORTS, seen) }
        return result
    }

    companion object {
        private const val TAG = "FableControls"
        private const val PREFS = "control_profiles"
        private const val KEY_SELECTED = "selected"
        private const val KEY_BUILTIN_VERSION = "builtin_version"
        private const val KEY_HIDDEN_BUILTINS = "hidden_builtins"
        private const val KEY_SEEN_IMPORTS = "seen_imports"
        internal const val ASSET_DIR = "inputcontrols/profiles"

        /** Bump when the shipped presets change so installed copies are refreshed. */
        private const val BUILTIN_VERSION = 1

        /** Imported presets are numbered from here, clear of the built-ins. */
        internal const val USER_ID_BASE = 100

        /** Fable's own pad, the preset used until the user picks another. */
        const val DEFAULT_FILE = "controls-1.icp"

        internal val UNSAFE_NAME = Regex("[^A-Za-z0-9 ._()+-]")

        private fun downloads(sub: String): File? = try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
        } catch (_: Exception) {
            null
        }

        fun isProfileFile(name: String): Boolean =
            name.endsWith(".icp", ignoreCase = true) || name.endsWith(".json", ignoreCase = true)
    }
}

/** A preset and the file it was read from. */
data class StoredProfile(val profile: ControlProfile, val file: File, val builtin: Boolean)

/** Outcome of a management call: the result, or a message fit to show the user. */
sealed interface ProfileResult<out T> {
    data class Ok<T>(val value: T) : ProfileResult<T>
    data class Failed(val message: String) : ProfileResult<Nothing>
}

/** The little bit of key-value storage the repository needs; SharedPreferences in the app, a map in tests. */
interface ProfileStore {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun getStringSet(key: String): Set<String>
    fun keys(): Set<String>
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putInt(key: String, value: Int)
        fun putStringSet(key: String, value: Set<String>)
        fun remove(key: String)
    }
}

internal class SharedPreferencesProfileStore(private val prefs: SharedPreferences) : ProfileStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getStringSet(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet().orEmpty()
    override fun keys(): Set<String> = prefs.all.keys.toSet()
    override fun edit(block: ProfileStore.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : ProfileStore.Editor {
            override fun putString(key: String, value: String) { editor.putString(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putStringSet(key: String, value: Set<String>) { editor.putStringSet(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.block()
        editor.apply()
    }
}

/** Where the shipped presets come from: the app's assets, or a fake in tests. */
interface BuiltinSource {
    fun names(): List<String>
    fun open(name: String): InputStream
}

internal class AssetBuiltinSource(private val assets: AssetManager) : BuiltinSource {
    override fun names(): List<String> = assets.list(ControlProfileRepository.ASSET_DIR).orEmpty().toList()
    override fun open(name: String): InputStream = assets.open("${ControlProfileRepository.ASSET_DIR}/$name")
}

/** Name and id decisions, pure so they can be tested without files. */
internal object ProfileNames {
    /** Why [name] can't be used next to [taken] names, or null. Names compare ignoring case. */
    fun problem(name: String, taken: Collection<String>): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Give the preset a name"
            taken.any { it.trim().equals(trimmed, ignoreCase = true) } -> "A preset called \"$trimmed\" already exists"
            else -> null
        }
    }

    /** "<base> copy", then "<base> copy 2", "<base> copy 3", … whichever is free. */
    fun copyName(base: String, taken: Collection<String>): String {
        val stem = base.trim().ifEmpty { "Preset" }
        val first = "$stem copy"
        if (problem(first, taken) == null) return first
        return generateSequence(2) { it + 1 }.map { "$first $it" }.first { problem(it, taken) == null }
    }

    /** The next user id: above every [used] id and the built-ins, skipping ids whose file exists. */
    fun nextId(used: Collection<Int>, fileExists: (Int) -> Boolean): Int {
        var id = (used.maxOrNull() ?: 0).coerceAtLeast(ControlProfileRepository.USER_ID_BASE - 1) + 1
        while (fileExists(id)) id++
        return id
    }

    /** Identity of an import file: its content, ignoring the id that gets assigned on arrival. */
    fun contentKey(profile: ControlProfile): String {
        val text = profile.copy(id = 0).toJson().toString()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
