package io.harbor.fable.data

import java.io.File

/**
 * Reads Wine / Box64 output (the container's [WineRuntime.LAUNCH_LOG]) for the usual reasons a
 * start fails, so the UI can say "libfreetype.so is missing" instead of showing a black screen.
 *
 * Recognized lines (Box64 on bionic, Wine's loader):
 * ```
 * [BOX64] Error initializing native libfreetype.so (last dlerror is dlopen failed: library "libfreetype.so" not found)
 * Wine cannot find the FreeType font library.
 * Wine cannot find certain functions that it needs inside the FreeType font library. ... upgrade FreeType to at least version 2.1.4.
 * wine: could not load kernel32.dll, status c0000135
 * CANNOT LINK EXECUTABLE "...": cannot locate symbol "foo" referenced by "libbar.so"
 * 0024:err:wgl:X11DRV_WineGL_InitOpenglInfo couldn't initialize OpenGL, expect problems
 * err:   DxvkInstance: Failed to create Vulkan instance
 * 0024:err:vulkan:wine_vk_init Failed to load libvulkan.so.1
 * ```
 *
 * The last three are why a game can show a black screen while the Wine desktop works: WineD3D
 * (no DXVK) needs OpenGL, DXVK / VKD3D-Proton need a working Vulkan driver.
 */
internal object WineDiagnosis {
    private const val TAIL_BYTES = LaunchLog.TAIL_BYTES

    private val DLOPEN_NOT_FOUND = Regex("""library "([^"]+)" not found""")
    private val NATIVE_INIT_FAILED = Regex("""Error initializing native (\S+)""")
    private val NEEDED_LIB_FAILED = Regex("""Error loading needed lib (\S+)""")
    private val DLL_NOT_LOADED = Regex("""could not load ([\w.\-]+\.dll), status ([0-9a-fA-Fx]+)""")
    /** `err:module:import_dll Library UnityPlayer.dll (which is needed by L"C:\...\Game.exe") not found`. */
    private val IMPORT_NOT_FOUND = Regex("""Library ([\w.\-]+) \(which is needed by L?"([^"]+)"\) not found""")
    /** `err:module:loader_init Importing dlls for L"C:\...\Game.exe" failed, status c0000135`. */
    private val IMPORTS_FAILED = Regex("""Importing dlls for L?"([^"]+)" failed, status ([0-9a-fA-Fx]+)""")

