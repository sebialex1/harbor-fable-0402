package io.harbor.fable.data.models

import org.json.JSONObject

/**
 * How a RADV Xclipse release relates to the rest of the list.
 *
 * - [LATEST] — the newest stable build; the one setup installs and the one recommended to users.
 * - [VERSIONED] — an older stable build, kept so a regression can be worked around by going back.
 * - [PRERELEASE] — flagged as a pre-release by the publisher; listed but never recommended.
 */
enum class ReleaseChannel { LATEST, VERSIONED, PRERELEASE }

/** The driver zip attached to a release. */
data class RadvAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/**
 * One published build of the RADV Xclipse driver (Mesa RADV compiled for Samsung Xclipse GPUs),
 * fetched from the `JimVulkan/radv-xclipse` releases.
 *
 * This is a Vulkan ICD that is loaded through the adrenotools namespace loader, but it is not a
 * Turnip/Adreno driver: the GPU it targets is AMD RDNA2 silicon, and the package format is the
 * generic `meta.json` + `vulkan.radeon.so` zip.
 */
data class RadvRelease(
    /** Git tag, e.g. `v1.5.0`. Unique per release and used as the id everywhere. */
    val tag: String,
    /** Release title, e.g. "RADV Xclipse (Based on Mesa 26.3.0-devel)". */
    val title: String,
    /** Mesa version the build is based on, parsed from the title or asset name; null if unknown. */
    val mesaVersion: String?,
    /** Short upstream commit the build was made from, when the asset name carries one. */
    val commit: String?,
    val publishedAt: Long,
    val channel: ReleaseChannel,
    val body: String,
    val htmlUrl: String?,
    val asset: RadvAsset,
    /** True when the zip is on disk and verified. */
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
) {
    /** Stable id used for download tasks and persistence. */
    val id: String get() = "$SOURCE/$tag"

    /** Numeric version parsed from [tag] for ordering; `v1.5.0` -> [1, 5, 0]. */
    val versionParts: List<Int> get() = parseVersion(tag)

    /** Major version group label, e.g. "v1". */
    val majorLabel: String get() = versionParts.firstOrNull()?.let { "v$it" } ?: tag

    val isLatest: Boolean get() = channel == ReleaseChannel.LATEST

    companion object {
        const val SOURCE = "radv-xclipse"

        private val VERSION = Regex("""(\d+)""")

        fun parseVersion(tag: String): List<Int> = VERSION.findAll(tag).map { it.value.toInt() }.toList()

        /** Orders two version lists; longer lists compare element-wise and missing parts count as 0. */
        val versionComparator: Comparator<List<Int>> = Comparator { a, b ->
            val size = maxOf(a.size, b.size)
            for (i in 0 until size) {
                val diff = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (diff != 0) return@Comparator diff
            }
            0
        }
    }
}

/**
 * The one driver that is currently extracted and active. There is never more than one: installing
 * a release removes the previous one first. Persisted as `drivers/active.json`.
 */
data class InstalledDriver(
    /** Release tag the driver came from, e.g. `v1.5.0`. */
    val tag: String,
    /** Name of the zip the driver was extracted from. */
    val assetName: String,
    /** Absolute path of the installed ICD (`.../vulkan.radeon.so`). */
    val libraryPath: String,
    /** Directory the package was extracted into. Removed on uninstall. */
    val installDir: String,
    /** `name` from meta.json, e.g. "RADV Xclipse (Mesa 26.3.0-devel-313870e)". */
    val name: String?,
    /** `driverVersion` from meta.json, e.g. "Vulkan 1.4.358". */
    val driverVersion: String?,
    /** Parsed Vulkan API version, e.g. "1.4.358". */
    val vulkanVersion: String?,
    val mesaVersion: String?,
    val installedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tag", tag)
        put("assetName", assetName)
        put("libraryPath", libraryPath)
        put("installDir", installDir)
        put("name", name ?: JSONObject.NULL)
        put("driverVersion", driverVersion ?: JSONObject.NULL)
        put("vulkanVersion", vulkanVersion ?: JSONObject.NULL)
        put("mesaVersion", mesaVersion ?: JSONObject.NULL)
        put("installedAt", installedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): InstalledDriver = InstalledDriver(
            tag = obj.getString("tag"),
            assetName = obj.optString("assetName"),
            libraryPath = obj.getString("libraryPath"),
            installDir = obj.getString("installDir"),
            name = obj.optString("name").ifBlank { null },
            driverVersion = obj.optString("driverVersion").ifBlank { null },
            vulkanVersion = obj.optString("vulkanVersion").ifBlank { null },
            mesaVersion = obj.optString("mesaVersion").ifBlank { null },
            installedAt = obj.optLong("installedAt", 0L),
        )
    }
}
