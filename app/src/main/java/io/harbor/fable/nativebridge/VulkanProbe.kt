package io.harbor.fable.nativebridge

import android.util.Log
import io.harbor.fable.data.models.VulkanProbeResult
import io.harbor.fable.data.models.VulkanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the native Vulkan extension probe and remembers the last result per [VulkanSource], so
 * the extensions screen shows instantly when reopened and the driver is only loaded again when
 * a different one is installed. Never throws: a missing native library or a parse problem becomes
 * a [VulkanProbeResult] with `ok = false`.
 */
object VulkanProbe {
    private const val TAG = "VulkanProbe"

    private val mutex = Mutex()
    private val _results = MutableStateFlow<Map<VulkanSource, VulkanProbeResult>>(emptyMap())
    private val _probing = MutableStateFlow<Set<VulkanSource>>(emptySet())

    /** Last result per source. */
    val results: StateFlow<Map<VulkanSource, VulkanProbeResult>> = _results.asStateFlow()

    /** Sources being probed right now. */
    val probing: StateFlow<Set<VulkanSource>> = _probing.asStateFlow()

    /**
     * The cached result for [source] if it was produced from [libraryPath] (always true for the
     * system source), or null when a probe is needed.
     */
    fun cached(source: VulkanSource, libraryPath: String?): VulkanProbeResult? {
        val result = _results.value[source] ?: return null
        return if (source == VulkanSource.SYSTEM || result.library == libraryPath) result else null
    }

    /**
     * Probes [source]; [libraryPath] is the installed ICD for [VulkanSource.INSTALLED_DRIVER] and
     * ignored for the system. Returns the cached result unless [force] is set or the installed
     * driver changed. Concurrent calls share one probe.
     */
    suspend fun probe(source: VulkanSource, libraryPath: String?, force: Boolean = false): VulkanProbeResult {
        if (source == VulkanSource.INSTALLED_DRIVER && libraryPath.isNullOrBlank()) {
            return VulkanProbeResult.failure(source, "No driver is installed")
        }
        mutex.withLock {
            if (!force) cached(source, libraryPath)?.let { return it }
            _probing.update { it + source }
            try {
                val result = withContext(Dispatchers.IO) { run(source, libraryPath) }
                _results.update { it + (source to result) }
                return result
            } finally {
                _probing.update { it - source }
            }
        }
    }

    /** Forgets the result for [source]; the next [probe] runs again. */
    fun invalidate(source: VulkanSource) {
        _results.update { it - source }
    }

    private fun run(source: VulkanSource, libraryPath: String?): VulkanProbeResult {
        val path = if (source == VulkanSource.INSTALLED_DRIVER) libraryPath else null
        val json = try {
            AdrenoToolsBridge.probeVulkanExtensions(path)
        } catch (error: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library unavailable", error)
            return VulkanProbeResult.failure(source, "The native Vulkan probe isn't available on this device", path)
        } catch (error: Throwable) {
            Log.w(TAG, "Probe threw", error)
            return VulkanProbeResult.failure(source, error.message ?: error.javaClass.simpleName, path)
        }
        return runCatching { VulkanProbeResult.fromJson(json, source) }
            .onFailure { Log.w(TAG, "Unreadable probe result: ${json.take(200)}", it) }
            .getOrElse { VulkanProbeResult.failure(source, "The probe returned an unreadable result", path) }
    }
}
