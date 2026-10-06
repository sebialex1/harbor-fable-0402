package io.harbor.fable.data

import io.harbor.fable.data.models.RadvAsset
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import java.time.Instant

/** Result of one catalog refresh: the releases plus where they came from. */
data class RadvReleaseFeed(
    val releases: List<RadvRelease>,
    /** True when the list came from the on-disk cache because the network failed. */
    val stale: Boolean,
    val fetchedAt: Long,
)

/**
 * The dedicated release source for the RADV Xclipse driver.
 *
 * Reads every release of `JimVulkan/radv-xclipse` (`GET /repos/{owner}/{repo}/releases`) through
 * the shared [GitHubReleaseFetcher] transport, picks the driver zip of each one, and sorts them
 * newest first. The newest stable release becomes [ReleaseChannel.LATEST]; older stable builds are
 * [ReleaseChannel.VERSIONED]; anything the publisher flagged is [ReleaseChannel.PRERELEASE].
 *
 * RADV is kept apart from the generic asset catalog on purpose: it has its own lifecycle
 * (download, then install as the single active driver) and must never be confused with
 * Turnip/Adreno packages, which this app does not offer.
 */
class RadvReleaseProvider(
    private val fetcher: GitHubReleaseFetcher,
    private val owner: String = OWNER,
    private val repo: String = REPO,
) {
    val slug: String get() = "$owner/$repo"

    /**
     * Fetches the release list. Falls back to a stale cache when offline; throws when there is
     * neither network nor cache.
     */
    suspend fun fetch(forceRefresh: Boolean = false): RadvReleaseFeed {
        val cached = fetcher.fetchReleasesOrCached(owner, repo, forceRefresh)
        return RadvReleaseFeed(
            releases = categorize(cached.releases.mapNotNull(::toRadvRelease)),
            stale = cached.stale,
            fetchedAt = cached.fetchedAt,
        )
    }

    private fun toRadvRelease(release: GitHubRelease): RadvRelease? {
        val asset = fetcher.filterAssets(release, ASSET_GLOBS)
            .filterNot { it.name.contains("source", ignoreCase = true) }
            .maxByOrNull { it.sizeBytes } ?: return null
        return RadvRelease(
            tag = release.tagName,
            title = release.name.ifBlank { release.tagName },
            mesaVersion = parseMesaVersion(release.name) ?: parseMesaVersion(asset.name),
            commit = parseCommit(asset.name),
            publishedAt = parseInstant(release.publishedAt),
            channel = if (release.prerelease) ReleaseChannel.PRERELEASE else ReleaseChannel.VERSIONED,
            body = release.body,
            htmlUrl = release.htmlUrl,
            asset = RadvAsset(
                name = asset.name,
                downloadUrl = asset.downloadUrl,
                sizeBytes = asset.sizeBytes,
                sha256 = asset.sha256,
            ),
        )
    }

    companion object {
        const val OWNER = "JimVulkan"
        const val REPO = "radv-xclipse"
        const val DISPLAY_NAME = "RADV Xclipse"

        private val ASSET_GLOBS = listOf("*.zip")

        // "RADV Xclipse (Based on Mesa 26.3.0-devel)" or "radv-xclipse-26.2.3-5e7f0c2.zip"
        private val MESA_IN_TITLE = Regex("""(?i)mesa\s+v?(\d+\.\d+(?:\.\d+)?(?:-[A-Za-z0-9]+)?)""")
        private val MESA_IN_ASSET = Regex("""(?i)radv-xclipse-(\d+\.\d+(?:\.\d+)?(?:-devel)?)""")
        private val COMMIT_IN_ASSET = Regex("""-([0-9a-f]{7,12})\.zip$""")

        internal fun parseMesaVersion(text: String?): String? {
            if (text.isNullOrBlank()) return null
            return MESA_IN_TITLE.find(text)?.groupValues?.get(1)
                ?: MESA_IN_ASSET.find(text)?.groupValues?.get(1)
        }

        internal fun parseCommit(assetName: String?): String? =
            assetName?.let { COMMIT_IN_ASSET.find(it)?.groupValues?.get(1) }

        internal fun parseInstant(iso: String?): Long =
            iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

        /**
         * Sorts newest first (by version, then publish date) and marks the newest stable build as
         * [ReleaseChannel.LATEST]. Pre-releases keep their channel and never become the latest.
         */
        internal fun categorize(releases: List<RadvRelease>): List<RadvRelease> {
            val sorted = releases.sortedWith(
                compareByDescending<RadvRelease, List<Int>>(RadvRelease.versionComparator) { it.versionParts }
                    .thenByDescending { it.publishedAt },
            )
            val latestTag = sorted.firstOrNull { it.channel != ReleaseChannel.PRERELEASE }?.tag
            return sorted.map { release ->
                when {
                    release.tag == latestTag -> release.copy(channel = ReleaseChannel.LATEST)
                    release.channel == ReleaseChannel.LATEST -> release.copy(channel = ReleaseChannel.VERSIONED)
                    else -> release
                }
            }
        }
    }
}
