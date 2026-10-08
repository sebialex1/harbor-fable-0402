package io.harbor.fable.data

import android.util.Log
import io.harbor.fable.data.models.DriverFamily
import io.harbor.fable.data.models.RadvAsset
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import kotlinx.coroutines.CancellationException

/**
 * The release source for Turnip, Mesa's Vulkan driver for Qualcomm Adreno GPUs.
 *
 * Turnip is what Adreno (Snapdragon) devices need: RADV Xclipse is built for Samsung's AMD-based
 * Xclipse GPUs and finds no device on an Adreno phone, which is why the Direct3D 11 test exited
 * silently there (no d3d11/dxgi ever loaded). The builds come from the repositories Winlator and
 * other adrenotools users install Turnip from, already in the adrenotools package layout this app
 * installs (`meta.json` + `vulkan.ad07xx.so` / `libvulkan_freedreno.so`, an Android Vulkan HAL
 * that exports `HMI`), so they go through the same validator, extractor and libvulkan shim path
 * as RADV:
 *
 *  - [PRIMARY] `K11MCH1/AdrenoToolsDrivers` — the long-running adrenotools driver collection.
 *    Its Qualcomm proprietary driver repacks (`Qualcomm_*_adpkg.zip`) are skipped: only Turnip.
 *    Releases carry a default build plus `_Gmem` / `_Sysmem` render-mode variants and, lately, a
 *    separate `turnip_a8xx.zip` for Adreno 8xx.
 *  - [SECONDARY] `whitebelyash/freedreno_turnip-CI` — mainline / stable Turnip CI builds (and
 *    `a8xx-turnip-gen8` builds for Adreno 8xx), each with a `-sync` variant.
 *
 * Per release one zip is picked for this device ([pickAsset]): the a8xx/gen8 build on an Adreno
 * 8xx, the plain build everywhere else. Tags are not comparable across repositories, so releases
 * are ordered by publish date; the newest stable release of [PRIMARY] (falling back to
 * [SECONDARY] when it has nothing usable) is [ReleaseChannel.LATEST].
 */
