package io.harbor.fable.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Games added with their whole folder ("Choose game folder" in the Add App sheet, a Storage
 * Access Framework tree Fable holds a persisted read grant for).
 *
 * Why: a game is more than its .exe. ULTRAKILL's `ULTRAKILL.exe` is a Unity stub that imports
 * `UnityPlayer.dll` and reads `ULTRAKILL_Data/` and `MonoBleedingEdge/` from its own folder.
 * Picking a single file grants Fable that one document only, so the launch could copy nothing
 * but the .exe into the container; Wine's loader then stopped at the missing `UnityPlayer.dll`
 * before any Direct3D DLL was loaded and the screen stayed black. A tree grant reaches every
 * file in the folder without Android's "All files access":
 *
 * - [inPlace]: the folder's real path on shared storage, when Fable can read and write it there
 *   (all-files access), so the game runs where it is through Wine's `Z:` drive;
 * - otherwise [sync] copies the folder into the container's C: drive (`C:\fable\<id>`), once:
 *   later launches only copy files whose size changed (a game update) or that are missing.
 */
internal class GameFolders(private val context: Context) {

    data class Report(
        /** The .exe to run, or null when the folder couldn't be used ([error] says why). */
        val exe: File?,
        val inPlace: Boolean = false,
        val copied: Int = 0,
        val copiedBytes: Long = 0,
        val upToDate: Int = 0,
        val failed: List<String> = emptyList(),
        val error: String? = null,
        /**
         * Lower-case `/` paths of every file the picked folder holds (what the copy worked from),
         * or null when it wasn't listed (in place). Lets the launch tell a file the folder never
         * had from one the copy lost.
         */
        val sourceFiles: Set<String>? = null,
        /** Lower-case source path to why its copy failed. */
        val failedPaths: Map<String, String> = emptyMap(),
        /** The picked folder's top-level entries (folders end with `/`), for the launch log. */
        val topLevel: List<String> = emptyList(),
        val directories: Int = 0,
    ) {
        fun describe(): List<String> = buildList {
            when {
                exe == null -> add("game folder: NOT usable: ${error ?: "unknown error"}")
                inPlace -> add("game folder: runs in place from ${exe.parentFile?.absolutePath}")
                else -> add(
                    "game folder: copied $copied file(s) (${copiedBytes / (1024 * 1024)} MiB), $upToDate already up to date, " +
                        "into ${exe.parentFile?.absolutePath}",
                )
            }
            if (sourceFiles != null) {
                add(
                    "source folder: ${sourceFiles.size} file(s) in $directories folder(s); top level: " +
                        topLevel.take(TOP_LEVEL_SHOWN).joinToString().ifEmpty { "(empty)" } +
                        if (topLevel.size > TOP_LEVEL_SHOWN) ", … ${topLevel.size - TOP_LEVEL_SHOWN} more" else "",
                )
            }
            failed.take(10).forEach { add("couldn't copy $it") }
            if (failed.size > 10) add("… and ${failed.size - 10} more copy failures")
        }
    }

    /** One entry of a folder listing. [relativePath] uses `/`. */
    data class Entry(val relativePath: String, val documentId: String, val isDirectory: Boolean, val size: Long)

    /**
     * The tree's directory on shared storage when it's on the external-storage provider and this
     * app can read *and* write it (games write saves and logs next to themselves), else null.
     */
    fun inPlace(tree: Uri, relativeExe: String): File? = runCatching {
        if (tree.authority != EXTERNAL_STORAGE_DOCUMENTS) return@runCatching null
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory() else File("/storage/$volume")
        val dir = if (relative.isEmpty()) root else File(root, relative)
        val exe = File(dir, relativeExe)
        exe.takeIf { dir.isDirectory && dir.canWrite() && dir.list() != null && it.isFile && it.canRead() }
    }.getOrNull()

