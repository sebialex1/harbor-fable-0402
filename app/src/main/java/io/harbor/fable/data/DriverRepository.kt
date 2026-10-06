package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Owns everything about the RADV Xclipse driver: the release list from [RadvReleaseProvider],
 * the downloaded zips, and the single installed (active) driver.
 *
 * Layout under `filesDir/drivers/`:
 * ```
 * packages/<asset>.zip   downloaded release packages (one per version, kept for rollback)
 * active/<tag>/          the extracted driver that Wine loads; at most one at any time
 * active.json            which release is installed and where its library is
 * ```
 *
 * Download status is reconciled against the files on disk and the [DownloadManager] queue, so
 * the list is correct offline and before the first refresh.
 */
class DriverRepository internal constructor(
    private val provider: RadvReleaseProvider,
    private val downloads: DownloadManager,
    private val root: File,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packagesDir = File(root, "packages")
    private val activeFile = File(root, "active.json")

    private val _releases = MutableStateFlow<List<RadvRelease>>(emptyList())
    private val _installed = MutableStateFlow<InstalledDriver?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _refreshError = MutableStateFlow<String?>(null)
    private val _stale = MutableStateFlow(false)

    /** Every known release, newest first, with download state. */
    val releases: StateFlow<List<RadvRelease>> = _releases.asStateFlow()

    /** The one active driver, or null when none is installed. */
    val installed: StateFlow<InstalledDriver?> = _installed.asStateFlow()

    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Why the last [refresh] failed, or null. Cleared by a successful refresh. */
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    /** True when the current list came from the cache because the network was unavailable. */
    val stale: StateFlow<Boolean> = _stale.asStateFlow()

    init {
        root.mkdirs()
        packagesDir.mkdirs()
        migrateLegacyPackages()
        _installed.value = loadInstalled()
        // Keep download flags in step with the queue without every screen having to join the two.
        downloads.snapshot
            .onEach { reconcileAll() }
            .launchIn(scope)
    }

    // --- Queries ------------------------------------------------------------------------------

    val latest: RadvRelease? get() = _releases.value.firstOrNull { it.channel == ReleaseChannel.LATEST }

    fun release(tag: String): RadvRelease? = _releases.value.firstOrNull { it.tag == tag }

    /** The most recent download task for [release], if any. */
    fun taskFor(release: RadvRelease): DownloadTask? = downloads.taskForAsset(release.id)

    /** The downloaded zip for [release], or null when it is not on disk. */
    fun downloadedZip(release: RadvRelease): File? = zipFor(release).takeIf { it.isFile && it.length() > 0L }

    /** Zips on disk, newest first, whether or not the catalog knows them. */
    fun downloadedZips(): List<File> = packagesDir.listFiles()
        ?.filter { it.isFile && it.length() > 0L && it.name.endsWith(".zip", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    /** True when at least one release package has been downloaded. */
    fun hasDownloadedPackage(): Boolean = downloadedZips().isNotEmpty()

    /**
     * Library path of the active driver, or null when nothing is installed or the files are gone
     * (in which case the stale record is dropped).
     */
    fun activeLibraryPath(): String? {
        val current = _installed.value ?: return null
        if (File(current.libraryPath).isFile) return current.libraryPath
        Log.w(TAG, "Installed driver ${current.tag} is missing from disk; forgetting it")
        clearInstalledRecord()
        return null
    }

    // --- Refresh ------------------------------------------------------------------------------

    /** Fetches the release list. Failures are reported through [refreshError], never thrown. */
    suspend fun refresh(forceRefresh: Boolean = false) = mutex.withLock {
        _isRefreshing.value = true
        try {
            val feed = provider.fetch(forceRefresh)
            _releases.value = feed.releases.map(::reconcile)
            _stale.value = feed.stale
            _refreshError.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = when (error) {
                is GitHubFetchException -> if (error.httpCode > 0) "HTTP ${error.httpCode}: ${error.message}" else error.message
                is IOException -> error.message ?: "Network error"
                else -> error.message ?: error.javaClass.simpleName
            }
            _refreshError.value = message ?: "Couldn't load releases"
            Log.w(TAG, "Release refresh failed", error)
        } finally {
            _isRefreshing.value = false
        }
    }

    // --- Download -----------------------------------------------------------------------------

    /**
     * Queues the zip for [tag]. Returns the task, or null when the release is unknown or the zip
     * is already on disk.
     */
    suspend fun download(tag: String): DownloadTask? {
        val release = release(tag) ?: return null
        if (downloadedZip(release) != null) return null
        val dest = zipFor(release)
        return withContext(Dispatchers.IO) {
            downloads.enqueue(
                url = release.asset.downloadUrl,
                dest = dest,
                displayName = "${RadvReleaseProvider.DISPLAY_NAME} ${release.tag}",
                expectedSha256 = release.asset.sha256,
                assetId = release.id,
                recordKind = RecordKind.DRIVER,
            )
        }
    }

    /** Removes a downloaded zip. The active driver is unaffected: it was extracted elsewhere. */
    suspend fun deletePackage(tag: String): Boolean = withContext(Dispatchers.IO) {
        val release = release(tag) ?: return@withContext false
        downloads.cancelForAsset(release.id)
        val removed = zipFor(release).delete()
        reconcileAll()
        removed
    }

    // --- Installed record ---------------------------------------------------------------------

    internal fun zipFor(release: RadvRelease): File = File(packagesDir, sanitizeFileName(release.asset.name))

    internal fun activeDirFor(tag: String): File = File(root, "active/${sanitizeFileName(tag)}")

    internal fun writeInstalledRecord(record: InstalledDriver?) {
        if (record == null) {
            activeFile.delete()
        } else {
            runCatching { writeAtomic(activeFile, record.toJson().toString(2)) }
                .onFailure { logPersistFailure("active driver", it) }
        }
        _installed.value = record
    }

    private fun clearInstalledRecord() = writeInstalledRecord(null)

    private fun loadInstalled(): InstalledDriver? {
        val text = readTextOrNull(activeFile) ?: return null
        val record = runCatching { InstalledDriver.fromJson(JSONObject(text)) }
            .onFailure { Log.w(TAG, "active.json unreadable, ignoring", it) }
            .getOrNull() ?: return null
        if (!File(record.libraryPath).isFile) {
            Log.w(TAG, "Installed driver ${record.tag} has no library on disk; ignoring record")
            activeFile.delete()
            return null
        }
        return record
    }

    // --- Reconciliation -----------------------------------------------------------------------

    private fun reconcile(release: RadvRelease): RadvRelease {
        val zip = zipFor(release)
        return if (zip.isFile && zip.length() > 0L) {
            release.copy(isDownloaded = true, localPath = zip.absolutePath)
        } else {
            release.copy(isDownloaded = false, localPath = null)
        }
    }

    private fun reconcileAll() {
        val current = _releases.value
        if (current.isEmpty()) return
        val next = current.map(::reconcile)
        if (next != current) _releases.value = next
    }

    /** Earlier builds stored driver zips under `drivers/<owner>_<repo>/`; fold them into `packages/`. */
    private fun migrateLegacyPackages() {
        val legacy = File(root, sanitizeFileName("${RadvReleaseProvider.OWNER}/${RadvReleaseProvider.REPO}"))
        if (!legacy.isDirectory) return
        legacy.listFiles()?.forEach { file ->
            if (!file.isFile || file.name.endsWith(".partial")) {
                file.delete()
                return@forEach
            }
            val target = File(packagesDir, file.name)
            if (target.exists()) file.delete() else if (!file.renameTo(target)) {
                runCatching { file.copyTo(target, overwrite = true); file.delete() }
            }
        }
        legacy.delete()
    }

    companion object {
        private const val TAG = "DriverRepository"

        @Volatile
        private var instance: DriverRepository? = null

        @Synchronized
        fun get(context: Context): DriverRepository {
            instance?.let { return it }
            val app = context.applicationContext
            val created = DriverRepository(
                provider = RadvReleaseProvider(GitHubReleaseFetcher.get(app)),
                downloads = DownloadManager.get(app),
                root = File(app.filesDir, "drivers"),
            )
            instance = created
            return created
        }
    }
}
