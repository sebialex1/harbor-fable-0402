package io.harbor.fable.data

/**
 * What Direct3D 12 through VKD3D-Proton needs, checked before Wine starts so a D3D12 failure is
 * explained instead of ending in a game's own crash reporter.
 *
 * Three things break D3D12 while DXVK (D3D9/10/11) keeps working:
 *
 * 1. **The DXGI pairing.** VKD3D-Proton ships only `d3d12.dll` / `d3d12core.dll`. Since 2.9 it
 *    shares DXVK's DXGI (swap chains are created through DXVK 2.1+'s `dxgi.dll`) and there is
 *    no fallback to Wine's dxgi or a pre-2.1 DXVK any more. A container with DXVK 1.x (or DXVK
 *    off) runs D3D11 games but every D3D12 game fails at swap-chain creation.
 * 2. **`VKD3D_FEATURE_LEVEL`.** In VKD3D-Proton this is not a "maximum" or a default: its
 *    `d3d12_device_caps_override` *forces* the capabilities of that level on, whatever the
 *    Vulkan driver supports (12_1 sets ROVsSupported, conservative rasterization tier 1, tiled
 *    resources tier 2, resource binding tier 2, typed UAV loads and SM 6.0). Games then use
 *    features the driver doesn't have. Fable used to set 12_1 for every container (Winlator's
 *    default); it no longer does, so VKD3D-Proton reports what the driver really supports. A
 *    container can still set it, and the launch log says what that does.
 * 3. **The Vulkan driver.** VKD3D-Proton needs Vulkan 1.3, `VK_KHR_push_descriptor` and
 *    `VK_EXT_robustness2` (with nullDescriptor). D3D12 ray tracing (DXR) is only reported when
 *    the driver has `VK_KHR_ray_tracing_pipeline` + `VK_KHR_acceleration_structure` (+
 *    `VK_KHR_deferred_host_operations`); DXR 1.1 also needs `VK_KHR_ray_query`. A ray tracing
 *    benchmark on a driver without them is unsupported hardware/driver functionality, not a
 *    setup problem — Fable never pretends otherwise.
 */
internal object Vkd3dSetup {
    const val FEATURE_LEVEL_ENV = "VKD3D_FEATURE_LEVEL"

    /** First VKD3D-Proton release that requires DXVK's DXGI (2.1+). */
    private val VKD3D_SHARED_DXGI = listOf(2, 9)
    private val DXVK_MIN_FOR_SHARED_DXGI = listOf(2, 1)

    /** Device extensions VKD3D-Proton refuses to create a device without (on top of Vulkan 1.3). */
    val REQUIRED_DEVICE_EXTENSIONS = listOf("VK_KHR_push_descriptor", "VK_EXT_robustness2")

    /** Device extensions D3D12 ray tracing tier 1.0 is built on. */
    val DXR_EXTENSIONS = listOf(
        "VK_KHR_ray_tracing_pipeline",
        "VK_KHR_acceleration_structure",
        "VK_KHR_deferred_host_operations",
    )
    const val RAY_QUERY_EXTENSION = "VK_KHR_ray_query"

    private val VERSION = Regex("""(?<![0-9])v?(\d+)\.(\d+)(?:\.(\d+))?""")

