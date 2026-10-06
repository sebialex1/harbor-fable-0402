package io.harbor.fable.data.models

import java.util.UUID

enum class ContainerStatus { CREATED, CONFIGURING, READY, RUNNING, ERROR }

/**
 * Built-in fallbacks for container configuration. The user-facing defaults for new
 * containers live in `SettingsRepository`; these values seed it and are used when a
 * persisted record is missing a field.
 */
object ContainerDefaults {
    const val WINE_VERSION = "wine-9.0"
    const val GRAPHICS_DRIVER = "Turnip (default)"
    const val SCREEN_RESOLUTION = "1280x720"

    val RESOLUTION_PRESETS: List<String> = listOf(
        "800x600",
        "1024x768",
        "1280x720",
        "1366x768",
        "1600x900",
        "1920x1080",
    )
}

data class Container(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val exePath: String? = null,
    val exeName: String? = null,
    val wineVersion: String = ContainerDefaults.WINE_VERSION,
    val dxvkVersion: String? = null,
    val driverId: String? = null,
    val status: ContainerStatus = ContainerStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val graphicsDriver: String = ContainerDefaults.GRAPHICS_DRIVER,
    val envVars: Map<String, String> = emptyMap(),
    val screenResolution: String = ContainerDefaults.SCREEN_RESOLUTION,
    val isFullscreen: Boolean = false,
)

data class ExeEntry(
    val id: String = UUID.randomUUID().toString(),
    val containerId: String,
    val name: String,
    val path: String,
    val icon: String? = null,
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
)
