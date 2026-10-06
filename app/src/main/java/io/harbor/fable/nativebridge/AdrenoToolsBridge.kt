package io.harbor.fable.nativebridge

/**
 * JNI bridge to the native adrenotools driver loader.
 * Delegates to native_bridge.cpp via JNI.
 */
object AdrenoToolsBridge {
    init {
        System.loadLibrary("fable_native")
    }

    /**
     * Validate a driver zip package.
     * Checks for meta.json, vulkan.radeon.so (or equivalent), and correct ABI.
     * Returns null on success, error message on failure.
     */
    external fun validateDriverZip(zipPath: String): String?

    /**
     * Extract and install a driver package to the app's private storage.
     * Returns the installed driver library path, or null on failure.
     */
    external fun installDriver(zipPath: String, destDir: String): String?

    /**
     * Load a driver library via the adrenotools linker namespace bypass.
     * Returns a handle for the loaded driver, or 0 on failure.
     */
    external fun loadDriver(libraryPath: String): Long

    /**
     * Unload a previously loaded driver.
     */
    external fun unloadDriver(handle: Long): Boolean

    /**
     * Get the Vulkan ICD function pointer from a loaded driver.
     */
    external fun getVkGetInstanceProcAddr(handle: Long): Long

    /**
     * Check if the system supports adrenotools driver loading.
     */
    external fun isAdrenoToolsSupported(): Boolean

    /**
     * Get the GPU info string from the system.
     */
    external fun getGpuInfo(): String
}
