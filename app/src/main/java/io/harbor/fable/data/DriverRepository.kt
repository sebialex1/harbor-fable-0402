package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.nativebridge.AdrenoToolsBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Outcome of [DriverRepository.install]. */
sealed interface DriverInstallResult {
    data class Installed(val driver: InstalledDriver, val replaced: InstalledDriver?) : DriverInstallResult

    data class Failed(val reason: String) : DriverInstallResult

    /** Another install was still running. */
    data object Busy : DriverInstallResult

    /** One line for a snackbar. */
    val message: String
        get() = when (this) {
            is Installed -> if (replaced != null && replaced.tag != driver.tag) {
                "Installed ${driver.tag}, replacing ${replaced.tag}"
            } else {
                "Installed ${driver.tag}"
            }
            is Failed -> reason
            Busy -> "Another driver is still being installed"
        }
}

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
 * Exactly one driver can be active. [install] removes whatever is in `active/` (and the record)
 * before it extracts the new package, so a failed install never leaves two drivers behind, and
 * [uninstall] returns to the "no driver" state. Download status is reconciled against the files
 * on disk and the [DownloadManager] queue, so the list is correct offline and before the first
 * refresh.
 */
class DriverRepository internal constructor(
    private val provider: RadvReleaseProvider,
    private val downloads: DownloadManager,
    private val root: File,
) {
    private val mutex = Mutex()
    private val installMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packagesDir = File(root, "packages")
    private val activeRoot = File(root, "active")
    private val activeFile = File(root, "active.json")

    private val _releases = MutableStateFlow<List<RadvRelease>>(emptyList())
    private val _installed = MutableStateFlow<InstalledDriver?>(null)
    private val _installing = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _refreshError = MutableStateFlow<String?>(null)
    private val _stale = MutableStateFlow(false)

    /** Every known release, newest first, with download state. */
    val releases: StateFlow<List<RadvRelease>> = _releases.asStateFlow()

    /** The one active driver, or null when none is installed. */
    val installed: StateFlow<InstalledDriver?> = _installed.asStateFlow()

    /** Tag of the release being extracted right now, or null. */
    val installing: StateFlow<String?> = _installing.asStateFlow()

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
        // A crash between "remove old" and "extract new" can leave half a driver behind.
        if (_installed.value == null && activeRoot.exists()) removeActiveFiles()
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

    // --- Install / uninstall ------------------------------------------------------------------

    /**
     * Makes [tag] the active driver: validates the downloaded zip, removes the previously
     * installed driver (files and record), extracts the new package through the native loader
     * and records it. Returns [DriverInstallResult.Failed] when the zip is missing or rejected;
     * in that case the previous driver is already gone, which is reported in the reason.
     */
    suspend fun install(tag: String): DriverInstallResult {
        val release = release(tag) ?: return DriverInstallResult.Failed("Unknown release $tag")
        val zip = downloadedZip(release) ?: return DriverInstallResult.Failed("Download ${release.tag} first")
        if (!installMutex.tryLock()) return DriverInstallResult.Busy
        _installing.value = tag
        try {
            return withContext(Dispatchers.IO) { installLocked(release, zip) }
        } finally {
            _installing.value = null
            installMutex.unlock()
        }
    }

    private fun installLocked(release: RadvRelease, zip: File): DriverInstallResult {
        val previous = _installed.value
        // 1. Validate before touching the active driver, so a bad package cannot remove a good one.
        val problem = try {
            AdrenoToolsBridge.validateDriverZip(zip.absolutePath)
        } catch (error: UnsatisfiedLinkError) {
            return DriverInstallResult.Failed("The native driver loader isn't available on this device")
        }
        if (problem != null) return DriverInstallResult.Failed("${release.tag} was rejected: $problem")

        // 2. One active driver: clear the previous install and its record first.
        writeInstalledRecord(null)
        removeActiveFiles()

        // 3. Extract into a fresh directory.
        val dir = activeDirFor(release.tag)
        val library = runCatching { AdrenoToolsBridge.installDriver(zip.absolutePath, dir.absolutePath) }
            .onFailure { Log.w(TAG, "installDriver threw for ${release.tag}", it) }
            .getOrNull()
        if (library.isNullOrBlank() || !File(library).isFile) {
            removeActiveFiles()
            val suffix = if (previous != null) " ${previous.tag} was removed; install a release again." else ""
            return DriverInstallResult.Failed("Couldn't extract ${release.tag}.$suffix")
        }

        // 4. Record what native accepted (fable-driver.json is written by the extractor).
        val meta = readTextOrNull(File(dir, "fable-driver.json"))?.let { runCatching { JSONObject(it) }.getOrNull() }
        val driverVersion = meta?.stringOrNull("driverVersion")
        val record = InstalledDriver(
            tag = release.tag,
            assetName = release.asset.name,
            libraryPath = library,
            installDir = dir.absolutePath,
            name = meta?.stringOrNull("name"),
            driverVersion = driverVersion,
            vulkanVersion = meta?.stringOrNull("vulkan")?.takeIf { it.isNotBlank() && it != "0.0.0" }
                ?: driverVersion?.let { VERSION_TRIPLET.find(it)?.value },
            mesaVersion = release.mesaVersion ?: RadvReleaseProvider.parseMesaVersion(meta?.stringOrNull("name")),
            installedAt = System.currentTimeMillis(),
        )
        writeInstalledRecord(record)
        Log.i(TAG, "Active driver is now ${record.tag} (${record.libraryPath})")
        return DriverInstallResult.Installed(record, previous)
    }

    /** Removes the active driver and its files. Returns the driver that was removed, or null. */
    suspend fun uninstall(): InstalledDriver? {
        installMutex.withLock {
            return withContext(Dispatchers.IO) {
                val previous = _installed.value
                writeInstalledRecord(null)
                removeActiveFiles()
                previous
            }
        }
    }

    /**
     * Downloads [tag] when the zip is not on disk, then installs it as the active driver once
     * the download completes. Returns the download task when one was started, or null when the
     * install started straight away (or [tag] is already active).
     */
    suspend fun downloadAndInstall(tag: String): DownloadTask? {
        val release = release(tag) ?: return null
        if (_installed.value?.tag == tag) return null
        if (downloadedZip(release) != null) {
            scope.launch { install(tag) }
            return null
        }
        val task = download(tag) ?: return null
        scope.launch {
            val finished = downloads.await(task.id)
            if (finished.status == DownloadStatus.COMPLETED) install(tag)
        }
        return task
    }

    /** Deletes `active/` entirely plus the legacy `runtime/drivers/` extraction directory. */
    private fun removeActiveFiles() {
        if (activeRoot.exists() && !activeRoot.deleteRecursively()) {
            Log.w(TAG, "Could not fully remove ${activeRoot.path}")
        }
        val legacy = File(root.parentFile, "runtime/drivers")
        if (legacy.exists()) legacy.deleteRecursively()
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
        private val VERSION_TRIPLET = Regex("""\d+\.\d+\.\d+""")

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
