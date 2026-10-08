package io.harbor.fable.data.models

import java.util.UUID

enum class ContainerStatus { CREATED, CONFIGURING, READY, RUNNING, ERROR }

/**
 * Built-in fallbacks for container configuration. The user-facing defaults for new
 * containers live in `SettingsRepository`; these values seed it and are used when a
 * persisted record is missing a field.
 */
object ContainerDefaults {
    /**
     * Empty means "the newest downloaded bionic Wine package". New containers get an explicit
     * build from the create-container picker; this only covers records that predate it.
     */
    const val WINE_VERSION = ""
    const val GRAPHICS_DRIVER = "System"
    const val SCREEN_RESOLUTION = "1280x720"
    const val TRANSLATOR = "box64"

    /**
     * [Container.dxvkVersion] value that turns DXVK off (Direct3D through WineD3D). Null means
     * the newest downloaded DXVK, any other value names a build (`dxvk-3.1.1`).
     */
    const val DXVK_OFF = "none"

    /** [Container.vkd3dVersion] value that turns VKD3D-Proton off (Wine's builtin d3d12). */
    const val VKD3D_OFF = "none"

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
    /** DXVK build installed into the prefix: null = newest downloaded, [ContainerDefaults.DXVK_OFF] = none. */
    val dxvkVersion: String? = null,
    /** VKD3D-Proton build installed into the prefix: null = newest downloaded, [ContainerDefaults.VKD3D_OFF] = none. */
    val vkd3dVersion: String? = null,
    val driverId: String? = null,
    val status: ContainerStatus = ContainerStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val graphicsDriver: String = ContainerDefaults.GRAPHICS_DRIVER,
    val envVars: Map<String, String> = emptyMap(),
    val screenResolution: String = ContainerDefaults.SCREEN_RESOLUTION,
    val isFullscreen: Boolean = false,
    /** x86 translation layer: "box64" or "fex". */
    val translator: String = ContainerDefaults.TRANSLATOR,
    /** Box64 preset and per-variable changes; passed to Wine as `BOX64_*` variables. */
    val box64: Box64Settings = Box64Settings(),
    /** What the display screen's performance overlay shows for this container. */
    val hud: HudSettings = HudSettings(),
    /**
     * Built-in tools ([ExeEntry.toolId]: `gpu-info`, `d3d11-test`, …) the user hid from this
     * container's Apps page. They stay installed and launchable from the "Hidden tools" group.
     */
    val hiddenTools: Set<String> = emptySet(),
)

/**
 * Which of a container's built-in tools show on its Apps page: [visible] in the tile grid,
 * [hidden] in the collapsed "Hidden tools" group (in the same order), from
 * [Container.hiddenTools]. Tool ids that no longer exist are ignored.
 */
data class ToolVisibility(val visible: List<ExeEntry>, val hidden: List<ExeEntry>) {
    companion object {
        fun of(tools: List<ExeEntry>, hiddenIds: Set<String>): ToolVisibility {
            val (hidden, visible) = tools.partition { it.toolId != null && it.toolId in hiddenIds }
            return ToolVisibility(visible = visible, hidden = hidden)
        }

        /** [hiddenIds] with [toolId] hidden ([hide]) or shown again. */
        fun toggle(hiddenIds: Set<String>, toolId: String, hide: Boolean): Set<String> =
            if (hide) hiddenIds + toolId else hiddenIds - toolId
    }
}

/** Corner of the display screen the performance overlay sits in. */
enum class HudPosition(val label: String) {
    TOP_START("Top left"),
    TOP_END("Top right"),
    BOTTOM_START("Bottom left"),
    BOTTOM_END("Bottom right"),
    ;

    companion object {
        fun fromName(name: String?): HudPosition = entries.firstOrNull { it.name == name } ?: TOP_START
    }
}

/** How the performance overlay arranges its readings; tapping the overlay cycles them. */
enum class HudLayout(val label: String) {
    /** One reading per line, top to bottom. */
    STACKED("Stacked"),

