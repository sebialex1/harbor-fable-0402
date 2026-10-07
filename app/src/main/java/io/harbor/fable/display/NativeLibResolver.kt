package io.harbor.fable.display

import android.content.Context
import android.os.Build
import android.util.Log
import io.harbor.fable.data.ElfInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Supplies the native (aarch64 bionic) libraries Box64 wraps Wine's Unix-side dependencies onto
 * that the APK doesn't bundle, when Android has them under some other file name.
 *
 * Box64 maps an x86_64 `dlopen("libfreetype.so.6")` onto the host's library by trying the
 * versioned name and then the unversioned one (`libfreetype.so`). Libraries [X11ClientLibs]
 * ships (FreeType and its deps, the X extensions, …) are already in [X11ClientLibs.libDir] and
 * always win: they are reported as [Status.BUNDLED] and never replaced or deleted here.
 *
 * FreeType is bundled-only ([Spec.systemFallback] = false). Android's `/system/lib64/libft2.so` is
 * a stripped-down build: Wine loads it, misses functions it needs ("upgrade FreeType to at least
 * version 2.1.4"), `win32u` / `gdi32` fail and `kernel32.dll` doesn't load (`status c0000135`).
 * Without a bundled FreeType the report says MISSING and names the incompatible system copy.
 * Copies of libft2.so that the previous resolver (`native-libs-1`) installed as
 * `libfreetype.so` / `libfreetype.so.6` are deleted ([purgeStaleSystemCopies]).
 *
 * Fontconfig (without it Box64 can't initialize the wrapped `libfontconfig.so` and
 * `kernel32.dll` fails with c0000135), GnuTLS and SDL2, plus their DT_NEEDED closures, are
 * bundled-only as well (`native-libs-3`): Android has none of them, so a system lookup could only
 * ever report them missing.
 *
 * The Vulkan loader is the opposite case (`native-libs-4`): Android ships it, but only as
 * `/system/lib64/libvulkan.so`. Wine's `winevulkan.so` does `dlopen("libvulkan.so.1")` — the
 * SONAME on every Linux distribution — and Box64 doesn't strip the version suffix, so without a
 * `libvulkan.so.1` on `LD_LIBRARY_PATH` DXVK can't create a Vulkan instance (Direct3D 11 apps never
 * open) and WineD3D falls back to an OpenGL that isn't there either (Direct3D 9 shows a black
 * screen). The system loader is copied in as `libvulkan.so`; `libvulkan.so.1`, the name Wine
 * actually opens, is **not** a copy of it (`native-libs-5`) but the APK's own `libvulkan_shim.so`
 * (`cpp/vulkan/vulkan_shim.c`, built by CMake and packaged as a JNI library; see [Spec.shim]).
 * Android's loader has no Xlib WSI: it doesn't export
 * `vkGetPhysicalDeviceXlibPresentationSupportKHR`, which winex11 dlsym's at Vulkan init, so with a
 * plain copy winevulkan asserts (`!status`, loader.c, status 0xc000007a) and DXVK still gets no
 * instance. The shim dlopen's the real loader, forwards the entry points Wine dlsym's and answers
 * the Xlib query itself. It also makes `VK_KHR_xlib_surface` look like an instance extension:
 * winevulkan rewrites DXVK's `VK_KHR_win32_surface` into the X11 driver's host extension and the
 * Android loader rejects it (`wine_vkCreateInstance ... res=-7`), so the shim advertises it in
 * `vkEnumerateInstanceExtensionProperties` and translates it to Android WSI in `vkCreateInstance`.
 * Its ImageReader-backed ANativeWindow lives in Wine's process; presented frames update the
 * app's X drawable over the display bridge. When the shim isn't in the APK the alias falls
 * back to the plain copy.
 *
 * For the rest, once per [VERSION], system build and bundled set, each [Spec] missing from the
 * library directory is looked up in [SYSTEM_LIB_DIRS] under its candidate names and the first
 * aarch64 ELF found is **copied** (not symlinked; links across Android's partitions are
 * unreliable) under the unversioned name Box64 asks for. That directory is already first on the
 * Wine process's `LD_LIBRARY_PATH`.
 *
 * Never throws: a library that can't be found or copied is reported as missing in the [Report],
 * which the launch log records.
 */
