package io.harbor.fable.ui.screens

import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetType

/**
 * One version of a catalog component with every build published for it: Kron4ek ships plain,
 * staging, staging-tkg and wow64 builds of each Wine release, which used to flood the list with
 * near-identical rows. The UI shows one row per version and lets the user pick the build.
 */
internal data class AssetVersionGroup(
    val key: String,
    val type: AssetType,
    val version: String,
    /** Builds of this version, the preferred one first. */
    val variants: List<AssetVariant>,
) {
    /** The build to offer when the user has not picked one: a downloaded build, else the preferred one. */
    val defaultVariant: AssetVariant
        get() = variants.firstOrNull { it.asset.isDownloaded } ?: variants.first()
}

internal data class AssetVariant(
    val asset: AssetEntry,
    /** "Standard", "Staging", "Staging TkG WoW64", … */
    val label: String,
    /** Lower is preferred: plain < staging < tkg < wow64 < anything unrecognised. */
    val rank: Int,
    /** The build without its WoW64 mode: "Standard", "Staging", "Staging TkG". */
    val flavor: String,
    /** True for the WoW64 builds (32-bit programs without a separate 32-bit Wine). */
    val wow64: Boolean,
)

/** Distinct flavors of a version's builds, preferred first. */
internal val AssetVersionGroup.flavors: List<String>
    get() = variants.map { it.flavor }.distinct()

/** True when the version ships both WoW64 and non-WoW64 builds, so the mode is a real choice. */
internal val AssetVersionGroup.hasWow64Choice: Boolean
    get() = variants.any { it.wow64 } && variants.any { !it.wow64 }

/** The build matching [flavor] and [wow64], or the closest one when that combination is missing. */
internal fun AssetVersionGroup.pick(flavor: String, wow64: Boolean): AssetVariant =
    variants.firstOrNull { it.flavor == flavor && it.wow64 == wow64 }
        ?: variants.firstOrNull { it.flavor == flavor }
        ?: variants.first()

/** How many versions each component lists; older ones are not worth the scroll. */
internal const val MAX_VERSIONS_PER_TYPE = 5

private val ArchiveSuffix = Regex("""\.(tar\.(xz|gz|bz2|zst)|tgz|txz|zip|7z)$""", RegexOption.IGNORE_CASE)
private val VersionToken = Regex("""^v?\d+(\.\d+)*([a-z]*\d*)?$""", RegexOption.IGNORE_CASE)
private val Digits = Regex("""\d+""")

/** Tokens that only restate the component or the (only supported) architecture. */
private val NeutralTokens = setOf(
    "wine", "box64", "dxvk", "vkd3d", "fex", "proton", "amd64", "x86", "x86_64", "x64", "64", "bit",
)

/**
 * Groups [assets] of one [type] by release version, newest first, capped at [limit] versions.
 * Within a version the builds are ordered plain → staging → tkg → wow64.
 */
internal fun groupAssetVersions(
    type: AssetType,
    assets: List<AssetEntry>,
    limit: Int = MAX_VERSIONS_PER_TYPE,
): List<AssetVersionGroup> =
    assets
        .groupBy { versionOf(it) }
        .map { (version, builds) ->
            AssetVersionGroup(
                key = "${type.name}/$version",
                type = type,
                version = version,
                variants = builds
                    .map { asset -> variantOf(asset, version) }
                    .sortedWith(compareBy<AssetVariant> { it.rank }.thenBy { it.asset.name }),
            )
        }
        .sortedWith { a, b -> compareVersions(b.version, a.version) }
        .take(limit)

/** The release tag, or the version found in the file name when the tag is missing. */
private fun versionOf(asset: AssetEntry): String {
    val tag = asset.version.trim()
    if (tag.isNotEmpty() && tag != "local") return tag.removePrefix("v").removePrefix("V")
    return tokens(asset.name).firstOrNull { VersionToken.matches(it) && it.any(Char::isDigit) } ?: asset.name
}

private fun tokens(fileName: String): List<String> =
    fileName.replace(ArchiveSuffix, "").lowercase().split('-', '_').filter { it.isNotBlank() }

private fun variantOf(asset: AssetEntry, version: String): AssetVariant {
    val versionParts = version.lowercase().split('-', '_').toSet()
    val extra = tokens(asset.name).filter { token ->
        token !in NeutralTokens &&
            token !in versionParts &&
            !(VersionToken.matches(token) && token.any(Char::isDigit))
    }
    var rank = 0
    for (token in extra) {
        rank += when (token) {
            "staging" -> 1
            "tkg" -> 2
            "wow64" -> 4
            else -> 8
        }
    }
    val label = if (extra.isEmpty()) "Standard" else extra.joinToString(" ") { prettyToken(it) }
    val flavorTokens = extra.filter { it != "wow64" }
    val flavor = if (flavorTokens.isEmpty()) "Standard" else flavorTokens.joinToString(" ") { prettyToken(it) }
    return AssetVariant(asset = asset, label = label, rank = rank, flavor = flavor, wow64 = "wow64" in extra)
}

private fun prettyToken(token: String): String = when (token) {
    "tkg" -> "TkG"
    "wow64" -> "WoW64"
    "aarch64", "arm64" -> "ARM64"
    else -> token.replaceFirstChar { it.uppercase() }
}

/** Numeric comparison of dotted versions ("11.19" > "11.9" > "10.20"); text breaks ties. */
internal fun compareVersions(a: String, b: String): Int {
    val left = Digits.findAll(a).map { it.value.toLongOrNull() ?: 0L }.toList()
    val right = Digits.findAll(b).map { it.value.toLongOrNull() ?: 0L }.toList()
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrElse(i) { -1L }).compareTo(right.getOrElse(i) { -1L })
        if (diff != 0) return diff
    }
    return a.compareTo(b)
}
