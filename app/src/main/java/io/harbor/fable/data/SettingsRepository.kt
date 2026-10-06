package io.harbor.fable.data

import android.content.Context
import android.content.SharedPreferences
import io.harbor.fable.data.models.ContainerDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How frames are paced when a container runs. Consumed by the launcher once it ships. */
enum class FramePacing(val label: String) {
    OFF("Off"),
    ADAPTIVE("Adaptive"),
    LIMIT_30("30 FPS cap"),
    LIMIT_60("60 FPS cap"),
}

/**
 * Every user-configurable setting in one place.
 *
 * The `default*` fields seed the "New container" form; existing containers keep their own
 * configuration and are edited from the container detail screen.
 */
data class AppSettings(
    val defaultWineVersion: String = ContainerDefaults.WINE_VERSION,
    val defaultGraphicsDriver: String = ContainerDefaults.GRAPHICS_DRIVER,
    val defaultDriverId: String? = null,
    val defaultDxvkVersion: String? = null,
    val defaultResolution: String = ContainerDefaults.SCREEN_RESOLUTION,
    val defaultFullscreen: Boolean = false,
    val vsync: Boolean = true,
    val framePacing: FramePacing = FramePacing.ADAPTIVE,
    val refreshCatalogOnLaunch: Boolean = true,
)

/**
 * SharedPreferences-backed settings store exposed as a [StateFlow].
 *
 * Writes update the flow immediately and are persisted asynchronously with
 * [SharedPreferences.Editor.apply], so [update] is safe to call from the main thread.
 */
class SettingsRepository internal constructor(private val prefs: SharedPreferences) {

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        write(_settings.value)
    }

    fun reset() = update { AppSettings() }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            defaultWineVersion = prefs.getString(KEY_WINE, null) ?: defaults.defaultWineVersion,
            defaultGraphicsDriver = prefs.getString(KEY_DRIVER_LABEL, null)
                ?.takeUnless { it == LEGACY_DEFAULT_DRIVER }
                ?: defaults.defaultGraphicsDriver,
            defaultDriverId = prefs.getString(KEY_DRIVER_ID, null),
            defaultDxvkVersion = prefs.getString(KEY_DXVK, null),
            defaultResolution = prefs.getString(KEY_RESOLUTION, null) ?: defaults.defaultResolution,
            defaultFullscreen = prefs.getBoolean(KEY_FULLSCREEN, defaults.defaultFullscreen),
            vsync = prefs.getBoolean(KEY_VSYNC, defaults.vsync),
            framePacing = prefs.getString(KEY_FRAME_PACING, null)
                ?.let { name -> FramePacing.entries.firstOrNull { it.name == name } }
                ?: defaults.framePacing,
            refreshCatalogOnLaunch = prefs.getBoolean(KEY_REFRESH_ON_LAUNCH, defaults.refreshCatalogOnLaunch),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_WINE, settings.defaultWineVersion)
            .putString(KEY_DRIVER_LABEL, settings.defaultGraphicsDriver)
            .putString(KEY_DRIVER_ID, settings.defaultDriverId)
            .putString(KEY_DXVK, settings.defaultDxvkVersion)
            .putString(KEY_RESOLUTION, settings.defaultResolution)
            .putBoolean(KEY_FULLSCREEN, settings.defaultFullscreen)
            .putBoolean(KEY_VSYNC, settings.vsync)
            .putString(KEY_FRAME_PACING, settings.framePacing.name)
            .putBoolean(KEY_REFRESH_ON_LAUNCH, settings.refreshCatalogOnLaunch)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "fable_settings"
        private const val KEY_WINE = "default_wine_version"
        /** Pre-Xclipse default label; migrated to [ContainerDefaults.GRAPHICS_DRIVER] on read. */
        private const val LEGACY_DEFAULT_DRIVER = "Turnip (default)"
        private const val KEY_DRIVER_LABEL = "default_graphics_driver"
        private const val KEY_DRIVER_ID = "default_driver_id"
        private const val KEY_DXVK = "default_dxvk_version"
        private const val KEY_RESOLUTION = "default_resolution"
        private const val KEY_FULLSCREEN = "default_fullscreen"
        private const val KEY_VSYNC = "vsync"
        private const val KEY_FRAME_PACING = "frame_pacing"
        private const val KEY_REFRESH_ON_LAUNCH = "refresh_catalog_on_launch"

        @Volatile
        private var instance: SettingsRepository? = null

        @Synchronized
        fun get(context: Context): SettingsRepository {
            instance?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val created = SettingsRepository(prefs)
            instance = created
            return created
        }
    }
}
