package io.harbor.fable.display

import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.HudLayout
import io.harbor.fable.data.models.HudSettings
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * One second's readings for the performance HUD. A null field means the reading isn't known:
 * [HudReadout] shows it as "--" while it may still arrive (the first CPU sample needs a
 * previous one, no frame drawn yet) and as "unavailable" when the device doesn't expose it.
 */
data class HudSample(
    val fps: Int? = null,
    val frameTimeMs: Float? = null,
    /** API the running program actually loaded ([GraphicsApiDetector]); null until seen. */
    val detectedApi: String? = null,
    /** What the container is set up for ([HudReadout.configuredApi]). */
    val configuredApi: String? = null,
    val driver: String? = null,
    val gpuBusyPercent: Int? = null,
    val gpuTempC: Float? = null,
    val cpuPercent: Int? = null,
    /** CPU usage covers only Fable and Wine (Android hides /proc/stat), not the whole system. */
    val cpuAppOnly: Boolean = false,
    /** False until a CPU reading had the chance to arrive (the sampler needs two reads). */
    val cpuWarmedUp: Boolean = true,
    val ramUsedBytes: Long? = null,
    val ramTotalBytes: Long? = null,
    val resolution: String? = null,
)

/** Turns [HudSettings] + a [HudSample] into the HUD's label / value pairs, in a fixed order. */
object HudReadout {
    const val UNAVAILABLE = "unavailable"
    const val PENDING = "--"

    /** Between readings in [HudLayout.ROW]; lines in [HudLayout.STACKED]. */
    fun separator(layout: HudLayout): String = when (layout) {
        HudLayout.STACKED -> "\n"
        HudLayout.ROW -> "  "
    }

    fun lines(hud: HudSettings, sample: HudSample): List<Pair<String, String>> = buildList {
        if (hud.showFps) add("FPS" to (sample.fps?.toString() ?: PENDING))
        if (hud.showFrameTime) add("FT" to (sample.frameTimeMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: PENDING))
        if (hud.showApi) add("API" to api(sample))
        if (hud.showDriver) add("DRV" to (sample.driver ?: UNAVAILABLE))
        if (hud.showGpuUsage || hud.showGpuTemp) add("GPU" to gpu(hud, sample))
        if (hud.showCpu) {
            val cpu = sample.cpuPercent?.let { "$it%" + if (sample.cpuAppOnly) " app" else "" }
            add("CPU" to (cpu ?: if (sample.cpuWarmedUp) UNAVAILABLE else PENDING))
        }
        if (hud.showRam) add("RAM" to ram(sample))
        if (hud.showResolution) add("RES" to (sample.resolution ?: PENDING))
    }

    /** Plain text of [lines] in [layout] (the HUD adds colour on top). */
    fun plain(lines: List<Pair<String, String>>, layout: HudLayout): String =
        lines.joinToString(separator(layout)) { (label, value) -> "$label $value" }

    /**
     * What the program uses when Wine's DLL loads said so, else the container's setup marked
     * "(set)" so it isn't mistaken for a detection.
     */
    private fun api(sample: HudSample): String = sample.detectedApi
        ?: sample.configuredApi?.let { "$it (set)" }
        ?: PENDING

    private fun gpu(hud: HudSettings, sample: HudSample): String {
        val parts = buildList {
            if (hud.showGpuUsage) add(sample.gpuBusyPercent?.let { "$it%" })
            if (hud.showGpuTemp) add(sample.gpuTempC?.let { String.format(Locale.US, "%.0f°C", it) })
        }
        return when {
            parts.all { it == null } -> UNAVAILABLE
            parts.size == 2 && parts[0] == null -> "${parts[1]} · usage $UNAVAILABLE"
            parts.size == 2 && parts[1] == null -> "${parts[0]} · temp $UNAVAILABLE"
            else -> parts.filterNotNull().joinToString(" ")
        }
    }