    /**
     * `[2, 14, 1]` from a package file name: `vkd3d-proton-2.14.1.tar.zst`, `Vkd3d-2.12-1.wcp`,
     * `dxvk-gplasync-v2.6-1.tar.gz`, `dxvk-1.10.3-async.wcp`. Null when there is no version.
     */
    fun versionOf(packageName: String?): List<Int>? {
        val match = VERSION.find(packageName ?: return null) ?: return null
        return listOfNotNull(
            match.groupValues[1].toIntOrNull(),
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].ifEmpty { null }?.toIntOrNull(),
        )
    }

    /** Lexicographic version comparison; missing components count as 0. */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * Problems with the DXVK / VKD3D-Proton combination in a prefix: [vkd3d] and [dxvk] are the
     * installed package names (null = not installed). Empty when D3D12 has what it needs, or
     * when VKD3D-Proton isn't installed at all.
     */
    fun pairingProblems(dxvk: String?, vkd3d: String?): List<String> {
        if (vkd3d == null) return emptyList()
        val vkd3dVersion = versionOf(vkd3d)
        // Unknown VKD3D version: assume a current one (every build in the catalog is >= 2.9).
        val needsDxvkDxgi = vkd3dVersion == null || compare(vkd3dVersion, VKD3D_SHARED_DXGI) >= 0
        if (!needsDxvkDxgi) return emptyList()
        if (dxvk == null) {
            return listOf(
                "$vkd3d has no DXGI of its own and needs DXVK 2.1+'s dxgi.dll, but DXVK isn't installed in this " +
                    "container: Direct3D 12 games can't create a swap chain. Pick a DXVK 2.1+ build in the container's settings",
            )
        }
        val dxvkVersion = versionOf(dxvk) ?: return emptyList()
        if (compare(dxvkVersion, DXVK_MIN_FOR_SHARED_DXGI) < 0) {
            return listOf(
                "$vkd3d needs DXVK 2.1+'s dxgi.dll, but this container uses $dxvk " +
                    "(${dxvkVersion.joinToString(".")}): Direct3D 11 works, Direct3D 12 can't create a swap chain. " +
                    "Pick a DXVK 2.1+ build, or a VKD3D-Proton older than 2.9",
            )
        }
        return emptyList()
    }

    /**
     * What a container's own `VKD3D_FEATURE_LEVEL` does, for the launch log, or null when it
     * isn't set. VKD3D-Proton raises capabilities to that level regardless of the driver.
     */
    fun featureLevelWarning(value: String?): String? {
        val level = value?.trim()?.ifEmpty { null } ?: return null
        val forced = when (level) {
            "11_0" -> return null
            "11_1" -> "OutputMergerLogicOp"
            "12_0" -> "OutputMergerLogicOp, tiled resources tier 2, resource binding tier 2, typed UAV loads, SM 6.0"
            "12_1" -> "OutputMergerLogicOp, tiled resources tier 2, resource binding tier 2, typed UAV loads, SM 6.0, " +
                "ROVs, conservative rasterization tier 1"
            "12_2" -> "everything up to 12_1 plus ray tracing tier 1.1, mesh shaders, VRS, sampler feedback, " +
                "resource binding / tiled resources / conservative rasterization tier 3, SM 6.5"
            else -> return "$FEATURE_LEVEL_ENV=$level isn't a level VKD3D-Proton knows (11_0 … 12_2); it is ignored"
        }
        return "$FEATURE_LEVEL_ENV=$level is set for this container: VKD3D-Proton reports $forced as supported " +
            "even when the Vulkan driver lacks them, and games that use them can crash. Remove it to get the " +
            "driver's real capabilities"
    }

    enum class RayTracing(val label: String) {
        /** No DXR: the driver lacks the Vulkan ray tracing pipeline extensions. */
        UNSUPPORTED("not supported (no VK_KHR_ray_tracing_pipeline / VK_KHR_acceleration_structure)"),
        /** DXR 1.0 can be reported (VKD3D-Proton still checks limits and formats). */
        TIER_1_0("DXR 1.0 possible"),
        /** DXR 1.0 + ray queries: VKD3D-Proton can report DXR 1.1. */
        TIER_1_1("DXR 1.1 possible"),
        /** No device information (probe failed or not run). */
        UNKNOWN("unknown (the Vulkan driver couldn't be probed)"),
    }

    /** VKD3D-Proton's view of a Vulkan device, from its API version and device extensions. */
    data class DriverSupport(
        val deviceName: String?,
        val apiVersion: String?,
        /** False when the device is below Vulkan 1.3 (VKD3D-Proton skips such devices). */
        val apiOk: Boolean,
        val missingRequired: List<String>,
        val rayTracing: RayTracing,
    ) {
        val canRunD3d12: Boolean get() = apiOk && missingRequired.isEmpty()

        fun describe(): List<String> = buildList {
            add("D3D12 device: ${deviceName ?: "unknown"}, Vulkan ${apiVersion ?: "?"}")
            if (!apiOk) add("ERROR VKD3D-Proton needs Vulkan 1.3; this device reports ${apiVersion ?: "an unknown version"}")
            if (missingRequired.isNotEmpty()) {
                add("ERROR VKD3D-Proton requires ${missingRequired.joinToString()}, which this driver doesn't expose: D3D12CreateDevice will fail")
            }
            if (canRunD3d12) add("D3D12 requirements (Vulkan 1.3, ${REQUIRED_DEVICE_EXTENSIONS.joinToString()}): met")
            add("D3D12 ray tracing (DXR): ${rayTracing.label}")
        }

        companion object {
            val UNKNOWN = DriverSupport(null, null, apiOk = true, missingRequired = emptyList(), rayTracing = RayTracing.UNKNOWN)
        }
    }

    /** [DriverSupport] for a device reporting [apiVersion] ("1.3.280") and [extensions]. */
    fun assess(deviceName: String?, apiVersion: String?, extensions: Collection<String>): DriverSupport {
        val names = extensions.toSet()
        val version = apiVersion?.split('.')?.mapNotNull { it.toIntOrNull() }
        val apiOk = version == null || compare(version, listOf(1, 3)) >= 0
        val rayTracing = when {
            !names.containsAll(DXR_EXTENSIONS) -> RayTracing.UNSUPPORTED
            RAY_QUERY_EXTENSION in names -> RayTracing.TIER_1_1
            else -> RayTracing.TIER_1_0
        }
        return DriverSupport(
            deviceName = deviceName,
            apiVersion = apiVersion,
            apiOk = apiOk,
            missingRequired = REQUIRED_DEVICE_EXTENSIONS.filterNot { it in names },
            rayTracing = rayTracing,
        )
    }
}
