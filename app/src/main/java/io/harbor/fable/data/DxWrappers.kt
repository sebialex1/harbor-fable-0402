package io.harbor.fable.data

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Installs the Direct3D-to-Vulkan translation layers into a container's Wine prefix, the way
 * Winlator does (`XServerDisplayActivity.extractDXWrapperFiles` + `WineUtils.applySystemTweaks`):
 *
 * - **DXVK** (Direct3D 8/9/10/11 and DXGI) and **VKD3D-Proton** (Direct3D 12) DLLs are copied
 *   into `drive_c/windows/system32` (64-bit) and `drive_c/windows/syswow64` (32-bit), replacing
 *   the copies of Wine's builtin `d3d*.dll` / `dxgi.dll` that [WinePrefix] put there.
 * - Wine is told to load them as native: Winlator writes `native,builtin` for every Direct3D DLL
 *   into `user.reg`'s DllOverrides; Fable passes the same through `WINEDLLOVERRIDES`
 *   ([dllOverrides]), so it also applies when the registry is rewritten by wineserver.
 *
 * Why this matters: without them Direct3D goes through WineD3D, which renders with desktop
 * OpenGL over GLX. There is no GLX-capable OpenGL for Fable's X server on Android, so a D3D11
 * game such as ULTRAKILL (Unity) gets no device: the Wine desktop works but the game shows a
 * black screen and exits. DXVK renders through `winevulkan` and the Vulkan driver instead.
 *
 * Package layouts understood (found by directory name, at any depth up to 3):
 * - upstream DXVK (`dxvk-3.1.1/x64`, `x32`), upstream VKD3D-Proton (`vkd3d-proton-3.0.1/x64`,
 *   `x86`);
 * - Winlator `.wcp` contents (`system32/`, `syswow64/` next to `profile.json`).
 *
 * The state is recorded in [MARKER]. Switching a layer off or to another version restores
 * Wine's own DLL for every file the previous install replaced (Winlator's
 * `restoreOriginalDllFiles`), copied from the Wine tree's `lib/wine/<arch>-windows`.
 */
internal object DxWrappers {
    private const val TAG = "DxWrappers"

    /** What was installed into the prefix: `{"version":1,"DXVK":{"package":…,"files":[…]},…}`. */
    private const val MARKER = ".fable-dxwrappers.json"
    private const val MARKER_VERSION = 1
    private const val TMP_SUFFIX = ".fable-tmp"

    /** `dxvk.conf` in the prefix root; DXVK_CONFIG_FILE points at it. */
    const val DXVK_CONF = "dxvk.conf"

    private const val SYSTEM32 = "drive_c/windows/system32"
    private const val SYSWOW64 = "drive_c/windows/syswow64"

    enum class Kind(val label: String, val dlls: Set<String>) {
        /** d3d10.dll / d3d10_1.dll only ship in DXVK < 2.0; newer builds rely on Wine's on top of d3d10core. */
        DXVK("DXVK", setOf("d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")),
        VKD3D("VKD3D-Proton", setOf("d3d12.dll", "d3d12core.dll")),
    }

    /**
     * A layer to install: [name] identifies the downloaded package (its file name), [dir] is
     * where it was unpacked.
     */
    data class Package(val kind: Kind, val name: String, val dir: File)

