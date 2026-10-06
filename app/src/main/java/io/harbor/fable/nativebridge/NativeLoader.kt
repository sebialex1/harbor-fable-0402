package io.harbor.fable.nativebridge

/**
 * Stub placeholder for Wine process management.
 * The real implementation will manage Wine container lifecycles.
 */
object NativeLoader {
    init {
        try {
            System.loadLibrary("fable_native")
        } catch (_: UnsatisfiedLinkError) {
            // Native lib not built yet — stubs still work
        }
    }

    /**
     * Launch a Wine container with the given configuration.
     * This will be implemented in the native layer.
     */
    external fun launchWineContainer(
        containerPath: String,
        exePath: String,
        envVars: Array<String>,
        driverPath: String?,
    ): Int

    /**
     * Check if a Wine build is available on the system.
     */
    external fun isWineAvailable(winePath: String): Boolean
}