    private fun ram(sample: HudSample): String {
        val used = sample.ramUsedBytes ?: return UNAVAILABLE
        val total = sample.ramTotalBytes ?: return gib(used)
        return "${gib(used, unit = false)}/${gib(total)}"
    }

    private fun gib(bytes: Long, unit: Boolean = true): String =
        String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0)) + if (unit) "G" else ""

    /** The container's Direct3D setup: DXVK / WineD3D for D3D8–11, plus VKD3D-Proton for D3D12. */
    fun configuredApi(dxvkVersion: String?, vkd3dVersion: String?): String {
        val d3d11 = if (dxvkVersion == ContainerDefaults.DXVK_OFF) "WineD3D" else "DXVK"
        return if (vkd3dVersion == ContainerDefaults.VKD3D_OFF) d3d11 else "$d3d11+VKD3D"
    }
}

/**
 * Which graphics API a Windows program really uses, from Wine's `+loaddll` lines in the process
 * log (`…:loaddll:build_module Loaded L"C:\windows\system32\d3d11.dll" at …: native`). Native
 * means the DLL Fable installed (DXVK, VKD3D-Proton), builtin means Wine's own. The newest
 * Direct3D loaded wins: games often load dxgi/d3d11 alongside d3d12, never the reverse for
 * nothing. A program that loads d3d12 only to probe for it and then renders with d3d11 still
 * reads as D3D12; that is a limit of what the log shows.
 */
class GraphicsApiDetector {
    private val loaded = LinkedHashMap<String, Boolean>()

    /** Feeds one log line; true when it changed the detection. */
    fun feed(line: String): Boolean {
        if (!line.contains(":loaddll:")) return false
        val match = DLL_LOADED.find(line) ?: return false
        val dll = match.groupValues[1].lowercase()
        if (dll !in API_DLLS || dll in loaded) return false
        val before = label
        loaded[dll] = match.groupValues[2] == "native"
        return label != before
    }

    /** "D3D11 · DXVK", "D3D12 · VKD3D-Proton", "D3D9 · WineD3D", …; null before any graphics DLL. */
    val label: String?
        get() {
            fun native(dll: String) = loaded[dll] == true
            return when {
                "d3d12.dll" in loaded ->
                    if (native("d3d12.dll") || native("d3d12core.dll")) "D3D12 · VKD3D-Proton" else "D3D12 · Wine vkd3d"
                "d3d11.dll" in loaded -> "D3D11 · " + if (native("d3d11.dll")) "DXVK" else "WineD3D"
                "d3d10core.dll" in loaded || "d3d10.dll" in loaded || "d3d10_1.dll" in loaded ->
                    "D3D10 · " + if (native("d3d10core.dll") || native("d3d10.dll") || native("d3d10_1.dll")) "DXVK" else "WineD3D"
                "d3d9.dll" in loaded -> "D3D9 · " + if (native("d3d9.dll")) "DXVK" else "WineD3D"
                "d3d8.dll" in loaded -> "D3D8 · " + if (native("d3d8.dll")) "DXVK" else "WineD3D"
                "ddraw.dll" in loaded -> "DirectDraw · WineD3D"
                "vulkan-1.dll" in loaded -> "Vulkan"
                "opengl32.dll" in loaded -> "OpenGL"
                else -> null
            }
        }

    /** Nothing newer than D3D12 can turn up, so the log needn't be read any further. */
    val isFinal: Boolean get() = "d3d12.dll" in loaded

    private companion object {
        val DLL_LOADED = Regex(""":loaddll:.*Loaded L?"[^"]*?([A-Za-z0-9_\-]+\.dll)" at [0-9A-Fa-f]+: (native|builtin)""")
        val API_DLLS = setOf(
            "d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll",
            "d3d12core.dll", "ddraw.dll", "opengl32.dll", "vulkan-1.dll",
        )
    }
}

/**
 * Reads the lines appended to [file] since the last call, a bounded chunk at a time. A partial
 * last line waits for the next call; a file that shrank (a new launch rewrote it) starts over.
 */
