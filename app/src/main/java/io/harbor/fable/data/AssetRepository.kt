package io.harbor.fable.data

import android.content.Context
import android.net.Uri
import android.util.Log
import io.harbor.fable.data.models.AssetType
import io.harbor.fable.data.models.AssetSource
import io.harbor.fable.data.models.DriverPackage
import io.harbor.fable.data.models.AssetEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One GitHub release source in the catalog.
 *
 * [assetGlobs] filters the release assets. An empty list keeps every non-
 * checksum asset. [displayName] is the user-facing label; [type] determines
 * which [AssetType] the resulting [AssetEntry] records get.
 */
data class CatalogSource(
    val owner: String,
    val repo: String,
    val displayName: String,
    val type: AssetType,
    val assetGlobs: List<String> = emptyList(),
    val notes: String? = null,
) {
    val slug: String get() = "$owner/$repo"
}

/**
 * In-memory asset catalog with search, type filter, local import, and download
 * status tracking.
 *
 * The catalog is seeded from [defaultCatalog] — the GitHub release sources the
 * app knows about. [refresh] fetches the latest release for each source via
 * [GitHubReleaseFetcher] and builds [AssetEntry] records from the filtered
 * assets. [download] enqueues a transfer through [DownloadManager].
 *
 * Locally imported files ([importLocal]) are stored as [AssetSource.LOCAL_IMPORT]
 * entries with no remote URL.
 *
 * Download status is reconciled from [DownloadManager] on every access to
 * [assets] so the UI always sees whether a file is on disk.
 */
