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
 * wine: could not load kernel32.dll, status c0000135
 * CANNOT LINK EXECUTABLE "...": cannot locate symbol "foo" referenced by "libbar.so"
 * ```
 */
internal object WineDiagnosis {
    private const val TAIL_BYTES = 32 * 1024

    private val DLOPEN_NOT_FOUND = Regex("""library "([^"]+)" not found""")
    private val NATIVE_INIT_FAILED = Regex("""Error initializing native (\S+)""")
    private val NEEDED_LIB_FAILED = Regex("""Error loading needed lib (\S+)""")
    private val DLL_NOT_LOADED = Regex("""could not load ([\w.\-]+\.dll), status ([0-9a-fA-Fx]+)""")
    private val SYMBOL_NOT_FOUND = Regex("""cannot locate symbol "([^"]+)" referenced by "([^"]+)"""")
    private const val FREETYPE_MISSING = "Wine cannot find the FreeType font library"
    private const val LSCPU_MISSING = "lscpu: inaccessible or not found"

    data class Diagnosis(
        /** Native libraries `dlopen` couldn't find (e.g. `libfreetype.so`). */
        val missingLibraries: List<String> = emptyList(),
        /** Native libraries Box64 found but couldn't initialize, other than [missingLibraries]. */
        val nativeInitFailures: List<String> = emptyList(),
        /** Windows DLLs Wine couldn't load, with the NTSTATUS: `kernel32.dll (c0000135)`. */
        val failedDlls: List<String> = emptyList(),
        /** `symbol (referenced by lib)` the dynamic linker couldn't resolve. */
        val missingSymbols: List<String> = emptyList(),
        /** Wine printed "Wine cannot find the FreeType font library". */
        val freeTypeMissing: Boolean = false,
        /** Box64 couldn't run `lscpu` (harmless; noted so it isn't mistaken for the cause). */
        val lscpuMissing: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = missingLibraries.isEmpty() && nativeInitFailures.isEmpty() && failedDlls.isEmpty() &&
                missingSymbols.isEmpty() && !freeTypeMissing

        /** Short user-facing explanation, or null when nothing was recognized. */
        fun summary(): String? {
            val parts = buildList {
                val libs = missingLibraries.toMutableList()
                if (freeTypeMissing && libs.none { it.contains("freetype", ignoreCase = true) }) libs.add(0, "FreeType")
                if (libs.isNotEmpty()) {
                    add((if (libs.size == 1) "missing native library " else "missing native libraries ") + libs.joinToString())
                }
                if (nativeInitFailures.isNotEmpty()) add("couldn't initialize ${nativeInitFailures.joinToString()}")
                if (missingSymbols.isNotEmpty()) add("unresolved symbol ${missingSymbols.first()}")
                if (failedDlls.isNotEmpty()) add("Wine couldn't load ${failedDlls.joinToString()}")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        /** Every finding, one per line, for the launch log. */
        fun describe(): List<String> = buildList {
            missingLibraries.forEach { add("missing native library: $it") }
            nativeInitFailures.forEach { add("native library failed to initialize: $it") }
            missingSymbols.forEach { add("unresolved symbol: $it") }
            if (freeTypeMissing) add("Wine cannot find FreeType (libfreetype.so); see the Native libraries section")
            failedDlls.forEach { add("Wine couldn't load $it") }
            if (lscpuMissing) add("lscpu not found (harmless: Box64 falls back to /proc/cpuinfo)")
            if (isEmpty && !lscpuMissing) add("no known failure pattern in the process output")
        }
    }

    /** Analyzes the last [maxBytes] of [processLog]; an absent file gives an empty [Diagnosis]. */
    fun analyze(processLog: File, maxBytes: Int = TAIL_BYTES): Diagnosis =
        LaunchLog.tail(processLog, maxBytes)?.let(::analyze) ?: Diagnosis()

    fun analyze(output: String): Diagnosis {
        val missing = LinkedHashSet<String>()
        val initFailures = LinkedHashSet<String>()
        val dlls = LinkedHashSet<String>()
        val symbols = LinkedHashSet<String>()
        var freeType = false
        var lscpu = false
        for (line in output.lineSequence()) {
            DLOPEN_NOT_FOUND.findAll(line).forEach { missing += it.groupValues[1] }
            NATIVE_INIT_FAILED.find(line)?.let { initFailures += it.groupValues[1] }
            NEEDED_LIB_FAILED.find(line)?.let { missing += it.groupValues[1].trimEnd('.', ',') }
            DLL_NOT_LOADED.find(line)?.let { dlls += "${it.groupValues[1]} (${it.groupValues[2]})" }
            SYMBOL_NOT_FOUND.find(line)?.let { symbols += "${it.groupValues[1]} (referenced by ${it.groupValues[2]})" }
            if (line.contains(FREETYPE_MISSING)) freeType = true
            if (line.contains(LSCPU_MISSING)) lscpu = true
        }
        return Diagnosis(
            missingLibraries = missing.toList(),
            nativeInitFailures = (initFailures - missing).toList(),
            failedDlls = dlls.toList(),
            missingSymbols = symbols.toList(),
            freeTypeMissing = freeType,
            lscpuMissing = lscpu,
        )
    }
}
