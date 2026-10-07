package io.harbor.fable.data

import android.content.Context
import android.net.Uri
import android.util.Log
import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetSource
import io.harbor.fable.data.models.AssetType
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
 * One source in the catalog: a GitHub repository's releases or, with [contentsIndex], a Winlator
 * `contents.json` index of `.wcp` packages (see [WinlatorContentsFetcher]).
 *
 * [assetGlobs] filters the release assets (or the index's file names). An empty list keeps every
 * non-checksum asset. [displayName] is the user-facing label; [type] determines which [AssetType]
 * the resulting [AssetEntry] records get. [contentsTypes] are the index's own `type` values
 * (`DXVK`, `VKD3D`, `Box64`, `FEXCore`, `Wine`, `Proton`) this source lists; empty keeps all.
 */
data class CatalogSource(
    val owner: String,
    val repo: String,
    val displayName: String,
    val type: AssetType,
    val assetGlobs: List<String> = emptyList(),
    val notes: String? = null,
    val contentsIndex: String? = null,
    val contentsTypes: List<String> = emptyList(),
) {
    /**
     * Catalog key and download directory. An index source of the same repository gets a suffix
     * so its files don't share a directory with the release source's (the download directory
     * is what [AssetRepository.downloadedFiles] types files by).
     */
    val slug: String
        get() = if (contentsIndex == null) {
            "$owner/$repo"
        } else {
            "$owner/$repo@" + (contentsTypes.ifEmpty { listOf("contents") }).joinToString("+") { it.lowercase() }
        }

    val isContentsIndex: Boolean get() = contentsIndex != null
}

/**
 * In-memory asset catalog with search, type filter, local import, and download
 * status tracking.
 *
 * The catalog is seeded from [defaultCatalog] — the GitHub release sources the
 * app knows about: Wine builds, Box64 and DXVK. [refresh] fetches the latest
 * release for each source via [GitHubReleaseFetcher] and builds [AssetEntry]
 * records from the filtered assets. [download] enqueues a transfer through
 * [DownloadManager].
 *
 * The RADV Xclipse Vulkan driver is deliberately not part of this catalog. It
 * has its own release feed and install lifecycle in [DriverRepository]; a
 * persisted catalog from an earlier build that still lists a driver source is
 * migrated on load.
 *
 * Locally imported files ([importLocal]) are stored as [AssetSource.LOCAL_IMPORT]
 * entries with no remote URL.
 *
 * Download status is reconciled from [DownloadManager] on every access to
 * [assets] so the UI always sees whether a file is on disk.
 */
class AssetRepository internal constructor(
    @Suppress("unused") private val appContext: Context,
    private val fetcher: GitHubReleaseFetcher,
    private val downloadManager: DownloadManager,
    private val catalogFile: File,
    private val assetsRoot: File,
    initialCatalog: List<CatalogSource> = defaultCatalog,
    private val contentsFetcher: WinlatorContentsFetcher = WinlatorContentsFetcher.get(appContext),
) {
    private val mutex = Mutex()

    private val initialDefaults: List<CatalogSource> = initialCatalog

    private val catalogSources = LinkedHashMap<String, CatalogSource>().apply {
        initialCatalog.forEach { put(it.slug, it) }
    }

    private val entriesById = LinkedHashMap<String, AssetEntry>()
    private val errors = LinkedHashMap<String, String>()

    private val _assets = MutableStateFlow<List<AssetEntry>>(emptyList())
    private val _catalog = MutableStateFlow(catalogSources.values.toList())
    private val _refreshErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _isRefreshing = MutableStateFlow(false)

    /** All known assets, newest first. */
    val assets: StateFlow<List<AssetEntry>> = _assets.asStateFlow()

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

            for (source in catalogSources.values) {
                try {
                    if (source.isContentsIndex) {
                        for (entry in resolveContents(source, forceRefresh)) newEntries[entry.id] = entry
                        continue
                    }
                    // Newest release first, so "first match" consumers (setup picks) see the latest build.
                    for ((release, filtered) in resolveReleases(source, forceRefresh)) {
                        val builds = filtered.map { asset ->
                            AssetEntry(
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
                        }
                        // One build per release for Wine; see CatalogPolicy.buildsToList.
                        for (entry in CatalogPolicy.buildsToList(source.type, builds)) {
                            newEntries[entry.id] = entry
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
            errors.clear()
            errors.putAll(newErrors)

            publish()
            _refreshErrors.value = errors.toMap()
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * The packages a Winlator index source lists, newest first: the index's items of the source's
     * [CatalogSource.contentsTypes] whose file names match its globs, trimmed to
     * [CatalogPolicy.indexLimit]. Indexes list oldest first and carry no sizes or checksums; the
     * download manager learns the size from the server.
     */
    private suspend fun resolveContents(source: CatalogSource, forceRefresh: Boolean): List<AssetEntry> {
        val indexUrl = source.contentsIndex ?: return emptyList()
        val wanted = source.contentsTypes.map { it.lowercase() }.toSet()
        val items = contentsFetcher.fetchOrCached(indexUrl, forceRefresh)
            .filter { wanted.isEmpty() || it.type.lowercase() in wanted }
            .filter { matchesAnyPattern(it.fileName, source.assetGlobs) }
            .distinctBy { it.remoteUrl }
            .asReversed()
            .take(CatalogPolicy.indexLimit(source.type))
        return items.map { item ->
            AssetEntry(
                id = "${source.slug}/${item.fileName}",
                name = item.fileName,
                version = item.versionName,
                type = source.type,
                downloadUrl = item.remoteUrl,
                source = AssetSource.DIRECT_URL,
                sourceRepo = source.slug,
                fileSizeBytes = 0,
                sha256 = null,
            ).let { reconcileDownloadStatus(it) }
        }
    }

    /**
     * The releases whose assets a source lists, newest first, each with the assets that match
     * its globs: up to [CatalogPolicy.releaseLimit] of them (a few recent versions of Wine and
     * Box64, only the latest DXVK). Pre-releases only count when no stable release matches.
     * When the release list is unavailable this falls back to [resolveRelease].
     */
    private suspend fun resolveReleases(
        source: CatalogSource,
        forceRefresh: Boolean,
    ): List<Pair<GitHubRelease, List<GitHubAsset>>> {
        val releases = try {
            fetcher.fetchReleasesOrCached(source.owner, source.repo, forceRefresh).releases
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Release list unavailable for ${source.slug}; using the latest release", error)
            null
        }
        if (releases != null) {
            val matching = releases
                .map { release -> release to fetcher.filterAssets(release, source.assetGlobs) }
                .filter { (_, assets) -> assets.isNotEmpty() }
            val stable = matching.filter { (release, _) -> !release.prerelease }
            val picked = (stable.ifEmpty { matching }).take(CatalogPolicy.releaseLimit(source.type))
            if (picked.isNotEmpty()) return picked
        }
        return listOf(resolveRelease(source, forceRefresh))
    }

    /**
     * The release whose assets a source lists, with the assets that match its globs.
     *
     * `releases/latest` is tried first. When it carries nothing that matches — which is the
     * normal case for repositories that publish several components under separate tags (the
     * Box64 sources also release DXVK, VKD3D or app builds, so "latest" is often one of those) —
     * the newest release in the full list that does match wins. Nothing matching anywhere
     * yields the latest release with an empty asset list.
     */
    private suspend fun resolveRelease(
        source: CatalogSource,
        forceRefresh: Boolean,
    ): Pair<GitHubRelease, List<GitHubAsset>> {
        val latest = try {
            fetcher.fetchLatestOrCached(source.owner, source.repo, forceRefresh).release
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // `releases/latest` is a 404 for repositories whose releases are all pre-releases.
            return scanReleases(source, forceRefresh) ?: throw error
        }
        val filtered = fetcher.filterAssets(latest, source.assetGlobs)
        if (filtered.isNotEmpty()) return latest to filtered
        return scanReleases(source, forceRefresh) ?: (latest to filtered)
    }

    private suspend fun scanReleases(source: CatalogSource, forceRefresh: Boolean): Pair<GitHubRelease, List<GitHubAsset>>? {
        val releases = try {
            fetcher.fetchReleasesOrCached(source.owner, source.repo, forceRefresh).releases
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Release list unavailable for ${source.slug}", error)
            return null
        }
        for (release in releases) {
            val filtered = fetcher.filterAssets(release, source.assetGlobs)
            if (filtered.isNotEmpty()) return release to filtered
        }
        return null
    }

    // --- Search and filter --------------------------------------------------

    fun listAssets(): List<AssetEntry> = _assets.value

    fun getAsset(id: String): AssetEntry? = entriesById[id]

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
     * Files on disk for the catalog sources of [type], newest first. Reads the download
     * directories directly, so it works offline and before the catalog has been refreshed.
     */
    fun downloadedFiles(type: AssetType): List<File> {
        val root = assetsRoot
        val directories = _catalog.value
            .filter { it.type == type }
            .map { File(root, sanitizeFileName(it.slug)) }
            .distinct()
        return directories
            .flatMap { dir ->
                dir.listFiles()
                    ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".partial") }
                    .orEmpty()
            }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
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

    private fun assetDestFile(entry: AssetEntry): File =
        File(assetsRoot, "${sanitizeFileName(entry.sourceRepo ?: "misc")}/${sanitizeFileName(entry.name)}")

    private fun publish() {
        _assets.value = entriesById.values
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
            // Driver sources moved to DriverRepository; drop any that an older build persisted.
            catalogSources.clear()
            persisted.filter { it.type != AssetType.VULKAN_DRIVER }.forEach { catalogSources[it.slug] = it }
            val persistedDefaults = root.optInt(DEFAULTS_VERSION_KEY, 1)
            if (persistedDefaults < DEFAULTS_VERSION && migrateDefaults(persistedDefaults)) {
                persistCatalog()
            }
            _catalog.value = catalogSources.values.toList()
        }.onFailure { error ->
            Log.e(TAG, "Catalog unreadable, using defaults", error)
        }
    }

    /**
     * Brings a catalog persisted by an older build up to the current [defaultCatalog] without
     * touching sources the user added. Retired defaults (listed in [retiredDefaultSlugs]) are
     * dropped and defaults missing from the file are appended. Returns true when anything changed.
     */
    private fun migrateDefaults(fromVersion: Int): Boolean {
        var changed = false
        for (slug in retiredDefaultSlugs) {
            if (catalogSources.remove(slug) != null) {
                Log.i(TAG, "Dropped retired default source $slug (catalog defaults v$fromVersion -> v$DEFAULTS_VERSION)")
                changed = true
            }
        }
        for (source in initialDefaults) {
            val existing = catalogSources[source.slug]
            if (existing == null) {
                catalogSources[source.slug] = source
                Log.i(TAG, "Added default source ${source.slug} (catalog defaults v$fromVersion -> v$DEFAULTS_VERSION)")
                changed = true
            } else if (existing != source) {
                // A default whose definition moved on (new globs, a renamed label): take the
                // current one. Only defaults are keyed by these slugs, so nothing user-made is hit.
                catalogSources[source.slug] = source
                Log.i(TAG, "Updated default source ${source.slug} (catalog defaults v$fromVersion -> v$DEFAULTS_VERSION)")
                changed = true
            }
        }
        return changed
    }

    private fun persistCatalog() {
        val array = JSONArray()
        catalogSources.values.forEach { array.put(catalogSourceToJson(it)) }
        val root = JSONObject()
            .put("version", 1)
            .put(DEFAULTS_VERSION_KEY, DEFAULTS_VERSION)
            .put("sources", array)
        writeAtomic(catalogFile, root.toString(2))
    }

    private fun catalogSourceToJson(source: CatalogSource): JSONObject = JSONObject().apply {
        put("owner", source.owner)
        put("repo", source.repo)
        put("displayName", source.displayName)
        put("type", source.type.name)
        put("assetGlobs", JSONArray().apply { source.assetGlobs.forEach { put(it) } })
        putNullable("notes", source.notes)
        putNullable("contentsIndex", source.contentsIndex)
        put("contentsTypes", JSONArray().apply { source.contentsTypes.forEach { put(it) } })
    }

    private fun catalogSourceFromJson(obj: JSONObject): CatalogSource {
        val globsArray = obj.optJSONArray("assetGlobs") ?: JSONArray()
        val globs = ArrayList<String>(globsArray.length())
        for (i in 0 until globsArray.length()) {
            globs.add(globsArray.optString(i))
        }
        val typesArray = obj.optJSONArray("contentsTypes") ?: JSONArray()
        val contentsTypes = ArrayList<String>(typesArray.length())
        for (i in 0 until typesArray.length()) {
            typesArray.optString(i).takeIf { it.isNotBlank() }?.let { contentsTypes.add(it) }
        }
        return CatalogSource(
            owner = obj.getString("owner"),
            repo = obj.getString("repo"),
            displayName = obj.optString("displayName", "${obj.getString("owner")}/${obj.getString("repo")}"),
            type = runCatching { AssetType.valueOf(obj.optString("type")) }.getOrDefault(AssetType.OTHER),
            assetGlobs = globs,
            notes = obj.stringOrNull("notes"),
            contentsIndex = obj.stringOrNull("contentsIndex"),
            contentsTypes = contentsTypes,
        )
    }

    companion object {
        private const val TAG = "AssetRepository"

        private const val DEFAULTS_VERSION_KEY = "defaultsVersion"

        /**
         * Bump whenever [defaultCatalog] changes in a way existing installs must pick up. The
         * persisted catalog records the version it was last reconciled with; older files get
         * [retiredDefaultSlugs] removed and new defaults appended on load.
         *
         * - 1: original defaults (implicit; files written before this key existed).
         * - 2: Box64 moved off `ptitSeb/box64`, whose releases carry no Android/ARM64 binaries
         *      (only x86 library bundles), to sources that ship Android bionic builds.
         * - 3: Wine moved off `Kron4ek/Wine-Builds` (glibc builds that cannot run without a glibc
         *      rootfs) to bionic `.wcp` packages from `StevenMXZ/Winlator-Contents`.
         * - 4: VKD3D-Proton (`HansKristian-Work/vkd3d-proton`) added, so Direct3D 12 games get a
         *      d3d12.dll next to DXVK.
         * - 5: Winlator `.wcp` packages from the `StevenMXZ/Winlator-Contents` index (DXVK,
         *      VKD3D, Box64, FEXCore) added, and the Wine source there also lists the x86_64
         *      Proton package. Default sources already in the file are re-synced to their current
         *      definition (globs, names) from here on; user-added sources are left alone.
         * - 6: Unsupported Proton packages removed from the default Wine source.
         */
        internal const val DEFAULTS_VERSION = 6

        /** Former default sources that are removed from persisted catalogs on migration. */
        internal val retiredDefaultSlugs: List<String> = listOf("ptitSeb/box64", "Kron4ek/Wine-Builds")

        /**
         * The default catalog. Sourced from verified GitHub releases.
         * `GGlessT/modern-treex` is included as requested; the GitHub API may
         * return a client error for repos with no releases, which is recorded
         * in [refreshErrors] rather than crashing the refresh.
         *
         * The RADV Xclipse driver is not listed here: see [DriverRepository].
         */
        val defaultCatalog: List<CatalogSource> = listOf(
            // Wine must be a Winlator-style bionic build: x86_64 Wine linked against Android's
            // libc (interpreter /system/bin/linker64), run by a bionic Box64. Kron4ek's generic
            // builds are glibc-linked and cannot start without a glibc rootfs, so they were
            // retired in defaults v3. Xnick417x/winlator-nightly-wcp publishes no Wine packages
            // (only Box64, WOWBox64, FEXCore, DXVK, VKD3D), so Wine comes from the content
            // repository Winlator-Ludashi itself reads (ContentsManager.REMOTE_PROFILES).
            CatalogSource(
                owner = "StevenMXZ",
                repo = "Winlator-Contents",
                displayName = "Wine (Winlator bionic)",
                type = AssetType.WINE,
                // wine-9.20.wcp: xz tar, profile.json + bin/ + lib/wine/{x86_64,i386}-windows +
                // x86_64-unix + prefixPack.txz. Proton packages are not supported, even when
                // their archive layout resembles Wine, and must not be auto-downloaded.
                assetGlobs = listOf(
                    "regex:^wine-[0-9][0-9.]*\\.wcp$",
                ),
                notes = "Bionic x86_64 Wine for Box64; Wine 9.20 is recommended (Winlator .wcp format)",
            ),
            // Upstream ptitSeb/box64 releases ship no Android or ARM64 binaries (only the
            // x86 library bundles), so Box64 comes from projects that publish Android NDK
            // (bionic) builds of it. Both are plain aarch64 executables linked against
            // libc/libm/libdl only — they run from app storage without Termux or a rootfs.
            CatalogSource(
                owner = "KreitinnSoftware",
                repo = "MiceWine-Repository",
                displayName = "Box64 (x86_64 Emulator)",
                type = AssetType.BOX64,
                // One rolling release carries every tagged version: box64-0.4.2-0.4.2-aarch64.rat
                // is a .tar.xz with usr/bin/box64 inside. DXVK and driver packages are skipped.
                assetGlobs = listOf("box64-*-aarch64.rat"),
                notes = "ARM64 x86_64 translator — required to run Wine. Stable releases, built for Android with the NDK",
            ),
            CatalogSource(
                owner = "Xnick417x",
                repo = "winlator-nightly-wcp",
                displayName = "Box64 nightly (Winlator bionic)",
                type = AssetType.BOX64,
                // Box64-0.4.5-<hash>.wcp is a .tar.xz with box64 at the root. Anchored so the
                // WOWBox64-*.wcp (Wine WoW64 DLL) and FEXCore-*.wcp assets are not picked up.
                assetGlobs = listOf("Box64-*.wcp"),
                notes = "Daily builds of upstream Box64 for Android (bionic). Newer, less tested",
            ),
            // Winlator's own content index (contents.json, what Winlator-Ludashi's Contents
            // Manager installs from). Its .wcp files live in the repository tree, not in
            // releases, so these sources read the index instead of the GitHub releases API.
            CatalogSource(
                owner = "StevenMXZ",
                repo = "Winlator-Contents",
                displayName = "Box64 (Winlator .wcp)",
                type = AssetType.BOX64,
                contentsIndex = WinlatorContentsFetcher.STEVENMXZ_INDEX,
                contentsTypes = listOf("Box64"),
                // box64-0.4.4.wcp: xz tar with profile.json + a bare bionic `box64`.
                assetGlobs = listOf("regex:^box64-.*\\.wcp$"),
                notes = "Box64 builds Winlator ships, including the -fix variants",
            ),
            CatalogSource(
                owner = "StevenMXZ",
                repo = "Winlator-Contents",
                displayName = "DXVK (Winlator .wcp)",
                type = AssetType.DXVK,
                contentsIndex = WinlatorContentsFetcher.STEVENMXZ_INDEX,
                contentsTypes = listOf("DXVK"),
                // dxvk-2.7.1-gplasync.wcp: system32/ + syswow64/ DLLs next to profile.json
                // (DxWrappers installs that layout). ARM64EC builds can't load in x86_64 Wine.
                assetGlobs = listOf("regex:^(?!.*arm64ec)dxvk-.*\\.wcp$"),
                notes = "DXVK variants Winlator ships: gplasync, sarek, async, legacy 1.x",
            ),
            CatalogSource(
                owner = "StevenMXZ",
                repo = "Winlator-Contents",
                displayName = "VKD3D-Proton (Winlator .wcp)",
                type = AssetType.VKD3D,
                contentsIndex = WinlatorContentsFetcher.STEVENMXZ_INDEX,
                contentsTypes = listOf("VKD3D"),
                // vkd3d-proton-3.0b-2763dd2.wcp: system32/ + syswow64/ d3d12.dll + d3d12core.dll.
                assetGlobs = listOf("regex:^(?!.*arm64ec)vkd3d-.*\\.wcp$"),
                notes = "VKD3D-Proton builds Winlator ships",
            ),
            CatalogSource(
                owner = "StevenMXZ",
                repo = "Winlator-Contents",
                displayName = "FEXCore (Winlator .wcp)",
                type = AssetType.FEX,
                contentsIndex = WinlatorContentsFetcher.STEVENMXZ_INDEX,
                contentsTypes = listOf("FEXCore"),
                assetGlobs = listOf("*.wcp"),
                // 2609.wcp: libarm64ecfex / libwow64fex .dll + .so — the in-Wine FEX of ARM64EC
                // Wine builds, not a standalone FEXInterpreter. Listed so the packages are at
                // hand; a container set to FEX still needs an interpreter build to launch.
                notes = "FEXCore DLLs for ARM64EC Wine. Not a standalone FEXInterpreter: " +
                    "containers set to FEX can't launch with only this",
            ),
            CatalogSource(
                owner = "FEX-Emu",
                repo = "FEX",
                displayName = "FEX (x86_64 Emulator)",
                type = AssetType.FEX,
                // Upstream releases ship source archives only, and no third party publishes a
                // standalone FEXInterpreter for Android (the Winlator "FEXCore" packages are
                // Wine arm64ec DLLs, not an interpreter). These globs pick up ARM64 binary
                // packages when a release carries them, and match nothing otherwise.
                assetGlobs = listOf("*aarch64*.tar.*", "*arm64*.tar.*", "*android*.tar.*", "*aarch64*.zip", "*arm64*.zip"),
                notes = "Optional alternative to Box64. No Android binaries are published yet",
            ),
            CatalogSource(
                owner = "doitsujin",
                repo = "dxvk",
                displayName = "DXVK",
                type = AssetType.DXVK,
                // Release tarballs for Windows (dxvk-3.1.1.tar.gz); not the dxvk-native-* Linux builds.
                assetGlobs = listOf("regex:^dxvk-[0-9][0-9.]*\\.tar\\.(gz|zst)$"),
                notes = "DirectX to Vulkan translation",
            ),
            // Direct3D 12 for the prefix, like the vkd3d packages Winlator installs next to DXVK.
            // Upstream release tarballs: vkd3d-proton-3.0.1.tar.zst with x64/ and x86/ d3d12.dll +
            // d3d12core.dll (DxWrappers copies them into system32 / syswow64).
            CatalogSource(
                owner = "HansKristian-Work",
                repo = "vkd3d-proton",
                displayName = "VKD3D-Proton",
                type = AssetType.VKD3D,
                // Versions such as 3.0b carry a letter; the setup_vkd3d_proton.sh script and
                // source archives are not release assets.
                assetGlobs = listOf("regex:^vkd3d-proton-[0-9][0-9a-z.]*\\.tar\\.(zst|gz|xz)$"),
                notes = "Direct3D 12 to Vulkan translation",
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