class AssetRepository internal constructor(
    private val appContext: Context,
    private val fetcher: GitHubReleaseFetcher,
    private val downloadManager: DownloadManager,
    private val catalogFile: File,
    private val assetsRoot: File,
    initialCatalog: List<CatalogSource> = defaultCatalog,
) {
    private val mutex = Mutex()

    private val catalogSources = LinkedHashMap<String, CatalogSource>().apply {
        initialCatalog.forEach { put(it.slug, it) }
    }

    private val entriesById = LinkedHashMap<String, AssetEntry>()
    private val driversById = LinkedHashMap<String, DriverPackage>()
    private val errors = LinkedHashMap<String, String>()

    private val _assets = MutableStateFlow<List<AssetEntry>>(emptyList())
    private val _drivers = MutableStateFlow<List<DriverPackage>>(emptyList())
    private val _catalog = MutableStateFlow(catalogSources.values.toList())
    private val _refreshErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _isRefreshing = MutableStateFlow(false)

    /** All known assets, newest first. */
    val assets: StateFlow<List<AssetEntry>> = _assets.asStateFlow()

    /** All known driver packages. */
    val drivers: StateFlow<List<DriverPackage>> = _drivers.asStateFlow()

    /** Catalog sources. */
    val catalog: StateFlow<List<CatalogSource>> = _catalog.asStateFlow()

    /** Per-source errors from the last [refresh]. Cleared on a successful refresh. */
    val refreshErrors: StateFlow<Map<String, String>> = _refreshErrors.asStateFlow()

    /** True while [refresh] is running. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadCatalog()
        assetsRoot.mkdirs()
    }

    // --- Catalog management ------------------------------------------------

    /** Returns the default catalog shipped with the app. */
    fun listCatalog(): List<CatalogSource> = catalogSources.values.toList()

    /** Adds a source to the catalog. Returns the added source. */
    suspend fun addCatalogSource(source: CatalogSource): CatalogSource = mutex.withLock {
        withContext(Dispatchers.IO) {
            catalogSources[source.slug] = source
            _catalog.value = catalogSources.values.toList()
            persistCatalog()
            source
        }
    }

    /** Removes a source from the catalog by slug. */
    suspend fun removeCatalogSource(slug: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val removed = catalogSources.remove(slug) != null
            if (removed) {
                _catalog.value = catalogSources.values.toList()
                persistCatalog()
            }
            removed
        }
    }

    // --- Refresh -----------------------------------------------------------

    /**
     * Fetches the latest release for every catalog source and rebuilds the
     * asset list. Sources that fail are recorded in [refreshErrors]; a partial
     * refresh still populates whatever succeeded.
     */
    suspend fun refresh(forceRefresh: Boolean = false) = mutex.withLock {
        _isRefreshing.value = true
        try {
            val newErrors = LinkedHashMap<String, String>()
            val newEntries = LinkedHashMap<String, AssetEntry>()
            val newDrivers = LinkedHashMap<String, DriverPackage>()

            for (source in catalogSources.values) {
                try {
                    val cached = fetcher.fetchLatestOrCached(source.owner, source.repo, forceRefresh)
                    val release = cached.release
                    val filtered = fetcher.filterAssets(release, source.assetGlobs)

                    for (asset in filtered) {
                        val entry = AssetEntry(
                            id = "${source.slug}/${asset.name}",
                            name = asset.name,
                            version = release.tagName,
                            type = source.type,
                            downloadUrl = asset.downloadUrl,
                            source = AssetSource.GITHUB_RELEASE,
                            sourceRepo = source.slug,
                            fileSizeBytes = asset.sizeBytes,
                            sha256 = asset.sha256,
                        ).let { reconcileDownloadStatus(it) }
                        newEntries[entry.id] = entry
                    }

                    if (source.type == AssetType.VULKAN_DRIVER) {
                        for (asset in filtered) {
                            val driver = DriverPackage(
                                id = "${source.slug}/${asset.name}",
                                name = asset.name,
                                version = release.tagName,
                                downloadUrl = asset.downloadUrl,
                                source = AssetSource.GITHUB_RELEASE,
                                sourceRepo = source.slug,
                                fileSizeBytes = asset.sizeBytes,
                                sha256 = asset.sha256,
                            ).let { reconcileDriverStatus(it) }
                            newDrivers[driver.id] = driver
                        }
                    }
                } catch (error: Exception) {
                    // A cancelled refresh (e.g. the screen that started it left composition)
                    // must abort as a whole instead of publishing a partial catalog with a
                    // "cancelled" error for every remaining source.
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val message = when (error) {
                        is GitHubFetchException -> "HTTP ${error.httpCode}: ${error.message}"
                        is IOException -> error.message ?: "Network error"
                        else -> error.message ?: error.javaClass.simpleName
                    }
                    newErrors[source.slug] = message
                    Log.w(TAG, "Refresh failed for ${source.slug}", error)
                }
            }

            entriesById.clear()
            entriesById.putAll(newEntries)
            driversById.clear()
            driversById.putAll(newDrivers)
            errors.clear()
            errors.putAll(newErrors)

            publish()
            _refreshErrors.value = errors.toMap()
        } finally {
            _isRefreshing.value = false
        }
    }

    // --- Search and filter --------------------------------------------------

    fun listAssets(): List<AssetEntry> = _assets.value

    fun listDrivers(): List<DriverPackage> = _drivers.value

    fun getAsset(id: String): AssetEntry? = entriesById[id]

    fun getDriver(id: String): DriverPackage? = driversById[id]

    /** Filters assets by type. */
    fun filterByType(type: AssetType): List<AssetEntry> =
        _assets.value.filter { it.type == type }

    /** Case-insensitive substring search across name, version, and source repo. */
    fun search(query: String): List<AssetEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _assets.value
        return _assets.value.filter {
            it.name.lowercase().contains(q) ||
                it.version.lowercase().contains(q) ||
                (it.sourceRepo?.lowercase()?.contains(q) == true)
        }
    }

    // --- Local import ------------------------------------------------------

    /**
     * Imports a local file as an asset entry. The file is not copied; the
     * entry stores [Uri] as [AssetEntry.localPath]. Returns the created entry.
     */
    suspend fun importLocal(
        name: String,
        type: AssetType,
        uri: Uri,
        sizeBytes: Long = -1,
    ): AssetEntry = mutex.withLock {
        withContext(Dispatchers.IO) {
            val id = "local/${System.currentTimeMillis()}/${sanitizeFileName(name)}"
            val entry = AssetEntry(
                id = id,
                name = name,
                version = "local",
                type = type,
                downloadUrl = "",
                source = AssetSource.LOCAL_IMPORT,
                sourceRepo = null,
                fileSizeBytes = sizeBytes,
                sha256 = null,
                isDownloaded = true,
                localPath = uri.toString(),
            )
            entriesById[entry.id] = entry
            publish()
            entry
        }
    }

    // --- Download ----------------------------------------------------------

    /**
     * Enqueues a download for [assetId]. Returns the [DownloadTask] or null
     * when the asset is unknown or already on disk.
     */
    suspend fun download(assetId: String): DownloadTask? {
        val entry = entriesById[assetId] ?: return null
        if (entry.isDownloaded) return null
        val dest = assetDestFile(entry)
        return downloadManager.enqueue(
            url = entry.downloadUrl,
            dest = dest,
            displayName = entry.name,
            expectedSha256 = entry.sha256,
            assetId = entry.id,
            recordKind = RecordKind.ASSET,
        ).also {
            // Optimistically mark as downloading; the reconciler will correct.
            entriesById[entry.id] = entry.copy(isDownloaded = false, localPath = dest.absolutePath)
            publish()
        }
    }

    /**
     * Enqueues a driver download. Drivers go to a separate directory and are
     * validated by the native adrenotools layer on install.
     */
    suspend fun downloadDriver(driverId: String): DownloadTask? {
        val driver = driversById[driverId] ?: return null
        if (driver.isDownloaded) return null
        val dest = driverDestFile(driver)
        return downloadManager.enqueue(
            url = driver.downloadUrl,
            dest = dest,
            displayName = driver.name,
            expectedSha256 = driver.sha256,
            assetId = driver.id,
            recordKind = RecordKind.DRIVER,
        ).also {
            driversById[driver.id] = driver.copy(isDownloaded = false, localPath = dest.absolutePath)
            publish()
        }
    }

    /** Local path for a downloaded asset, or null if not downloaded. */
    fun localPathFor(assetId: String): String? {
        val entry = entriesById[assetId] ?: return null
        if (entry.isDownloaded && !entry.localPath.isNullOrBlank()) return entry.localPath
        // Reconcile from download manager.
        return reconcileDownloadStatus(entry).takeIf { it.isDownloaded }?.localPath
    }

    // --- Reconciliation ----------------------------------------------------

    private fun reconcileDownloadStatus(entry: AssetEntry): AssetEntry {
        val dest = assetDestFile(entry)
        if (dest.isFile && dest.length() > 0) {
            return entry.copy(isDownloaded = true, localPath = dest.absolutePath)
        }
        val task = downloadManager.taskForAsset(entry.id)
        return if (task?.status == DownloadStatus.COMPLETED && dest.isFile) {
            entry.copy(isDownloaded = true, localPath = dest.absolutePath)
        } else {
            entry.copy(isDownloaded = false, localPath = null)
        }
    }

    private fun reconcileDriverStatus(driver: DriverPackage): DriverPackage {
        val dest = driverDestFile(driver)
        if (dest.isFile && dest.length() > 0) {
            return driver.copy(isDownloaded = true, localPath = dest.absolutePath)
        }
        val task = downloadManager.taskForAsset(driver.id)
        return if (task?.status == DownloadStatus.COMPLETED && dest.isFile) {
            driver.copy(isDownloaded = true, localPath = dest.absolutePath)
        } else {
            driver.copy(isDownloaded = false, localPath = null)
        }
    }

    private fun assetDestFile(entry: AssetEntry): File =
        File(assetsRoot, "${sanitizeFileName(entry.sourceRepo ?: "misc")}/${sanitizeFileName(entry.name)}")

    private fun driverDestFile(driver: DriverPackage): File =
        File(appContext.filesDir, "drivers/${sanitizeFileName(driver.sourceRepo ?: "misc")}/${sanitizeFileName(driver.name)}")

    private fun publish() {
        _assets.value = entriesById.values
            .sortedByDescending { it.sourceRepo }
            .toList()
        _drivers.value = driversById.values
            .sortedByDescending { it.sourceRepo }
            .toList()
    }

    // --- Persistence -------------------------------------------------------

    private fun loadCatalog() {
        val text = readTextOrNull(catalogFile) ?: return
        runCatching {
            val root = JSONObject(text)
            val array = root.optJSONArray("sources") ?: JSONArray()
            val persisted = ArrayList<CatalogSource>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val source = runCatching { catalogSourceFromJson(item) }.getOrNull() ?: continue
                persisted += source
            }
            // The file is only written by add/remove, so once it exists it is the full
            // source list. Replacing the seeded defaults keeps removals across restarts.
            catalogSources.clear()
            persisted.forEach { catalogSources[it.slug] = it }
            _catalog.value = catalogSources.values.toList()
        }.onFailure { error ->
            Log.e(TAG, "Catalog unreadable, using defaults", error)
        }
    }

    private fun persistCatalog() {
        val array = JSONArray()
        catalogSources.values.forEach { array.put(catalogSourceToJson(it)) }
        val root = JSONObject().put("version", 1).put("sources", array)
        writeAtomic(catalogFile, root.toString(2))
    }

    private fun catalogSourceToJson(source: CatalogSource): JSONObject = JSONObject().apply {
        put("owner", source.owner)
        put("repo", source.repo)
        put("displayName", source.displayName)
        put("type", source.type.name)
        put("assetGlobs", JSONArray().apply { source.assetGlobs.forEach { put(it) } })
        putNullable("notes", source.notes)
    }

    private fun catalogSourceFromJson(obj: JSONObject): CatalogSource {
        val globsArray = obj.optJSONArray("assetGlobs") ?: JSONArray()
        val globs = ArrayList<String>(globsArray.length())
        for (i in 0 until globsArray.length()) {
            globs.add(globsArray.optString(i))
        }
        return CatalogSource(
            owner = obj.getString("owner"),
            repo = obj.getString("repo"),
            displayName = obj.optString("displayName", "${obj.getString("owner")}/${obj.getString("repo")}"),
            type = runCatching { AssetType.valueOf(obj.optString("type")) }.getOrDefault(AssetType.OTHER),
            assetGlobs = globs,
            notes = obj.stringOrNull("notes"),
        )
    }

    companion object {
        private const val TAG = "AssetRepository"

        /**
         * The default catalog. Sourced from verified GitHub releases.
         * `GGlessT/modern-treex` is included as requested; the GitHub API may
         * return a client error for repos with no releases, which is recorded
         * in [refreshErrors] rather than crashing the refresh.
         */
        val defaultCatalog: List<CatalogSource> = listOf(
            CatalogSource(
                owner = "Kron4ek",
                repo = "Wine-Builds",
                displayName = "Wine Builds",
                type = AssetType.WINE,
                assetGlobs = listOf("*.tar.xz", "*.tar.gz"),
                notes = "Proton/Wine builds for Android (AArch64)",
            ),
            CatalogSource(
                owner = "doitsujin",
                repo = "dxvk",
                displayName = "DXVK",
                type = AssetType.DXVK,
                assetGlobs = listOf("*.tar.gz", "*.tar.zst"),
                notes = "DirectX to Vulkan translation",
            ),
            CatalogSource(
                owner = "JimVulkan",
                repo = "radv-xclipse",
                displayName = "RADV Xclipse (Mesa Vulkan)",
                type = AssetType.VULKAN_DRIVER,
                assetGlobs = listOf("*.apk", "*.zip"),
                notes = "RADV (Mesa) for Samsung Xclipse 920 (RDNA2)",
            ),
            CatalogSource(
                owner = "K11MCH1",
                repo = "AdrenoToolsDrivers",
                displayName = "Turnip Adreno Drivers",
                type = AssetType.VULKAN_DRIVER,
                assetGlobs = listOf("*.zip"),
                notes = "Turnip (Adreno) Vulkan driver packages",
            ),
            CatalogSource(
                owner = "GGlessT",
                repo = "modern-treex",
                displayName = "Modern TreeX",
                type = AssetType.OTHER,
                assetGlobs = listOf("*.zip", "*.tar.gz"),
                notes = "Included as requested; may have no releases",
            ),
        )

        @Volatile
        private var instance: AssetRepository? = null

        @Synchronized
        fun get(context: Context): AssetRepository {
            instance?.let { return it }
            val app = context.applicationContext
            val fetcher = GitHubReleaseFetcher.get(app)
            val downloads = DownloadManager.get(app)
            val catalogFile = File(app.filesDir, "assets/catalog.json")
            val assetsRoot = File(app.filesDir, "assets/downloads")
            val created = AssetRepository(app, fetcher, downloads, catalogFile, assetsRoot)
            instance = created
            return created
        }
    }
}
