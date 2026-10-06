package io.harbor.fable.data

import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetType

/**
 * How much of each source's release history the catalog lists, and which build of a release it
 * keeps. Sources stay as they are (see [AssetRepository.defaultCatalog]); this only trims what
 * they publish down to what is worth showing.
 */
internal object CatalogPolicy {
    /** Recent releases listed per source when nothing narrower applies. */
    const val DEFAULT_RELEASES_PER_SOURCE = 5

    /**
     * How many releases of a source of [type] the catalog lists, newest first. DXVK is a single
     * drop-in package, so only the latest release is offered.
     */
    fun releaseLimit(type: AssetType): Int = when (type) {
        AssetType.DXVK -> 1
        else -> DEFAULT_RELEASES_PER_SOURCE
    }

    private val StableWow64 = Regex("""^wine-[0-9][0-9.]*-amd64-wow64\.tar\.(xz|gz)$""", RegexOption.IGNORE_CASE)
    private val StableAmd64 = Regex("""^wine-[0-9][0-9.]*-amd64\.tar\.(xz|gz)$""", RegexOption.IGNORE_CASE)

    /**
     * Lower is better. The stable WoW64 build comes first: it is x86_64 like every other build
     * here, and it also runs 32-bit programs without a separate Box86. Staging and TkG builds
     * come last.
     */
    fun wineRank(name: String): Int = when {
        StableWow64.matches(name) -> 0
        StableAmd64.matches(name) -> 1
        !name.contains("staging", ignoreCase = true) -> 2
        else -> 3
    }

    /**
     * One build per release of a [type] source. Kron4ek publishes plain, staging, staging-tkg and
     * WoW64 builds of every Wine version, which listed as a pile of near-identical entries; only
     * the best of them is kept (a build already on disk wins, so a download never disappears from
     * the list). [builds] are the matching assets of a single release. Other types are unchanged.
     */
    fun buildsToList(type: AssetType, builds: List<AssetEntry>): List<AssetEntry> = when (type) {
        AssetType.WINE -> listOfNotNull(
            builds.minWithOrNull(
                compareBy<AssetEntry>({ !it.isDownloaded }, { wineRank(it.name) }, { it.name }),
            ),
        )
        else -> builds
    }
}
