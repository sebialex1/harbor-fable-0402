package io.harbor.fable.nativebridge

import android.os.Build
import io.harbor.fable.data.models.DriverFamily
import io.harbor.fable.data.models.VulkanDevice
import java.io.File

/** The GPU line a device has, as far as driver choice is concerned. */
enum class GpuKind(val label: String) {
    /** Qualcomm Adreno (Snapdragon): needs Turnip. */
    ADRENO("Adreno"),

    /** Samsung Xclipse (AMD RDNA2, Exynos 2200 and later): needs RADV Xclipse. */
    XCLIPSE("Xclipse"),

    /** Arm Mali, PowerVR, …: neither Mesa driver Fable offers is built for these. */
    OTHER("Other"),

    /** Nothing conclusive could be read. */
    UNKNOWN("Unknown"),
}

/**
 * What [GpuDetector] found: the [kind], a model string when one was readable
 * ("Adreno (TM) 740", "Adreno740v2"), the Adreno generation (6, 7, 8) when known, and which
 * signal decided it, for diagnostics.
 */
data class GpuIdentity(
    val kind: GpuKind,
    val model: String?,
    val adrenoGeneration: Int?,
    val evidence: String,
) {
    /**
     * The driver family to recommend. Adreno gets Turnip; Xclipse (and anything not identified,
     * which keeps the behaviour from before Turnip support) gets RADV Xclipse.
     */
    val recommendedFamily: DriverFamily
        get() = if (kind == GpuKind.ADRENO) DriverFamily.TURNIP else DriverFamily.RADV_XCLIPSE

    /** True when [family] is built for this GPU; unknown GPUs accept either. */
    fun matches(family: DriverFamily): Boolean = when (kind) {
        GpuKind.ADRENO -> family == DriverFamily.TURNIP
        GpuKind.XCLIPSE -> family == DriverFamily.RADV_XCLIPSE
        GpuKind.OTHER, GpuKind.UNKNOWN -> true
    }

    /** True for Adreno 8xx (Snapdragon 8 Elite), which needs the a8xx/gen8 Turnip builds. */
    val isAdreno8xx: Boolean get() = kind == GpuKind.ADRENO && adrenoGeneration == 8
}

/**
 * Works out whether this device has an Adreno or an Xclipse GPU, so the Drivers screen and setup
 * can recommend Turnip or RADV Xclipse. Never throws.
 *
 * Signals, strongest first:
 *  1. the Vulkan device the system driver reports ([fromVulkan]): Qualcomm's PCI vendor id
 *     0x5143 or a name / driverName with "Adreno" / "Qualcomm" / "Turnip"; AMD 0x1002 or
 *     Samsung 0x144D with "Xclipse" / "RADV";
 *  2. `/sys/class/kgsl/kgsl-3d0/gpu_model` (KGSL is Qualcomm's GPU kernel driver, so the node
 *     only exists on Adreno devices; it reads e.g. "Adreno740v2");
 *  3. system properties: `ro.hardware.vulkan` / `ro.hardware.egl` ("adreno", "mali", "samsung"),
 *     `ro.soc.manufacturer` / [Build.SOC_MANUFACTURER] ("QTI", "Qualcomm", "Samsung"),
 *     `ro.board.platform` / [Build.HARDWARE] ("qcom", "kalama", "s5e9925", …).
 */
object GpuDetector {

    @Volatile
    private var cached: GpuIdentity? = null

    /** The quick, synchronous detection (sysfs + properties), cached for the process. */
    fun detect(): GpuIdentity = cached ?: detectFromSystem().also { cached = it }

    /**
     * Refines [detect] with the system Vulkan driver's primary device, when one was probed. The
     * Vulkan answer wins when it is conclusive; the result replaces the cached identity.
     */
    fun refine(device: VulkanDevice?): GpuIdentity {
        val base = detect()
        val fromVulkan = device?.let(::fromVulkan) ?: return base
        if (fromVulkan.kind == GpuKind.UNKNOWN) return base
        val merged = fromVulkan.copy(
            model = fromVulkan.model ?: base.model,
            adrenoGeneration = fromVulkan.adrenoGeneration ?: base.adrenoGeneration,
        )
        cached = merged
        return merged
    }

    /** Classifies a Vulkan physical device; [GpuKind.UNKNOWN] when it says nothing useful. */
    fun fromVulkan(device: VulkanDevice): GpuIdentity {
        val text = listOfNotNull(device.name, device.driverName, device.driverInfo, device.vendorName).joinToString(" ")
        val kind = when {
            device.vendorId == VENDOR_QUALCOMM || ADRENO_WORDS.containsMatchIn(text) -> GpuKind.ADRENO
            device.vendorId == VENDOR_SAMSUNG || XCLIPSE_WORDS.containsMatchIn(text) -> GpuKind.XCLIPSE
            // RADV on an AMD id is only seen on Xclipse phones.
            device.vendorId == VENDOR_AMD -> GpuKind.XCLIPSE
            device.vendorId == VENDOR_ARM || device.vendorId == VENDOR_IMG || OTHER_WORDS.containsMatchIn(text) -> GpuKind.OTHER
            else -> GpuKind.UNKNOWN
        }
        return GpuIdentity(
            kind = kind,
            model = device.name.takeIf { it.isNotBlank() },
            adrenoGeneration = if (kind == GpuKind.ADRENO) adrenoGeneration(device.name) else null,
            evidence = "vulkan: ${device.name} (vendor 0x%04x)".format(device.vendorId),
        )
    }

