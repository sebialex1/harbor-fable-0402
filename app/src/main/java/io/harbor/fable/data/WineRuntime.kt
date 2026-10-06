package io.harbor.fable.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.harbor.fable.data.models.AssetType
import io.harbor.fable.nativebridge.AdrenoToolsBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Result of looking for a usable `box64` executable. */
internal sealed interface Box64Status {
    data class Ready(val executable: File) : Box64Status

    /** No Box64 package has been downloaded yet. */
    data object NotDownloaded : Box64Status

    /** A package is on disk but nothing inside it is a `box64` executable. */
    data class NoExecutable(val packageName: String) : Box64Status
}

/** A Wine tree extracted into a container directory. */
internal data class InstalledWine(val build: String, val binary: File)

/**
 * Finds, unpacks and wires together the downloaded runtime pieces: Box64 (shared, extracted
 * once under `filesDir/runtime/box64`) and a Wine build (extracted into each container).
 *
 * Everything is discovered from files on disk, so it works offline and before the catalog has
 * been refreshed.
 */
internal class WineRuntime(
    private val appContext: Context,
    private val assets: AssetRepository,
    private val runtimeRoot: File,
) {
    private val box64Lock = Mutex()

    // --- Wine -----------------------------------------------------------------------------

    /**
     * Downloaded Wine archives that can run under Box64, newest first. Only 64-bit (amd64)
     * builds qualify: Box64 cannot execute the 32-bit x86 builds.
     */
    fun wineArchives(): List<File> = assets.downloadedFiles(AssetType.WINE)
        .filter { it.name.contains("amd64", ignoreCase = true) && isTarArchive(it.name) }

    /** The downloaded archive to use: [preferred] (e.g. `wine-11.19-amd64`) when present, else the newest. */
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
        val binary = wineBinary(containerDir) ?: return null
        return InstalledWine(build, binary)
    }

    fun wineBinary(containerDir: File): File? =
        WINE_BINARIES.map { File(containerDir, it) }.firstOrNull { it.isFile }

    /**
     * Extracts [archive] into [containerDir], replacing any previous Wine tree. The marker file is
     * written last, so an interrupted extraction is redone on the next launch.
     */
    suspend fun installWine(archive: File, containerDir: File): InstalledWine = withContext(Dispatchers.IO) {
        File(containerDir, WINE_MARKER).delete()
        ArchiveExtractor.extract(
            archive = archive,
            destDir = containerDir,
            stripComponents = 1,
            skip = ::isDevelopmentFile,
            context = coroutineContext,
        )
        val binary = wineBinary(containerDir)
            ?: throw IOException("${archive.name} has no bin/wine")
        binary.setExecutable(true, false)
        File(containerDir, "bin").listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        val build = buildName(archive)
        writeAtomic(
            File(containerDir, WINE_MARKER),
            JSONObject().put("build", build).put("archive", archive.name).put("size", archive.length()).toString(),
        )
        InstalledWine(build, binary)
    }

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
                    return@withContext Box64Status.Ready(executable)
                }
            }
            Box64Status.NoExecutable(packages.first().name)
        }
    }

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

    /** Installed driver library for [driverId] when that package has been downloaded; null otherwise. */
    fun installedDriverLibrary(driverId: String?): String? {
        if (driverId.isNullOrBlank()) return null
        val zip = assets.getDriver(driverId)?.takeIf { it.isDownloaded }?.localPath ?: return null
        val dest = File(runtimeRoot, "drivers/${sanitizeFileName(File(zip).nameWithoutExtension)}")
        return runCatching { AdrenoToolsBridge.installDriver(zip, dest.absolutePath) }
            .onFailure { Log.w(TAG, "Driver install failed for $driverId", it) }
            .getOrNull()
    }

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

        /** Name of the file the native launcher writes Wine/Box64 output to. */
        const val LAUNCH_LOG = "fable-launch.log"

        /** `wine-11.19-amd64.tar.xz` -> `wine-11.19-amd64`. */
        fun buildName(archive: File): String = archive.name
            .removeSuffix(".tar.xz").removeSuffix(".tar.gz").removeSuffix(".tgz").removeSuffix(".txz")

        private fun isTarArchive(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".tar.xz") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".txz")
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