class TurnipReleaseProvider(
    private val fetcher: GitHubReleaseFetcher,
    /** True on Adreno 8xx: prefer a8xx/gen8 builds. Read at every fetch. */
    private val preferA8xx: () -> Boolean = { false },
    private val repos: List<Repo> = listOf(PRIMARY, SECONDARY),
) {
    data class Repo(val owner: String, val repo: String) {
        val slug: String get() = "$owner/$repo"
    }

    /**
     * Fetches every repository. One repository failing (rate limit, offline without cache) does
     * not hide the others; throws only when none produced a list.
     */
    suspend fun fetch(forceRefresh: Boolean = false): RadvReleaseFeed {
        val a8xx = preferA8xx()
        val lists = mutableListOf<Pair<Repo, List<RadvRelease>>>()
        var stale = false
        var fetchedAt = 0L
        var firstError: Exception? = null
        for (repo in repos) {
            try {
                val cached = fetcher.fetchReleasesOrCached(repo.owner, repo.repo, forceRefresh)
                lists += repo to cached.releases.mapNotNull { toRelease(it, repo, a8xx) }
                stale = stale || cached.stale
                fetchedAt = maxOf(fetchedAt, cached.fetchedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Turnip releases from ${repo.slug} unavailable", error)
                if (firstError == null) firstError = error
            }
        }
        if (lists.isEmpty()) throw firstError ?: IllegalStateException("No Turnip source configured")
        return RadvReleaseFeed(
            releases = categorize(lists),
            stale = stale,
            fetchedAt = fetchedAt,
        )
    }

    private fun toRelease(release: GitHubRelease, repo: Repo, a8xx: Boolean): RadvRelease? {
        if (PROPRIETARY_TITLE.containsMatchIn(release.name)) return null
        val zips = fetcher.filterAssets(release, ASSET_GLOBS)
            .filter { isTurnipAsset(it.name) }
        val asset = pickAsset(zips.map { it.name }, a8xx)?.let { name -> zips.first { it.name == name } } ?: return null
        return RadvRelease(
            tag = release.tagName,
            title = release.name.ifBlank { release.tagName },
            mesaVersion = parseMesaVersion(release.name) ?: parseMesaVersion(release.tagName),
            commit = null,
            publishedAt = RadvReleaseProvider.parseInstant(release.publishedAt),
            channel = if (release.prerelease) ReleaseChannel.PRERELEASE else ReleaseChannel.VERSIONED,
            body = release.body,
            htmlUrl = release.htmlUrl,
            asset = RadvAsset(
                name = asset.name,
                downloadUrl = asset.downloadUrl,
                sizeBytes = asset.sizeBytes,
                sha256 = asset.sha256,
            ),
            family = DriverFamily.TURNIP,
            sourceRepo = repo.slug,
        )
    }

    companion object {
        private const val TAG = "TurnipReleaseProvider"
        const val DISPLAY_NAME = "Turnip"

        val PRIMARY = Repo("K11MCH1", "AdrenoToolsDrivers")
        val SECONDARY = Repo("whitebelyash", "freedreno_turnip-CI")

        private val ASSET_GLOBS = listOf("*.zip")

        /** K11MCH1's Qualcomm proprietary driver repacks ("Qualcomm Driver v840"). */
        private val PROPRIETARY_TITLE = Regex("""(?i)^\s*qualcomm\b""")
        private val PROPRIETARY_ASSET = Regex("""(?i)qualcomm|_adpkg""")
        private val A8XX_ASSET = Regex("""(?i)a8xx|gen8""")

        /** Render-mode or sync variants; the plain build is preferred when there is one. */
        private val VARIANT_ASSET = Regex("""(?i)[_-](gmem|sysmem|sync|experimental|patched|oneui\d*|8g\d\w*)\b""")

        // "v26.0.0 - Revision 8", "Turnip - 25.3.0-devel - Oct 11, 2025", "Mesa Turnip driver v26.0.0"
        private val MESA_VERSION = Regex("""(?i)(?:^|[^\d.])v?(\d{2}\.\d+\.\d+(?:-devel)?)""")

        internal fun isTurnipAsset(name: String): Boolean =
            name.endsWith(".zip", ignoreCase = true) &&
                !PROPRIETARY_ASSET.containsMatchIn(name) &&
                (name.contains("turnip", ignoreCase = true) || name.contains("freedreno", ignoreCase = true) ||
                    name.contains("mesa", ignoreCase = true))

        /**
         * The zip to offer from one release's Turnip assets. On Adreno 8xx ([a8xx]) the a8xx/gen8
         * build wins; elsewhere those are excluded (they target the 8xx register layout). Within
         * what is left the plain build beats the Gmem/Sysmem/sync variants. Null when nothing fits.
         */
        internal fun pickAsset(names: List<String>, a8xx: Boolean): String? {
            val (gen8, generic) = names.partition { A8XX_ASSET.containsMatchIn(it) }
            val pool = when {
                a8xx && gen8.isNotEmpty() -> gen8
                // Mainline Turnip carries a8xx support too; better than nothing on an 8xx.
                a8xx -> generic
                else -> generic
            }
            if (pool.isEmpty()) return null
            return pool.sortedWith(compareBy<String> { if (VARIANT_ASSET.containsMatchIn(it)) 1 else 0 }.thenBy { it.length })
                .first()
        }

        internal fun parseMesaVersion(text: String?): String? =
            text?.let { MESA_VERSION.find(it)?.groupValues?.get(1) }

        /**
         * Newest first by publish date (tags differ in shape between repositories), keeping the
         * repository order as the tie-break. The newest stable release of the first repository
         * that has one becomes [ReleaseChannel.LATEST].
         */
        internal fun categorize(lists: List<Pair<Repo, List<RadvRelease>>>): List<RadvRelease> {
            val latestId = lists.firstNotNullOfOrNull { (_, releases) ->
                releases.filter { it.channel != ReleaseChannel.PRERELEASE }.maxByOrNull { it.publishedAt }?.id
            }
            val order = lists.mapIndexed { index, (repo, _) -> repo.slug to index }.toMap()
            return lists.flatMap { it.second }
                .sortedWith(
                    compareByDescending<RadvRelease> { it.id == latestId }
                        .thenBy { order[it.sourceRepo] ?: Int.MAX_VALUE }
                        .thenByDescending { it.publishedAt },
                )
                .map { release ->
                    when {
                        release.id == latestId -> release.copy(channel = ReleaseChannel.LATEST)
                        release.channel == ReleaseChannel.LATEST -> release.copy(channel = ReleaseChannel.VERSIONED)
                        else -> release
                    }
                }
        }
    }
}
