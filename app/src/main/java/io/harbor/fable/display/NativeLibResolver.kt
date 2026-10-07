package io.harbor.fable.display

import android.content.Context
import android.os.Build
import android.util.Log
import io.harbor.fable.data.ElfInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Supplies the native (aarch64 bionic) libraries Box64 wraps Wine's Unix-side dependencies onto
 * when Android has them only under another file name.
 *
 * Box64 maps an x86_64 `dlopen("libfreetype.so.6")` onto the host's library by trying the
 * versioned name and then the unversioned one (`libfreetype.so`). Android ships neither: its
 * FreeType is `/system/lib64/libft2.so`, and other deps exist only in versioned form or in
 * `/vendor` / `/system_ext`. Without FreeType, `winefreetype` / `win32u` / `gdi32` fail to load,
 * Wine can't bring up `kernel32.dll` (`status c0000135`) and the display stays black.
 *
 * So, once per [VERSION] and system build, each [Spec] is looked up in [SYSTEM_LIB_DIRS] under
 * its candidate names and the first aarch64 ELF found is **copied** (not symlinked; links across
 * Android's partitions are unreliable) into [X11ClientLibs.libDir] under the unversioned name
 * Box64 asks for. That directory is already first on the Wine process's `LD_LIBRARY_PATH`.
 * A library that is already there (shipped by [X11ClientLibs]) is left alone.
 *
 * Never throws: a library that can't be found or copied is reported as missing in the [Report],
 * which the launch log records.
 */
internal object NativeLibResolver {
    private const val TAG = "NativeLibResolver"

    /** Bump when [SPECS] or the copy logic changes so existing installs re-resolve. */
    private const val VERSION = "native-libs-1"
    private const val MARKER = ".fable-native-libs"

    /** Where Android keeps 64-bit shared libraries, in search order. */
    val SYSTEM_LIB_DIRS = listOf(
        "/system/lib64",
        "/vendor/lib64",
        "/system_ext/lib64",
        "/product/lib64",
        "/odm/lib64",
    )

    /** The search directory that is already on the Wine process's `LD_LIBRARY_PATH`. */
    const val DEFAULT_SYSTEM_DIR = "/system/lib64"

    /**
     * A library Wine (through Box64) may need. [target] is the unversioned name installed into
     * the library directory; [candidates] are the file names to look for, in order; [aliases]
     * are extra names written next to [target] (the name Box64 tries first). [required] marks
     * libraries without which Wine cannot start.
     */
    data class Spec(
        val target: String,
        val candidates: List<String>,
        val purpose: String,
        val aliases: List<String> = emptyList(),
        val required: Boolean = false,
    )

    val SPECS: List<Spec> = listOf(
        // Android builds FreeType as libft2; distros (and some ROMs) as libfreetype.so.6.
        Spec(
            target = "libfreetype.so",
            candidates = listOf("libfreetype.so", "libfreetype.so.6", "libft2.so"),
            purpose = "FreeType fonts (win32u, gdi32)",
            aliases = listOf("libfreetype.so.6"),
            required = true,
        ),
        Spec("libpng.so", listOf("libpng.so", "libpng16.so", "libpng.so.16", "libpng16.so.16"), "PNG (FreeType, windowscodecs)"),
        Spec("libz.so", listOf("libz.so", "libz.so.1"), "zlib (FreeType, libpng)"),
        Spec("libXrender.so", listOf("libXrender.so", "libXrender.so.1"), "X Render (winex11)"),
        Spec("libXcursor.so", listOf("libXcursor.so", "libXcursor.so.1"), "X cursors (winex11)"),
        Spec("libXfixes.so", listOf("libXfixes.so", "libXfixes.so.3"), "X Fixes (winex11)"),
        Spec("libXi.so", listOf("libXi.so", "libXi.so.6"), "X Input (winex11)"),
        Spec("libXrandr.so", listOf("libXrandr.so", "libXrandr.so.2"), "X RandR (winex11)"),
        Spec("libXinerama.so", listOf("libXinerama.so", "libXinerama.so.1"), "Xinerama (winex11)"),
        Spec("libXcomposite.so", listOf("libXcomposite.so", "libXcomposite.so.1"), "X Composite (winex11)"),
        Spec("libgnutls.so", listOf("libgnutls.so", "libgnutls.so.30"), "TLS (secur32, bcrypt)"),
        Spec(
            target = "libSDL2.so",
            candidates = listOf("libSDL2.so", "libSDL2-2.0.so.0", "libSDL2-2.0.so"),
            purpose = "SDL2 (winebus joysticks)",
            aliases = listOf("libSDL2-2.0.so.0"),
        ),
    )

    enum class Status {
        /** Already in the library directory (shipped as an asset or installed earlier by someone else). */
        BUNDLED,

        /** Copied from [Entry.source] by this resolver. */
        RESOLVED,

        /** Not found (or not readable) in any of [SYSTEM_LIB_DIRS]. */
        MISSING,
    }

    data class Entry(val target: String, val status: Status, val source: String? = null, val required: Boolean = false)

    data class Report(val dir: File, val entries: List<Entry>, val cached: Boolean) {
        val resolved: List<Entry> get() = entries.filter { it.status == Status.RESOLVED }
        val missing: List<Entry> get() = entries.filter { it.status == Status.MISSING }
        val missingRequired: List<String> get() = missing.filter { it.required }.map { it.target }

        /**
         * System directories (other than [DEFAULT_SYSTEM_DIR]) that libraries were copied from.
         * Their own dependencies live next to them, so the launcher appends these to
         * `LD_LIBRARY_PATH`.
         */
        val extraSearchDirs: List<String>
            get() = resolved.mapNotNull { entry -> entry.source?.let { File(it).parent } }
                .filter { it != DEFAULT_SYSTEM_DIR }
                .distinct()

        /** One line per library, for the launch log. */
        fun describe(): List<String> = entries.map { entry ->
            val tag = if (entry.required) " (required)" else ""
            when (entry.status) {
                Status.BUNDLED -> "${entry.target}: bundled$tag"
                Status.RESOLVED -> "${entry.target}: copied from ${entry.source}$tag"
                Status.MISSING -> "${entry.target}: MISSING$tag"
            }
        }

        fun summary(): String = buildString {
            append("resolved ").append(resolved.size)
            if (resolved.isNotEmpty()) append(" (").append(resolved.joinToString { "${it.target}<-${it.source}" }).append(')')
            append(", missing ").append(missing.size)
            if (missing.isNotEmpty()) append(" (").append(missing.joinToString { it.target }).append(')')
            if (cached) append(", cached")
        }
    }

    /** The outcome of the last [resolveWithReport] in this process, for diagnostics. */
    @Volatile
    var lastReport: Report? = null
        private set

    /** Resolves the libraries (see [resolveWithReport]) and returns the library directory. */
    fun resolve(context: Context): File = resolveWithReport(context).dir

    /**
     * Copies (once per [VERSION] and system build fingerprint) every [SPECS] library that the
     * library directory lacks and the system has under some name. Never throws.
     */
    @Synchronized
    fun resolveWithReport(context: Context): Report {
        val dir = X11ClientLibs.libDir(context)
        val report = try {
            resolveIn(dir, stamp())
        } catch (error: Exception) {
            // Resolution is best effort: a failure here must not stop the launch.
            Log.e(TAG, "Native library resolution failed", error)
            Report(dir, SPECS.map { Entry(it.target, Status.MISSING, required = it.required) }, cached = false)
        }
        lastReport = report
        return report
    }

    /** The resolver itself, separated from [Context] / [Build] for tests. */
    internal fun resolveIn(dir: File, stamp: String, searchDirs: List<String> = SYSTEM_LIB_DIRS): Report {
        dir.mkdirs()
        val marker = File(dir, MARKER)
        val previous = readMarker(marker)
        if (previous != null && previous.first == stamp) {
            val entries = previous.second
            // A copy that vanished (storage cleanup, X11 reinstall) means resolving again.
            val intact = entries.all { it.status != Status.RESOLVED || File(dir, it.target).isFile }
            if (intact) return Report(dir, entries, cached = true)
        }
        // Stale (older resolver, or a system update replaced the libraries): drop our own copies so
        // they are taken fresh from the current system image instead of being seen as bundled.
        previous?.second?.filter { it.status == Status.RESOLVED }?.forEach { entry ->
            val spec = SPECS.firstOrNull { it.target == entry.target }
            (listOf(entry.target) + spec?.aliases.orEmpty()).forEach { File(dir, it).delete() }
        }
        marker.delete()

        val entries = SPECS.map { spec -> resolveOne(dir, spec, searchDirs) }
        writeMarker(marker, stamp, entries)
        val report = Report(dir, entries, cached = false)
        report.resolved.forEach { Log.i(TAG, "Resolved ${it.target} from ${it.source}") }
        report.missing.forEach { entry ->
            if (entry.required) Log.e(TAG, "Missing required native library ${entry.target}")
            else Log.w(TAG, "Native library ${entry.target} not found on this device (optional)")
        }
        Log.i(TAG, "Native libraries in ${dir.absolutePath}: ${report.summary()}")
        return report
    }

    private fun resolveOne(dir: File, spec: Spec, searchDirs: List<String>): Entry {
        val dest = File(dir, spec.target)
        if (dest.isFile && dest.length() > 0) {
            Log.d(TAG, "${spec.target} already present in ${dir.absolutePath}")
            return Entry(spec.target, Status.BUNDLED, dest.absolutePath, spec.required)
        }
        for (systemDir in searchDirs) {
            for (name in spec.candidates) {
                val source = File(systemDir, name)
                if (!source.isFile) continue
                if (!source.canRead()) {
                    Log.w(TAG, "${source.absolutePath} exists but isn't readable")
                    continue
                }
                if (ElfInfo.machine(source) != ElfInfo.MACHINE_AARCH64) {
                    Log.w(TAG, "${source.absolutePath} is not an aarch64 ELF, skipping")
                    continue
                }
                try {
                    copy(source, dest)
                    for (alias in spec.aliases) {
                        val aliasFile = File(dir, alias)
                        if (!aliasFile.isFile) copy(source, aliasFile)
                    }
                    return Entry(spec.target, Status.RESOLVED, source.absolutePath, spec.required)
                } catch (error: Exception) {
                    Log.w(TAG, "Couldn't copy ${source.absolutePath} to ${dest.absolutePath}", error)
                    dest.delete()
                }
            }
        }
        return Entry(spec.target, Status.MISSING, required = spec.required)
    }

    /** Copies [source] to [dest] through a temp file, then marks it readable + executable like [X11ClientLibs]. */
    private fun copy(source: File, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("Couldn't install ${dest.name}")
            }
        }
        dest.setReadable(true, false)
        dest.setExecutable(true, false)
    }

    private fun stamp(): String = "$VERSION\n${Build.FINGERPRINT}"

    private fun readMarker(marker: File): Pair<String, List<Entry>>? = runCatching {
        if (!marker.isFile) return null
        val json = JSONObject(marker.readText())
        val array = json.getJSONArray("entries")
        val entries = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Entry(
                target = item.getString("target"),
                status = Status.valueOf(item.getString("status")),
                source = item.optString("source").ifBlank { null },
                required = item.optBoolean("required", false),
            )
        }
        json.getString("stamp") to entries
    }.getOrNull()

    private fun writeMarker(marker: File, stamp: String, entries: List<Entry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("target", entry.target)
                        .put("status", entry.status.name)
                        .put("source", entry.source ?: "")
                        .put("required", entry.required),
                )
            }
            marker.writeText(JSONObject().put("stamp", stamp).put("entries", array).toString(2))
        }.onFailure { Log.w(TAG, "Couldn't write ${marker.absolutePath}", it) }
    }
}
