package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.DriverFamily
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.nativebridge.AdrenoToolsBridge
import io.harbor.fable.nativebridge.GpuDetector
import io.harbor.fable.nativebridge.GpuIdentity
import io.harbor.fable.data.models.VulkanDevice
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
            is Installed -> if (replaced != null && (replaced.tag != driver.tag || replaced.family != driver.family)) {
                "Installed ${driver.family.displayName} ${driver.tag}, replacing ${replaced.family.displayName} ${replaced.tag}"
            } else {
                "Installed ${driver.family.displayName} ${driver.tag}"
            }
            is Failed -> reason
            Busy -> "Another install is running"
        }
}

/**
 * Owns everything about the custom Vulkan drivers: the release lists of both driver families —
 * RADV Xclipse ([RadvReleaseProvider], for Samsung Xclipse GPUs) and Turnip
 * ([TurnipReleaseProvider], for Qualcomm Adreno GPUs) — the downloaded zips, and the single
 * installed (active) driver.
 *
 * Which family is *recommended* follows the GPU ([GpuDetector]): Turnip on Adreno, RADV Xclipse
 * otherwise; [latest] (what setup installs and the Drivers screen offers first) is the newest
 * stable release of the [recommendedFamily]. Turnip is only offered on a positively identified
 * Adreno ([GpuIdentity.offers]): on Xclipse, Mali or an unidentified GPU it is neither listed
 * ([visibleReleases]) nor installable, and a Turnip install left over from a misdetection is
 * removed once the system Vulkan driver confirms a non-Adreno GPU ([refineGpu]).
 *
 * Releases are addressed by [RadvRelease.id] (`radv-xclipse/<tag>`, `turnip/<owner>/<repo>/<tag>`);
 * the public methods also accept a bare tag, resolved within the recommended family first.
 *
 * Layout under `filesDir/drivers/`:
 * ```
 * packages/<asset>.zip          downloaded RADV packages (one per version, kept for rollback)
 * packages/turnip/<asset>.zip   downloaded Turnip packages
 * active/<family>-<tag>/        the extracted driver that Wine loads; at most one at any time
 * active.json                   which release is installed (and its family) and where its library is
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
    private val turnipProvider: TurnipReleaseProvider? = null,
    private val gpuDetector: () -> GpuIdentity = { GpuDetector.detect() },
) {
    private val mutex = Mutex()
    private val installMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packagesDir = File(root, "packages")
    private val turnipPackagesDir = File(packagesDir, "turnip")
    private val activeRoot = File(root, "active")
    private val activeFile = File(root, "active.json")

    private val _releases = MutableStateFlow<List<RadvRelease>>(emptyList())
    private val _installed = MutableStateFlow<InstalledDriver?>(null)
    private val _installing = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _refreshError = MutableStateFlow<String?>(null)
    private val _stale = MutableStateFlow(false)
    private val _gpu = MutableStateFlow(runCatching(gpuDetector).getOrElse { GpuIdentity(io.harbor.fable.nativebridge.GpuKind.UNKNOWN, null, null, "detection failed") })

    /** What GPU this device has, as far as driver choice goes; refined by [refineGpu]. */
    val gpu: StateFlow<GpuIdentity> = _gpu.asStateFlow()

    /** The family to recommend on this device: Turnip on Adreno, RADV Xclipse otherwise. */
    val recommendedFamily: DriverFamily get() = _gpu.value.recommendedFamily

    /**
     * Every known release with download state: the recommended family's releases first (newest
     * first), then the other family's.
     */
    val releases: StateFlow<List<RadvRelease>> = _releases.asStateFlow()

    /** The one active driver, or null when none is installed. */
    val installed: StateFlow<InstalledDriver?> = _installed.asStateFlow()

    /** [RadvRelease.id] of the release being extracted right now, or null. */
    val installing: StateFlow<String?> = _installing.asStateFlow()

    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Why the last [refresh] failed, or null. Cleared by a successful refresh. */
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    /** True when the current list came from the cache because the network was unavailable. */
    val stale: StateFlow<Boolean> = _stale.asStateFlow()

    init {
        root.mkdirs()
        packagesDir.mkdirs()
        turnipPackagesDir.mkdirs()
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

    /** The newest stable release of the [recommendedFamily]: what setup installs. */
    val latest: RadvRelease? get() = latestFor(recommendedFamily)

    fun latestFor(family: DriverFamily): RadvRelease? =
        _releases.value.firstOrNull { it.family == family && it.channel == ReleaseChannel.LATEST }

    /**
     * The release with id [key], or (for callers that still pass a tag) the release with that
     * tag, preferring the recommended family.
     */
    fun release(key: String): RadvRelease? {
        val all = _releases.value
        return all.firstOrNull { it.id == key }
            ?: all.firstOrNull { it.tag == key && it.family == recommendedFamily }
            ?: all.firstOrNull { it.tag == key }
    }

    /** True when the installed driver is of the family this GPU needs (always true for unknown GPUs). */
    fun installedMatchesGpu(): Boolean {
        val current = _installed.value ?: return false
        return _gpu.value.matches(current.family)
    }

    /**
     * Refines GPU detection with the system Vulkan driver's primary device (more reliable than
     * sysfs, which SELinux may hide). Re-sorts the list when the recommendation changes.
     */
    fun refineGpu(device: VulkanDevice?) {
        val refined = runCatching { GpuDetector.refine(device) }.getOrNull() ?: return
        if (refined != _gpu.value) {
            val familyChanged = refined.recommendedFamily != _gpu.value.recommendedFamily
            _gpu.value = refined
            if (familyChanged) _releases.value = order(_releases.value)
        }
        // Turnip cannot drive anything but Adreno. A Turnip install on a GPU the system driver
        // has now identified as something else (left by an earlier misdetection) only breaks
        // Vulkan, so it goes; the Drivers screen and setup then offer RADV Xclipse.
        val current = _installed.value
        val vulkanConclusive = device != null && runCatching { GpuDetector.fromVulkan(device).isConclusive }.getOrDefault(false)
        if (vulkanConclusive && current != null && !refined.offers(current.family)) {
            Log.i(TAG, "Removing ${current.family.displayName} ${current.tag}: not usable on ${refined.kind.label} (${refined.evidence})")
            scope.launch { uninstall() }
        }
    }

    /** The most recent download task for [release], if any. */
    fun taskFor(release: RadvRelease): DownloadTask? = downloads.taskForAsset(release.id)

    /** The downloaded zip for [release], or null when it is not on disk. */
    fun downloadedZip(release: RadvRelease): File? = zipFor(release).takeIf { it.isFile && it.length() > 0L }

    /** Zips on disk (both families), newest first, whether or not the catalog knows them. */
    fun downloadedZips(): List<File> = listOf(packagesDir, turnipPackagesDir)
        .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
        .filter { it.isFile && it.length() > 0L && it.name.endsWith(".zip", ignoreCase = true) }
        .sortedByDescending { it.lastModified() }

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

    /**
     * Fetches both release lists. Failures are reported through [refreshError], never thrown;
     * one family failing keeps the other's list (and that family's previous list, if any).
     * [refreshError] is set when the recommended family could not be loaded.
     */
    suspend fun refresh(forceRefresh: Boolean = false) = mutex.withLock {
        _isRefreshing.value = true
        try {
            val previous = _releases.value
            val radv = fetchFamily(DriverFamily.RADV_XCLIPSE) { provider.fetch(forceRefresh) }
            val turnip = turnipProvider?.let { p -> fetchFamily(DriverFamily.TURNIP) { p.fetch(forceRefresh) } }
            val radvReleases = radv.feed?.releases ?: previous.filter { it.family == DriverFamily.RADV_XCLIPSE }
            val turnipReleases = turnip?.feed?.releases ?: previous.filter { it.family == DriverFamily.TURNIP }
            _releases.value = order((radvReleases + turnipReleases).map(::reconcile))
            _stale.value = (radv.feed?.stale ?: false) || (turnip?.feed?.stale ?: false)
            val recommendedError = when (recommendedFamily) {
                DriverFamily.RADV_XCLIPSE -> radv.error
                DriverFamily.TURNIP -> turnip?.error ?: radv.error.takeIf { turnipProvider == null }
            }
            _refreshError.value = recommendedError
                ?: if (radv.error != null && (turnip == null || turnip.error != null)) radv.error else null
        } finally {
            _isRefreshing.value = false
        }
    }

    private class FamilyFetch(val feed: RadvReleaseFeed?, val error: String?)

    private suspend fun fetchFamily(family: DriverFamily, fetch: suspend () -> RadvReleaseFeed): FamilyFetch = try {
        FamilyFetch(fetch(), null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val message = when (error) {
            is GitHubFetchException -> if (error.httpCode > 0) "HTTP ${error.httpCode}: ${error.message}" else error.message
            is IOException -> error.message ?: "Network error"
            else -> error.message ?: error.javaClass.simpleName
        }
        Log.w(TAG, "${family.displayName} release refresh failed", error)
        FamilyFetch(null, message ?: "Couldn't load ${family.displayName} releases")
    }

    /** Recommended family first; each family keeps its provider's order (newest first). */
    private fun order(releases: List<RadvRelease>): List<RadvRelease> {
        val recommended = recommendedFamily
        return releases.filter { it.family == recommended } + releases.filter { it.family != recommended }
    }

    // --- Download -----------------------------------------------------------------------------

    /**
     * Queues the zip for [key] (a release id or tag). Returns the task, or null when the release
     * is unknown or the zip is already on disk.
     */
    suspend fun download(key: String): DownloadTask? {
        val release = release(key) ?: return null
        if (!_gpu.value.offers(release.family)) return null
        if (downloadedZip(release) != null) return null
        val dest = zipFor(release)
        return withContext(Dispatchers.IO) {
            downloads.enqueue(
                url = release.asset.downloadUrl,
                dest = dest,
                displayName = release.label,
                expectedSha256 = release.asset.sha256,
                assetId = release.id,
                recordKind = RecordKind.DRIVER,
            )
        }
    }

    /** Removes a downloaded zip. The active driver is unaffected: it was extracted elsewhere. */
    suspend fun deletePackage(key: String): Boolean = withContext(Dispatchers.IO) {
        val release = release(key) ?: return@withContext false
        downloads.cancelForAsset(release.id)
        val removed = zipFor(release).delete()
        reconcileAll()
        removed
    }

    // --- Install / uninstall ------------------------------------------------------------------

    /**
     * Makes [key] (a release id or tag) the active driver, whichever family it is: validates the downloaded zip, removes the previously
     * installed driver (files and record), extracts the new package through the native loader
     * and records it. Returns [DriverInstallResult.Failed] when the zip is missing or rejected;
     * in that case the previous driver is already gone, which is reported in the reason.
     */
    suspend fun install(key: String): DriverInstallResult {
        val release = release(key) ?: return DriverInstallResult.Failed("Unknown release $key")
        if (!_gpu.value.offers(release.family)) {
            return DriverInstallResult.Failed("${release.family.displayName} is only for ${release.family.targetGpus}")
        }
        val zip = downloadedZip(release) ?: return DriverInstallResult.Failed("Download ${release.label} first")
        if (!installMutex.tryLock()) return DriverInstallResult.Busy
        _installing.value = release.id
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
        val dir = activeDirFor(release)
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
            mesaVersion = release.mesaVersion
                ?: RadvReleaseProvider.parseMesaVersion(meta?.stringOrNull("name"))
                ?: TurnipReleaseProvider.parseMesaVersion(meta?.stringOrNull("name")),
            installedAt = System.currentTimeMillis(),
            family = release.family,
            releaseId = release.id,
        )
        writeInstalledRecord(record)
        Log.i(TAG, "Active driver is now ${release.label} (${record.libraryPath})")
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
     * Downloads [key] (a release id or tag) when the zip is not on disk, then installs it as the
     * active driver once the download completes. Returns the download task when one was started,
     * or null when the install started straight away (or the release is already active).
     */
    suspend fun downloadAndInstall(key: String): DownloadTask? {
        val release = release(key) ?: return null
        if (!_gpu.value.offers(release.family)) return null
        if (_installed.value?.isFrom(release) == true) return null
        if (downloadedZip(release) != null) {
            scope.launch { install(release.id) }
            return null
        }
        val task = download(release.id) ?: return null
        scope.launch {
            val finished = downloads.await(task.id)
            if (finished.status == DownloadStatus.COMPLETED) install(release.id)
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

    internal fun zipFor(release: RadvRelease): File = when (release.family) {
        DriverFamily.RADV_XCLIPSE -> File(packagesDir, sanitizeFileName(release.asset.name))
        // Repositories reuse asset names across releases ("turnip_a8xx.zip"), so the tag is part
        // of the file name.
        DriverFamily.TURNIP -> File(turnipPackagesDir, sanitizeFileName("${release.tag}-${release.asset.name}"))
    }

    internal fun activeDirFor(release: RadvRelease): File = when (release.family) {
        DriverFamily.RADV_XCLIPSE -> File(root, "active/${sanitizeFileName(release.tag)}")
        DriverFamily.TURNIP -> File(root, "active/${sanitizeFileName("turnip-${release.tag}")}")
    }

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

        /**
         * The releases the Drivers screen lists on [gpu]: everything on Adreno, everything but
         * Turnip elsewhere (Xclipse, Mali, unidentified).
         */
        fun visibleReleases(releases: List<RadvRelease>, gpu: GpuIdentity): List<RadvRelease> =
            releases.filter { gpu.offers(it.family) }
        private val VERSION_TRIPLET = Regex("""\d+\.\d+\.\d+""")

        @Volatile
        private var instance: DriverRepository? = null

        @Synchronized
        fun get(context: Context): DriverRepository {
            instance?.let { return it }
            val app = context.applicationContext
            val fetcher = GitHubReleaseFetcher.get(app)
            val created = DriverRepository(
                provider = RadvReleaseProvider(fetcher),
                downloads = DownloadManager.get(app),
                root = File(app.filesDir, "drivers"),
                turnipProvider = TurnipReleaseProvider(fetcher, preferA8xx = { GpuDetector.detect().isAdreno8xx }),
            )
            instance = created
            return created
        }
    }
}