    /**
     * A program crashing on its own: Wine's unhandled-exception banner (`wine: Unhandled page
     * fault on read access to …`), `err:seh` lines, access violations (`c0000005`) and breakpoint
     * exceptions (`80000003`, an int3 a program raises on a fatal CHECK when no debugger is
     * attached — CEF/Chromium games die this way) from the `+seh` trace. A game that crashes
     * before Direct3D init used to look like a clean exit.
     */
    private val CRASH = Regex(
        """(wine: Unhandled .*|Unhandled exception: .*|err:seh:.*|.*code=c0000005.*|.*EXCEPTION_ACCESS_VIOLATION.*|.*code=80000003.*|.*EXCEPTION_BREAKPOINT.*)""",
    )
    private const val MAX_CRASHES = 5
    private val SYMBOL_NOT_FOUND = Regex("""cannot locate symbol "([^"]+)" referenced by "([^"]+)"""")
    private const val FREETYPE_MISSING = "Wine cannot find the FreeType font library"
    private const val FREETYPE_TOO_OLD = "Wine cannot find certain functions that it needs inside the FreeType font library"
    private const val LSCPU_MISSING = "lscpu: inaccessible or not found"

    /**
     * Error lines from the graphics stack: WineD3D/OpenGL, winevulkan, DXVK, VKD3D-Proton.
     *
     * `winediag` is NOT a graphics channel: it also carries harmless environment notes
     * (`err:winediag:ntlm_check_version ntlm_auth was not found`, missing `libgnutls`, …), and
     * DRAPLINE exited to "graphics error: ntlm_auth was not found" — a networking warning blamed
     * for a crash that was really a missing kernel32 export. winediag lines count as graphics
     * only when they name something graphical (OpenGL, Vulkan, D3D, DXVK, MESA, a GPU).
     */
    private val GRAPHICS_ERROR = Regex(
        """(err:(wgl|d3d|wined3d|vulkan|dxgi|d3d11|d3d12|vkd3d)[:\s].*|err:\s+.*(Dxvk|DXGI|D3D11|D3D9|Vulkan|vk[A-Z]).*|err:winediag:.*(OpenGL|Vulkan|D3D|DXVK|MESA|GPU).*)""",
    )
    private const val MAX_GRAPHICS_ERRORS = 5

    /**
     * `warn:module:LdrGetProcedureAddress "IsUserCetAvailableInEnvironment" (ordinal 0) not found
     * in L"C:\windows\system32\kernel32.dll"`: the program asked for an export this Wine build
     * doesn't have. Programs often probe and carry on, so this alone isn't a failure — but
     * Chromium-based apps (CEF games like DRAPLINE) CHECK-fail on it and die on a breakpoint
     * right after, so it's recorded to explain the crash.
     */
    private val MISSING_EXPORT = Regex(
        """LdrGetProcedureAddress "([^"]+)"(?: \(ordinal \d+\))? not found in L?"[^"]*?([\w.\-]+\.dll)"""",
    )
    private const val MAX_MISSING_EXPORTS = 5

    /**
     * VKD3D-Proton's own messages: `%04x:<level>:<function>: <message>` with a C function name
     * (`0210:err:vkd3d_init_device_caps: Push descriptors are not supported …`). Wine's d3d12
     * channel (`err:d3d12:func …`) has a colon, not a space, after the channel and isn't matched.
     */
    private val VKD3D_ERROR = Regex("""[0-9a-fA-F]{4}:err:(\w*(?:vkd3d|d3d12|dxgi_vk)\w*): (.+)""")
    private const val MAX_VKD3D_ERRORS = 5
    private const val VKD3D_DXR_ENABLED = "DXR support enabled"
    private const val VKD3D_FEATURE_LEVEL_OVERRIDE = "Overriding feature level"

    /**
     * `trace:loaddll:build_module Loaded L"C:\\windows\\system32\\d3d12.dll" at 000000027A8F0000: native`
     * for the graphics API DLLs: which API the program really used and whether Wine loaded
     * DXVK / VKD3D-Proton (native) or its own (builtin).
     */
    private val GRAPHICS_DLL_LOADED = Regex(
        """:loaddll:.*Loaded L?"[^"]*?([A-Za-z0-9_\-]+\.dll)" at [0-9A-Fa-f]+: (native|builtin)""",
    )
    val GRAPHICS_DLLS = setOf(
        "d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll",
        "dxgi.dll", "ddraw.dll", "opengl32.dll", "vulkan-1.dll",
    )

    data class Diagnosis(
        /** Native libraries `dlopen` couldn't find (e.g. `libfreetype.so`). */
        val missingLibraries: List<String> = emptyList(),
        /** Native libraries Box64 found but couldn't initialize, other than [missingLibraries]. */
        val nativeInitFailures: List<String> = emptyList(),
        /** Windows DLLs Wine couldn't load, with the NTSTATUS: `kernel32.dll (c0000135)`. */
        val failedDlls: List<String> = emptyList(),
        /**
         * DLLs a program imports that Wine found nowhere (`UnityPlayer.dll (needed by
         * ULTRAKILL.exe)`): the program can't start. Typically a game whose .exe was copied
         * into the container without the files next to it.
         */
        val missingImports: List<String> = emptyList(),
        /** `symbol (referenced by lib)` the dynamic linker couldn't resolve. */
        val missingSymbols: List<String> = emptyList(),
        /** Wine printed "Wine cannot find the FreeType font library". */
        val freeTypeMissing: Boolean = false,
        /**
         * Wine loaded a FreeType that lacks functions it needs ("upgrade FreeType to at least
         * version 2.1.4"): typically Android's `libft2.so` instead of the bundled FreeType.
         */
        val freeTypeTooOld: Boolean = false,
        /** Box64 couldn't run `lscpu` (harmless; noted so it isn't mistaken for the cause). */
        val lscpuMissing: Boolean = false,
        /** The first few graphics error lines (WineD3D/OpenGL, winevulkan, DXVK, VKD3D-Proton). */
        val graphicsErrors: List<String> = emptyList(),
        /** WineD3D couldn't get OpenGL: DXVK isn't installed or isn't loaded as native. */
        val openGlUnavailable: Boolean = false,
        /** Programs whose imports failed to load, with the NTSTATUS: `ULTRAKILL.exe (c0000135)`. */
        val failedImports: List<String> = emptyList(),
        /** The first few crash lines: unhandled exceptions, `err:seh`, access violations. */
        val crashes: List<String> = emptyList(),
        /** VKD3D-Proton's error messages (`function: message`), first few. */
        val vkd3dErrors: List<String> = emptyList(),
        /**
         * Exports the program asked for that this Wine build doesn't have
         * (`IsUserCetAvailableInEnvironment (kernel32.dll)`). Not a failure by itself — most
         * programs probe and carry on — but it explains a breakpoint crash right after.
         */
        val missingExports: List<String> = emptyList(),
        /** VKD3D-Proton printed "DXR support enabled." (the device can report D3D12 ray tracing). */
        val dxrEnabled: Boolean = false,
        /** VKD3D-Proton printed "Overriding feature level" (VKD3D_FEATURE_LEVEL forced capabilities). */
        val featureLevelOverridden: Boolean = false,
        /** Graphics API DLLs Wine loaded, lower case name to "native" / "builtin", in load order. */
        val graphicsDlls: Map<String, String> = emptyMap(),
    ) {
        /** The program loaded d3d12.dll: it went down the Direct3D 12 path. */
        val usedD3d12: Boolean get() = "d3d12.dll" in graphicsDlls

        val isEmpty: Boolean
            get() = missingLibraries.isEmpty() && nativeInitFailures.isEmpty() && failedDlls.isEmpty() &&
                missingSymbols.isEmpty() && !freeTypeMissing && !freeTypeTooOld && graphicsErrors.isEmpty() &&
                missingImports.isEmpty() && failedImports.isEmpty() && crashes.isEmpty() && vkd3dErrors.isEmpty() &&
                missingExports.isEmpty()

        /** Findings that mean the program itself couldn't start, even when Wine exited with 0. */
        val programFailed: Boolean
            get() = missingImports.isNotEmpty() || failedDlls.isNotEmpty() || failedImports.isNotEmpty() || crashes.isNotEmpty()

        /** Short user-facing explanation, or null when nothing was recognized. */
        fun summary(): String? {
            val parts = buildList {
                val libs = missingLibraries.toMutableList()
                if (freeTypeMissing && libs.none { it.contains("freetype", ignoreCase = true) }) libs.add(0, "FreeType")
                if (libs.isNotEmpty()) {
                    add((if (libs.size == 1) "missing native library " else "missing native libraries ") + libs.joinToString())
                }
                if (freeTypeTooOld) add("incompatible FreeType (Wine needs a newer libfreetype.so.6 than Android's libft2.so)")
                if (nativeInitFailures.isNotEmpty()) add("couldn't initialize ${nativeInitFailures.joinToString()}")
                if (missingSymbols.isNotEmpty()) add("unresolved symbol ${missingSymbols.first()}")
                if (missingImports.isNotEmpty()) {
                    add("missing ${missingImports.joinToString()} (the game's files must be next to its .exe)")
                }
                if (failedDlls.isNotEmpty()) add("Wine couldn't load ${failedDlls.joinToString()}")
                if (missingImports.isEmpty() && failedImports.isNotEmpty()) {
                    add("couldn't load the DLLs ${failedImports.first()} imports")
                }
                if (crashes.isNotEmpty()) add("crashed: ${crashes.first().take(120)}")
                if (missingExports.isNotEmpty()) {
                    add("this Wine build lacks ${missingExports.joinToString()}, which the program asked for")
                }
                if (vkd3dErrors.isNotEmpty()) add("VKD3D-Proton (Direct3D 12) error: ${vkd3dErrors.first().take(160)}")
                if (featureLevelOverridden) add("VKD3D_FEATURE_LEVEL forced Direct3D 12 capabilities the driver may not have")
                when {
                    openGlUnavailable -> add("Direct3D fell back to WineD3D, which needs OpenGL (download DXVK in Assets)")
                    graphicsErrors.isNotEmpty() -> add("graphics error: ${graphicsErrors.first().take(120)}")
                }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        /** Every finding, one per line, for the launch log. */
        fun describe(): List<String> = buildList {
            missingLibraries.forEach { add("missing native library: $it") }
            nativeInitFailures.forEach { add("native library failed to initialize: $it") }
            missingSymbols.forEach { add("unresolved symbol: $it") }
            if (freeTypeMissing) add("Wine cannot find FreeType (libfreetype.so); see the Native libraries section")
            if (freeTypeTooOld) add("Wine loaded a FreeType without the functions it needs (Android's libft2.so?); see the Native libraries section")
            missingImports.forEach { add("program import not found: $it") }
            failedDlls.forEach { add("Wine couldn't load $it") }
            failedImports.forEach { add("imports failed to load for $it") }
            crashes.forEach { add("crash: $it") }
            missingExports.forEach { add("missing export: $it") }
            if (failedDlls.any { it.startsWith("kernel32.dll", ignoreCase = true) && it.contains("c0000135", ignoreCase = true) }) {
                // STATUS_DLL_NOT_FOUND for the first DLL a process loads: outside wineboot's
                // bootstrap Wine only loads builtins that exist in C:\windows\system32.
                add("kernel32.dll c0000135 = not found in C:\\windows\\system32; check the Prefix section (WinePrefix copies Wine's DLLs there)")
            }
            if (lscpuMissing) add("lscpu not found (harmless: Box64 falls back to /proc/cpuinfo)")
            graphicsErrors.forEach { add("graphics: $it") }
            if (openGlUnavailable) {
                add("WineD3D has no OpenGL here; games need DXVK (and VKD3D-Proton for D3D12), see the Direct3D section")
            }
            vkd3dErrors.forEach { add("VKD3D-Proton: $it") }
            if (graphicsDlls.isNotEmpty()) {
                add("graphics DLLs loaded: ${graphicsDlls.entries.joinToString { "${it.key} (${it.value})" }}")
            }
            if (usedD3d12) {
                add(
                    "Direct3D 12: d3d12.dll loaded ${graphicsDlls["d3d12.dll"]}" +
                        (if (graphicsDlls["d3d12.dll"] == "builtin") " — Wine's own d3d12, not VKD3D-Proton" else "") +
                        "; DXR ${if (dxrEnabled) "enabled by VKD3D-Proton" else "not reported by VKD3D-Proton"}",
                )
            }
            if (featureLevelOverridden) add("VKD3D-Proton overrode the feature level (VKD3D_FEATURE_LEVEL): reported caps aren't the driver's")
            if (isEmpty && !lscpuMissing) add("no known failure pattern in the process output")
        }
    }

    /**
     * Analyzes [processLog] (the whole file, streamed: with +module the tail is explorer.exe's
     * loader trace and the game's errors are further up); an absent file gives an empty
     * [Diagnosis].
     */
    fun analyze(processLog: File): Diagnosis = runCatching {
        if (!processLog.isFile) return Diagnosis()
        processLog.bufferedReader(Charsets.UTF_8).useLines { lines ->
            analyze(lines.filterNot { it.contains(":trace:module:") })
        }
    }.getOrElse { LaunchLog.tail(processLog, TAIL_BYTES)?.let(::analyze) ?: Diagnosis() }

    fun analyze(output: String): Diagnosis = analyze(output.lineSequence())

    private fun analyze(output: Sequence<String>): Diagnosis {
        val missing = LinkedHashSet<String>()
        val initFailures = LinkedHashSet<String>()
        val dlls = LinkedHashSet<String>()
        val symbols = LinkedHashSet<String>()
        val imports = LinkedHashSet<String>()
        var freeType = false
        var freeTypeOld = false
        var lscpu = false
        val graphics = LinkedHashSet<String>()
        var noOpenGl = false
        val importFailures = LinkedHashSet<String>()
        val crashes = LinkedHashSet<String>()
        val vkd3d = LinkedHashSet<String>()
        val exports = LinkedHashSet<String>()
        var dxr = false
        var flOverride = false
        val graphicsDlls = LinkedHashMap<String, String>()
        for (raw in output) {
            val line = raw.trimEnd('\r')
            if (vkd3d.size < MAX_VKD3D_ERRORS) {
                VKD3D_ERROR.find(line)?.let { vkd3d += "${it.groupValues[1]}: ${it.groupValues[2].trim()}".take(240) }
            }
            if (line.contains(VKD3D_DXR_ENABLED)) dxr = true
            if (line.contains(VKD3D_FEATURE_LEVEL_OVERRIDE)) flOverride = true
            if (line.contains(":loaddll:")) {
                GRAPHICS_DLL_LOADED.find(line)?.let {
                    val name = it.groupValues[1].lowercase()
                    if (name in GRAPHICS_DLLS && name !in graphicsDlls) graphicsDlls[name] = it.groupValues[2]
                }
            }
            if (graphics.size < MAX_GRAPHICS_ERRORS) {
                GRAPHICS_ERROR.find(line)?.let { graphics += it.value.trim().take(200) }
            }
            if (line.contains("err:") && line.contains("OpenGL", ignoreCase = true)) noOpenGl = true
            DLOPEN_NOT_FOUND.findAll(line).forEach { missing += it.groupValues[1] }
            NATIVE_INIT_FAILED.find(line)?.let { initFailures += it.groupValues[1] }
            NEEDED_LIB_FAILED.find(line)?.let { missing += it.groupValues[1].trimEnd('.', ',') }
            DLL_NOT_LOADED.find(line)?.let { dlls += "${it.groupValues[1]} (${it.groupValues[2]})" }
            IMPORT_NOT_FOUND.find(line)?.let {
                val needer = it.groupValues[2].replace("\\\\", "\\").substringAfterLast('\\')
                imports += "${it.groupValues[1]} (needed by $needer)"
            }
            SYMBOL_NOT_FOUND.find(line)?.let { symbols += "${it.groupValues[1]} (referenced by ${it.groupValues[2]})" }
            if (line.contains(FREETYPE_MISSING)) freeType = true
            if (line.contains(FREETYPE_TOO_OLD)) freeTypeOld = true
            if (line.contains(LSCPU_MISSING)) lscpu = true
            IMPORTS_FAILED.find(line)?.let {
                val program = it.groupValues[1].replace("\\\\", "\\").substringAfterLast('\\')
                importFailures += "$program (${it.groupValues[2]})"
            }
            if (crashes.size < MAX_CRASHES) CRASH.find(line)?.let { crashes += it.value.trim().take(200) }
            if (exports.size < MAX_MISSING_EXPORTS) {
                MISSING_EXPORT.find(line)?.let { exports += "${it.groupValues[1]} (${it.groupValues[2]})" }
            }
        }
        return Diagnosis(
            missingLibraries = missing.toList(),
            nativeInitFailures = (initFailures - missing).toList(),
            failedDlls = dlls.toList(),
            missingSymbols = symbols.toList(),
            missingImports = imports.toList(),
            freeTypeMissing = freeType,
            freeTypeTooOld = freeTypeOld,
            lscpuMissing = lscpu,
            graphicsErrors = graphics.toList(),
            openGlUnavailable = noOpenGl,
            failedImports = importFailures.toList(),
            crashes = crashes.toList(),
            vkd3dErrors = vkd3d.toList(),
            missingExports = exports.toList(),
            dxrEnabled = dxr,
            featureLevelOverridden = flOverride,
            graphicsDlls = graphicsDlls,
        )
    }
}
