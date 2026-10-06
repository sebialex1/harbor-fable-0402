package io.harbor.fable.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Which catalog record a finished download should update. */
enum class RecordKind {
    ASSET,
    DRIVER,
    GENERIC,
}

data class DownloadRequest(
    val url: String,
    val destPath: String,
    val displayName: String,
    val expectedSha256: String? = null,
    val assetId: String? = null,
    val recordKind: RecordKind = RecordKind.GENERIC,
    val id: String = UUID.randomUUID().toString(),
)

data class DownloadTask(
    val id: String,
    val url: String,
    val destPath: String,
    val displayName: String,
    val expectedSha256: String? = null,
    val assetId: String? = null,
    val recordKind: RecordKind = RecordKind.GENERIC,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val sha256: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val isTerminal: Boolean
        get() = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED

    /** Foreground service must stay up while any task is in one of these states. */
    val keepsServiceAlive: Boolean
        get() = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.VERIFYING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("destPath", destPath)
        put("displayName", displayName)
        putNullable("expectedSha256", expectedSha256)
        putNullable("assetId", assetId)
        put("recordKind", recordKind.name)
        put("status", status.name)
        put("bytesDownloaded", bytesDownloaded)
        put("totalBytes", totalBytes)
        putNullable("sha256", sha256)
        putNullable("error", error)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): DownloadTask = DownloadTask(
            id = obj.getString("id"),
            url = obj.getString("url"),
            destPath = obj.getString("destPath"),
            displayName = obj.optString("displayName", "Download"),
            expectedSha256 = normalizeSha256(obj.stringOrNull("expectedSha256")),
            assetId = obj.stringOrNull("assetId"),
            recordKind = runCatching { RecordKind.valueOf(obj.optString("recordKind")) }
                .getOrDefault(RecordKind.GENERIC),
            status = runCatching { DownloadStatus.valueOf(obj.optString("status")) }
                .getOrDefault(DownloadStatus.QUEUED),
            bytesDownloaded = obj.optLong("bytesDownloaded", 0L),
            totalBytes = obj.optLong("totalBytes", -1L),
            sha256 = normalizeSha256(obj.stringOrNull("sha256")),
            error = obj.stringOrNull("error"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}

data class DownloadSnapshot(val tasks: List<DownloadTask>) {
    val active: List<DownloadTask> get() = tasks.filter { it.keepsServiceAlive }
    val hasActiveWork: Boolean get() = active.isNotEmpty()
}

data class DownloadEvent(val task: DownloadTask)

/**
 * HTTP downloader with a persistent queue, Range resume, and SHA-256 checks.
 *
 * Long downloads are handed to [DownloadService], which holds a dataSync
 * foreground notification. If the system refuses a background service start,
 * the transfer still runs in-process so an open activity is not stuck.
 *
 * Partial files are `{destPath}.partial`. A completed file is renamed into
 * place only after the checksum matches (or after it is computed, when the
 * publisher did not provide one).
 */
class DownloadManager internal constructor(
    private val appContext: Context,
    private val queueFile: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val tasks = LinkedHashMap<String, DownloadTask>()
    private val controls = ConcurrentHashMap<String, TransferControl>()
    private val processing = AtomicBoolean(false)
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fable-download-persist")
    }
    private val persistLock = Any()

    private val _snapshot = MutableStateFlow(DownloadSnapshot(emptyList()))
    val snapshot: StateFlow<DownloadSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    init {
        loadQueue()
    }

    fun tasks(): List<DownloadTask> = snapshot.value.tasks

    fun task(id: String): DownloadTask? = synchronized(lock) { tasks[id] }

    fun taskForAsset(assetId: String): DownloadTask? = synchronized(lock) {
        tasks.values
            .filter { it.assetId == assetId }
            .maxByOrNull { it.updatedAt }
    }

    fun enqueue(request: DownloadRequest): DownloadTask {
        requireHttpUrl(request.url)
        val expected = normalizeSha256(request.expectedSha256)
        val normalized = request.copy(expectedSha256 = expected, displayName = request.displayName.ifBlank { "Download" })
        val task = synchronized(lock) {
            val existing = tasks.values.find { it.url == normalized.url && it.destPath == normalized.destPath }
            when (existing?.status) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> return existing
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    controlFor(existing.id).apply {
                        pauseRequested = false
                        cancelRequested = false
                    }
                    val resumed = existing.copy(
                        status = DownloadStatus.QUEUED,
                        error = null,
                        expectedSha256 = expected ?: existing.expectedSha256,
                        displayName = normalized.displayName,
                        assetId = normalized.assetId ?: existing.assetId,
                        recordKind = normalized.recordKind,
                        updatedAt = System.currentTimeMillis(),
                    )
                    tasks[resumed.id] = resumed
                    publishLocked()
                    resumed
                }
                else -> {
                    val created = DownloadTask(
                        id = normalized.id,
                        url = normalized.url,
                        destPath = normalized.destPath,
                        displayName = normalized.displayName,
                        expectedSha256 = expected,
                        assetId = normalized.assetId,
                        recordKind = normalized.recordKind,
                    )
                    tasks[created.id] = created
                    publishLocked()
                    created
                }
            }
        }
        persistSync()
        startServiceOrKick()
        return task
    }

    fun enqueue(
        url: String,
        dest: File,
        displayName: String,
        expectedSha256: String? = null,
        assetId: String? = null,
        recordKind: RecordKind = RecordKind.GENERIC,
    ): DownloadTask = enqueue(
        DownloadRequest(
            url = url,
            destPath = dest.absolutePath,
            displayName = displayName,
            expectedSha256 = expectedSha256,
            assetId = assetId,
            recordKind = recordKind,
        )
    )

    /** Suspends until [id] reaches a terminal state. */
    suspend fun await(id: String): DownloadTask {
        task(id)?.takeIf { it.isTerminal }?.let { return it }
        return snapshot
            .map { snap -> snap.tasks.find { it.id == id } }
            .filterNotNull()
            .first { it.isTerminal }
    }

    fun pause(id: String) {
        val control = controlFor(id)
        control.pauseRequested = true
        val current = task(id) ?: return
        if (current.status == DownloadStatus.QUEUED) {
            update(id, persist = true) { it.copy(status = DownloadStatus.PAUSED, error = null) }
        }
    }

    fun resume(id: String) {
        val control = controlFor(id)
        control.pauseRequested = false
        control.cancelRequested = false
        val current = task(id) ?: return
        if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.FAILED) {
            update(id, persist = true) { it.copy(status = DownloadStatus.QUEUED, error = null) }
            startServiceOrKick()
        }
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        val control = controlFor(id)
        control.cancelRequested = true
        control.pauseRequested = false
        val current = task(id) ?: return
        if (current.status == DownloadStatus.DOWNLOADING || current.status == DownloadStatus.VERIFYING) {
            return
        }
        deletePartial(current.destPath)
        update(id, persist = true) {
            it.copy(status = DownloadStatus.CANCELLED, error = null, updatedAt = System.currentTimeMillis())
        }
    }

    fun cancelForAsset(assetId: String) {
        val id = taskForAsset(assetId)?.id ?: return
        cancel(id)
    }

    /** Starts the foreground service when a previous run left work queued. Does not resume pauses. */
    fun resumePending() {
        val pending = synchronized(lock) { tasks.values.any { it.status == DownloadStatus.QUEUED } }
        if (pending) startServiceOrKick()
    }

    fun clearFinished() {
        synchronized(lock) {
            val drop = tasks.values.filter {
                it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.CANCELLED ||
                    it.status == DownloadStatus.FAILED
            }.map { it.id }
            drop.forEach {
                tasks.remove(it)
                controls.remove(it)
            }
            publishLocked()
        }
        persistSync()
    }

    /** Called by [DownloadService] after it has entered the foreground. */
    fun kick() {
        if (!processing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val next = claimNext() ?: break
                    runDownload(next)
                }
            } finally {
                processing.set(false)
                if (hasQueued()) kick()
            }
        }
    }

    private fun hasQueued(): Boolean = synchronized(lock) {
        tasks.values.any { it.status == DownloadStatus.QUEUED }
    }

    private fun claimNext(): DownloadTask? = synchronized(lock) {
        val next = tasks.values.filter { it.status == DownloadStatus.QUEUED }.minByOrNull { it.createdAt }
            ?: return null
        val claimed = next.copy(status = DownloadStatus.DOWNLOADING, error = null, updatedAt = System.currentTimeMillis())
        tasks[claimed.id] = claimed
        publishLocked()
        claimed
    }

    private suspend fun runDownload(claimed: DownloadTask) {
        val control = controlFor(claimed.id)
        if (control.cancelRequested) {
            deletePartial(claimed.destPath)
            update(claimed.id, persist = true) { it.copy(status = DownloadStatus.CANCELLED, error = null) }
            return
        }
        if (control.pauseRequested) {
            update(claimed.id, persist = true) { it.copy(status = DownloadStatus.PAUSED, error = null) }
            return
        }
        val dest = File(claimed.destPath)
        val partial = partialFile(claimed.destPath)
        dest.parentFile?.mkdirs()

        val expected = claimed.expectedSha256
        if (dest.isFile && dest.length() > 0L) {
            val hash = runCatching { sha256Of(dest) }.getOrNull()
            if (hash != null && (expected == null || hash == expected)) {
                update(claimed.id, persist = true) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = dest.length(),
                        totalBytes = dest.length(),
                        sha256 = hash,
                        error = null,
                    )
                }
                return
            }
            dest.delete()
        }

        try {
            val outcome = transfer(claimed, partial, control)
            if (control.cancelRequested || outcome.cancelled) {
                partial.delete()
                update(claimed.id, persist = true) { it.copy(status = DownloadStatus.CANCELLED, error = null) }
                return
            }
            if (control.pauseRequested || outcome.paused) {
                update(claimed.id, persist = true) {
                    it.copy(
                        status = DownloadStatus.PAUSED,
                        bytesDownloaded = partial.takeIf { it.isFile }?.length() ?: it.bytesDownloaded,
                        totalBytes = outcome.totalBytes,
                        error = null,
                    )
                }
                return
            }
            if (!partial.isFile || partial.length() <= 0L) {
                partial.delete()
                update(claimed.id, persist = true) {
                    it.copy(status = DownloadStatus.FAILED, error = "Empty download")
                }
                return
            }
            update(claimed.id, persist = true) {
                it.copy(status = DownloadStatus.VERIFYING, bytesDownloaded = partial.length(), totalBytes = partial.length())
            }
            val hash = sha256Of(partial)
            if (expected != null && hash != expected) {
                partial.delete()
                update(claimed.id, persist = true) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        sha256 = hash,
                        error = "SHA-256 mismatch (expected ${expected.take(12)}…, got ${hash.take(12)}…)",
                    )
                }
                return
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
            update(claimed.id, persist = true) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = dest.length(),
                    totalBytes = dest.length(),
                    sha256 = hash,
                    error = null,
                )
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Download failed for ${claimed.displayName}", error)
            val status = when {
                control.cancelRequested -> DownloadStatus.CANCELLED
                control.pauseRequested -> DownloadStatus.PAUSED
                else -> DownloadStatus.FAILED
            }
            if (status == DownloadStatus.CANCELLED) deletePartial(claimed.destPath)
            update(claimed.id, persist = true) {
                it.copy(
                    status = status,
                    bytesDownloaded = partial.takeIf { file -> file.isFile }?.length() ?: it.bytesDownloaded,
                    error = if (status == DownloadStatus.FAILED) error.message ?: "Download failed" else null,
                )
            }
        }
    }

    private fun transfer(task: DownloadTask, partial: File, control: TransferControl): TransferOutcome {
        var resumeFrom = if (partial.isFile) partial.length() else 0L
        var restarted = false
        while (true) {
            if (control.cancelRequested) return TransferOutcome(cancelled = true, totalBytes = task.totalBytes)
            if (control.pauseRequested && resumeFrom > 0L && !restarted) {
                return TransferOutcome(paused = true, totalBytes = task.totalBytes)
            }
            val conn = openFollowing(task.url, resumeFrom)
            try {
                val code = conn.responseCode
                if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                    conn.disconnect()
                    if (acceptCompletePartial(task, partial)) {
                        return TransferOutcome(complete = true, totalBytes = partial.length())
                    }
                    if (restarted) throw IOException("Range not satisfiable for ${task.url}")
                    restarted = true
                    partial.delete()
                    resumeFrom = 0L
                    continue
                }
                if (code == HttpURLConnection.HTTP_OK && resumeFrom > 0L) {
                    partial.delete()
                    resumeFrom = 0L
                    restarted = true
                } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(180)
                    throw IOException("HTTP $code${err?.let { " $it" } ?: ""}")
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L && partial.isFile
                val total = resolveTotal(conn, resumeFrom, append)
                return writeBody(conn, partial, append, resumeFrom, total, task.id, control)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun acceptCompletePartial(task: DownloadTask, partial: File): Boolean {
        if (!partial.isFile || partial.length() <= 0L) return false
        val remote = runCatching { headContentLength(task.url) }.getOrDefault(-1L)
        if (remote > 0L && partial.length() == remote) return true
        val expected = task.expectedSha256 ?: return false
        val hash = runCatching { sha256Of(partial) }.getOrNull() ?: return false
        return hash == expected
    }

    private fun writeBody(
        conn: HttpURLConnection,
        partial: File,
        append: Boolean,
        resumeFrom: Long,
        total: Long,
        taskId: String,
        control: TransferControl,
    ): TransferOutcome {
        var downloaded = if (append) resumeFrom else 0L
        var lastUi = 0L
        var lastFlush = downloaded
        conn.inputStream.use { input ->
            FileOutputStream(partial, append).use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (control.cancelRequested) {
                        output.flush()
                        return TransferOutcome(cancelled = true, totalBytes = total, bytesDownloaded = downloaded)
                    }
                    if (control.pauseRequested) {
                        output.flush()
                        runCatching { output.channel.force(true) }
                        report(taskId, downloaded, total, force = true)
                        return TransferOutcome(paused = true, totalBytes = total, bytesDownloaded = downloaded)
                    }
                    val read = input.read(buf)
                    if (read < 0) break
                    output.write(buf, 0, read)
                    downloaded += read
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (downloaded - lastFlush >= FLUSH_EVERY_BYTES) {
                        output.flush()
                        lastFlush = downloaded
                    }
                    if (now - lastUi >= UI_INTERVAL_MS) {
                        lastUi = now
                        report(taskId, downloaded, total, force = false)
                    }
                }
                output.flush()
                runCatching { output.channel.force(true) }
            }
        }
        report(taskId, downloaded, if (total > 0) total else downloaded, force = true)
        return TransferOutcome(complete = true, totalBytes = if (total > 0) total else downloaded, bytesDownloaded = downloaded)
    }

    private fun report(id: String, downloaded: Long, total: Long, force: Boolean) {
        update(id, persist = force) {
            it.copy(
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = downloaded,
                totalBytes = if (total > 0) total else it.totalBytes,
            )
        }
    }

    private fun resolveTotal(conn: HttpURLConnection, resumeFrom: Long, append: Boolean): Long {
        val range = conn.getHeaderField("Content-Range")
        if (range != null) {
            val total = range.substringAfter('/', "").toLongOrNull()
            if (total != null && total > 0L) return total
        }
        val length = conn.contentLengthLong
        if (length > 0L) return if (append) resumeFrom + length else length
        return -1L
    }

    private fun openFollowing(url: String, rangeFrom: Long): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = open(current, rangeFrom)
            val code = conn.responseCode
            if (code !in REDIRECTS) return conn
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (loc.isNullOrBlank()) throw IOException("HTTP $code without Location")
            current = URL(URL(current), loc).toExternalForm()
        }
        throw IOException("Too many redirects for $url")
    }

    private fun headContentLength(url: String): Long {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", FABLE_USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val loc = conn.getHeaderField("Location") ?: return -1L
                    current = URL(URL(current), loc).toExternalForm()
                    return@repeat
                }
                return conn.contentLengthLong
            } finally {
                conn.disconnect()
            }
        }
        return -1L
    }

    private fun open(url: String, rangeFrom: Long): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", FABLE_USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (rangeFrom > 0L) setRequestProperty("Range", "bytes=$rangeFrom-")
        }
    }

    private fun update(id: String, persist: Boolean, transform: (DownloadTask) -> DownloadTask) {
        val updated = synchronized(lock) {
            val current = tasks[id] ?: return
            val next = transform(current).copy(updatedAt = System.currentTimeMillis())
            tasks[id] = next
            publishLocked()
            next
        }
        if (persist || updated.isTerminal || updated.status == DownloadStatus.PAUSED) persistSync() else persistAsync()
        _events.tryEmit(DownloadEvent(updated))
    }

    private fun publishLocked() {
        _snapshot.value = DownloadSnapshot(tasks.values.sortedBy { it.createdAt })
    }

    private fun startServiceOrKick() {
        DownloadService.ensureChannel(appContext)
        val started = try {
            val intent = Intent(appContext, DownloadService::class.java).apply {
                action = DownloadService.ACTION_PROCESS
            }
            ContextCompat.startForegroundService(appContext, intent)
            true
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Foreground service not allowed; downloading in-process", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Missing permission to start download service", error)
            false
        }
        if (!started) kick()
    }

    private fun loadQueue() {
        val text = readTextOrNull(queueFile) ?: return
        runCatching {
            val root = JSONObject(text)
            val array = root.optJSONArray("tasks") ?: JSONArray()
            val loaded = ArrayList<DownloadTask>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val parsed = runCatching { DownloadTask.fromJson(item) }.getOrNull() ?: continue
                val reconciled = reconcile(parsed)
                loaded += if (reconciled.status == DownloadStatus.DOWNLOADING || reconciled.status == DownloadStatus.VERIFYING) {
                    reconciled.copy(status = DownloadStatus.QUEUED, error = null)
                } else {
                    reconciled
                }
            }
            synchronized(lock) {
                tasks.clear()
                prune(loaded).forEach { tasks[it.id] = it }
                publishLocked()
            }
        }.onFailure { error ->
            Log.e(TAG, "Download queue unreadable", error)
            runCatching { queueFile.renameTo(File(queueFile.parentFile, "queue.json.bak")) }
        }
    }

    private fun reconcile(task: DownloadTask): DownloadTask {
        val dest = File(task.destPath)
        val partial = partialFile(task.destPath)
        val onDisk = when {
            dest.isFile -> dest.length()
            partial.isFile -> partial.length()
            else -> 0L
        }
        return if (onDisk > task.bytesDownloaded) task.copy(bytesDownloaded = onDisk) else task
    }

    private fun prune(loaded: List<DownloadTask>): List<DownloadTask> {
        val keep = loaded.filter { task ->
            task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.PAUSED ||
                task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.VERIFYING ||
                task.status == DownloadStatus.FAILED
        }
        val recentDone = loaded
            .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELLED }
            .sortedByDescending { it.updatedAt }
            .take(20)
        return (keep + recentDone).distinctBy { it.id }.sortedBy { it.createdAt }
    }

    private fun persistSync() {
        val copy = synchronized(lock) { tasks.values.toList() }
        synchronized(persistLock) {
            runCatching { writeQueue(copy) }.onFailure { logPersistFailure("download queue", it) }
        }
    }

    private fun persistAsync() {
        val copy = synchronized(lock) { tasks.values.toList() }
        persistExecutor.execute {
            synchronized(persistLock) {
                runCatching { writeQueue(copy) }.onFailure { logPersistFailure("download queue", it) }
            }
        }
    }

    private fun writeQueue(copy: List<DownloadTask>) {
        val array = JSONArray()
        copy.forEach { array.put(it.toJson()) }
        writeAtomic(queueFile, JSONObject().put("version", 1).put("tasks", array).toString())
    }

    private fun controlFor(id: String): TransferControl = controls.getOrPut(id) { TransferControl() }

    private fun deletePartial(destPath: String) {
        val partial = partialFile(destPath)
        if (partial.exists()) partial.delete()
    }

    private fun partialFile(destPath: String): File = File("$destPath.partial")

    private fun requireHttpUrl(url: String) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Only http(s) URLs are supported" }
    }

    private class TransferControl {
        @Volatile var pauseRequested: Boolean = false
        @Volatile var cancelRequested: Boolean = false
    }

    private data class TransferOutcome(
        val complete: Boolean = false,
        val paused: Boolean = false,
        val cancelled: Boolean = false,
        val totalBytes: Long = -1,
        val bytesDownloaded: Long = 0,
    )

    companion object {
        private const val TAG = "DownloadManager"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val UI_INTERVAL_MS = 250L
        private const val FLUSH_EVERY_BYTES = 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        @Volatile
        private var instance: DownloadManager? = null

        @Synchronized
        fun get(context: Context): DownloadManager {
            instance?.let { return it }
            val app = context.applicationContext
            val file = File(app.filesDir, "downloads/queue.json")
            val created = DownloadManager(app, file)
            instance = created
            return created
        }
    }
}

private fun InputStream.bufferedReaderSafe(): String =
    bufferedReader().use { it.readText() }
