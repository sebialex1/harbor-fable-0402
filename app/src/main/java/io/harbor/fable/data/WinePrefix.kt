package io.harbor.fable.data

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Completes the Wine prefix that a Winlator `.wcp` package's `prefixPack.txz` leaves behind.
 *
 * **Why `kernel32.dll` failed with `c0000135` (STATUS_DLL_NOT_FOUND).** `prefixPack.txz` is a
 * prefix made by `wineboot` with the builtin DLLs and EXEs *removed* from the top of
 * `drive_c/windows/system32` and `syswow64` (only `.nls` files and subdirectories such as
 * `drivers/` and `wbem/` remain), and its `.update-timestamp` says `disable`, so `wineboot`
 * never puts them back. Since Wine 7, outside of prefix bootstrap
 * (`WINEBOOTSTRAPMODE`, i.e. the `wineboot --init` child), the loader only loads a builtin whose
 * file exists in the system directory (`dlls/ntdll/loader.c`, `find_builtin_without_file`:
 * "if (!is_prefix_bootstrap) … return status"; `unix/loader.c`, `is_builtin_path`: "only fake
 * builtin existence during prefix bootstrap"). With no `system32\kernel32.dll` the very first
 * DLL the main process loads isn't found and Wine exits with
 * `wine: could not load kernel32.dll, status c0000135`.
 *
 * Winlator-Ludashi fills those directories in when it creates a container
 * (`ContainerManager.extractCommonDlls`): every file of `<wine>/lib/wine/x86_64-windows` is
 * copied into `system32` and every file of `i386-windows` into `syswow64`, skipping files that
 * are already there plus `tabtip.exe` and `icu.dll`. [ensure] does exactly that. The copies
 * carry Wine's "builtin DLL" marker, so Wine still prefers the matching file from
 * `lib/wine/<arch>-windows` (and falls back to the copy), the same as in a `wineboot`-made prefix.
 * Copies, not symlinks: an installer that overwrites a system DLL (DXVK, VC++ runtimes) must not
 * write through into the Wine tree.
 *
 * It also restores the prefix's drive links (Winlator's `WineUtils.createDosdevicesSymlinks`):
 * `dosdevices/z:` points at `/`, which [ArchiveExtractor] refuses to create from an archive
 * because the target lies outside the destination, and without `Z:` Wine can't reach the
 * programs Fable passes as `Z:\…` paths.
 */
internal object WinePrefix {
    private const val TAG = "WinePrefix"

    /** Records which Wine build's DLLs were copied in; rewritten after a complete copy. */
    private const val MARKER = ".fable-system-dlls"

    /** Bump when the copy rules change so existing prefixes are completed again. */
    private const val VERSION = 1

    private const val TMP_SUFFIX = ".fable-tmp"

    /**
     * Wine's PE directories (first existing candidate wins; `.wcp` packages use `lib/`, some
     * builds `lib64/`) and the prefix directories their files go to.
     */
    private val DLL_DIRS = listOf(
        listOf("lib/wine/x86_64-windows", "lib64/wine/x86_64-windows") to "drive_c/windows/system32",
        listOf("lib/wine/i386-windows", "lib64/wine/i386-windows") to "drive_c/windows/syswow64",
    )

    /** Files Winlator never copies into the prefix (`extractCommonDlls`). */
    private val SKIPPED = setOf("tabtip.exe", "icu.dll")

    /** Drive links every prefix needs: `C:` -> `drive_c`, `Z:` -> `/`. */
    private val DRIVES = listOf("c:" to "../drive_c", "z:" to "/")

    /** The file whose absence means the system directory was never filled in. */
    const val KERNEL32 = "drive_c/windows/system32/kernel32.dll"

    data class Report(
        /** Files copied into system32/syswow64 on this call. */
        val copied: Int = 0,
        /** Files that were already in place (from an earlier copy, or replaced by an installer). */
        val present: Int = 0,
        /** Bytes copied on this call. */
        val bytes: Long = 0,
        /** `dir/file: reason` for each file that couldn't be copied. */
        val failed: List<String> = emptyList(),
        /** Wine directories the package doesn't have (`lib/wine/i386-windows` is optional). */
        val missingSources: List<String> = emptyList(),
        /** Drive links created or repaired, e.g. `z: -> /`. */
        val drivesFixed: List<String> = emptyList(),
        /** Drive links that exist but couldn't be checked or replaced. */
        val drivesFailed: List<String> = emptyList(),
        /** True when the marker matched and nothing had to be copied. */
        val upToDate: Boolean = false,
        /** Whether `system32\kernel32.dll` exists afterwards; Wine can't start without it. */
        val hasKernel32: Boolean = false,
    ) {
        fun describe(): List<String> = buildList {
            if (upToDate) {
                add("system DLLs: up to date")
            } else {
                add("system DLLs: copied $copied file(s) (${bytes / (1024 * 1024)} MiB), $present already present")
            }
            missingSources.forEach { add("Wine package has no $it") }
            failed.take(MAX_LISTED).forEach { add("couldn't copy $it") }
            if (failed.size > MAX_LISTED) add("… and ${failed.size - MAX_LISTED} more copy failures")
            drivesFixed.forEach { add("drive link fixed: $it") }
            drivesFailed.forEach { add("drive link problem: $it") }
            add("system32\\kernel32.dll: ${if (hasKernel32) "present" else "MISSING"}")
        }
    }

