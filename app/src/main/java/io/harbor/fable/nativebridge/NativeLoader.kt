package io.harbor.fable.nativebridge

/**
 * Process management for Wine containers, implemented in `wine_launcher.cpp`.
 *
 * Windows programs are x86_64, so the launcher starts them as `box64 wine <program>`; see
 * [launchWineContainer].
 */
object NativeLoader {
    /** False when `libfable_native` could not be loaded; the external functions then throw. */
    val isLoaded: Boolean = try {
        System.loadLibrary("fable_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /**
     * Starts Wine for [exePath] inside the container directory [containerPath] and returns the
     * child process id, or -1 on failure (see [lastLaunchError]).
     *
     * The Wine build is expected under [containerPath] (`bin/wine`). When [box64Path] is set the
     * process is started as `box64 wine …`, which is how x86_64 Wine runs on an ARM64 device.
     * [exePath] may also be a Wine built-in such as `explorer`; [args] follow it on the command
     * line. [envVars] are `KEY=VALUE` pairs, [driverPath] an optional installed Vulkan driver.
     * Wine's output goes to `fable-launch.log` in the container directory.
     */
    external fun launchWineContainer(
        containerPath: String,
        exePath: String,
        args: Array<String>,
        envVars: Array<String>,
        driverPath: String?,
        box64Path: String?,
    ): Int

    /** Why the last [launchWineContainer] call failed, or null when it did not fail. */
    external fun lastLaunchError(): String?

    /**
     * Check if a Wine build is available on the system.
     */
    external fun isWineAvailable(winePath: String): Boolean
}
