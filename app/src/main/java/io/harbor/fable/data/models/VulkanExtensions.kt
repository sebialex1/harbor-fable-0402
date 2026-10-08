package io.harbor.fable.data.models

import org.json.JSONArray
import org.json.JSONObject

/** Which Vulkan implementation an extension list was read from. */
enum class VulkanSource(val label: String) {
    /** The installed, active driver (Turnip or RADV Xclipse), opened through the adrenotools loader. */
    INSTALLED_DRIVER("Installed driver"),

    /** `libvulkan.so`: the driver the device vendor ships. */
    SYSTEM("System Vulkan"),
}

/** Instance extensions describe the loader/instance level; device extensions describe the GPU. */
enum class VulkanExtensionScope(val label: String) { INSTANCE("Instance"), DEVICE("Device") }

/** Who registered an extension, from the vendor tag after `VK_`. */
enum class VulkanExtensionFamily(val label: String, val description: String) {
    KHR("KHR", "Khronos-ratified"),
    EXT("EXT", "Multi-vendor"),
    VENDOR("Vendor", "Vendor-specific"),
    ;

    companion object {
        fun of(extensionName: String): VulkanExtensionFamily = when (vendorTag(extensionName)) {
            "KHR" -> KHR
            "EXT" -> EXT
            else -> VENDOR
        }

        /** "VK_KHR_swapchain" -> "KHR", "VK_ANDROID_external_memory_android_hardware_buffer" -> "ANDROID". */
        fun vendorTag(extensionName: String): String = extensionName.removePrefix("VK_").substringBefore('_')
    }
}

/** One `VkExtensionProperties` entry. */
data class VulkanExtension(
    val name: String,
    /** The extension's own revision (`specVersion`), not a Vulkan API version. */
    val specVersion: Int,
    val scope: VulkanExtensionScope,
) {
    val vendorTag: String get() = VulkanExtensionFamily.vendorTag(name)
    val family: VulkanExtensionFamily get() = VulkanExtensionFamily.of(name)

    /** Name without the `VK_` prefix, for denser rows. */
    val shortName: String get() = name.removePrefix("VK_")
}

/** A physical device the implementation enumerated, with its device extensions. */
data class VulkanDevice(
    val name: String,
    /** `VkPhysicalDeviceProperties.apiVersion`, e.g. "1.4.358". */
    val apiVersion: String,
    val apiVersionRaw: Long,
    /** Decoded as major.minor.patch; vendors may encode it differently, so prefer [driverInfo]. */
    val driverVersion: String,
    val driverVersionRaw: Long,
    val vendorId: Int,
    val deviceId: Int,
    val deviceType: String,
    /** `VkPhysicalDeviceDriverProperties.driverName`, e.g. "radv"; null when unavailable. */
    val driverName: String?,
    /** `VkPhysicalDeviceDriverProperties.driverInfo`, e.g. "Mesa 26.3.0-devel"; null when unavailable. */
    val driverInfo: String?,
    val conformanceVersion: String?,
    val extensions: List<VulkanExtension>,
) {
    /** "1.4" from "1.4.358": the API generation the device supports. */
    val apiGeneration: String get() = apiVersion.split('.').take(2).joinToString(".")

    val vendorName: String get() = when (vendorId) {
        0x1002 -> "AMD"
        0x10DE -> "NVIDIA"
        0x8086 -> "Intel"
        0x13B5 -> "Arm"
        0x5143 -> "Qualcomm"
        0x1010 -> "Imagination"
        0x144D -> "Samsung"
        0x10005 -> "Mesa"
        else -> "0x${Integer.toHexString(vendorId).uppercase()}"
    }
}

/**
 * Everything one probe of a [VulkanSource] produced. [ok] is false when the implementation could
 * not even be opened or an instance could not be created ([error] says why). [deviceError] is set
 * when the instance half worked but device enumeration did not, so a partial result still shows.
 */
data class VulkanProbeResult(
    val source: VulkanSource,
    /** Path of the ICD, or "libvulkan.so" for the system. */
    val library: String?,
    val ok: Boolean,
    val error: String?,
    val deviceError: String?,
    /** `vkEnumerateInstanceVersion`, e.g. "1.3.0"; null on failure. */
    val instanceVersion: String?,
    val instanceExtensions: List<VulkanExtension>,
    val devices: List<VulkanDevice>,
    val probedAt: Long,
) {
    val primaryDevice: VulkanDevice? get() = devices.firstOrNull()

    val deviceExtensions: List<VulkanExtension> get() = primaryDevice?.extensions.orEmpty()

    val totalCount: Int get() = instanceExtensions.size + deviceExtensions.size

    companion object {
        fun failure(source: VulkanSource, message: String, library: String? = null): VulkanProbeResult = VulkanProbeResult(
            source = source,
            library = library,
            ok = false,
            error = message,
            deviceError = null,
            instanceVersion = null,
            instanceExtensions = emptyList(),
            devices = emptyList(),
            probedAt = System.currentTimeMillis(),
        )

        /** Parses the document produced by `probe_vulkan_extensions`. Throws on malformed JSON. */
        fun fromJson(text: String, source: VulkanSource): VulkanProbeResult {
            val obj = JSONObject(text)
            val devices = obj.optJSONArray("devices").toObjects().map { device ->
                VulkanDevice(
                    name = device.optString("name").ifBlank { "Unknown device" },
                    apiVersion = device.optString("apiVersion").ifBlank { "0.0.0" },
                    apiVersionRaw = device.optLong("apiVersionRaw", 0L),
                    driverVersion = device.optString("driverVersion").ifBlank { "0.0.0" },
                    driverVersionRaw = device.optLong("driverVersionRaw", 0L),
                    vendorId = device.optInt("vendorId", 0),
                    deviceId = device.optInt("deviceId", 0),
                    deviceType = device.optString("deviceType").ifBlank { "other" },
                    driverName = device.stringOrNull("driverName"),
                    driverInfo = device.stringOrNull("driverInfo"),
                    conformanceVersion = device.stringOrNull("conformanceVersion"),
                    extensions = device.optJSONArray("extensions").toExtensions(VulkanExtensionScope.DEVICE),
                )
            }
            return VulkanProbeResult(
                source = source,
                library = obj.stringOrNull("library"),
                ok = obj.optBoolean("ok", false),
                error = obj.stringOrNull("error"),
                deviceError = obj.stringOrNull("deviceError"),
                instanceVersion = obj.stringOrNull("instanceVersion"),
                instanceExtensions = obj.optJSONArray("instanceExtensions").toExtensions(VulkanExtensionScope.INSTANCE),
                devices = devices,
                probedAt = System.currentTimeMillis(),
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun JSONArray?.toObjects(): List<JSONObject> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it) }
        }

        private fun JSONArray?.toExtensions(scope: VulkanExtensionScope): List<VulkanExtension> = toObjects()
            .mapNotNull { item ->
                val name = item.optString("name")
                if (name.isBlank()) null else VulkanExtension(name, item.optInt("specVersion", 0), scope)
            }
            .sortedBy { it.name }
    }
}
