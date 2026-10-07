package io.harbor.fable.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.harbor.fable.data.models.AssetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address

/** Result of looking for a usable `box64` executable. */
internal sealed interface Box64Status {
    /**
     * [rcFile] is the `box64rc` (per-program settings) shipped in the same package, used when a
     * container turns on [io.harbor.fable.data.models.Box64Settings.useRcFile]; null when the
     * package has none.
     */
    data class Ready(val executable: File, val rcFile: File? = null) : Box64Status

    /** No Box64 package has been downloaded yet. */
    data object NotDownloaded : Box64Status

    /** A package is on disk but nothing inside it is a `box64` executable. */
    data class NoExecutable(val packageName: String) : Box64Status
}

/** Result of looking for a usable `FEXInterpreter` executable. */
internal sealed interface FexStatus {
    /**
     * [executable] is `FEXInterpreter`. [rootFs] is the x86_64 guest root file system shipped in
     * the same package (FEX resolves the guest's glibc and other libraries from it), or null when
     * the package has none and FEX must find one through its own config or `FEX_ROOTFS`.
     */
    data class Ready(val executable: File, val rootFs: File? = null) : FexStatus

    /** No FEX package has been downloaded yet. */
    data object NotDownloaded : FexStatus

    /** A package is on disk but nothing inside it is a `FEXInterpreter` executable. */
    data class NoExecutable(val packageName: String) : FexStatus
}

/** A Wine tree extracted into a container directory. */
internal data class InstalledWine(val build: String, val binary: File)

/** A downloaded bionic Wine package the create-container picker can offer. */
data class WineBuild(val id: String, val label: String, val archive: File)

/**
 * A downloaded DXVK / VKD3D-Proton package a container can use: [id] is what the container
 * stores (`dxvk-3.1.1`), [label] what the picker shows (`DXVK 3.1.1`).
 */
data class ComponentBuild(val id: String, val label: String, val archive: File)

/**
 * Finds, unpacks and wires together the downloaded runtime pieces: the x86_64 translator (Box64
 * or FEX, shared, extracted once under `filesDir/runtime/box64` / `filesDir/runtime/fex`), a Wine build (extracted into each container) and the
 * active Vulkan driver from [DriverRepository].
 *
 * Everything is discovered from files on disk, so it works offline and before the catalog has
 * been refreshed.
 */
