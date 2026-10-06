package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetType
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The packages setup knows about. The four [required] ones are needed before anything can run;
 * FEX is an optional alternative to Box64 that containers can opt into, so it is tracked (the
 * Assets tab can show whether a build exists) but never blocks setup or is auto-downloaded.
 */
enum class RecommendedKind(val label: String, val required: Boolean = true) {
    WINE("Wine"),
    BOX64("Box64"),
    DRIVER("RADV Xclipse"),
    DXVK("DXVK"),
    FEX("FEX", required = false),
}

enum class RecommendedStatus {
    /** At least one package of this kind is on disk. */
    INSTALLED,

    /** The recommended package is being downloaded right now. */
    DOWNLOADING,

    /** The catalog has a package to download. */
    AVAILABLE,

    /** The catalog has nothing for this kind (not refreshed yet, offline, or no matching release). */
    UNAVAILABLE,
}

data class RecommendedItem(
    val kind: RecommendedKind,
    val status: RecommendedStatus,
    val sizeBytes: Long = 0,
    /** 0..1 while [status] is [RecommendedStatus.DOWNLOADING]. */
    val progress: Float = 0f,
)

/**
 * Where the recommended downloads stand. Empty until the first catalog/disk check. Everything
 * derived here (what is pending, progress, whether setup is needed) only counts the
 * [RecommendedKind.required] items; optional kinds such as FEX ride along in [items] only.
 */
data class SetupState(val items: List<RecommendedItem> = emptyList()) {
    /** The items setup is responsible for. */
    val required: List<RecommendedItem> get() = items.filter { it.kind.required }

    /** True while something required can still be downloaded or is on its way. */
    val needsSetup: Boolean
        get() = required.any { it.status == RecommendedStatus.AVAILABLE || it.status == RecommendedStatus.DOWNLOADING }

    val isDownloading: Boolean get() = required.any { it.status == RecommendedStatus.DOWNLOADING }

    /** True on a fresh install: nothing required has been downloaded yet. */
    val nothingInstalled: Boolean
        get() = required.isNotEmpty() && required.none { it.status == RecommendedStatus.INSTALLED }

    /** Required items that still need to be downloaded or are downloading. */
    val pending: List<RecommendedItem>
        get() = required.filter { it.status == RecommendedStatus.AVAILABLE || it.status == RecommendedStatus.DOWNLOADING }

    val unavailable: List<RecommendedItem> get() = required.filter { it.status == RecommendedStatus.UNAVAILABLE }

    val pendingBytes: Long get() = pending.sumOf { it.sizeBytes }

    /** Share of the downloadable required items that is done, 0..1. */
    val progress: Float
        get() {
            val tracked = required.filter { it.status != RecommendedStatus.UNAVAILABLE }
            if (tracked.isEmpty()) return 0f
            return tracked.sumOf { item ->
                when (item.status) {
                    RecommendedStatus.INSTALLED -> 1.0
                    RecommendedStatus.DOWNLOADING -> item.progress.toDouble()
                    else -> 0.0
                }
            }.toFloat() / tracked.size
        }
}

/** Outcome of [SetupManager.installRecommended]. */
data class SetupResult(
    val started: List<RecommendedKind>,
    val alreadyInstalled: List<RecommendedKind>,
    val unavailable: List<RecommendedKind>,
    val failed: List<RecommendedKind>,
    val catalogReachable: Boolean,
    /** True when another [SetupManager.installRecommended] call was still running. */
    val busy: Boolean = false,
) {
    /** One line for a snackbar. */
    val message: String
        get() = when {
            busy -> "Setup is already running"
            started.isNotEmpty() -> buildString {
                append("Downloading ").append(started.joinToString(", ") { it.label })
                if (unavailable.isNotEmpty()) {
                    append(". No build available for ").append(unavailable.joinToString(", ") { it.label })
                }
            }
            !catalogReachable && unavailable.isNotEmpty() -> "Couldn't reach the catalog. Check your connection"
            failed.isNotEmpty() -> "Couldn't start the download for ${failed.joinToString(", ") { it.label }}"
            unavailable.isNotEmpty() -> "No build available for ${unavailable.joinToString(", ") { it.label }}"
            else -> "Everything is already downloaded"
        }
}

