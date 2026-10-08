package io.harbor.fable.data.models

import org.json.JSONObject

/**
 * Which Mesa Vulkan driver a release (or the installed driver) belongs to. Each family targets
 * one GPU line, so the right one depends on the device ([io.harbor.fable.nativebridge.GpuDetector]):
 *
 * - [RADV_XCLIPSE] — Mesa RADV built for Samsung Xclipse (AMD RDNA2) GPUs, from
 *   `JimVulkan/radv-xclipse`. Ships `vulkan.radeon.so`.
 * - [TURNIP] — Mesa Turnip, the open Vulkan driver for Qualcomm Adreno GPUs, in the adrenotools
 *   package layout (`meta.json` + `vulkan.ad07xx.so` / `libvulkan_freedreno.so`), from the
 *   repositories Winlator users get Turnip from (`K11MCH1/AdrenoToolsDrivers`,
 *   `whitebelyash/freedreno_turnip-CI`).
 *
 * Both are Android Vulkan HALs (they export `HMI`), so both go through the same
 * libvulkan shim path (split mode: system loader + injected HAL).
 */
enum class DriverFamily(
    /** Stable id, persisted in `active.json`. */
    val id: String,
    val displayName: String,
    /** The GPUs the family is built for, for the Drivers screen. */
    val targetGpus: String,
) {
    RADV_XCLIPSE("radv-xclipse", "RADV Xclipse", "Samsung Xclipse GPUs"),
    TURNIP("turnip", "Turnip", "Qualcomm Adreno GPUs"),
    ;

    companion object {
        fun fromId(id: String?): DriverFamily? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a driver release relates to the rest of its family's list.
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
 * One published build of a Mesa Vulkan driver package ([family]): RADV Xclipse (Samsung Xclipse
 * GPUs, from `JimVulkan/radv-xclipse`) or Turnip (Qualcomm Adreno GPUs, from the adrenotools
 * driver repositories). The name is historical; [DriverRelease] is the same type.
 *
 * Every package is the generic adrenotools zip (`meta.json` + the driver `.so`) and is loaded the
 * same way, through the adrenotools namespace loader / the libvulkan shim's HAL injection.
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
    /** Driver family; RADV Xclipse for everything that predates Turnip support. */
    val family: DriverFamily = DriverFamily.RADV_XCLIPSE,
    /** `owner/repo` the release came from; null means the RADV Xclipse repository. */
    val sourceRepo: String? = null,
) {
    /**
     * Stable id used for download tasks and persistence. RADV keeps its original
     * `radv-xclipse/<tag>` form (so queued/finished downloads from older builds still match);
     * Turnip ids carry the repository, as tags are only unique per repository.
     */
    val id: String
        get() = when (family) {
            DriverFamily.RADV_XCLIPSE -> "$SOURCE/$tag"
            DriverFamily.TURNIP -> "${DriverFamily.TURNIP.id}/${sourceRepo.orEmpty()}/$tag"
        }

    /** "Turnip v26.0.0-rc08", "RADV Xclipse v1.5.0": the tag with its family, for messages. */
    val label: String get() = "${family.displayName} $tag"

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

/** The family-neutral name for [RadvRelease]. */
typealias DriverRelease = RadvRelease

/**
 * The one driver that is currently extracted and active. There is never more than one (of either
 * family): installing a release removes the previous one first. Persisted as `drivers/active.json`.
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
    /** RADV Xclipse for records written before Turnip support (no `family` key). */
    val family: DriverFamily = DriverFamily.RADV_XCLIPSE,
    /** [RadvRelease.id] of the release this came from; null for older records. */
    val releaseId: String? = null,
) {
    /** True when this record came from [release]. */
    fun isFrom(release: RadvRelease): Boolean =
        if (releaseId != null) releaseId == release.id else family == release.family && tag == release.tag

    /** Name for titles: the package's own name, else "Turnip" / "RADV Xclipse". */
    val displayName: String get() = name ?: family.displayName

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
        put("family", family.id)
        put("releaseId", releaseId ?: JSONObject.NULL)
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
            family = DriverFamily.fromId(obj.optString("family").ifBlank { null }) ?: DriverFamily.RADV_XCLIPSE,
            releaseId = obj.optString("releaseId").ifBlank { null }?.takeIf { it != "null" },
        )
    }
}