internal class WineRuntime(
    private val appContext: Context,
    private val assets: AssetRepository,
    private val drivers: DriverRepository,
    private val runtimeRoot: File,
) {
    private val box64Lock = Mutex()
    private val fexLock = Mutex()
    private val dxWrapperLock = Mutex()

    // --- Wine -----------------------------------------------------------------------------

    /**
     * Downloaded Wine packages that can actually run in Fable's runtime, newest first.
     *
     * Only Winlator-style **bionic** Wine packages (`.wcp`, a zstd/xz tar with `profile.json`,
     * `bin/`, `lib/wine/` and `prefixPack.txz`) qualify. Their `bin/wine` is an x86_64 ELF whose
     * interpreter is `/system/bin/linker64`, so a bionic Box64 can run it against Android's own
     * libc. Generic Linux builds (Kron4ek `wine-*-amd64.tar.xz`) are linked against glibc
     * (`/lib64/ld-linux-x86-64.so.2`) and cannot start without a glibc root file system, which
     * Fable does not ship, so they are never offered. ARM64EC packages need FEXCore/WOWBox64
     * DLLs that Fable does not install yet, so they are excluded too.
     */
    fun wineArchives(): List<File> = assets.downloadedFiles(AssetType.WINE)
        .filter { isBionicWinePackageName(it.name) }
        .sortedBy { CatalogPolicy.wineRank(it.name) }

    /** [wineArchives] as picker entries: id is the build name stored on the container. */
    fun wineBuilds(): List<WineBuild> = wineArchives().map { archive ->
        WineBuild(id = buildName(archive), label = displayName(archive), archive = archive)
    }

    /** The downloaded package to use: [preferred] when present, else the recommended Wine 9.20. */
    fun pickWineArchive(preferred: String?): File? {
        val archives = wineArchives()
        val key = preferred?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return archives.firstOrNull()
        return archives.firstOrNull { buildName(it).lowercase() == key }
            ?: archives.firstOrNull { it.name.lowercase().startsWith(key) }
            ?: archives.firstOrNull()
    }

    /** The Wine tree already extracted into [containerDir], or null when it is missing or incomplete. */
    fun installedWine(containerDir: File): InstalledWine? {
        val marker = readTextOrNull(File(containerDir, WINE_MARKER)) ?: return null
        val build = runCatching { JSONObject(marker).optString("build") }.getOrNull()?.ifBlank { null } ?: return null
        if (build.startsWith("proton", ignoreCase = true)) return null
        val binary = wineBinary(containerDir) ?: return null
        // A glibc tree left by an older Fable (Kron4ek builds) can never start: treat it as absent
        // so the container is re-provisioned from a bionic package.
        if (ElfInfo.interpreter(binary)?.contains("ld-linux") == true) return null
        return InstalledWine(build, binary)
    }

    fun wineBinary(containerDir: File): File? =
        WINE_BINARIES.map { File(containerDir, it) }.firstOrNull { it.isFile }

    /** True when [installWine] set up a fresh prefix that hasn't been through `wineboot -u` yet. */
    fun winebootPending(containerDir: File): Boolean = File(containerDir, WINEBOOT_PENDING).isFile

    /** Records that `wineboot -u` ran for [containerDir], so it isn't repeated on every launch. */
    fun markWinebootDone(containerDir: File) {
        File(containerDir, WINEBOOT_PENDING).delete()
    }

    /**
     * Extracts the bionic Wine package [archive] into [containerDir], replacing any previous Wine
     * tree, then unpacks its `prefixPack.txz` (a ready-made `.wine` prefix) into the same
     * directory, which is the container's WINEPREFIX, and copies Wine's DLLs into the prefix's
     * `system32` / `syswow64` the way Winlator does ([WinePrefix]; the prefix pack ships those
     * directories empty). The marker file is written last, so an interrupted extraction is
     * redone on the next launch.
     *
     * Throws [IOException] with a user-facing message when the package is not a bionic Wine
     * build (e.g. a glibc build imported by hand).
     */
    suspend fun installWine(archive: File, containerDir: File): InstalledWine = withContext(Dispatchers.IO) {
        // A prefix without system.reg has never been set up; remember that before anything is
        // extracted so wineboot -u can be scheduled for it below.
        val freshPrefix = !File(containerDir, "system.reg").isFile
        File(containerDir, WINE_MARKER).delete()
        val profile = readWcpProfile(archive)
            ?: throw IOException("${archive.name} is not a Winlator Wine package (no profile.json)")
        val type = profile.optString("type")
        if (!type.equals("Wine", ignoreCase = true) && !type.equals("Proton", ignoreCase = true)) {
            throw IOException("${archive.name} is a $type package, not Wine")
        }
        ArchiveExtractor.extract(
            archive = archive,
            destDir = containerDir,
            stripComponents = 0,
            skip = ::isDevelopmentFile,
            context = coroutineContext,
        )
        val binary = wineBinary(containerDir)
            ?: throw IOException("${archive.name} has no bin/wine")
        val interpreter = ElfInfo.interpreter(binary)
        if (interpreter != null && interpreter.contains("ld-linux")) {
            throw IOException("${archive.name} is a glibc build ($interpreter); Fable needs a bionic Wine")
        }
        binary.setExecutable(true, false)
        File(containerDir, "bin").listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        val prefixPack = profile.optJSONObject("wine")?.optString("prefixPack")?.ifBlank { null } ?: "prefixPack.txz"
        val prefixArchive = File(containerDir, prefixPack)
        if (prefixArchive.isFile && !File(containerDir, "system.reg").isFile) {
            // prefixPack.txz holds `.wine/…`; strip it so drive_c and the registry land in the prefix.
            ArchiveExtractor.extract(prefixArchive, containerDir, stripComponents = 1, context = coroutineContext)
        }
        val build = buildName(archive)
        // prefixPack.txz ships system32/syswow64 without Wine's DLLs; without them the main
        // process stops with "could not load kernel32.dll, status c0000135" (see WinePrefix).
        // Existing files are kept (an older build's copies still work: Wine prefers the builtin
        // in lib/wine), new ones are added.
        val prefix = WinePrefix.ensure(prefix = containerDir, wineRoot = containerDir, build = build)
        if (!prefix.hasKernel32) {
            val reason = prefix.failed.firstOrNull() ?: prefix.missingSources.firstOrNull()?.let { "the package has no $it" }
            throw IOException("Couldn't copy Wine's DLLs into the prefix" + (reason?.let { ": $it" } ?: ""))
        }
        if (freshPrefix) {
            // Remembered for ContainerRepository's optional `wineboot -u` (container variable
            // FABLE_WINEBOOT=1). Winlator-Ludashi never runs wineboot -u: prefixPack.txz plus
            // the DLL copy above is a complete prefix, and Wine's automatic `wineboot --init`
            // starts the prefix's services on every start.
            runCatching { File(containerDir, WINEBOOT_PENDING).writeText(buildName(archive)) }
                .onFailure { Log.w(TAG, "Could not mark ${containerDir.name} for wineboot", it) }
        }
        writeAtomic(
            File(containerDir, WINE_MARKER),
            JSONObject()
                .put("build", build)
                .put("archive", archive.name)
                .put("size", archive.length())
                .put("profileVersion", profile.optString("versionName"))
                .put("interpreter", interpreter ?: "")
                .toString(),
        )
        InstalledWine(build, binary)
    }

    /** `profile.json` of a Winlator package, or null when [archive] has none. */
    fun readWcpProfile(archive: File): JSONObject? = runCatching {
        ArchiveExtractor.readTarEntry(archive, "profile.json")?.let { JSONObject(String(it, Charsets.UTF_8)) }
    }.getOrNull()

    // --- Box64 ----------------------------------------------------------------------------

    fun hasBox64Download(): Boolean = assets.downloadedFiles(AssetType.BOX64).isNotEmpty()

    /**
     * Returns the `box64` executable from the newest downloaded Box64 package, unpacking it into
     * app storage the first time. A package may be an archive that contains `box64` anywhere
     * inside, or the bare executable.
     */
    suspend fun ensureBox64(): Box64Status = box64Lock.withLock {
        withContext(Dispatchers.IO) {
            val packages = assets.downloadedFiles(AssetType.BOX64)
            if (packages.isEmpty()) return@withContext Box64Status.NotDownloaded
            for (pkg in packages) {
                val dir = File(runtimeRoot, "box64/${sanitizeFileName(pkg.name)}")
                val done = File(dir, COMPLETE_MARKER)
                if (!done.isFile) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    try {
                        if (ArchiveExtractor.detectFormat(pkg) == ArchiveFormat.ELF) {
                            pkg.copyTo(File(dir, "box64"), overwrite = true)
                        } else {
                            ArchiveExtractor.extract(pkg, dir, context = coroutineContext)
                        }
                        done.writeText(pkg.name)
                    } catch (error: CancellationException) {
                        dir.deleteRecursively()
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not unpack ${pkg.name}", error)
                        dir.deleteRecursively()
                        continue
                    }
                }
                val executable = ArchiveExtractor.findFile(dir, "box64")
                if (executable != null) {
                    executable.setReadable(true, false)
                    executable.setExecutable(true, false)
                    val rcFile = BOX64_RC_NAMES.firstNotNullOfOrNull { ArchiveExtractor.findFile(dir, it) }
                    return@withContext Box64Status.Ready(executable, rcFile)
                }
            }
            Box64Status.NoExecutable(packages.first().name)
        }
    }

    // --- FEX (optional alternative to Box64) -----------------------------------------------

    fun hasFexDownload(): Boolean = assets.downloadedFiles(AssetType.FEX).isNotEmpty()

    /**
     * Returns the `FEXInterpreter` executable from the newest downloaded FEX package, unpacking
     * it into app storage the first time. Mirrors [ensureBox64].
     */
    suspend fun ensureFex(): FexStatus = fexLock.withLock {
        withContext(Dispatchers.IO) {
            val packages = assets.downloadedFiles(AssetType.FEX)
            if (packages.isEmpty()) return@withContext FexStatus.NotDownloaded
            for (pkg in packages) {
                val dir = File(runtimeRoot, "fex/${sanitizeFileName(pkg.name)}")
                val done = File(dir, COMPLETE_MARKER)
                if (!done.isFile) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    try {
                        if (ArchiveExtractor.detectFormat(pkg) == ArchiveFormat.ELF) {
                            pkg.copyTo(File(dir, "FEXInterpreter"), overwrite = true)
                        } else {
                            ArchiveExtractor.extract(pkg, dir, context = coroutineContext)
                        }
                        done.writeText(pkg.name)
                    } catch (error: CancellationException) {
                        dir.deleteRecursively()
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not unpack ${pkg.name}", error)
                        dir.deleteRecursively()
                        continue
                    }
                }
                val executable = ArchiveExtractor.findFile(dir, "FEXInterpreter")
                if (executable != null) {
                    executable.setReadable(true, false)
                    executable.setExecutable(true, false)
                    // FEXInterpreter launches FEXServer from its own directory on demand.
                    executable.parentFile?.listFiles()?.forEach { sibling ->
                        if (sibling.isFile && sibling.name.startsWith("FEX")) {
                            sibling.setReadable(true, false)
                            sibling.setExecutable(true, false)
                        }
                    }
                    return@withContext FexStatus.Ready(executable, findFexRootFs(dir))
                }
            }
            FexStatus.NoExecutable(packages.first().name)
        }
    }

    /**
     * An x86_64 root file system inside an unpacked FEX package: a directory holding the x86_64
     * dynamic loader (`lib64/ld-linux-x86-64.so.2`) or a multiarch `usr/lib/x86_64-linux-gnu`.
     */
    private fun findFexRootFs(packageDir: File): File? = packageDir.walkTopDown()
        .maxDepth(FEX_ROOTFS_SEARCH_DEPTH)
        .filter { it.isDirectory }
        .firstOrNull { candidate ->
            File(candidate, "lib64/ld-linux-x86-64.so.2").exists() ||
                File(candidate, "usr/lib/x86_64-linux-gnu").isDirectory
        }

    // --- DXVK / VKD3D-Proton ----------------------------------------------------------------

    /**
     * Downloaded DXVK packages for Windows, newest first: the release tarballs
     * (`dxvk-3.1.1.tar.gz`) and Winlator `.wcp` DXVK packages. `dxvk-native-*` (Linux) and
     * ARM64EC builds can't be loaded by an x86_64 Wine and are left out.
     */
    fun dxvkArchives(): List<File> = assets.downloadedFiles(AssetType.DXVK)
        .filter { isDxvkPackageName(it.name) }

    /**
     * Downloaded VKD3D-Proton packages, newest first: the [AssetType.VKD3D] catalog downloads
     * (`vkd3d-proton-3.0.1.tar.zst`), then any VKD3D package found by name among the DXVK and
     * "other" downloads (Winlator `Vkd3d-*.wcp` from a user-added source, or files fetched before
     * VKD3D had its own type).
     */
    fun vkd3dArchives(): List<File> {
        val catalog = assets.downloadedFiles(AssetType.VKD3D).filter { isVkd3dPackageName(it.name) }
        val byName = (assets.downloadedFiles(AssetType.DXVK) + assets.downloadedFiles(AssetType.OTHER))
            .filter { isVkd3dPackageName(it.name) }
        return (catalog + byName).distinctBy { it.absolutePath }
    }

    fun dxvkBuilds(): List<ComponentBuild> = dxvkArchives().map { componentBuild(it, "DXVK") }

    fun vkd3dBuilds(): List<ComponentBuild> = vkd3dArchives().map { componentBuild(it, "VKD3D-Proton") }

    /**
     * Unpacks a DXVK / VKD3D-Proton [archive] under `filesDir/runtime/dxwrappers` the first time
     * and returns the directory. Throws [IOException] when it can't be unpacked.
     */
    suspend fun unpackDxWrapper(archive: File): File = dxWrapperLock.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(runtimeRoot, "dxwrappers/${sanitizeFileName(archive.name)}")
            val done = File(dir, COMPLETE_MARKER)
            // The package name doesn't change when a download is replaced; its size does.
            if (done.isFile && readTextOrNull(done)?.trim() == "${archive.name} ${archive.length()}") {
                return@withContext dir
            }
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                ArchiveExtractor.extract(archive, dir, context = coroutineContext)
                done.writeText("${archive.name} ${archive.length()}")
            } catch (error: CancellationException) {
                dir.deleteRecursively()
                throw error
            } catch (error: Exception) {
                dir.deleteRecursively()
                throw IOException("Couldn't unpack ${archive.name}: ${error.message ?: error.javaClass.simpleName}", error)
            }
            dir
        }
    }

    // --- Container tools --------------------------------------------------------------------

    /** Lower-case names of the tool binaries this APK bundles ([ContainerTools.ASSET_DIR]). */
    val bundledTools: Set<String> by lazy { ContainerTools.bundledFiles(appContext.assets) }

    /**
     * Writes GPU Info and the bundled Direct3D tests into [containerDir]'s `C:\fable\tools`
     * ([ContainerTools]); refreshed after an app update.
     */
    fun installContainerTools(containerDir: File): ContainerTools.Report {
        // The APK's install/update time: tools are rewritten once after every app update.
        val stamp = runCatching { packageUpdateTime() }.getOrDefault("0")
        return ContainerTools.install(containerDir, appContext.assets, stamp)
    }

    @Suppress("DEPRECATION") // getPackageInfo(String, Int) is deprecated from API 33; minSdk is 28.
    private fun packageUpdateTime(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString()

    // --- Executables and drivers ---------------------------------------------------------

    /**
     * A path Wine can open for [storedPath]. Apps are picked through the system file picker, so
     * they are stored as `content://` URIs that a child process cannot read; those are copied
     * into the container's C: drive. Plain paths (and Windows paths) are used as they are.
     */
    suspend fun materializeExecutable(containerDir: File, exeId: String, name: String, storedPath: String): String? =
        withContext(Dispatchers.IO) {
            if (!storedPath.startsWith("content://") && !storedPath.startsWith("file://")) {
                val file = File(storedPath)
                return@withContext when {
                    file.isFile -> file.absolutePath
                    looksLikeWindowsPath(storedPath) -> storedPath
                    else -> null
                }
            }
            val uri = Uri.parse(storedPath)
            val fileName = sanitizeFileName(queryDisplayName(uri) ?: "$name.exe")
            val dest = File(containerDir, "drive_c/fable/$exeId/$fileName")
            val remoteSize = querySize(uri)
            if (dest.isFile && remoteSize > 0 && dest.length() == remoteSize) return@withContext dest.absolutePath
            dest.parentFile?.mkdirs()
            val input = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return@withContext null
            try {
                input.use { source -> dest.outputStream().use { sink -> source.copyTo(sink) } }
            } catch (error: IOException) {
                Log.w(TAG, "Could not copy $name into the container", error)
                dest.delete()
                return@withContext null
            }
            dest.setReadable(true, false)
            dest.absolutePath
        }

    // --- Wine process environment --------------------------------------------------------

    /**
     * Variables Winlator's bionic Wine builds read that a desktop Wine doesn't know about, set the
     * way Winlator-Ludashi's GuestProgramLauncherComponent sets them on every launch:
     *
     * - `ANDROID_RESOLV_DNS`: Android has no `/etc/resolv.conf`, so the bionic `dnsapi.so` /
     *   `ws2_32` take the resolver from this variable (the active network's first DNS server,
     *   [DEFAULT_DNS] when it can't be read).
     * - `WINE_NEW_NDIS=1`: `nsiproxy.so`'s Android interface enumeration (iphlpapi / network
     *   adapters) instead of the netlink dumps app processes aren't allowed to do.
     */
    fun bionicWineEnvironment(): List<String> = listOf(
        "ANDROID_RESOLV_DNS=${primaryDns()}",
        "WINE_NEW_NDIS=1",
    )

    /** The active network's first DNS server (IPv4 preferred), or [DEFAULT_DNS]. Never throws. */
    fun primaryDns(): String = runCatching {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val network = connectivity.activeNetwork ?: return@runCatching null
        val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
        (servers.firstOrNull { it is Inet4Address } ?: servers.firstOrNull())
            ?.hostAddress
            ?.substringBefore('%')
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_DNS

    /**
     * Library path of the active RADV Xclipse driver, or null when none is installed. There is a
     * single active driver for the whole app, so every container launches with the same one.
     */
    fun activeDriverLibrary(): String? = drivers.activeLibraryPath()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun querySize(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L }
    }.getOrNull() ?: -1L

    private fun looksLikeWindowsPath(path: String): Boolean =
        (path.length >= 2 && path[0].isLetter() && path[1] == ':') || path.contains('\\')

    companion object {
        private const val TAG = "WineRuntime"
        private const val WINE_MARKER = ".fable-wine.json"
        private const val COMPLETE_MARKER = ".fable-complete"
        private val WINE_BINARIES = listOf("bin/wine", "bin/wine64")

        /** Names a Box64 package's rc file goes by (upstream installs `/etc/box64.box64rc`). */
        private val BOX64_RC_NAMES = listOf("box64.box64rc", ".box64rc")
        private const val FEX_ROOTFS_SEARCH_DEPTH = 3

        /** Winlator's fallback resolver when the active network reports none. */
        const val DEFAULT_DNS = "8.8.4.4"

        /** Name of the file in the container directory that Wine/Box64/FEX output goes to. */
        const val LAUNCH_LOG = "fable-launch.log"

        /** Output of the one-off `wineboot -u` that initialises a fresh prefix. */
        const val WINEBOOT_LOG = "fable-wineboot.log"

        /** Present while a freshly extracted prefix still needs `wineboot -u`. */
        private const val WINEBOOT_PENDING = ".fable-wineboot-pending"

        /**
         * Directories (relative to the container) that can hold Wine's built-in PE DLLs, in the
         * order Wine should search them. Bionic packages put them in `lib/wine/x86_64-windows`
         * and `lib/wine/i386-windows`; some builds use `lib64/`. The plain `lib/wine` entries are
         * kept for older layouts where Wine appends the `<arch>-windows` subdirectory itself.
         */
        val WINE_DLL_DIRS = listOf(
            "lib/wine/x86_64-windows",
            "lib64/wine/x86_64-windows",
            "lib/wine/i386-windows",
            "lib64/wine/i386-windows",
            "lib/wine",
            "lib64/wine",
        )

        /** Existing [WINE_DLL_DIRS] in [containerDir], as absolute paths. */
        fun wineDllDirs(containerDir: File): List<File> =
            WINE_DLL_DIRS.map { File(containerDir, it) }.filter { it.isDirectory }

        /**
         * `KEY=VALUE` variables that tell Wine where its own files are.
         *
         * WINEDLLPATH lists the PE DLL directories installed in the container, WINELOADER /
         * WINESERVER the container's `bin/wine` and `bin/wineserver` (`start_server` honours
         * WINESERVER). Wine computes the same places itself from where ntdll.so was loaded
         * (`init_paths`: dll_dir from dladdr, bin_dir relative to it; the Termux paths compiled
         * into the build are only fallbacks), so these are explicit rather than required.
         * They don't fix `could not load kernel32.dll, status c0000135`: outside prefix
         * bootstrap Wine ignores WINEDLLPATH for DLLs that have no file in system32
         * (`find_builtin_without_file`), which is why [WinePrefix] copies them there.
         */
        fun wineLocationEnvironment(containerDir: File): List<String> = buildList {
            val dllPath = wineDllDirs(containerDir).joinToString(":") { it.absolutePath }
            if (dllPath.isNotEmpty()) add("WINEDLLPATH=$dllPath")
            val loader = File(containerDir, "bin/wine")
            if (loader.isFile) add("WINELOADER=${loader.absolutePath}")
            val server = File(containerDir, "bin/wineserver")
            if (server.isFile) add("WINESERVER=${server.absolutePath}")
        }

        /**
         * Picks from [archives] (newest first) the package whose [buildName] is [preferred]
         * (case-insensitive, or a file name starting with it); the newest when [preferred] is
         * blank. Null when [preferred] names a package that isn't downloaded.
         */
        fun pickComponent(archives: List<File>, preferred: String?): File? {
            val key = preferred?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return archives.firstOrNull()
            return archives.firstOrNull { buildName(it).lowercase() == key }
                ?: archives.firstOrNull { it.name.lowercase().startsWith(key) }
        }

        /** Windows DXVK packages: not dxvk-native (Linux), not ARM64EC, not VKD3D. */
        fun isDxvkPackageName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("dxvk") && !lower.contains("native") && !lower.contains("arm64ec") &&
                !lower.contains("vkd3d")
        }

        /** VKD3D-Proton packages (`vkd3d-proton-3.0.1.tar.zst`, Winlator `Vkd3d-3.0.1-….wcp`), not ARM64EC. */
        fun isVkd3dPackageName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("vkd3d") && !lower.contains("arm64ec")
        }

        /** `dxvk-3.1.1.tar.gz` -> id `dxvk-3.1.1`, label `DXVK 3.1.1`. */
        private fun componentBuild(archive: File, family: String): ComponentBuild {
            val id = buildName(archive)
            val version = Regex("""[0-9]+(\.[0-9]+)+[a-z]?""").find(id)?.value
            return ComponentBuild(id = id, label = if (version != null) "$family $version" else id, archive = archive)
        }

        /** `wine-9.20.wcp` -> `wine-9.20`, `Proton.9.0-x86_64.wcp` -> `Proton.9.0-x86_64`. */
        fun buildName(archive: File): String = archive.name
            .removeSuffix(".wcp.xz").removeSuffix(".wcp")
            .removeSuffix(".tar.zst").removeSuffix(".tar.xz").removeSuffix(".tar.gz").removeSuffix(".tgz").removeSuffix(".txz")

        /** Human label for the picker: `wine-9.20.wcp` -> `Wine 9.20 (bionic x86_64)`. */
        fun displayName(archive: File): String {
            val base = buildName(archive)
            val version = Regex("""[0-9]+(\.[0-9]+)+""").find(base)?.value
            val family = if (base.startsWith("proton", ignoreCase = true)) "Proton" else "Wine"
            return if (version != null) "$family $version (bionic x86_64)" else base
        }

        /**
         * Winlator bionic Wine packages: `.wcp` (zstd tar) or `.wcp.xz`. ARM64EC builds are left
         * out (they need FEXCore / WOWBox64 DLLs Fable doesn't install); glibc tarballs and
         * unsupported Proton packages never match.
         */
        fun isBionicWinePackageName(name: String): Boolean {
            val lower = name.lowercase()
            if (!(lower.endsWith(".wcp") || lower.endsWith(".wcp.xz"))) return false
            return (lower.startsWith("wine-") || lower.startsWith("wine.")) && !lower.contains("arm64ec")
        }

        /** Headers, man pages and static import libraries are only needed to build Wine programs. */
        private fun isDevelopmentFile(path: String): Boolean =
            path == "include" || path.startsWith("include/") || path.startsWith("share/man/") || path.endsWith(".a")

        /** True while [pid] exists and is not a zombie. */
        fun isAlive(pid: Int): Boolean {
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
            val state = stat.substringAfterLast(')').trim().firstOrNull() ?: return false
            return state != 'Z' && state != 'X'
        }

        /** Last line of the launch log that is not launcher bookkeeping, shortened for a snackbar. */
        fun lastLogLine(containerDir: File): String? {
            val log = File(containerDir, LAUNCH_LOG)
            if (!log.isFile) return null
            val text = runCatching {
                log.inputStream().use { input ->
                    val skip = (log.length() - 4096).coerceAtLeast(0)
                    input.skip(skip)
                    String(input.readBytes(), Charsets.UTF_8)
                }
            }.getOrNull() ?: return null
            return text.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() && !it.startsWith("[fable]") }
                ?.take(160)
        }

        /** True when the launcher recorded a clean exit (code 0) in the launch log. */
        fun exitedCleanly(containerDir: File): Boolean {
            val log = File(containerDir, LAUNCH_LOG)
            val text = runCatching { log.readText() }.getOrNull() ?: return false
            return text.lineSequence().any { it.trim() == "[fable] exit code 0" }
        }
    }
}
