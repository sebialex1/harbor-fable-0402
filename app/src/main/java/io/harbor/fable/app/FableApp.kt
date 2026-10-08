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
import io.harbor.fable.ui.components.FableUi
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * The app-wide UI services (message line, long-lived UI scope). One per process, so a message
     * survives navigation and Activity recreation and is never shown (or animated in) twice.
     *
     * Work launched from screens (container launches above all) must never take the app down: an
     * exception that escapes the scope is logged to filesDir/logs and shown as a message.
     */
    val fableUi: FableUi by lazy {
        val holder = arrayOfNulls<FableUi>(1)
        val guard = CoroutineExceptionHandler { _, error ->
            Log.e("FableUi", "Uncaught error in UI scope", error)
            LaunchLog.crash(this, Thread.currentThread(), error)
            holder[0]?.showMessage("Something failed: ${error.javaClass.simpleName}. Log saved", long = true)
        }
        FableUi(scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)).also { holder[0] = it }
    }

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