internal object NativeLibResolver {
    private const val TAG = "NativeLibResolver"

    /** Bump when [SPECS] or the copy logic changes so existing installs re-resolve. */
    private const val VERSION = "native-libs-6"
    private const val MARKER = ".fable-native-libs"

    /** The ABI this app builds its JNI libraries for (`ndk.abiFilters` in app/build.gradle.kts). */
    private const val JNI_ABI = "arm64-v8a"

    /** The CMake `vulkan_shim` target: the libvulkan.so.1 shim installed over the system loader. */
    const val VULKAN_SHIM = "libvulkan_shim.so"

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
     * libraries without which Wine cannot start. With [systemFallback] false the library is only
     * taken from the APK ([X11ClientLibs]); [incompatible] are system file names that exist on
     * Android but must not be used, named in the report when the library is missing.
     */
    data class Spec(
        val target: String,
        val candidates: List<String>,
        val purpose: String,
        val aliases: List<String> = emptyList(),
        val required: Boolean = false,
        val systemFallback: Boolean = true,
        val incompatible: List<String> = emptyList(),
        val shim: Shim? = null,
    ) {
        /** Every file name this library may have in the library directory. */
        val names: List<String> get() = (listOf(target) + aliases + candidates).distinct()

        init {
            require(shim == null || shim.alias in aliases) { "$target: the shim alias ${shim?.alias} must be one of the aliases" }
        }
    }

    /**
     * One of a [Spec]'s [Spec.aliases] that is written from a library packaged in the APK
     * ([library], a CMake target in `app/src/main/cpp`) instead of as a copy of the system file.
     * The library wraps the system copy installed under [Spec.target] and adds what Wine needs on
     * top of it. When the APK doesn't have it the alias falls back to a copy of the system file.
     */
    data class Shim(val alias: String, val library: String, val purpose: String)

    /**
     * Opens libraries packaged in the APK under `lib/<abi>/` (CMake targets, `jniLibs`), by file
     * name. Returns null when this build has no such library. Separated from [Context] for tests.
     */
    internal fun interface JniLibs {
        fun open(name: String): InputStream?

        companion object {
            val NONE = JniLibs { null }
        }
    }

    /** Android's FreeType build; too old / too stripped for Wine (see the class comment). */
    const val SYSTEM_FREETYPE = "libft2.so"

