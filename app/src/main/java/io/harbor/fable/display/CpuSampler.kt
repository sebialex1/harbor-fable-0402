package io.harbor.fable.display

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Rough CPU usage for the performance HUD, sampled from `/proc`.
 *
 * Prefers system-wide usage from `/proc/stat`. Since Android 8 apps usually can't read it
 * (SELinux), it falls back to the CPU time of every process visible to the app — this app plus
 * the container's Wine processes, which run under the same uid — as a share of all cores.
 * Call [sample] periodically (off the main thread); each call reports usage since the previous one.
 */
internal class CpuSampler {
    data class Usage(val percent: Int, val systemWide: Boolean)

    private val clockTicks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var systemUnavailable = false
    private var lastTotal = -1L
    private var lastIdle = -1L

    private var lastWallNanos = 0L
    private var lastPidTicks: Map<Int, Long> = emptyMap()

    /** Usage since the previous call, or null on the first call / when nothing can be read. */
    fun sample(): Usage? {
        if (!systemUnavailable) {
            val system = readSystem()
            if (system != null) return system.takeIf { it.percent >= 0 }
            systemUnavailable = true
        }
        return readProcesses()
    }

    /** Null when `/proc/stat` is unreadable; percent -1 on the first successful read. */
    private fun readSystem(): Usage? {
        val line = runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull()
        if (line == null || !line.startsWith("cpu ")) return null
        val fields = line.substring(4).trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        // user nice system idle iowait irq softirq steal (guest time is already in user).
        val counted = fields.take(8)
        val total = counted.sum()
        val idle = counted[3] + (counted.getOrNull(4) ?: 0L)
        val prevTotal = lastTotal
        val prevIdle = lastIdle
        lastTotal = total
        lastIdle = idle
        if (prevTotal < 0) return Usage(-1, true)
        val dTotal = total - prevTotal
        val dIdle = idle - prevIdle
        if (dTotal <= 0) return Usage(0, true)
        val percent = ((dTotal - dIdle) * 100 / dTotal).toInt().coerceIn(0, 100)
        return Usage(percent, true)
    }

    private fun readProcesses(): Usage? {
        val now = System.nanoTime()
        val ticks = HashMap<Int, Long>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val t = readPidTicks(pid) ?: return@forEach
            ticks[pid] = t
        }
        if (ticks.isEmpty()) return null
        val prev = lastPidTicks
        val prevWall = lastWallNanos
        lastPidTicks = ticks
        lastWallNanos = now
        if (prevWall == 0L) return null
        // Per-process deltas, so exiting processes don't make the sum go backwards. A process
        // first seen now started during the interval, so all of its time counts.
        var delta = 0L
        for ((pid, t) in ticks) delta += (t - (prev[pid] ?: 0L)).coerceAtLeast(0L)
        val elapsedSec = (now - prevWall) / 1_000_000_000.0
        if (elapsedSec <= 0.0) return null
        val busySec = delta.toDouble() / clockTicks
        val percent = (busySec / (elapsedSec * cores) * 100).toInt().coerceIn(0, 100)
        return Usage(percent, false)
    }

    /** utime + stime of [pid] in clock ticks, or null if it can't be read. */
    private fun readPidTicks(pid: Int): Long? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        // The command name (field 2) may contain spaces; fields after it start with state (3).
        val rest = stat.substring(stat.lastIndexOf(')') + 1).trim().split(WHITESPACE)
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