    data class Report(
        /** Package name per layer after this call; null = not installed (Wine's builtin is used). */
        val installed: Map<Kind, String?>,
        /** DLL file names (lower case) Wine must load as native, per layer. */
        val nativeDlls: Map<Kind, List<String>>,
        val copied: List<String> = emptyList(),
        val restored: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        /** Layers whose package had no DLLs Fable knows where to put. */
        val emptyPackages: List<String> = emptyList(),
        val upToDate: Boolean = false,
    ) {
        /** `WINEDLLOVERRIDES` value for the installed layers, or null when none is installed. */
        val dllOverrides: String? get() = dllOverrides(nativeDlls)

        fun describe(): List<String> = buildList {
            Kind.entries.forEach { kind ->
                val name = installed[kind]
                add("${kind.label}: ${name ?: "not installed (Wine's builtin ${kind.dlls.joinToString("/") { it.removeSuffix(".dll") }})"}")
                nativeDlls[kind]?.takeIf { it.isNotEmpty() }?.let { add("  native: ${it.joinToString()}") }
            }
            if (upToDate) add("prefix DLLs: up to date") else add("prefix DLLs: copied ${copied.size}, restored ${restored.size} to Wine's")
            emptyPackages.forEach { add("package has no x64/x32/system32/syswow64 DLLs: $it") }
            failed.take(10).forEach { add("failed: $it") }
            if (failed.size > 10) add("… and ${failed.size - 10} more failures")
            dllOverrides?.let { add("WINEDLLOVERRIDES=$it") }
        }
    }

    /**
     * Brings [prefix] to the state [wanted] describes: a [Package] per layer, null for "use Wine's
     * builtin", and a layer missing from the map is left exactly as it is (used when its package
     * couldn't be unpacked, so a working install isn't torn down). [wineRoot] is the Wine tree
     * whose PE DLLs are restored when a layer is removed. Never throws for I/O problems: they are
     * listed in the [Report].
     */
    suspend fun apply(prefix: File, wineRoot: File, wanted: Map<Kind, Package?>): Report {
        val previous = readMarker(prefix)
        val installed = LinkedHashMap<Kind, String?>()
        val native = LinkedHashMap<Kind, List<String>>()
        val state = JSONObject().put("version", MARKER_VERSION)
        val copied = mutableListOf<String>()
        val restored = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val empty = mutableListOf<String>()
        var changed = false

        for (kind in Kind.entries) {
            val had = previous?.optJSONObject(kind.name)
            val hadName = had?.stringOrNull("package")
            val hadFiles = had?.optJSONArray("files").toStringList()
            if (kind !in wanted) {
                installed[kind] = hadName
                if (had != null && hadName != null) {
                    native[kind] = hadFiles.map { File(it).name.lowercase() }.distinct().sorted()
                    state.put(kind.name, had)
                }
                continue
            }
            val want = wanted[kind]
            val plan = want?.let { filesToInstall(prefix, it) }.orEmpty()
            if (want != null && plan.isEmpty()) empty += want.name

            val current = want != null && plan.isNotEmpty() && hadName == want.name &&
                plan.all { (target, source) -> target.isFile && target.length() == source.length() }
            if (current || (want == null && hadName == null)) {
                // Nothing to do for this layer.
                if (current) {
                    installed[kind] = want!!.name
                    native[kind] = plan.keys.map { it.name.lowercase() }.distinct().sorted()
                    state.put(kind.name, layerJson(want.name, plan.keys.map { it.relativeTo(prefix).path }))
                } else {
                    installed[kind] = null
                }
                continue
            }
            changed = true

            // Files the previous install replaced that this one doesn't provide go back to Wine's.
            val keep = plan.keys.map { it.relativeTo(prefix).path }.toSet()
            for (relative in hadFiles) {
                if (relative in keep) continue
                currentCoroutineContext().ensureActive()
                restoreBuiltin(prefix, wineRoot, relative)
                    .onSuccess { restored += relative }
                    .onFailure { failed += "$relative: restore: ${it.message ?: it.javaClass.simpleName}" }
            }

            if (want == null || plan.isEmpty()) {
                installed[kind] = null
                continue
            }
            val written = mutableListOf<String>()
            for ((target, source) in plan) {
                currentCoroutineContext().ensureActive()
                val relative = target.relativeTo(prefix).path
                try {
                    copyReplacing(source, target)
                    copied += relative
                    written += relative
                } catch (error: IOException) {
                    failed += "$relative: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            if (written.isNotEmpty()) {
                installed[kind] = want.name
                native[kind] = written.map { File(it).name.lowercase() }.distinct().sorted()
                state.put(kind.name, layerJson(want.name, written))
            } else {
                installed[kind] = null
            }
        }

        if (changed || previous == null) {
            runCatching { writeAtomic(File(prefix, MARKER), state.toString(2)) }
                .onFailure { Log.w(TAG, "Couldn't write $MARKER", it) }
        }
        Log.i(TAG, "${prefix.name}: ${installed.entries.joinToString { "${it.key}=${it.value}" }}, copied ${copied.size}, restored ${restored.size}")
        return Report(
            installed = installed,
            nativeDlls = native,
            copied = copied,
            restored = restored,
            failed = failed,
            emptyPackages = empty,
            upToDate = !changed,
        )
    }

    /**
     * `WINEDLLOVERRIDES` for [nativeDlls]: `d3d8,d3d9,d3d10core,d3d11,dxgi=n,b;d3d12,d3d12core=n,b`.
     * `n,b` (Winlator's `native,builtin`) so a DLL that is missing for one architecture still
     * falls back to Wine's builtin instead of failing to load.
     */
    fun dllOverrides(nativeDlls: Map<Kind, List<String>>): String? =
        nativeDlls.values
            .filter { it.isNotEmpty() }
            .joinToString(";") { dlls -> dlls.joinToString(",") { it.removeSuffix(".dll") } + "=n,b" }
            .ifEmpty { null }

    /**
     * Combines Fable's overrides with the container's own `WINEDLLOVERRIDES`. Wine applies the
     * entries left to right and a later entry for the same DLL replaces an earlier one, so the
     * container's value goes last and wins.
     */
    fun mergeOverrides(ours: String?, theirs: String?): String? =
        listOfNotNull(ours?.trim()?.ifEmpty { null }, theirs?.trim()?.trim(';')?.ifEmpty { null })
            .joinToString(";")
            .ifEmpty { null }

    /**
     * Writes a commented `dxvk.conf` into the prefix root unless one exists (it is never
     * overwritten, so edits survive). Returns the file, or null when it couldn't be written.
     */
    fun ensureDxvkConf(prefix: File): File? {
        val file = File(prefix, DXVK_CONF)
        if (file.isFile) return file
        return runCatching {
            writeAtomic(file, DXVK_CONF_TEMPLATE)
            file
        }.onFailure { Log.w(TAG, "Couldn't write ${file.absolutePath}", it) }.getOrNull()
    }

    /**
     * Target file in the prefix to source file in the package, for every DLL of [pkg]'s layer.
     * 32-bit DLLs are only planned when the prefix has a `syswow64` (WoW64 Wine builds).
     */
    private fun filesToInstall(prefix: File, pkg: Package): Map<File, File> {
        val plan = LinkedHashMap<File, File>()
        val hasWow64 = File(prefix, SYSWOW64).isDirectory
        for ((names, destination) in listOf(DIRS_64 to SYSTEM32, DIRS_32 to SYSWOW64)) {
            if (destination == SYSWOW64 && !hasWow64) continue
            val sourceDir = findDir(pkg.dir, names) ?: continue
            sourceDir.listFiles()
                ?.filter { it.isFile && it.name.lowercase() in pkg.kind.dlls }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { source -> plan[File(prefix, "$destination/${source.name.lowercase()}")] = source }
        }
        return plan
    }

    /** The first directory under [root] (depth ≤ 3, breadth first) whose name is in [names]. */
    private fun findDir(root: File, names: Set<String>): File? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: continue
            children.firstOrNull { it.name.lowercase() in names }?.let { return it }
            if (depth < 3) children.forEach { queue.addLast(it to depth + 1) }
        }
        return null
    }

    /**
     * Puts Wine's own DLL back for [relative] (`drive_c/windows/system32/d3d11.dll`), or deletes
     * the file when this Wine build has no such builtin.
     */
    private fun restoreBuiltin(prefix: File, wineRoot: File, relative: String): Result<Unit> = runCatching {
        val target = File(prefix, relative)
        val arch = if (relative.startsWith(SYSWOW64)) "i386-windows" else "x86_64-windows"
        val builtin = listOf("lib/wine/$arch", "lib64/wine/$arch")
            .map { File(wineRoot, "$it/${target.name}") }
            .firstOrNull { it.isFile }
        if (builtin != null) {
            copyReplacing(builtin, target)
        } else if (target.exists() && !target.delete()) {
            throw IOException("couldn't delete it")
        }
    }

    /** Copy through a temporary file and rename, so a crash never leaves a half-written DLL. */
    private fun copyReplacing(source: File, target: File) {
        val parent = target.parentFile ?: throw IOException("no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("couldn't create ${parent.path}")
        val temp = File(parent, target.name + TMP_SUFFIX)
        try {
            source.copyTo(temp, overwrite = true)
            if (target.exists() && !target.delete()) throw IOException("couldn't replace the existing file")
            if (!temp.renameTo(target)) throw IOException("rename failed")
            target.setReadable(true, false)
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
    }

    private fun layerJson(name: String, files: List<String>): JSONObject =
        JSONObject().put("package", name).put("files", JSONArray().apply { files.forEach { put(it) } })

    private fun readMarker(prefix: File): JSONObject? =
        readTextOrNull(File(prefix, MARKER))?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    /** Directory names holding 64-bit DLLs: upstream DXVK/VKD3D-Proton, Winlator `.wcp`. */
    private val DIRS_64 = setOf("x64", "system32")

    /** Directory names holding 32-bit DLLs: DXVK uses `x32`, VKD3D-Proton `x86`, `.wcp` `syswow64`. */
    private val DIRS_32 = setOf("x32", "x86", "syswow64")

    private val DXVK_CONF_TEMPLATE = """
        |# DXVK settings for this container. Fable created this file and never overwrites it.
        |# Every option: https://github.com/doitsujin/dxvk/blob/master/dxvk.conf
        |# Lines are "option = value"; a line starting with # is ignored. Options can be limited
        |# to one game with a [ULTRAKILL.exe]-style section header.
        |
        |# Cap the frame rate (0 = no cap). Saves battery and heat on phones.
        |# dxgi.maxFrameRate = 60
        |# d3d9.maxFrameRate = 60
        |
        |# Present with vsync off (0) or let the game decide (-1).
        |# dxgi.syncInterval = -1
        |# d3d9.presentInterval = -1
        |
        |# Report a desktop GPU to games that refuse unknown vendors (0x10de = NVIDIA, 0x1002 = AMD).
        |# dxgi.customVendorId = 1002
        |# d3d9.customVendorId = 1002
        |
        |# Video memory games see, in MiB. Phones share system memory with the GPU.
        |# dxgi.maxDeviceMemory = 2048
        |
        |# On-screen overlay; the DXVK_HUD environment variable does the same.
        |# dxvk.hud = fps,devinfo
        |""".trimMargin()
}
