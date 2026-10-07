package io.harbor.fable.data

import android.os.Build
import android.util.Log
import java.io.File

/**
 * What Box64 wants to know about the host CPU, read the Android way.
 *
 * Box64 fills its CPUID brand string, core count and clock from `/proc/cpuinfo` and
 * `/sys/devices/system/cpu/cpuN/cpufreq`. On ARM64 `/proc/cpuinfo` has no "model name" line, so it
 * falls back to `popen("lscpu")` (older builds: `lscpu | grep "Model name:" | sed …`), and Android
 * has no `lscpu`: the launch log shows `sh: lscpu: inaccessible or not found` on every start.
 *
 * Two fixes, so any Box64 version is covered:
 * - [box64Environment]: `BOX64_SYSINFO_CACHED=1` plus `BOX64_SYSINFO_NCPU` / `_CPUNAME` /
 *   `_FREQUENCY`, the variables Box64 itself exports for its child processes once it has read
 *   the CPU. Box64 builds that know them skip `/proc/cpuinfo` and `lscpu` entirely; older
 *   builds ignore them.
 * - [installLscpu]: a tiny `lscpu` shell script in the container's `bin/` (first on the Wine
 *   process's `PATH`) that prints the same facts in util-linux's format, for builds that still
 *   run `lscpu`.
 */
internal data class HostCpu(
    /** Number of CPU cores (possible CPUs, not only the ones online right now). */
    val count: Int,
    /** Human-readable SoC / CPU name, printable ASCII only. */
    val name: String,
    /** Highest core clock in Hz, or null when cpufreq isn't readable. */
    val maxFrequencyHz: Long?,
) {
    /** Variables that make Box64 take the CPU facts from the environment instead of `lscpu`. */
    fun box64Environment(): List<String> = buildList {
        add("BOX64_SYSINFO_CACHED=1")
        add("BOX64_SYSINFO_NCPU=$count")
        add("BOX64_SYSINFO_CPUNAME=$name")
        maxFrequencyHz?.let { add("BOX64_SYSINFO_FREQUENCY=$it") }
    }

    /** util-linux `lscpu` output with the fields Box64 parses (`CPU(s):`, `Model name:`, `CPU max MHz:`). */
    fun lscpuOutput(): String = buildString {
        fun row(key: String, value: String) = append(key.padEnd(LSCPU_KEY_WIDTH)).append(value).append('\n')
        row("Architecture:", "aarch64")
        row("CPU op-mode(s):", "32-bit, 64-bit")
        row("Byte Order:", "Little Endian")
        row("CPU(s):", count.toString())
        row("On-line CPU(s) list:", if (count > 1) "0-${count - 1}" else "0")
        row("Vendor ID:", "ARM")
        row("Model name:", name)
        row("Thread(s) per core:", "1")
        row("Core(s) per socket:", count.toString())
        row("Socket(s):", "1")
        maxFrequencyHz?.let { row("CPU max MHz:", String.format(java.util.Locale.US, "%.4f", it / 1_000_000.0)) }
    }

    /**
     * Writes `lscpu` into [binDir] (the container's `bin/`, on the Wine process's `PATH`) unless
     * an identical one is there. Returns the script, or null when it couldn't be written.
     */
    fun installLscpu(binDir: File): File? = runCatching {
        binDir.mkdirs()
        val script = File(binDir, LSCPU)
        val body = buildString {
            append("#!/system/bin/sh\n")
            append("# Written by Fable: Android has no lscpu, and Box64 runs it to read the CPU name,\n")
            append("# core count and clock. Same fields as util-linux lscpu; arguments are ignored.\n")
            append("cat <<'").append(HEREDOC).append("'\n")
            append(lscpuOutput())
            append(HEREDOC).append('\n')
        }
        if (!script.isFile || runCatching { script.readText() }.getOrNull() != body) {
            val tmp = File(binDir, "$LSCPU.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(script)) {
                script.delete()
                check(tmp.renameTo(script)) { "rename failed" }
            }
        }
        script.setReadable(true, false)
        script.setExecutable(true, false)
        script
    }.onFailure { Log.w(TAG, "Couldn't write the lscpu stub into $binDir", it) }.getOrNull()

    companion object {
        private const val TAG = "HostCpu"
        private const val LSCPU = "lscpu"
        private const val HEREDOC = "FABLE_LSCPU_EOF"
        private const val LSCPU_KEY_WIDTH = 33
        private const val MAX_NAME = 48

        @Volatile
        private var cached: HostCpu? = null

        /** The host CPU, read once per process (it doesn't change while the app runs). */
        fun get(): HostCpu = cached ?: read().also { cached = it }

        private fun read(): HostCpu {
            val count = possibleCpuCount() ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return HostCpu(count = count, name = cpuName(), maxFrequencyHz = maxFrequencyHz(count))
        }

        /** `/sys/devices/system/cpu/possible` is a range list such as `0-7` or `0-3,4-7`. */
        private fun possibleCpuCount(): Int? = runCatching {
            val text = File("/sys/devices/system/cpu/possible").readText().trim()
            text.split(',').sumOf { part ->
                val bounds = part.trim().split('-')
                if (bounds.size == 2) bounds[1].toInt() - bounds[0].toInt() + 1 else 1
            }
        }.getOrNull()?.takeIf { it > 0 }

        /** Highest `cpuinfo_max_freq` (kHz) over the cores, in Hz. */
        private fun maxFrequencyHz(count: Int): Long? = (0 until count.coerceAtMost(64))
            .mapNotNull { cpu ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull()
            }
            .maxOrNull()
            ?.takeIf { it > 0 }
            ?.let { it * 1000L }

        /** "QTI SM8550", "Samsung s5e9945", the `/proc/cpuinfo` Hardware line, or `Build.HARDWARE`. */
        private fun cpuName(): String {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                    .filter { !it.isNullOrBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true) }
                    .joinToString(" ")
            } else {
                ""
            }
            val hardware = runCatching {
                File("/proc/cpuinfo").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim()
                }
            }.getOrNull()
            val raw = listOf(soc, hardware, Build.HARDWARE)
                .firstOrNull { !it.isNullOrBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true) }
                ?: "ARM64"
            val clean = raw.filter { it in ' '..'~' && it != '\'' && it != '"' && it != '\\' }.trim().take(MAX_NAME)
            return clean.ifEmpty { "ARM64" }
        }
    }
}