/**
 * First-run setup. Knows which catalog entries are the recommended ones, reports whether they
 * are on disk, and queues the missing ones on the download manager (progress shows in the
 * Assets list and the download notification).
 *
 * Wine, Box64 and DXVK come from the asset catalog; the graphics driver is the latest RADV
 * Xclipse release from [DriverRepository], which has its own feed and install lifecycle.
 */
class SetupManager internal constructor(
    private val assets: AssetRepository,
    private val drivers: DriverRepository,
    private val downloads: DownloadManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _installing = MutableStateFlow(false)

    /** True while [installRecommended] is refreshing the catalog and queueing downloads. */
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    /** Live view of the recommended downloads, recomputed whenever the catalog or a download changes. */
    val state: StateFlow<SetupState> = combine(
        assets.assets,
        drivers.releases,
        drivers.installed,
        drivers.installing,
        downloads.snapshot,
    ) { entries, releases, installed, installing, snapshot ->
        compute(entries, releases, installed, installing, snapshot)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SetupState())

    /**
     * Refreshes the catalog, then downloads the latest Wine (amd64), Box64, RADV Xclipse and DXVK
     * packages that are not on disk yet. The driver is installed as the active driver as soon as
     * its download completes.
     */
    suspend fun installRecommended(): SetupResult {
        if (!_installing.compareAndSet(false, true)) {
            return SetupResult(emptyList(), emptyList(), emptyList(), emptyList(), catalogReachable = true, busy = true)
        }
        try {
            assets.refresh()
            drivers.refresh()
            val picks = pickRecommended(assets.assets.value, drivers.releases.value)
            val started = mutableListOf<RecommendedKind>()
            val installed = mutableListOf<RecommendedKind>()
            val unavailable = mutableListOf<RecommendedKind>()
            val failed = mutableListOf<RecommendedKind>()
            for (kind in RecommendedKind.entries) {
                // Optional kinds (FEX) are a per-container choice, not part of first-run setup.
                if (!kind.required) continue
                if (isInstalled(kind, drivers.installed.value)) {
                    installed += kind
                    continue
                }
                val pick = picks[kind]
                if (pick == null) {
                    unavailable += kind
                    continue
                }
                try {
                    if (pick.isDriver) {
                        // Either queues the download (install follows) or starts the install now.
                        drivers.downloadAndInstall(pick.id)
                        started += kind
                    } else {
                        val task = assets.download(pick.id)
                        if (task != null) started += kind else installed += kind
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Could not queue ${kind.label}", error)
                    failed += kind
                }
            }
            val reachable = (assets.assets.value.isNotEmpty() || assets.refreshErrors.value.isEmpty()) &&
                (drivers.releases.value.isNotEmpty() || drivers.refreshError.value == null)
            return SetupResult(started, installed, unavailable, failed, reachable)
        } finally {
            _installing.value = false
        }
    }

    private fun compute(
        entries: List<AssetEntry>,
        releases: List<RadvRelease>,
        installedDriver: InstalledDriver?,
        installingDriver: String?,
        snapshot: DownloadSnapshot,
    ): SetupState {
        val picks = pickRecommended(entries, releases)
        val items = RecommendedKind.entries.map { kind ->
            val pick = picks[kind]
            val task = pick?.let { p ->
                snapshot.tasks.filter { it.assetId == p.taskId }.maxByOrNull { it.updatedAt }
            }
            when {
                isInstalled(kind, installedDriver) -> RecommendedItem(kind, RecommendedStatus.INSTALLED)
                // Extraction after the download: nearly done, but not yet usable.
                kind == RecommendedKind.DRIVER && installingDriver != null -> RecommendedItem(
                    kind = kind,
                    status = RecommendedStatus.DOWNLOADING,
                    sizeBytes = pick?.sizeBytes ?: 0L,
                    progress = 1f,
                )
                pick != null && task != null && task.keepsServiceAlive -> RecommendedItem(
                    kind = kind,
                    status = RecommendedStatus.DOWNLOADING,
                    sizeBytes = pick.sizeBytes,
                    progress = if (task.status == DownloadStatus.VERIFYING) 1f else task.progressFraction,
                )
                pick != null -> RecommendedItem(kind, RecommendedStatus.AVAILABLE, pick.sizeBytes)
                else -> RecommendedItem(kind, RecommendedStatus.UNAVAILABLE)
            }
        }
        return SetupState(items)
    }

    /**
     * "Installed" means usable: a package on disk for Wine, Box64 and DXVK, and an extracted,
     * active driver for the graphics driver (a downloaded zip alone is not enough).
     */
    private fun isInstalled(kind: RecommendedKind, installedDriver: InstalledDriver?): Boolean = when (kind) {
        RecommendedKind.WINE -> assets.downloadedFiles(AssetType.WINE).isNotEmpty()
        RecommendedKind.BOX64 -> assets.downloadedFiles(AssetType.BOX64).isNotEmpty()
        RecommendedKind.DXVK -> assets.downloadedFiles(AssetType.DXVK).isNotEmpty()
        RecommendedKind.DRIVER -> installedDriver != null
        RecommendedKind.FEX -> assets.downloadedFiles(AssetType.FEX).isNotEmpty()
    }

    /**
     * [id] is an asset id, or a release tag when [isDriver]; [taskId] is the id download tasks
     * carry in [DownloadTask.assetId] (differs from [id] for driver releases).
     */
    private class Pick(val id: String, val sizeBytes: Long, val isDriver: Boolean, val taskId: String = id)

    private fun pickRecommended(entries: List<AssetEntry>, releases: List<RadvRelease>): Map<RecommendedKind, Pick> {
        val picks = LinkedHashMap<RecommendedKind, Pick>()
        entries.filter { it.type == AssetType.WINE }
            .minByOrNull { wineRank(it.name) }
            ?.let { picks[RecommendedKind.WINE] = Pick(it.id, it.fileSizeBytes, isDriver = false) }
        entries.filter { it.type == AssetType.BOX64 }
            .minByOrNull { box64Rank(it.name) }
            ?.let { picks[RecommendedKind.BOX64] = Pick(it.id, it.fileSizeBytes, isDriver = false) }
        entries.firstOrNull { it.type == AssetType.DXVK && !it.name.contains("native", ignoreCase = true) }
            ?.let { picks[RecommendedKind.DXVK] = Pick(it.id, it.fileSizeBytes, isDriver = false) }
        entries.filter { it.type == AssetType.FEX }
            .minByOrNull { box64Rank(it.name) }
            ?.let { picks[RecommendedKind.FEX] = Pick(it.id, it.fileSizeBytes, isDriver = false) }
        releases.firstOrNull { it.channel == ReleaseChannel.LATEST }
            ?.let { picks[RecommendedKind.DRIVER] = Pick(it.tag, it.asset.sizeBytes, isDriver = true, taskId = it.id) }
        return picks
    }

    companion object {
        private const val TAG = "SetupManager"

        /** Lower is better; the same ranking the catalog uses to keep one Wine build per version. */
        internal fun wineRank(name: String): Int = CatalogPolicy.wineRank(name)

        private val NIGHTLY_MARKER = Regex("""(?i)nightly|-[0-9a-f]{7,}(?=[.-])""")
        private val VERSION_NUMBER = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        /**
         * Lower is better. Also used for FEX packages.
         *
         * Tagged releases beat nightlies (a `-<git hash>` or "nightly" in the name), and within
         * a tier the highest version number wins, so a source that lists every version it ever
         * published (`box64-0.3.2-…` through `box64-0.4.2-…`) recommends the newest one.
         */
        internal fun box64Rank(name: String): Long {
            val tier = if (NIGHTLY_MARKER.containsMatchIn(name)) 1L else 0L
            val version = VERSION_NUMBER.find(name)?.let { match ->
                val (major, minor, patch) = match.destructured
                major.toLong() * 1_000_000L + minor.toLong() * 1_000L + (patch.toLongOrNull() ?: 0L)
            } ?: 0L
            return tier * 10_000_000_000L + (9_999_999_999L - version.coerceAtMost(9_999_999_999L))
        }

        @Volatile
        private var instance: SetupManager? = null

        @Synchronized
        fun get(context: Context): SetupManager {
            instance?.let { return it }
            val app = context.applicationContext
            val created = SetupManager(AssetRepository.get(app), DriverRepository.get(app), DownloadManager.get(app))
            instance = created
            return created
        }
    }
}