class LogTail(private val file: File, private val maxBytesPerRead: Int = 1 shl 20) {
    private var offset = 0L
    private var partial = ""

    fun readNewLines(): List<String> {
        val length = file.length()
        if (length < offset) {
            offset = 0L
            partial = ""
        }
        if (length == offset) return emptyList()
        val bytes = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(minOf(length - offset, maxBytesPerRead.toLong()).toInt())
                raf.readFully(buffer)
                buffer
            }
        }.getOrNull() ?: return emptyList()
        offset += bytes.size
        val text = partial + String(bytes, Charsets.UTF_8)
        val end = text.lastIndexOf('\n')
        if (end < 0) {
            partial = text
            return emptyList()
        }
        partial = text.substring(end + 1)
        return text.substring(0, end).split('\n')
    }
}

/**
 * GPU load and temperature from the kernel's sysfs, where the device exposes them and SELinux
 * lets an app read them (often it doesn't on Android 10+; then both are null and the HUD says
 * "unavailable" rather than guessing). [root] is `/` except in tests.
 */
class GpuSensors(private val root: File = File("/")) {
    private var tempFile: File? = null
    private var tempSearched = false

    /** GPU busy %, 0–100, or null when no readable source exists. */
    fun busyPercent(): Int? {
        for (path in BUSY_PERCENT) {
            readText(path)?.let { text -> firstInt(text)?.let { return it.coerceIn(0, 100) } }
        }
        // Adreno: "<busy> <total>" for the last sampling window.
        readText(KGSL_GPUBUSY)?.let { text ->
            val numbers = text.trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
            if (numbers.size >= 2 && numbers[1] > 0) return (numbers[0] * 100 / numbers[1]).toInt().coerceIn(0, 100)
        }
        return null
    }

    /** GPU temperature in °C, or null when no readable sensor exists. */
    fun temperatureC(): Float? {
        if (!tempSearched) {
            tempSearched = true
            tempFile = findTemperatureFile()
        }
        val file = tempFile ?: return null
        return runCatching { file.readText() }.getOrNull()?.let(::firstInt)?.let(::celsius)
    }

    private fun findTemperatureFile(): File? {
        for (path in TEMP_FILES) {
            val file = File(root, path)
            if (runCatching { file.readText() }.getOrNull()?.let(::firstInt)?.let(::celsius) != null) return file
        }
        val zones = File(root, "sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        return zones.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            .firstOrNull { zone ->
                val type = runCatching { File(zone, "type").readText().trim() }.getOrNull() ?: return@firstOrNull false
                GPU_ZONE.containsMatchIn(type) &&
                    runCatching { File(zone, "temp").readText() }.getOrNull()?.let(::firstInt)?.let(::celsius) != null
            }
            ?.let { File(it, "temp") }
    }

    private fun readText(path: String): String? = runCatching { File(root, path).readText() }.getOrNull()

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val INT = Regex("-?\\d+")
        private val GPU_ZONE = Regex("gpu|g3d|mali|kgsl", RegexOption.IGNORE_CASE)

        /** Files holding a plain busy percentage: Adreno, Exynos / Xclipse, MediaTek, Mali. */
        private val BUSY_PERCENT = listOf(
            "sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "sys/kernel/gpu/gpu_busy",
            "sys/kernel/ged/hal/gpu_utilization",
            "sys/class/misc/mali0/device/utilization",
        )
        private const val KGSL_GPUBUSY = "sys/class/kgsl/kgsl-3d0/gpubusy"
        private val TEMP_FILES = listOf("sys/class/kgsl/kgsl-3d0/temp", "sys/kernel/gpu/gpu_tmu")

        internal fun firstInt(text: String): Int? = INT.find(text)?.value?.toIntOrNull()

        /** Millidegrees (45000) or degrees (45) to °C; implausible values are rejected. */
        internal fun celsius(raw: Int): Float? {
            val c = if (raw > 1000 || raw < -1000) raw / 1000f else raw.toFloat()
            return c.takeIf { it > 0f && it < 150f }
        }
    }
}
