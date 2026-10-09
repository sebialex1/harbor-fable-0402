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
     * Confirmed crash evidence: what Wine prints when an exception was NOT handled — the
     * `wine: Unhandled page fault on read access to …` / `wine: Unhandled exception 0x80000003 at
     * address …` banner, `Unhandled exception: …`, and `err:seh:…` lines (`raise_exception …
     * (unhandled)`). A `Backtrace:` header (see [BACKTRACE]) counts too.
     *
     * Deliberately NOT here: `trace:seh:dispatch_exception code=…`. The `+seh` trace writes that
     * record for EVERY exception, handled or not — .NET/Unity null checks, `IsBadReadPtr`
     * probes, debugger-detection `int3`s and C++ throws all land there while the program runs
     * on. DRAPLINE was reported as "crashed" off one such line (see [FIRST_CHANCE]); counting it
     * as a crash is a guess. A breakpoint CAN be fatal (Chromium/CEF games CHECK-fail on `int3`),
     * but then Wine also prints the banner above, or the process dies — that's the evidence.
     */
    private val CRASH = Regex("""(wine: Unhandled .*|Unhandled exception: .*|err:seh:.*)""")
    private val BACKTRACE = Regex("""^\s*Backtrace:\s*$""")

    /**
     * `0140:trace:seh:dispatch_exception code=80000003 (EXCEPTION_BREAKPOINT) flags=0 addr=0000000140E03C6C`:
     * a first-chance exception record. Context only (see [CRASH]): it says an exception was
     * raised, not that anything failed to handle it.
     */
    private val FIRST_CHANCE = Regex(
        """:trace:seh:dispatch_exception code=([0-9a-fA-F]+)(?: \(([^)]*)\))? flags=\S+ addr=([0-9a-fA-F]+)""",
    )

    /**
     * Exception codes programs raise on purpose all the time and that mean nothing here:
     * `DBG_PRINTEXCEPTION_C` (OutputDebugString), `DBG_PRINTEXCEPTION_WIDE_C`, the MSVC
     * thread-name exception, and C++ `throw` (`e06d7363`).
     */
    private val ROUTINE_EXCEPTIONS = setOf("40010006", "4001000a", "406d1388", "e06d7363")
    private const val MAX_EXCEPTIONS = 5
    const val BREAKPOINT_CODE = "80000003"
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
     * in L"C:\windows\system32\kernel32.dll"`: someone called GetProcAddress for an export this
     * Wine build doesn't have. The line doesn't say who. Most callers probe and carry on, so it's
     * context, not a failure — it is only worth blaming when a confirmed crash follows it
     * ([Diagnosis.crashAfterMissingExport]).
     */
    private val MISSING_EXPORT = Regex(
        """LdrGetProcedureAddress "([^"]+)"(?: \(ordinal \d+\))? not found in L?"[^"]*?([\w.\-]+\.dll)"""",
    )

    /**
     * Exports that Wine's own code looks up (COM's `CoFreeUnusedLibraries` asks every loaded
     * DLL, ole32.dll included, for `DllCanUnloadNow`; explorer.exe / the service host asks
     * `wevtsvc.dll` for `SvchostPushServiceGlobals`). They show up in every launch's log and are
     * never "what the program asked for". Anything else in the log may be the game's own probe.
     */
    private val WINE_INTERNAL_PROBES = setOf(
        "dllcanunloadnow", "dllgetclassobject", "dllregisterserver", "dllunregisterserver",
        "svchostpushserviceglobals", "servicemain",
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
        /**
         * Confirmed crash evidence, first few lines: Wine's unhandled-exception banner,
         * `err:seh` lines, a backtrace. Never a bare `trace:seh:dispatch_exception` record
         * (see [exceptionsObserved]).
         */
        val crashes: List<String> = emptyList(),
        /**
         * First-chance exceptions the `+seh` trace recorded (`EXCEPTION_BREAKPOINT (80000003) at
         * 0000000140E03C6C`), first few distinct ones. Context: the trace logs every exception
         * whether or not something handled it, so this is not crash evidence by itself.
         */
        val exceptionsObserved: List<String> = emptyList(),
        /** VKD3D-Proton's error messages (`function: message`), first few. */
        val vkd3dErrors: List<String> = emptyList(),
        /**
         * Exports somebody looked up that this Wine build doesn't have
         * (`IsUserCetAvailableInEnvironment (kernel32.dll)`), excluding [wineProbes]. Not a
         * failure by itself — most callers probe and carry on; a candidate cause only when a
         * confirmed crash follows ([crashAfterMissingExport]).
         */
        val missingExports: List<String> = emptyList(),
        /**
         * Missing exports that Wine's own code asked for (`DllCanUnloadNow (ole32.dll)`,
         * `SvchostPushServiceGlobals (wevtsvc.dll)`): in every launch's log, never the program's.
         */
        val wineProbes: List<String> = emptyList(),
        /** A confirmed crash line came after a [missingExports] line in the log. */
        val crashAfterMissingExport: Boolean = false,
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
                missingExports.isEmpty() && wineProbes.isEmpty() && exceptionsObserved.isEmpty()

        /** An `int3` / `EXCEPTION_BREAKPOINT` was raised (first-chance; may or may not have been fatal). */
        val breakpointObserved: Boolean get() = exceptionsObserved.any { it.contains(BREAKPOINT_CODE) }

        /**
         * Findings that mean the program itself couldn't start, even when Wine exited with 0.
         * [crashes] is confirmed evidence only; [exceptionsObserved] and [missingExports] never
         * make this true.
         */
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
                // A missing export is only a suspect when a confirmed crash follows it, and even
                // then the log can't say the crash was caused by it.
                if (crashes.isNotEmpty() && crashAfterMissingExport && missingExports.isNotEmpty()) {
                    add("before the crash the log shows a lookup of ${missingExports.joinToString()}, missing in this Wine build (possible cause, not confirmed)")
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

        /**
         * What the log observed without proving it caused anything: first-chance exceptions and
         * missing exports. Worded as observations, for messages that have no confirmed cause.
         */
        fun context(): List<String> = buildList {
            if (breakpointObserved) {
                add(
                    "Wine's trace logged a breakpoint exception (int3); Chromium-based games raise one when an " +
                        "internal check fails, but the trace also records handled ones, so it's a lead, not proof",
                )
            }
            val others = exceptionsObserved.filterNot { it.contains(BREAKPOINT_CODE) }
            if (others.isNotEmpty()) add("first-chance exceptions logged: ${others.joinToString()}")
            if (missingExports.isNotEmpty()) {
                add("a lookup of ${missingExports.joinToString()} failed (missing in this Wine build; programs often probe and carry on)")
            }
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
            exceptionsObserved.forEach { add("first-chance exception (trace records handled ones too; not a crash by itself): $it") }
            missingExports.forEach { add("missing export (lookup failed; caller not identified): $it") }
            wineProbes.forEach { add("missing export asked by Wine itself (harmless): $it") }
            if (crashes.isNotEmpty() && crashAfterMissingExport && missingExports.isNotEmpty()) {
                add("the crash came after a missing-export lookup (possible cause, not confirmed)")
            }
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
        val wineProbes = LinkedHashSet<String>()
        val exceptions = LinkedHashSet<String>()
        var crashAfterExport = false
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
            if (crashes.size < MAX_CRASHES) {
                val crash = CRASH.find(line)?.value ?: if (BACKTRACE.containsMatchIn(line)) "Backtrace:" else null
                if (crash != null) {
                    if (crashes.isEmpty() && exports.isNotEmpty()) crashAfterExport = true
                    crashes += crash.trim().take(200)
                }
            }
            if (exceptions.size < MAX_EXCEPTIONS && line.contains(":trace:seh:")) {
                FIRST_CHANCE.find(line)?.let {
                    val code = it.groupValues[1].lowercase().padStart(8, '0')
                    if (code !in ROUTINE_EXCEPTIONS) {
                        val name = it.groupValues[2].ifBlank { "exception" }
                        exceptions += "$name ($code) at ${it.groupValues[3].uppercase()}"
                    }
                }
            }
            MISSING_EXPORT.find(line)?.let {
                val export = "${it.groupValues[1]} (${it.groupValues[2]})"
                if (it.groupValues[1].lowercase() in WINE_INTERNAL_PROBES) {
                    if (wineProbes.size < MAX_MISSING_EXPORTS) wineProbes += export
                } else if (exports.size < MAX_MISSING_EXPORTS) {
                    exports += export
                }
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
            exceptionsObserved = exceptions.toList(),
            vkd3dErrors = vkd3d.toList(),
            missingExports = exports.toList(),
            wineProbes = wineProbes.toList(),
            crashAfterMissingExport = crashAfterExport,
            dxrEnabled = dxr,
            featureLevelOverridden = flOverride,
            graphicsDlls = graphicsDlls,
        )
    }
}