    private const val MAX_LISTED = 10

    /** True when [ensure] would have to copy files into [prefix] for [build]. */
    fun needsSystemDlls(prefix: File, build: String): Boolean =
        !File(prefix, KERNEL32).isFile || readMarker(prefix) != markerText(build)

    /**
     * Makes [prefix] usable for the Wine tree in [wineRoot] (in Fable both are the container
     * directory): repairs the drive links and, unless [build]'s files were already copied and
     * `kernel32.dll` is still there, copies Wine's PE files into `system32` / `syswow64`.
     * Existing files are never overwritten. Never throws for I/O problems: they are listed in
     * the [Report]. Cancellation is honoured between files.
     */
    suspend fun ensure(prefix: File, wineRoot: File, build: String): Report {
        val (drivesFixed, drivesFailed) = ensureDosDevices(prefix)
        if (!needsSystemDlls(prefix, build)) {
            return Report(drivesFixed = drivesFixed, drivesFailed = drivesFailed, upToDate = true, hasKernel32 = true)
        }
        var copied = 0
        var present = 0
        var bytes = 0L
        val failed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((sources, destination) in DLL_DIRS) {
            val sourceDir = sources.map { File(wineRoot, it) }.firstOrNull { it.isDirectory }
            val files = sourceDir?.listFiles()?.filter { it.isFile }
            if (files == null) {
                missing += sources.first()
                continue
            }
            val destinationDir = File(prefix, destination)
            if (!destinationDir.isDirectory && !destinationDir.mkdirs()) {
                failed += "$destination: couldn't create the directory"
                continue
            }
            // Leftovers of a copy that was interrupted (app killed, storage full).
            destinationDir.listFiles()?.forEach { if (it.name.endsWith(TMP_SUFFIX)) it.delete() }
            for (file in files.sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                val name = file.name
                if (name.lowercase() in SKIPPED || name.endsWith(".a")) continue
                val target = File(destinationDir, name)
                if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                    present++
                    continue
                }
                val temp = File(destinationDir, name + TMP_SUFFIX)
                try {
                    file.copyTo(temp, overwrite = true)
                    if (!temp.renameTo(target)) throw IOException("rename failed")
                    target.setReadable(true, false)
                    copied++
                    bytes += file.length()
                } catch (error: IOException) {
                    temp.delete()
                    failed += "$destination/$name: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
        val hasKernel32 = File(prefix, KERNEL32).isFile
        // The x86_64 directory is mandatory; i386-windows only exists in WoW64 builds.
        if (failed.isEmpty() && hasKernel32 && DLL_DIRS.first().first.first() !in missing) {
            runCatching { File(prefix, MARKER).writeText(markerText(build)) }
                .onFailure { Log.w(TAG, "Couldn't write $MARKER", it) }
        }
        Log.i(TAG, "${prefix.name}: copied $copied, present $present, failed ${failed.size}, kernel32=$hasKernel32")
        return Report(
            copied = copied,
            present = present,
            bytes = bytes,
            failed = failed,
            missingSources = missing,
            drivesFixed = drivesFixed,
            drivesFailed = drivesFailed,
            hasKernel32 = hasKernel32,
        )
    }

    /**
     * Creates `dosdevices/c:` and `dosdevices/z:` when they are missing or point somewhere else.
     * Returns the links fixed and the problems found.
     */
    fun ensureDosDevices(prefix: File): Pair<List<String>, List<String>> {
        val fixed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val dir = File(prefix, "dosdevices")
        if (!dir.isDirectory && !dir.mkdirs()) {
            failed += "couldn't create ${dir.absolutePath}"
            return fixed to failed
        }
        for ((drive, target) in DRIVES) {
            val link = File(dir, drive).toPath()
            try {
                if (Files.isSymbolicLink(link)) {
                    if (Files.readSymbolicLink(link).toString() == target) continue
                    Files.delete(link)
                } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    failed += "$drive is a regular file or directory, not a link; left as it is"
                    continue
                }
                Files.createSymbolicLink(link, Paths.get(target))
                fixed += "$drive -> $target"
            } catch (error: Exception) {
                failed += "$drive -> $target: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        return fixed to failed
    }

    private fun markerText(build: String) = "$VERSION $build"

    private fun readMarker(prefix: File): String? =
        runCatching { File(prefix, MARKER).readText().trim() }.getOrNull()
}
