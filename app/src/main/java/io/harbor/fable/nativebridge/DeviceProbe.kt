package io.harbor.fable.nativebridge

import android.os.Build

/** GPU / device facts shown on the Drivers and Settings screens. */
data class DeviceGpuInfo(
    val gpu: String,
    val vendor: String,
    val device: String,
    val abi: String,
    val sdk: String,
    val fromNative: Boolean,
)

/**
 * Reads device GPU info through [AdrenoToolsBridge], falling back to [Build] values when
 * the native library is unavailable. Never throws.
 */
object DeviceProbe {

    fun read(): DeviceGpuInfo = readNative() ?: readFallback()

    private fun readNative(): DeviceGpuInfo? = runCatching {
        // Format: "GPU: x | Vendor: y | Device: z | ABI: a | SDK: n"
        val fields = AdrenoToolsBridge.getGpuInfo()
            .split("|")
            .associate { part -> part.substringBefore(':').trim() to part.substringAfter(':', "").trim() }
        val fallback = readFallback()
        DeviceGpuInfo(
            gpu = fields["GPU"].orEmpty().ifBlank { fallback.gpu },
            vendor = fields["Vendor"].orEmpty().ifBlank { fallback.vendor },
            device = fields["Device"].orEmpty().ifBlank { fallback.device },
            abi = fields["ABI"].orEmpty().ifBlank { fallback.abi },
            sdk = fields["SDK"].orEmpty().ifBlank { fallback.sdk },
            fromNative = true,
        )
    }.getOrNull()

    private fun readFallback(): DeviceGpuInfo = DeviceGpuInfo(
        gpu = "Unknown",
        vendor = Build.HARDWARE.orEmpty(),
        device = Build.MODEL.orEmpty(),
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        sdk = Build.VERSION.SDK_INT.toString(),
        fromNative = false,
    )
}