    val SPECS: List<Spec> = listOf(
        // Bundled (Termux FreeType 2.14.3). Never Android's libft2.so: Wine rejects it.
        Spec(
            target = "libfreetype.so",
            candidates = listOf("libfreetype.so", "libfreetype.so.6"),
            purpose = "FreeType fonts (win32u, gdi32)",
            aliases = listOf("libfreetype.so.6"),
            required = true,
            systemFallback = false,
            incompatible = listOf(SYSTEM_FREETYPE),
        ),
        // FreeType's DT_NEEDED closure (libpng16.so, libz.so.1, libbz2.so.1.0, libbrotlidec.so):
        // bundled alongside it; without any of them FreeType itself can't load.
        Spec("libpng.so", listOf("libpng.so", "libpng16.so", "libpng.so.16", "libpng16.so.16"), "PNG (FreeType, windowscodecs)", aliases = listOf("libpng16.so"), required = true),
        Spec("libz.so", listOf("libz.so", "libz.so.1"), "zlib (FreeType, libpng)", aliases = listOf("libz.so.1"), required = true),
        Spec("libbz2.so", listOf("libbz2.so", "libbz2.so.1.0", "libbz2.so.1"), "bzip2 (FreeType)", aliases = listOf("libbz2.so.1.0"), required = true),
        Spec("libbrotlidec.so", listOf("libbrotlidec.so", "libbrotlidec.so.1"), "Brotli / WOFF2 (FreeType)", required = true),
        Spec("libbrotlicommon.so", listOf("libbrotlicommon.so", "libbrotlicommon.so.1"), "Brotli (libbrotlidec)", required = true),
        // Bundled (Termux Fontconfig 2.18.3). Without it Box64 reports "Error initializing native
        // libfontconfig.so" and kernel32.dll fails to load (status c0000135).
        Spec(
            target = "libfontconfig.so",
            candidates = listOf("libfontconfig.so", "libfontconfig.so.1"),
            purpose = "Fontconfig (win32u font enumeration)",
            aliases = listOf("libfontconfig.so.1"),
            required = true,
            systemFallback = false,
        ),
        // Fontconfig's DT_NEEDED closure beyond FreeType (libexpat.so.1): bundled alongside it.
        Spec("libexpat.so", listOf("libexpat.so", "libexpat.so.1"), "Expat XML (Fontconfig)", aliases = listOf("libexpat.so.1"), required = true, systemFallback = false),
        // X extensions winex11 loads; bundled (Termux), system copies only as a fallback.
        Spec("libXrender.so", listOf("libXrender.so", "libXrender.so.1"), "X Render (winex11)"),
        Spec("libXcursor.so", listOf("libXcursor.so", "libXcursor.so.1"), "X cursors (winex11)"),
        Spec("libXfixes.so", listOf("libXfixes.so", "libXfixes.so.3"), "X Fixes (winex11)"),
        Spec("libXi.so", listOf("libXi.so", "libXi.so.6"), "X Input (winex11)"),
        Spec("libXrandr.so", listOf("libXrandr.so", "libXrandr.so.2"), "X RandR (winex11)"),
        Spec("libXinerama.so", listOf("libXinerama.so", "libXinerama.so.1"), "Xinerama (winex11)"),
        Spec("libXcomposite.so", listOf("libXcomposite.so", "libXcomposite.so.1"), "X Composite (winex11)"),
        // Bundled (Termux GnuTLS 3.8.13); Android has no GnuTLS to fall back to.
        Spec(
            target = "libgnutls.so",
            candidates = listOf("libgnutls.so", "libgnutls.so.30"),
            purpose = "TLS (secur32, bcrypt)",
            aliases = listOf("libgnutls.so.30"),
            systemFallback = false,
        ),
        // GnuTLS's DT_NEEDED closure, bundled alongside it (libz.so.1 is listed above; libandroid-support.so
        // ships with the X11 libraries).
        Spec("libnettle.so", listOf("libnettle.so", "libnettle.so.8"), "Nettle crypto (GnuTLS)", aliases = listOf("libnettle.so.8"), systemFallback = false),
        Spec("libhogweed.so", listOf("libhogweed.so", "libhogweed.so.6"), "Nettle public-key crypto (GnuTLS)", aliases = listOf("libhogweed.so.6"), systemFallback = false),
        Spec("libgmp.so", listOf("libgmp.so", "libgmp.so.10"), "GMP bignums (libhogweed)", systemFallback = false),
        Spec("libtasn1.so", listOf("libtasn1.so", "libtasn1.so.6"), "ASN.1 (GnuTLS)", systemFallback = false),
        Spec("libidn2.so", listOf("libidn2.so", "libidn2.so.0"), "IDN (GnuTLS)", systemFallback = false),
        Spec("libunistring.so", listOf("libunistring.so", "libunistring.so.5"), "Unicode strings (GnuTLS, libidn2)", systemFallback = false),
        Spec("libiconv.so", listOf("libiconv.so", "libiconv.so.2"), "iconv (libidn2, libunistring)", systemFallback = false),
        Spec("libp11-kit.so", listOf("libp11-kit.so", "libp11-kit.so.0"), "PKCS#11 (GnuTLS)", systemFallback = false),
        Spec("libffi.so", listOf("libffi.so", "libffi.so.8"), "libffi (p11-kit)", systemFallback = false),
        Spec("libzstd.so", listOf("libzstd.so", "libzstd.so.1"), "Zstandard (GnuTLS)", aliases = listOf("libzstd.so.1"), systemFallback = false),
        // Bundled (Termux x11-repo SDL 2.32.10); Android has no SDL2 to fall back to.
        Spec(
            target = "libSDL2.so",
            candidates = listOf("libSDL2.so", "libSDL2-2.0.so.0", "libSDL2-2.0.so"),
            purpose = "SDL2 (winebus joysticks)",
            aliases = listOf("libSDL2-2.0.so.0"),
            systemFallback = false,
        ),
        // SDL2's DT_NEEDED closure, bundled alongside it (libiconv.so and the X libraries are listed above).
        Spec("libXss.so", listOf("libXss.so", "libXss.so.1"), "X ScreenSaver (SDL2)", systemFallback = false),
        Spec("libwayland-client.so", listOf("libwayland-client.so", "libwayland-client.so.0"), "Wayland client (SDL2)", systemFallback = false),
        Spec("libwayland-cursor.so", listOf("libwayland-cursor.so", "libwayland-cursor.so.0"), "Wayland cursors (SDL2)", systemFallback = false),
        Spec("libwayland-egl.so", listOf("libwayland-egl.so", "libwayland-egl.so.1"), "Wayland EGL (SDL2)", systemFallback = false),
        Spec("libxkbcommon.so", listOf("libxkbcommon.so", "libxkbcommon.so.0"), "xkbcommon (SDL2)", systemFallback = false),
        Spec("libdecor-0.so", listOf("libdecor-0.so", "libdecor-0.so.0"), "libdecor (SDL2)", systemFallback = false),
        // Android's own Vulkan loader. winevulkan dlopens the Linux SONAME "libvulkan.so.1", which
        // Android never provides. That name gets the APK's libvulkan_shim.so (cpp/vulkan/vulkan_shim.c):
        // it wraps the system copy installed as libvulkan.so and supplies the Xlib WSI entry point
        // Android's loader lacks (vkGetPhysicalDeviceXlibPresentationSupportKHR), without which
        // winex11's Vulkan init fails and winevulkan asserts (see the class comment). Not required:
        // Wine starts without Vulkan, only DXVK / D3D don't.
        Spec(
            target = "libvulkan.so",
            candidates = listOf("libvulkan.so", "libvulkan.so.1"),
            purpose = "Vulkan loader (DXVK / winevulkan)",
            aliases = listOf("libvulkan.so.1"),
            systemFallback = true,
            shim = Shim(alias = "libvulkan.so.1", library = VULKAN_SHIM, purpose = "Xlib WSI shim over the system loader"),
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

    /**
     * [note] explains a [Status.MISSING] library that was deliberately not taken from the system,
     * or what was installed next to a [Status.RESOLVED] / [Status.BUNDLED] one (a [Shim] alias).
     */
    data class Entry(
        val target: String,
        val status: Status,
        val source: String? = null,
        val required: Boolean = false,
        val note: String? = null,
    )

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
            val note = entry.note?.let { " ($it)" } ?: ""
            when (entry.status) {
                Status.BUNDLED -> "${entry.target}: bundled$tag$note"
                Status.RESOLVED -> "${entry.target}: copied from ${entry.source}$tag$note"
                Status.MISSING -> "${entry.target}: MISSING$tag$note"
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
     * Copies (once per [VERSION], system build fingerprint and bundled set) every [SPECS] library
     * that neither the APK bundles nor the library directory has, when the system has it under
     * some name (never FreeType, see [Spec.systemFallback]). Never throws.
     */
    @Synchronized
    fun resolveWithReport(context: Context): Report {
        val dir = X11ClientLibs.libDir(context)
        val report = try {
            val bundled = X11ClientLibs.bundledLibraries(context)
            resolveIn(dir, stamp(context, bundled), bundled = bundled, jniLibs = ApkJniLibs(context))
        } catch (error: Exception) {
            // Resolution is best effort: a failure here must not stop the launch.
            Log.e(TAG, "Native library resolution failed", error)
            Report(dir, SPECS.map { Entry(it.target, Status.MISSING, required = it.required) }, cached = false)
        }
        lastReport = report
        return report
    }

    /**
     * The resolver itself, separated from [Context] / [Build] for tests. [bundled] are the file
     * names [X11ClientLibs] installed into [dir] from the APK; those are never deleted or replaced.
     */
    internal fun resolveIn(
        dir: File,
        stamp: String,
        searchDirs: List<String> = SYSTEM_LIB_DIRS,
        bundled: Set<String> = emptySet(),
        jniLibs: JniLibs = JniLibs.NONE,
    ): Report {
        dir.mkdirs()
        val marker = File(dir, MARKER)
        val previous = readMarker(marker)
        if (previous != null && previous.first == stamp) {
            val entries = previous.second
            // A copy (or bundled file) that vanished (storage cleanup, X11 reinstall) means resolving again.
            // For copies this resolver made, the aliases it wrote next to the target (the versioned
            // name Box64 / dlopen actually asks for, e.g. libvulkan.so.1) must be there as well;
            // bundled libraries only get aliases the APK ships, so those are judged by the target alone.
            val intact = entries.all { entry ->
                when (entry.status) {
                    Status.MISSING -> true
                    Status.BUNDLED -> File(dir, entry.target).isFile
                    Status.RESOLVED -> File(dir, entry.target).isFile &&
                        SPECS.firstOrNull { it.target == entry.target }?.aliases.orEmpty().all { File(dir, it).isFile }
                }
            }
            if (intact) return Report(dir, entries, cached = true)
        }
        purgeStaleSystemCopies(dir, previous?.second.orEmpty(), searchDirs, bundled)
        marker.delete()

        val entries = SPECS.map { spec -> resolveOne(dir, spec, searchDirs, bundled, jniLibs) }
        writeMarker(marker, stamp, entries)
        val report = Report(dir, entries, cached = false)
        report.resolved.forEach { Log.i(TAG, "Resolved ${it.target} from ${it.source}") }
        report.missing.forEach { entry ->
            val note = entry.note?.let { " ($it)" }.orEmpty()
            if (entry.required) Log.e(TAG, "Missing required native library ${entry.target}$note")
            else Log.w(TAG, "Native library ${entry.target} not found on this device (optional)$note")
        }
        Log.i(TAG, "Native libraries in ${dir.absolutePath}: ${report.summary()}")
        return report
    }

    /**
     * Deletes library copies that came from the system image rather than the APK, so this run
     * starts from what [X11ClientLibs] installed:
     * - everything the previous run recorded as [Status.RESOLVED] (older resolver, or a system
     *   update replaced the libraries), so it is taken fresh from the current system image;
     * - any FreeType name not bundled that holds a system copy: recorded as copied from
     *   [SYSTEM_FREETYPE], byte-identical to a system `libft2.so`, or a `libfreetype.so` without
     *   the `libfreetype.so.6` the bundled FreeType always ships next to it.
     * Bundled names are never touched ([X11ClientLibs.install] has just written them).
     */
    private fun purgeStaleSystemCopies(dir: File, previous: List<Entry>, searchDirs: List<String>, bundled: Set<String>) {
        fun drop(name: String, why: String) {
            if (name in bundled) return
            val file = File(dir, name)
            if (file.exists() && file.delete()) Log.i(TAG, "Removed $name ($why)")
        }
        previous.filter { it.status == Status.RESOLVED }.forEach { entry ->
            val spec = SPECS.firstOrNull { it.target == entry.target }
            (listOf(entry.target) + spec?.aliases.orEmpty()).forEach { drop(it, "stale copy of ${entry.source}") }
        }
        val systemFreeType = searchDirs.map { File(it, SYSTEM_FREETYPE) }.filter { it.isFile && it.canRead() }
        val freeType = SPECS.first { it.target == "libfreetype.so" }
        for (name in freeType.names) {
            val file = File(dir, name)
            if (name in bundled || !file.isFile) continue
            val stale = when {
                systemFreeType.any { sameContent(it, file) } -> "copy of Android's $SYSTEM_FREETYPE, incompatible with Wine"
                name == "libfreetype.so" && !File(dir, "libfreetype.so.6").isFile -> "left by an older resolver, no bundled libfreetype.so.6 next to it"
                else -> null
            }
            if (stale != null) drop(name, stale)
        }
    }

    /** Whether [a] and [b] hold the same bytes (a few MB at most; only run on a stale install). */
    private fun sameContent(a: File, b: File): Boolean = runCatching {
        a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())
    }.getOrDefault(false)

    private fun resolveOne(dir: File, spec: Spec, searchDirs: List<String>, bundled: Set<String>, jniLibs: JniLibs): Entry {
        val dest = File(dir, spec.target)
        // Shipped in the APK (X11ClientLibs): always preferred over anything on the system.
        val shipped = spec.names.firstOrNull { it in bundled && File(dir, it).let { f -> f.isFile && f.length() > 0 } }
        if (shipped != null) {
            if (!dest.isFile) {
                // Bundled only under another spelling (e.g. libpng16.so): give Box64 the target name too.
                runCatching { copy(File(dir, shipped), dest) }
                    .onFailure { Log.w(TAG, "Couldn't copy bundled $shipped to ${spec.target}", it) }
            }
            Log.d(TAG, "${spec.target} bundled ($shipped)")
            return Entry(spec.target, Status.BUNDLED, File(dir, shipped).absolutePath, spec.required)
        }
        if (dest.isFile && dest.length() > 0) {
            // Not from the APK, but not a known system copy either (purgeStaleSystemCopies ran).
            // A shim alias is still (re)written: whatever sits under the alias isn't the shim.
            Log.d(TAG, "${spec.target} already present in ${dir.absolutePath}")
            val note = spec.shim?.let { installShim(dir, spec, it, bundled, jniLibs, fallback = dest) }
            return Entry(spec.target, Status.BUNDLED, dest.absolutePath, spec.required, note)
        }
        if (!spec.systemFallback) {
            // Don't fall back to a system copy that is known not to work (Android's libft2.so).
            val unusable = searchDirs.flatMap { d -> spec.incompatible.map { File(d, it) } }.firstOrNull { it.isFile }
            val note = if (unusable != null) {
                "incompatible system version available but not used: ${unusable.absolutePath}"
            } else {
                "not bundled in this build"
            }
            Log.e(TAG, "${spec.target} is not bundled; $note")
            return Entry(spec.target, Status.MISSING, required = spec.required, note = note)
        }
        for (systemDir in searchDirs) {
            for (name in spec.candidates) {
                if (name in spec.incompatible) continue
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
                        if (alias == spec.shim?.alias) continue
                        val aliasFile = File(dir, alias)
                        if (alias !in bundled && !aliasFile.isFile) copy(source, aliasFile)
                    }
                    val note = spec.shim?.let { installShim(dir, spec, it, bundled, jniLibs, fallback = source) }
                    return Entry(spec.target, Status.RESOLVED, source.absolutePath, spec.required, note)
                } catch (error: Exception) {
                    Log.w(TAG, "Couldn't copy ${source.absolutePath} to ${dest.absolutePath}", error)
                    dest.delete()
                }
            }
        }
        return Entry(spec.target, Status.MISSING, required = spec.required)
    }

