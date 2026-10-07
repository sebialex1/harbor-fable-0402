package io.harbor.fable.app

import io.harbor.fable.data.LaunchLog
import android.app.Application
import android.content.Context
import io.harbor.fable.data.AssetRepository
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.DownloadManager
import io.harbor.fable.data.DriverRepository
import io.harbor.fable.data.GitHubReleaseFetcher
import io.harbor.fable.data.SettingsRepository
import io.harbor.fable.data.SetupManager

/**
 * Application entry point. Wires the data layer singletons and exposes them
 * to screens via [from].
 *
 * Repositories are lazily created on first access through their
 * `get(context)` factory methods. The download manager is eagerly initialized
 * from [onCreate] so it can restore its persisted queue; network refreshes stay
 * deferred until requested. The manifest registers this class via `android:name`.
 */
class FableApp : Application() {

    val containerRepository: ContainerRepository
        get() = ContainerRepository.get(this)

    val assetRepository: AssetRepository
        get() = AssetRepository.get(this)

    val gitHubReleaseFetcher: GitHubReleaseFetcher
        get() = GitHubReleaseFetcher.get(this)

    val downloadManager: DownloadManager
        get() = DownloadManager.get(this)

    /** RADV Xclipse releases, downloaded packages and the single active driver. */
    val driverRepository: DriverRepository
        get() = DriverRepository.get(this)

    /** First-run setup: downloads the recommended Wine, Box64, driver, DXVK and VKD3D-Proton packages. */
    val setupManager: SetupManager
        get() = SetupManager.get(this)

    /** App-wide settings, including the defaults used for new containers. */
    val settingsRepository: SettingsRepository
        get() = SettingsRepository.get(this)

    override fun onCreate() {
        super.onCreate()
        // Anything that still escapes (a background thread, a framework callback) is written to
        // filesDir/logs/crash-*.log before the default handler ends the process, so the next
        // launch can show what happened (Settings > Diagnostics).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            LaunchLog.crash(this, thread, error)
            previous?.uncaughtException(thread, error)
        }
        // Eagerly create the download manager so its persisted queue is
        // restored before the user interacts with the UI. The notification
        // channel is created when the foreground service is first started.
        @Suppress("UNUSED_VARIABLE")
        val dm = downloadManager
    }

    companion object {
        /**
         * Returns the [FableApp] for the given context. Throws if the
         * Application is not a [FableApp] (e.g. in a test without a custom
         * Application class).
         */
        fun from(context: Context): FableApp =
            context.applicationContext as FableApp
    }
}
