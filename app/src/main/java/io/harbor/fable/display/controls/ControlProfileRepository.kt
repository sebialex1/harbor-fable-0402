package io.harbor.fable.display.controls

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

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
 *   removed from the import folders.
 * - **Export** writes a preset to `Download/Fable/profiles/<name>.icp` (falls back to the app's
 *   external files folder when Downloads isn't writable).
 *
 * The chosen preset is remembered per container, with a global fallback.
 */
class ControlProfileRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where presets live: `files/profiles`, like Winlator. */
    val profilesDir: File = File(app.filesDir, "profiles")

    /** Folders scanned for profiles to import (see the class comment). */
    val importDirs: List<File>
        get() = listOfNotNull(
            app.getExternalFilesDir("profiles"),
            runCatching {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/profiles")
            }.getOrNull(),
        )

    /** All presets, built-ins first then by name. Copies built-ins and imports first. */
    fun load(): List<StoredProfile> {
        profilesDir.mkdirs()
        runCatching { installBuiltins() }.onFailure { Log.w(TAG, "Couldn't copy built-in presets", it) }
        var stored = readDir()
        runCatching { stored = importFrom(importDirs, stored) }.onFailure { Log.w(TAG, "Profile import failed", it) }
        return stored.sortedWith(compareBy<StoredProfile>({ !it.builtin }, { it.profile.name.lowercase() }))
    }

    /** The preset last chosen for [containerId] (or globally), else the first non-template one. */
    fun selected(profiles: List<StoredProfile>, containerId: String?): StoredProfile? {
        val wanted = containerId?.let { prefs.getString(KEY_SELECTED + it, null) } ?: prefs.getString(KEY_SELECTED, null)
        return profiles.firstOrNull { it.file.name == wanted }
            ?: profiles.firstOrNull { it.file.name == DEFAULT_FILE }
            ?: profiles.firstOrNull { !it.profile.isTemplate }
    }

    /** Remembers [profile] as the preset for [containerId] and the global default. */
    fun select(profile: StoredProfile, containerId: String?) {
        prefs.edit().apply {
            putString(KEY_SELECTED, profile.file.name)
            if (containerId != null) putString(KEY_SELECTED + containerId, profile.file.name)
        }.apply()
    }

    /** Copies [profile] to the export folder; returns the written file or null. */
    fun export(profile: StoredProfile): File? {
        val safeName = profile.profile.name.replace(UNSAFE_NAME, "_").ifBlank { "profile" }
        val targets = listOfNotNull(
            runCatching {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Fable/profiles")
            }.getOrNull(),
            app.getExternalFilesDir("exported-profiles"),
        )
        for (dir in targets) {
            val written = runCatching {
                dir.mkdirs()
                File(dir, "$safeName.icp").also { it.writeText(profile.profile.toJson().toString()) }
            }.getOrNull()
            if (written != null && written.isFile) return written
        }
        return null
    }

    private fun readDir(): List<StoredProfile> {
        val builtinNames = runCatching { app.assets.list(ASSET_DIR)?.toSet() }.getOrNull().orEmpty()
        return profilesDir.listFiles().orEmpty()
            .filter { it.isFile && isProfileFile(it.name) }
            .mapNotNull { file ->
                val profile = runCatching { ControlProfile.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
                StoredProfile(profile, file, builtin = file.name in builtinNames)
            }
    }

    private fun installBuiltins() {
        val names = app.assets.list(ASSET_DIR).orEmpty().filter(::isProfileFile)
        val refresh = prefs.getInt(KEY_BUILTIN_VERSION, 0) != BUILTIN_VERSION
        for (name in names) {
            val target = File(profilesDir, name)
            if (target.isFile && !refresh) continue
            app.assets.open("$ASSET_DIR/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        if (refresh) prefs.edit().putInt(KEY_BUILTIN_VERSION, BUILTIN_VERSION).apply()
    }

    private fun importFrom(dirs: List<File>, current: List<StoredProfile>): List<StoredProfile> {
        val result = current.toMutableList()
        for (dir in dirs) {
            val files = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            for (file in files) {
                if (!file.isFile || !isProfileFile(file.name) || !file.canRead()) continue
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                val parsed = ControlProfile.parse(text) ?: continue
                val existing = result.indexOfFirst { it.profile.name == parsed.name }
                if (existing >= 0) {
                    val old = result[existing]
                    val replacement = parsed.copy(id = old.profile.id)
                    if (replacement.toJson().toString() == old.profile.toJson().toString()) continue
                    old.file.writeText(replacement.toJson().toString())
                    result[existing] = StoredProfile(replacement, old.file, builtin = false)
                } else {
                    val id = (result.maxOfOrNull { it.profile.id } ?: 0).coerceAtLeast(USER_ID_BASE - 1) + 1
                    val profile = parsed.copy(id = id)
                    val target = File(profilesDir, "controls-$id.icp")
                    target.writeText(profile.toJson().toString())
                    result += StoredProfile(profile, target, builtin = false)
                }
                Log.i(TAG, "Imported control profile '${parsed.name}' from ${file.name}")
            }
        }
        return result
    }

    companion object {
        private const val TAG = "FableControls"
        private const val PREFS = "control_profiles"
        private const val KEY_SELECTED = "selected"
        private const val KEY_BUILTIN_VERSION = "builtin_version"
        private const val ASSET_DIR = "inputcontrols/profiles"

        /** Bump when the shipped presets change so installed copies are refreshed. */
        private const val BUILTIN_VERSION = 1

        /** Imported presets are numbered from here, clear of the built-ins. */
        private const val USER_ID_BASE = 100

        /** Fable's own pad, the preset used until the user picks another. */
        const val DEFAULT_FILE = "controls-1.icp"

        private val UNSAFE_NAME = Regex("[^A-Za-z0-9 ._()+-]")

        fun isProfileFile(name: String): Boolean =
            name.endsWith(".icp", ignoreCase = true) || name.endsWith(".json", ignoreCase = true)
    }
}

/** A preset and the file it was read from. */
data class StoredProfile(val profile: ControlProfile, val file: File, val builtin: Boolean)