    /**
     * Writes [shim]'s library from the APK under its alias in [dir], replacing whatever was there
     * (a plain system copy from an older resolver, a previous build's shim). Without the library in
     * this build, or when it can't be written, the alias gets a copy of [fallback] (the system
     * library) so Wine at least finds the name. Returns the line for the report.
     */
    private fun installShim(dir: File, spec: Spec, shim: Shim, bundled: Set<String>, jniLibs: JniLibs, fallback: File): String {
        val aliasFile = File(dir, shim.alias)
        if (shim.alias in bundled) return "${shim.alias} bundled"
        val stream = runCatching { jniLibs.open(shim.library) }
            .onFailure { Log.w(TAG, "Couldn't open ${shim.library} from the APK", it) }
            .getOrNull()
        if (stream != null) {
            try {
                stream.use { copy(it, aliasFile) }
                Log.i(TAG, "Installed ${shim.library} as ${shim.alias} (${shim.purpose})")
                return "${shim.alias} is the APK's ${shim.library}: ${shim.purpose}"
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't install ${shim.library} as ${shim.alias}", error)
                aliasFile.delete()
            }
        } else {
            Log.w(TAG, "${shim.library} is not in this build; ${shim.alias} falls back to a copy of ${fallback.absolutePath}")
        }
        if (!aliasFile.isFile) {
            runCatching { copy(fallback, aliasFile) }
                .onFailure { Log.w(TAG, "Couldn't copy ${fallback.absolutePath} to ${aliasFile.absolutePath}", it) }
        }
        return "${shim.alias} is a plain copy, ${shim.library} unavailable: ${spec.purpose} may fail"
    }

