package io.harbor.fable.data

import android.util.Log
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext

/** Container formats [ArchiveExtractor] understands, detected from the file's magic bytes. */
internal enum class ArchiveFormat { TAR_XZ, TAR_GZ, TAR, ZIP, ELF, UNKNOWN }

/**
 * Unpacks the runtime packages the app downloads (Wine builds, Box64) into app storage.
 *
 * Handles `.tar.xz`, `.tar.gz`, plain `.tar` and `.zip`. The format is read from the file
 * header, not the name. Tar extraction preserves the executable bit and symlinks, which Wine
 * builds rely on (`bin/winecfg -> wine`). Entries that would land outside [destDir] are skipped.
 */
internal object ArchiveExtractor {
    private const val TAG = "ArchiveExtractor"
    private const val BUFFER_SIZE = 256 * 1024
    private const val EXEC_BITS = 0b001_001_001

    data class Result(val files: Int, val bytes: Long)

    fun detectFormat(file: File): ArchiveFormat {
        val head = ByteArray(512)
        val read = runCatching { FileInputStream(file).use { it.read(head) } }.getOrDefault(-1)
        if (read < 4) return ArchiveFormat.UNKNOWN
        fun at(index: Int): Int = head[index].toInt() and 0xFF
        return when {
            read >= 6 && at(0) == 0xFD && at(1) == 0x37 && at(2) == 0x7A && at(3) == 0x58 && at(4) == 0x5A && at(5) == 0x00 ->
                ArchiveFormat.TAR_XZ
            at(0) == 0x1F && at(1) == 0x8B -> ArchiveFormat.TAR_GZ
            at(0) == 'P'.code && at(1) == 'K'.code -> ArchiveFormat.ZIP
            at(0) == 0x7F && at(1) == 'E'.code && at(2) == 'L'.code && at(3) == 'F'.code -> ArchiveFormat.ELF
            read >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar" -> ArchiveFormat.TAR
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /**
     * Extracts [archive] into [destDir].
     *
     * @param stripComponents leading path components removed from every entry (1 drops the
     *   `wine-11.19-amd64/` wrapper directory).
     * @param skip receives the entry path after stripping; return true to leave it out.
     * @param context when given, extraction stops with a cancellation exception as soon as the
     *   owning coroutine is cancelled.
     */
    fun extract(
        archive: File,
        destDir: File,
        stripComponents: Int = 0,
        skip: (String) -> Boolean = { false },
        context: CoroutineContext? = null,
    ): Result {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Cannot create ${destDir.path}")
        return when (val format = detectFormat(archive)) {
            ArchiveFormat.TAR_XZ -> extractTar(XZCompressorInputStream(buffered(archive)), destDir, stripComponents, skip, context)
            ArchiveFormat.TAR_GZ -> extractTar(GzipCompressorInputStream(buffered(archive)), destDir, stripComponents, skip, context)
            ArchiveFormat.TAR -> extractTar(buffered(archive), destDir, stripComponents, skip, context)
            ArchiveFormat.ZIP -> extractZip(archive, destDir, stripComponents, skip, context)
            else -> throw IOException("${archive.name} is not a supported archive ($format)")
        }
    }

    /** Breadth-first search for a regular file called [name]; null when there is none. */
    fun findFile(root: File, name: String, maxDepth: Int = 8): File? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            children.firstOrNull { it.isFile && it.name == name }?.let { return it }
            if (depth < maxDepth) {
                children.filter { it.isDirectory }.sortedBy { it.name }.forEach { queue.addLast(it to depth + 1) }
            }
        }
        return null
    }

    private fun buffered(file: File): InputStream = BufferedInputStream(FileInputStream(file), BUFFER_SIZE)

    private fun extractTar(
        source: InputStream,
        destDir: File,
        strip: Int,
        skip: (String) -> Boolean,
        context: CoroutineContext?,
    ): Result {
        var files = 0
        var bytes = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        TarArchiveInputStream(source).use { tar ->
            while (true) {
                context?.ensureActive()
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val relative = relativePath(entry.name, strip) ?: continue
                if (skip(relative)) continue
                val target = File(destDir, relative)
                if (!isUnder(destDir, target)) {
                    Log.w(TAG, "Skipping entry outside destination: ${entry.name}")
                    continue
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> createSymlink(destDir, target, entry.linkName)
                    entry.isLink -> {
                        val original = relativePath(entry.linkName, strip)?.let { File(destDir, it) }
                        if (original != null && original.isFile && isUnder(destDir, original)) {
                            prepareTarget(target)
                            original.copyTo(target, overwrite = true)
                            applyMode(target, entry.mode)
                            files++
                        }
                    }
                    entry.isFile -> {
                        prepareTarget(target)
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = tar.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                bytes += n
                            }
                        }
                        applyMode(target, entry.mode)
                        files++
                    }
                    // Devices, FIFOs and the like have no meaning inside a Wine tree.
                }
            }
        }
        return Result(files, bytes)
    }

    private fun extractZip(
        archive: File,
        destDir: File,
        strip: Int,
        skip: (String) -> Boolean,
        context: CoroutineContext?,
    ): Result {
        var files = 0
        var bytes = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        ZipInputStream(buffered(archive)).use { zip ->
            while (true) {
                context?.ensureActive()
                val entry = zip.nextEntry ?: break
                val relative = relativePath(entry.name, strip) ?: continue
                if (skip(relative)) continue
                val target = File(destDir, relative)
                if (!isUnder(destDir, target)) {
                    Log.w(TAG, "Skipping entry outside destination: ${entry.name}")
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                prepareTarget(target)
                FileOutputStream(target).use { out ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytes += n
                    }
                }
                target.setReadable(true, false)
                files++
            }
        }
        return Result(files, bytes)
    }

    /** Entry name without `.`/empty parts and the first [strip] components; null to skip it. */
    private fun relativePath(name: String, strip: Int): String? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        if (parts.size <= strip) return null
        return parts.drop(strip).joinToString("/")
    }

    /** Makes room for a regular file: creates parents and removes whatever is in the way. */
    private fun prepareTarget(target: File) {
        target.parentFile?.mkdirs()
        val path = target.toPath()
        if (Files.isSymbolicLink(path) || target.isFile) {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(true, false)
        file.setWritable(true, true)
        if (mode and EXEC_BITS != 0) file.setExecutable(true, false)
    }

    private fun createSymlink(root: File, link: File, targetName: String) {
        if (targetName.isBlank()) return
        val base = link.parentFile ?: root
        val resolved = if (targetName.startsWith("/")) File(targetName) else File(base, targetName)
        if (!resolved.toPath().normalize().startsWith(root.toPath().normalize())) {
            Log.w(TAG, "Skipping symlink that leaves the destination: ${link.name} -> $targetName")
            return
        }
        link.parentFile?.mkdirs()
        runCatching { Files.deleteIfExists(link.toPath()) }
        try {
            Files.createSymbolicLink(link.toPath(), Paths.get(targetName))
        } catch (error: Exception) {
            Log.w(TAG, "Could not create symlink ${link.name} -> $targetName", error)
        }
    }
}
