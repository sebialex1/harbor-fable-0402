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
     * How many releases of a source of [type] the catalog lists, newest first. DXVK and
     * VKD3D-Proton ship a wide range of versions — legacy 1.x (1.10.3, 1.10.4), the 2.x series
     * (2.1–2.7), and 3.x — and users may need an older one for compatibility with specific games
     * or drivers, so the full recent history is listed.
     */
    fun releaseLimit(type: AssetType): Int = when (type) {
        AssetType.DXVK, AssetType.VKD3D -> 30
        else -> DEFAULT_RELEASES_PER_SOURCE
    }

    /**
     * How many packages a Winlator `contents.json` source lists, newest first. The index is the
     * whole history of a component (every DXVK since 1.5.5), and the variants are the point of
     * it (gplasync, sarek, -fix builds), so the full recent history is kept for DXVK and VKD3D.
     */
    fun indexLimit(type: AssetType): Int = when (type) {
        AssetType.DXVK, AssetType.VKD3D -> 30
        else -> DEFAULT_RELEASES_PER_SOURCE
    }

    private val StableWow64 = Regex("""^wine-[0-9][0-9.]*-amd64-wow64\.tar\.(xz|gz)$""", RegexOption.IGNORE_CASE)
    private val StableAmd64 = Regex("""^wine-[0-9][0-9.]*-amd64\.tar\.(xz|gz)$""", RegexOption.IGNORE_CASE)

    /**
     * Lower is better. Wine 9.20 is the supported default, before other bionic Wine builds.
     * Among legacy tarballs the stable WoW64 build comes first: it is x86_64 like every other build
     * here, and it also runs 32-bit programs without a separate Box86. Staging and TkG builds
     * come last.
     */
    fun wineRank(name: String): Int = when {
        name.startsWith("proton", ignoreCase = true) -> Int.MAX_VALUE
        name.equals("wine-9.20.wcp", ignoreCase = true) -> 0
        WineRuntime.isBionicWinePackageName(name) -> 1
        StableWow64.matches(name) -> 2
        StableAmd64.matches(name) -> 3
        !name.contains("staging", ignoreCase = true) -> 4
        else -> 5
    }

    /**
     * One build per release of a [type] source. Kron4ek publishes plain, staging, staging-tkg and
     * WoW64 builds of every Wine version, which listed as a pile of near-identical entries; only
     * the best of them is kept (a build already on disk wins, so a download never disappears from
     * the list). [builds] are the matching assets of a single release. Other types are unchanged.
     */
    fun buildsToList(type: AssetType, builds: List<AssetEntry>): List<AssetEntry> = when (type) {
        // Bionic .wcp packages are distinct Wine versions, often several per release: keep all.
        AssetType.WINE -> if (builds.all { WineRuntime.isBionicWinePackageName(it.name) }) builds else listOfNotNull(
            builds.minWithOrNull(
                compareBy<AssetEntry>({ !it.isDownloaded }, { wineRank(it.name) }, { it.name }),
            ),
        )
        else -> builds
    }
}