    private fun detectFromSystem(): GpuIdentity = runCatching {
        val kgsl = readFirst(KGSL_MODEL_PATHS)
        if (kgsl != null) {
            return@runCatching GpuIdentity(GpuKind.ADRENO, kgsl, adrenoGeneration(kgsl), "kgsl gpu_model: $kgsl")
        }
        val vulkanHal = systemProperty("ro.hardware.vulkan")
        val egl = systemProperty("ro.hardware.egl")
        val soc = listOfNotNull(
            systemProperty("ro.soc.manufacturer"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
        ).joinToString(" ")
        val socModel = listOfNotNull(
            systemProperty("ro.soc.model"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
        ).joinToString(" ")
        val board = listOfNotNull(systemProperty("ro.board.platform"), Build.HARDWARE, Build.BOARD).joinToString(" ")
        val hal = "$vulkanHal $egl".lowercase()
        when {
            "adreno" in hal -> GpuIdentity(GpuKind.ADRENO, null, null, "ro.hardware.vulkan/egl: ${hal.trim()}")
            "mali" in hal || "powervr" in hal || "img" in hal.split(' ') ->
                GpuIdentity(GpuKind.OTHER, null, null, "ro.hardware.vulkan/egl: ${hal.trim()}")
            "samsung" in hal || "xclipse" in hal ->
                GpuIdentity(GpuKind.XCLIPSE, null, null, "ro.hardware.vulkan/egl: ${hal.trim()}")
            QUALCOMM_SOC.containsMatchIn(soc) || QUALCOMM_BOARD.containsMatchIn(board) ->
                GpuIdentity(GpuKind.ADRENO, null, null, "soc/board: ${soc.trim()} ${board.trim()}".trim())
            // Exynos 2200 / 2400 / 2500 (s5e9925, s5e9945, s5e9955) carry Xclipse; older Exynos are Mali.
            XCLIPSE_SOC.containsMatchIn("$socModel $board") ->
                GpuIdentity(GpuKind.XCLIPSE, null, null, "soc/board: ${socModel.trim()} ${board.trim()}".trim())
            else -> GpuIdentity(GpuKind.UNKNOWN, null, null, "no conclusive GPU signal")
        }
    }.getOrElse { GpuIdentity(GpuKind.UNKNOWN, null, null, "detection failed: ${it.javaClass.simpleName}") }

    /** "Adreno740v2" / "Adreno (TM) 830" / "Turnip Adreno (TM) 750" -> 7 / 8 / 7. */
    internal fun adrenoGeneration(model: String?): Int? {
        if (model.isNullOrBlank()) return null
        val digits = ADRENO_NUMBER.find(model)?.groupValues?.get(1) ?: return null
        return digits.firstOrNull()?.digitToIntOrNull()
    }

    private fun readFirst(paths: List<String>): String? = paths.firstNotNullOfOrNull { path ->
        runCatching { File(path).takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
    }

    @Suppress("PrivateApi")
    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private const val VENDOR_QUALCOMM = 0x5143
    private const val VENDOR_AMD = 0x1002
    private const val VENDOR_SAMSUNG = 0x144D
    private const val VENDOR_ARM = 0x13B5
    private const val VENDOR_IMG = 0x1010

    private val KGSL_MODEL_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_model",
        "/sys/kernel/gpu/gpu_model",
    )
    private val ADRENO_WORDS = Regex("""(?i)adreno|qualcomm|turnip|freedreno""")
    private val XCLIPSE_WORDS = Regex("""(?i)xclipse|radv|samsung""")
    private val OTHER_WORDS = Regex("""(?i)\bmali\b|powervr|immortalis""")
    private val QUALCOMM_SOC = Regex("""(?i)\bqti\b|qualcomm""")
    private val QUALCOMM_BOARD = Regex("""(?i)\bqcom\b|\bmsm\d|\bsdm\d|\bsm\d{4}|kalama|taro|lahaina|pineapple|sun\b|kona|crow|parrot|cliffs""")
    private val XCLIPSE_SOC = Regex("""(?i)s5e99[2-9]5|exynos\s*2[2-9]\d{2}|\be2[2-9]\d{2}\b""")
    private val ADRENO_NUMBER = Regex("""(?i)adreno\D{0,8}(\d{3})""")
}