    /**
     * Every file and folder under [tree], depth first, at most [maxDepth] levels down (0 = the
     * folder's own entries). Never throws; an unreadable folder gives an empty or partial list.
     */
    fun list(tree: Uri, maxDepth: Int = Int.MAX_VALUE): List<Entry> {
        val result = mutableListOf<Entry>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return result
        val pending = ArrayDeque<Triple<String, String, Int>>() // documentId, relative dir, depth
        pending.add(Triple(rootId, "", 0))
        while (pending.isNotEmpty() && result.size < MAX_ENTRIES) {
            val (parentId, parentPath, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            runCatching {
                context.contentResolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1)?.takeIf(::isSafeName) ?: continue
                        val mime = cursor.getString(2)
                        val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        result += Entry(path, id, isDir, size)
                        if (isDir && depth < maxDepth) pending.add(Triple(id, path, depth + 1))
                    }
                }
            }.onFailure { Log.w(TAG, "Couldn't list $parentPath in $tree", it) }
        }
        return result
    }

    /** Document URI of [entry] inside [tree] (readable through the tree grant). */
    fun documentUri(tree: Uri, entry: Entry): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.documentId)

    /**
     * Copies [tree] into [dest] (files whose size already matches are skipped) and returns the
     * copy of [relativeExe]. Checks free space first. Honours cancellation between files.
     */
    suspend fun sync(tree: Uri, dest: File, relativeExe: String): Report = withContext(Dispatchers.IO) {
        val source = object : Source {
            override fun list(): List<Entry> = this@GameFolders.list(tree)
            override fun open(entry: Entry) = context.contentResolver.openInputStream(documentUri(tree, entry))
        }
        copyTree(source, dest, relativeExe)
    }

    /** Where [copyTree] reads a game folder from: a SAF tree on device, a plain folder in tests. */
    interface Source {
        /** Every file and folder, relative paths with `/`; empty when it can't be read. */
        fun list(): List<Entry>

        /** The content of [entry], or null when the provider returns no stream. */
        fun open(entry: Entry): java.io.InputStream?
    }

    companion object {
        /**
         * Copies everything [source] lists into [dest], keeping the folder structure and names
         * exactly (an Unreal package's launcher finds `Engine\Binaries\Win64\UE4Game.exe` and
         * `<Project>\<Project>.uproject` relative to itself), skipping files whose size already
         * matches, and returns the copy of [relativeExe]. Checks free space first. Honours
         * cancellation between files. No Android dependencies.
         */
        internal suspend fun copyTree(source: Source, dest: File, relativeExe: String): Report {
            val entries = source.list()
            if (entries.isEmpty()) {
                return Report(exe = null, error = "Fable can't read the game folder any more. Add the game again")
            }
            val files = entries.filter { !it.isDirectory }
            val sourceFiles = files.mapTo(HashSet()) { it.relativePath.lowercase() }
            val topLevel = entries.filter { '/' !in it.relativePath }
                .sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy { it.relativePath.lowercase() })
                .map { if (it.isDirectory) it.relativePath + "/" else it.relativePath }
            val directories = entries.count { it.isDirectory }
            fun report(exe: File?, error: String?, copied: Int = 0, bytes: Long = 0, upToDate: Int = 0, failed: List<Pair<String, String>> = emptyList()) =
                Report(
                    exe = exe, copied = copied, copiedBytes = bytes, upToDate = upToDate,
                    failed = failed.map { "${it.first}: ${it.second}" }, error = error,
                    sourceFiles = sourceFiles, failedPaths = failed.associate { it.first.lowercase() to it.second },
                    topLevel = topLevel, directories = directories,
                )
            if (files.none { it.relativePath.equals(relativeExe, ignoreCase = true) }) {
                return report(null, "$relativeExe isn't in the game folder any more. Add the game again")
            }
            if (!dest.isDirectory && !dest.mkdirs()) {
                return report(null, "Couldn't create ${dest.absolutePath}")
            }
            val todo = files.filter { entry ->
                val target = File(dest, entry.relativePath)
                !(target.isFile && entry.size >= 0 && target.length() == entry.size)
            }
            val needed = todo.sumOf { it.size.coerceAtLeast(0) }
            val free = dest.usableSpace
            if (needed > 0 && free in 1 until needed + FREE_SPACE_MARGIN) {
                return report(
                    null,
                    "Not enough storage to copy the game folder: it needs ${needed / (1024 * 1024)} MiB, " +
                        "${free / (1024 * 1024)} MiB are free. Free some space, or allow Fable \"All files access\" " +
                        "so the game runs from its own folder",
                )
            }
            entries.filter { it.isDirectory }.forEach { File(dest, it.relativePath).mkdirs() }
            var copied = 0
            var bytes = 0L
            val failed = mutableListOf<Pair<String, String>>()
            for (entry in todo) {
                currentCoroutineContext().ensureActive()
                val target = File(dest, entry.relativePath)
                val temp = File(target.parentFile, target.name + TMP_SUFFIX)
                try {
                    target.parentFile?.mkdirs()
                    val input = source.open(entry) ?: throw IOException("provider returned no stream")
                    input.use { from -> temp.outputStream().use { sink -> bytes += from.copyTo(sink, COPY_BUFFER) } }
                    if (entry.size >= 0 && temp.length() != entry.size) {
                        throw IOException("short copy: ${temp.length()} of ${entry.size} bytes")
                    }
                    if (target.exists() && !target.delete()) throw IOException("couldn't replace the old copy")
                    if (!temp.renameTo(target)) throw IOException("rename failed")
                    target.setReadable(true, false)
                    copied++
                } catch (error: IOException) {
                    temp.delete()
                    failed += entry.relativePath to (error.message ?: error.javaClass.simpleName)
                } catch (error: SecurityException) {
                    temp.delete()
                    failed += entry.relativePath to (error.message ?: "permission denied")
                }
            }
            val exe = files.firstOrNull { it.relativePath.equals(relativeExe, ignoreCase = true) }
                ?.let { File(dest, it.relativePath) }
                ?.takeIf { it.isFile }
            return report(
                exe = exe,
                error = if (exe == null) "Couldn't copy $relativeExe into the container" else null,
                copied = copied,
                bytes = bytes,
                upToDate = files.size - todo.size,
                failed = failed,
            )
        }

        private const val TOP_LEVEL_SHOWN = 24
        private const val TAG = "GameFolders"
        const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
        private const val TMP_SUFFIX = ".fable-tmp"
        private const val COPY_BUFFER = 1 shl 20
        private const val MAX_ENTRIES = 200_000
        private const val FREE_SPACE_MARGIN = 256L * 1024 * 1024
        private val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        /** File names are kept exactly (Unity looks for `<Name>_Data`); only unsafe ones are skipped. */
        internal fun isSafeName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

        /** Helpers and installers that are never the game itself. */
        private val NOT_THE_GAME = Regex(
            """(?i)^(unitycrashhandler(32|64)?|crashhandler|crashpad_handler|unins\d*|uninstall.*|.*setup.*|vc_?redist.*|""" +
                """dxsetup|dxwebsetup|oalinst|ue4prereqsetup.*|.*prereq.*|.*launcher_?helper.*|steam_?api.*|.*_?dedicated_?server.*)\.exe$""",
        )

        /**
         * The .exe the game most likely starts with, from [exes] (relative paths): top level
         * first, one named like the folder ([folderName]) or with a `<Name>_Data` folder next to
         * it, never a crash handler or installer; the largest of the rest otherwise.
         */
        internal fun pickMainExe(exes: List<Pair<String, Long>>, folderName: String?, dirs: Set<String>): String? {
            val candidates = exes.filterNot { NOT_THE_GAME.matches(it.first.substringAfterLast('/')) }.ifEmpty { exes }
            fun base(path: String) = path.substringAfterLast('/').substringBeforeLast('.')
            fun parent(path: String) = path.substringBeforeLast('/', "")
            return candidates
                .sortedWith(
                    compareBy<Pair<String, Long>> { it.first.count { c -> c == '/' } }
                        .thenByDescending { (path, _) ->
                            val unityData = (if (parent(path).isEmpty()) "" else parent(path) + "/") + base(path) + "_Data"
                            dirs.any { it.equals(unityData, ignoreCase = true) }
                        }
                        .thenByDescending { (path, _) -> folderName != null && base(path).equals(folderName, ignoreCase = true) }
                        .thenByDescending { it.second },
                )
                .firstOrNull()
                ?.first
        }
    }
}
