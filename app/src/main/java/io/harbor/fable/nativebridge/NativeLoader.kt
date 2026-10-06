package io.harbor.fable.nativebridge

/**
 * JNI entry points of `libfable_native` (Wine launcher fallback in `wine_launcher.cpp`).
 *
 * Windows programs are x86_64, so the launcher starts them through an x86_64 translator —
 * `box64 wine <program>` or `FEXInterpreter wine <program>`; see [launchWineContainer].
 */
object NativeLoader {
    /** [launchWineContainer] translator names; match `Container.translator`. */
    const val TRANSLATOR_BOX64 = "box64"
    const val TRANSLATOR_FEX = "fex"

    /** False when `libfable_native` could not be loaded; the external functions then throw. */
    val isLoaded: Boolean = try {
        System.loadLibrary("fable_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /**
     * DIAGNOSTIC FALLBACK ONLY. Wine is normally started with ProcessBuilder by
     * `io.harbor.fable.data.WineProcessLauncher`; this JNI fork/execve path killed the app with a
     * native signal on Android 16 and is used only when a container sets `FABLE_LAUNCHER=native`.
     * It writes `[fable] native: …` breadcrumbs to `fable-launch.log` at every stage.
     *
     * Starts Wine for [exePath] inside the container directory [containerPath] and returns the
     * child process id, or -1 on failure (see [lastLaunchError]).
     *
     * The Wine build is expected under [containerPath] (`bin/wine`). x86_64 Wine runs on an ARM64
     * device through a translator: [translator] is [TRANSLATOR_BOX64] or [TRANSLATOR_FEX] (null
     * means Box64) and [translatorPath] the matching `box64` / `FEXInterpreter` executable; the
     * process is then started as `<translatorPath> wine …`. When [translatorPath] is null the
     * launcher looks for a copy inside the container.
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
        translator: String?,
        translatorPath: String?,
    ): Int

    /** Why the last [launchWineContainer] call failed, or null when it did not fail. */
    external fun lastLaunchError(): String?

    /**
     * Check if a Wine build is available on the system.
     */
    external fun isWineAvailable(winePath: String): Boolean
}