    /** Every reading on one line, side by side. */
    ROW("Side by side"),
    ;

    /** The layout a tap on the overlay switches to. */
    fun next(): HudLayout = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): HudLayout = entries.firstOrNull { it.name == name } ?: STACKED
    }
}

/**
 * Performance overlay (HUD) customisation, per container: whether it shows at all, which
 * readings it carries, how they are laid out and where it sits. Readings: frame rate, frame
 * time, the graphics API the game uses (detected from Wine's DLL loads, else what the container
 * is set up for), the Vulkan driver, GPU usage and temperature (read from the kernel where
 * Android lets an app; "unavailable" otherwise), CPU usage, memory and the X screen resolution.
 * Records from before a reading existed get its default here.
 */
data class HudSettings(
    val enabled: Boolean = true,
    val showFps: Boolean = true,
    val showResolution: Boolean = true,
    val showCpu: Boolean = true,
    val position: HudPosition = HudPosition.TOP_START,
    /**
     * Where the user dragged the overlay on the display screen, as fractions (0–1) of the free
     * space left/right and above/below it (`HudAnchor`). Null (the default, and after picking a
     * corner in Settings) means [position].
     */
    val customX: Float? = null,
    val customY: Float? = null,
    /** Average time per frame over the last second, in ms. */
    val showFrameTime: Boolean = true,
    /** Direct3D version and translation layer in use (D3D11 · DXVK, D3D12 · VKD3D-Proton, …). */
    val showApi: Boolean = true,
    /** The Vulkan driver the container runs on (Turnip, RADV, the system driver). */
    val showDriver: Boolean = false,
    val showGpuUsage: Boolean = true,
    val showGpuTemp: Boolean = false,
    /** Device memory in use / total. */
    val showRam: Boolean = true,
    val layout: HudLayout = HudLayout.STACKED,
) {
    /** True when the overlay sits where it was dragged rather than in a corner. */
    val isCustomPosition: Boolean get() = customX != null && customY != null

    /** True when the overlay would have nothing to show. */
    val isEmpty: Boolean get() = readingCount == 0

    /** How many readings are switched on. */
    val readingCount: Int
        get() = listOf(showFps, showFrameTime, showApi, showDriver, showGpuUsage, showGpuTemp, showCpu, showRam, showResolution)
            .count { it }

    /** Every reading on (the side menu's HUD switch uses this when everything was off). */
    fun allReadings(): HudSettings = copy(
        showFps = true, showFrameTime = true, showApi = true, showDriver = true, showGpuUsage = true,
        showGpuTemp = true, showCpu = true, showRam = true, showResolution = true,
    )
}

data class ExeEntry(
    val id: String = UUID.randomUUID().toString(),
    val containerId: String,
    val name: String,
    val path: String,
    val icon: String? = null,
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    /**
     * Set for the built-in tools every container gets (GPU Info, Direct3D tests; see
     * `ContainerTools`): their id, e.g. `gpu-info`. Null for apps the user added. Tools never
     * become the container's primary app and aren't listed on the Home screen.
     */
    val toolId: String? = null,
    /**
     * The game's whole folder, when it was added with "Choose game folder": a Storage Access
     * Framework tree URI Fable holds a persisted read grant for. [path] is then the document URI
     * of the .exe inside it and [folderExe] that .exe's path relative to the folder
     * (`ULTRAKILL.exe`, `bin/Game.exe`). A game is more than its .exe (a Unity game's .exe
     * imports `UnityPlayer.dll` and reads `<Name>_Data/` next to it), and a single picked file
     * gives Fable no way to reach the rest. Null for apps added as a single file.
     */
    val folder: String? = null,
    val folderExe: String? = null,
) {
    val isTool: Boolean get() = toolId != null

    /** Added with its whole folder ([folder] / [folderExe] are both set). */
    val hasFolder: Boolean get() = !folder.isNullOrBlank() && !folderExe.isNullOrBlank()
}