    /** Copies [source] to [dest] through a temp file, then marks it readable + executable like [X11ClientLibs]. */
    private fun copy(source: File, dest: File) {
        source.inputStream().use { copy(it, dest) }
    }

    /** Writes [source] to [dest] through a temp file, then marks it readable + executable like [X11ClientLibs]. */
    private fun copy(source: InputStream, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.outputStream().use { source.copyTo(it) }
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

    /**
     * Re-resolve when the resolver, the system image, the bundled set or the APK itself changes
     * (a new build may carry a different [VULKAN_SHIM]; the APK's modification time changes on
     * every install).
     */
    private fun stamp(context: Context, bundled: Set<String>): String {
        val apk = runCatching { File(context.applicationInfo.sourceDir).lastModified() }.getOrDefault(0L)
        return "$VERSION\n${Build.FINGERPRINT}\n${X11ClientLibs.bundleStamp()}\n${bundled.sorted().joinToString(",")}\napk:$apk"
    }

    /**
     * The APK's JNI libraries: from `nativeLibraryDir` when the installer extracted them
     * (`extractNativeLibs="true"`), else read straight out of the APK (and its splits), where the
     * default packaging stores them uncompressed under `lib/<abi>/`.
     */
    private class ApkJniLibs(context: Context) : JniLibs {
        private val info = context.applicationInfo

        override fun open(name: String): InputStream? {
            val extracted = info.nativeLibraryDir?.let { File(it, name) }
            if (extracted != null && extracted.isFile && extracted.length() > 0) return extracted.inputStream()
            val entryName = "lib/$JNI_ABI/$name"
            val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
            for (path in apks) {
                val zip = runCatching { ZipFile(path) }.getOrNull() ?: continue
                val entry = zip.getEntry(entryName)
                if (entry == null) {
                    zip.close()
                    continue
                }
                // Closing the stream closes the archive with it.
                return object : FilterInputStream(zip.getInputStream(entry)) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            zip.close()
                        }
                    }
                }
            }
            return null
        }
    }

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
                note = item.optString("note").ifBlank { null },
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
                        .put("required", entry.required)
                        .put("note", entry.note ?: ""),
                )
            }
            marker.writeText(JSONObject().put("stamp", stamp).put("entries", array).toString(2))
        }.onFailure { Log.w(TAG, "Couldn't write ${marker.absolutePath}", it) }
    }
}
