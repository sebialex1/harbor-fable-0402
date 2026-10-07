package io.harbor.fable.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.ComponentBuild
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.ContainerTools
import io.harbor.fable.data.models.Box64Options
import io.harbor.fable.data.models.Box64Preset
import io.harbor.fable.data.models.Box64Settings
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.ControlHeight
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableOnAccent
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableTextFaint
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch
import io.harbor.fable.ui.icons.FableIcons

@Composable
fun ContainerDetailScreen(
    containerId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.containerRepository
    val fableUi = LocalFableUi.current

    val containers by repository.containers.collectAsStateWithLifecycle()
    val exes by repository.exes.collectAsStateWithLifecycle()
    val container = containers.firstOrNull { it.id == containerId }
    val containerExes = exes.filter { it.containerId == containerId }

    var showAddExe by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // The app whose trash button was tapped, while its confirmation is up.
    var exeToRemove by remember { mutableStateOf<ExeEntry?>(null) }
    // Downloaded DXVK builds, re-read whenever the asset list changes (a download finished).
    val assetEntries by app.assetRepository.assets.collectAsStateWithLifecycle()
    var dxvkBuilds by remember { mutableStateOf<List<ComponentBuild>>(emptyList()) }
    var vkd3dBuilds by remember { mutableStateOf<List<ComponentBuild>>(emptyList()) }
    LaunchedEffect(assetEntries) {
        dxvkBuilds = repository.availableDxvkBuilds()
        vkd3dBuilds = repository.availableVkd3dBuilds()
    }

    // Launching can take a while on first use, and a delete must finish even after the screen
    // is gone, so both run on the app-level scope.
    fun launchExe(exeId: String?) {
        fableUi.scope.launch {
            fableUi.showMessage(repository.launch(containerId, exeId).also { it.openDisplay(context, containerId) }.message(), long = true)
        }
    }

    ContainerDetailContent(
        container = container,
        exes = containerExes,
        unavailableTools = containerExes.filter { it.isTool && !repository.toolAvailable(it) }.map { it.id }.toSet(),
        dxvkBuilds = dxvkBuilds,
        vkd3dBuilds = vkd3dBuilds,
        onBack = onBack,
        onLaunchPrimary = { launchExe(null) },
        onLaunchTool = { tool -> launchExe(tool.id) },
        onLaunchDesktop = {
            fableUi.scope.launch {
                fableUi.showMessage(repository.launchDesktop(containerId).also { it.openDisplay(context, containerId) }.message(), long = true)
            }
        },
        onSetPrimary = { exe ->
            fableUi.scope.launch {
                repository.setPrimaryExe(exe.id)
                fableUi.showMessage("${exe.name} set as primary")
            }
        },
        onRemoveExe = { exe -> exeToRemove = exe },
        onSelectResolution = { res ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(screenResolution = res)) } }
        },
        onSelectTranslator = { translator ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(translator = translator)) } }
        },
        onSelectDxvk = { dxvk ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(dxvkVersion = dxvk)) } }
        },
        onSelectVkd3d = { vkd3d ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(vkd3dVersion = vkd3d)) } }
        },
        onFullscreenChange = { fullscreen ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(isFullscreen = fullscreen)) } }
        },
        onBox64Change = { box64 ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(box64 = box64)) } }
        },
        onAddExe = { showAddExe = true },
        onDelete = { showDeleteConfirm = true },
    )

    if (showAddExe) {
        AddAppSheet(
            onDismiss = { showAddExe = false },
            preselectedContainerId = containerId,
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete container?",
            message = "\"${container?.name}\" and all its files will be deleted.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                fableUi.scope.launch { repository.delete(containerId) }
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    exeToRemove?.let { exe ->
        // Only the shortcut goes; the program's files stay where they are. removeExe() hands
        // the primary slot to the next app when the primary one is removed.
        val wasPrimary = container?.exePath == exe.path
        ConfirmDialog(
            title = "Remove app?",
            message = buildString {
                append("\"${exe.name}\" will be removed from this container. Its files are not deleted.")
                if (wasPrimary) append(" Another app becomes the primary one, if there is any.")
            },
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                exeToRemove = null
                fableUi.scope.launch {
                    if (repository.removeExe(exe.id)) fableUi.showMessage("${exe.name} removed")
                }
            },
            onDismiss = { exeToRemove = null },
        )
    }
}

/**
 * The container screen in two tabs pinned under the bar. **Apps**: the launch buttons, the
 * container's apps and the built-in tools as one compact row. **Settings**: collapsed groups
 * — Graphics (DXVK, VKD3D-Proton, resolution, fullscreen), System (Wine, driver, translator),
 * Box64 (preset, rc file) and the advanced BOX64_* switches — each with a pill summarising its
 * current state, then Delete. Each tab keeps its own scroll position.
 */
@Composable
internal fun ContainerDetailContent(
    container: Container?,
    exes: List<ExeEntry>,
    dxvkBuilds: List<ComponentBuild>,
    vkd3dBuilds: List<ComponentBuild>,
    onBack: () -> Unit,
    onLaunchPrimary: () -> Unit,
    onLaunchTool: (ExeEntry) -> Unit,
    onLaunchDesktop: () -> Unit,
    onSetPrimary: (ExeEntry) -> Unit,
    onRemoveExe: (ExeEntry) -> Unit,
    onSelectResolution: (String) -> Unit,
    onSelectTranslator: (String) -> Unit,
    onSelectDxvk: (String?) -> Unit,
    onSelectVkd3d: (String?) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onBox64Change: (Box64Settings) -> Unit,
    onAddExe: () -> Unit,
    onDelete: () -> Unit,
    unavailableTools: Set<String> = emptySet(),
) {
    val appear = rememberEntrance()
    var tab by rememberSaveable { mutableStateOf(ContainerTab.Apps) }
    val appsListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    // Which Settings groups are open; all start collapsed so the tab is a short list.
    val expansion = rememberExpansionState()

    FableScreen(
        title = container?.name ?: "Container",
        onBack = onBack,
        listState = if (tab == ContainerTab.Apps) appsListState else settingsListState,
        actions = {
            if (container != null && tab == ContainerTab.Apps) {
                FableIconButton(
                    icon = FableIcons.Add,
                    contentDescription = "Add app",
                    onClick = onAddExe,
                )
            }
        },
        header = if (container != null) {
            { ContainerTabRow(selected = tab, onSelect = { tab = it }) }
        } else {
            null
        },
    ) {
        if (container == null) {
            item(key = "missing") {
                EmptyState(
                    icon = FableIcons.Error,
                    title = "Container not found",
                    modifier = Modifier.animateItem().entrance(appear, 0),
                )
            }
            return@FableScreen
        }

        when (tab) {
            ContainerTab.Apps -> {
                // Launch card: the primary app as a hero row with a white play button, and the
                // Wine desktop as a plain row beneath it. With no primary app the desktop is the
                // only thing to launch, so it takes the play button.
                item(key = "launch") {
                    val primaryName = container.exeName?.takeIf { !container.exePath.isNullOrBlank() }
                    val primaryIcon = exes.firstOrNull { !it.isTool && it.path == container.exePath }?.icon
                    LaunchCard(
                        primaryName = primaryName,
                        primaryIconPath = primaryIcon,
                        subtitle = graphicsSummary(container),
                        onLaunchPrimary = onLaunchPrimary,
                        onLaunchDesktop = onLaunchDesktop,
                        modifier = Modifier.animateItem().entrance(appear, 0),
                    )
                }

                val apps = exes.filter { !it.isTool }
                if (apps.isNotEmpty()) {
                    item(key = "apps-label") { SectionLabel("Apps", Modifier.animateItem().entrance(appear, 1)) }
                    item(key = "apps") {
                        FableCard(Modifier.animateItem().entrance(appear, 1)) {
                            apps.forEachIndexed { index, exe ->
                                if (index > 0) CardDivider(afterIcon = true)
                                ListRow(
                                    title = exe.name,
                                    subtitle = if (container.exePath == exe.path) "Primary" else null,
                                    leading = { ExeIcon(name = exe.name, iconPath = exe.icon) },
                                    showChevron = false,
                                    // No per-row play button: tapping a row makes it the primary
                                    // app, and the launch button at the top starts the primary
                                    // app, so a second launch control on every row was
                                    // redundant. "Primary" marks what will launch.
                                    onClick = { onSetPrimary(exe) },
                                    // The only way to take an app off a container; a
                                    // confirmation follows (the files themselves stay).
                                    trailing = {
                                        FableIconButton(
                                            icon = FableIcons.Trash,
                                            contentDescription = "Remove ${exe.name}",
                                            tint = FableTextDim,
                                            bordered = false,
                                            size = ControlHeight.Compact,
                                            onClick = { onRemoveExe(exe) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                // Built-in checks every container gets (ContainerTools), in ContainerTools order:
                // GPU Info first, then the Direct3D tests.
                val tools = exes.filter { it.isTool }.sortedBy { exe ->
                    ContainerTools.all.indexOfFirst { it.id == exe.toolId }.let { if (it < 0) Int.MAX_VALUE else it }
                }
                if (tools.isNotEmpty()) {
                    item(key = "tools-label") { SectionLabel("Tools", Modifier.animateItem().entrance(appear, 2)) }
                    item(key = "tools") {
                        ToolTiles(
                            tools = tools,
                            unavailable = unavailableTools,
                            onLaunch = onLaunchTool,
                            modifier = Modifier.animateItem().entrance(appear, 2),
                        )
                    }
                }
            }

            ContainerTab.Settings -> {
                // Only a failed container has a status worth a row of its own.
                if (container.status == ContainerStatus.ERROR) {
                    item(key = "status") {
                        FableCard(Modifier.animateItem().entrance(appear, 0)) {
                            InfoRow(
                                label = "Status",
                                value = container.status.name.lowercase(),
                                valueContent = { StatusPill(container.status) },
                            )
                        }
                    }
                }

                // How the container draws. DXVK / VKD3D-Proton go into the prefix on the next
                // launch (DxWrappers); their "Off" options say what Wine falls back to.
                item(key = "graphics") {
                    CollapsibleSection(
                        title = "Graphics",
                        expanded = expansion.isExpanded(GRAPHICS_KEY, default = false),
                        onToggle = { expansion.toggle(GRAPHICS_KEY, default = false) },
                        modifier = Modifier.animateItem().entrance(appear, 0),
                        badge = { Pill(text = graphicsSummary(container)) },
                    ) {
                        OptionSelector(
                            label = "DXVK",
                            options = dxvkOptions(dxvkBuilds),
                            selected = container.dxvkVersion,
                            onSelect = onSelectDxvk,
                        )
                        CardDivider()
                        OptionSelector(
                            label = "VKD3D-Proton",
                            options = vkd3dOptions(vkd3dBuilds),
                            selected = container.vkd3dVersion,
                            onSelect = onSelectVkd3d,
                        )
                        CardDivider()
                        OptionSelector(
                            label = "Resolution",
                            options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                            selected = container.screenResolution,
                            onSelect = onSelectResolution,
                        )
                        CardDivider()
                        ToggleRow(
                            title = "Fullscreen",
                            checked = container.isFullscreen,
                            onCheckedChange = onFullscreenChange,
                        )
                    }
                }

                // What runs the container: the Wine build and driver it was created with (fixed)
                // and the x86 translator.
                item(key = "system") {
                    CollapsibleSection(
                        title = "System",
                        expanded = expansion.isExpanded(SYSTEM_KEY, default = false),
                        onToggle = { expansion.toggle(SYSTEM_KEY, default = false) },
                        modifier = Modifier.animateItem().entrance(appear, 1),
                        badge = { Pill(text = translatorLabel(container.translator)) },
                    ) {
                        InfoRow(label = "Wine", value = container.wineVersion)
                        CardDivider()
                        InfoRow(label = "Driver", value = container.graphicsDriver)
                        CardDivider()
                        OptionSelector(
                            label = "Translator",
                            options = TRANSLATOR_OPTIONS,
                            selected = container.translator,
                            onSelect = onSelectTranslator,
                        )
                    }
                }

                // Box64 presets (Winlator's Box64PresetManager) and the individual BOX64_*
                // switches. FEX containers don't run Box64, so both are hidden for them.
                if (!ContainerRepository.usesFex(container)) {
                    item(key = "box64") {
                        Box64PresetSection(
                            settings = container.box64,
                            expanded = expansion.isExpanded(BOX64_KEY, default = false),
                            onToggle = { expansion.toggle(BOX64_KEY, default = false) },
                            onChange = onBox64Change,
                            modifier = Modifier.animateItem().entrance(appear, 2),
                        )
                    }
                    item(key = "box64-options") {
                        Box64OptionsSection(
                            settings = container.box64,
                            expanded = expansion.isExpanded(BOX64_ADVANCED_KEY, default = false),
                            onToggle = { expansion.toggle(BOX64_ADVANCED_KEY, default = false) },
                            onChange = onBox64Change,
                            modifier = Modifier.animateItem().entrance(appear, 2),
                        )
                    }
                }

                item(key = "delete") {
                    FableCard(Modifier.animateItem().entrance(appear, 3).padding(top = Spacing.md)) {
                        ListRow(
                            title = "Delete Container",
                            titleColor = FableError,
                            showChevron = false,
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

/** The container screen's tabs, in order: idle glyph, and its filled weight for the active tab. */
private enum class ContainerTab(val label: String, val icon: ImageVector, val activeIcon: ImageVector) {
    Apps("Apps", FableIcons.Apps, FableIcons.AppsFill),
    Settings("Settings", FableIcons.Settings, FableIcons.SettingsFill),
}

/**
 * Apps | Settings on the black bar, in the tab bar's idiom: white over grey, the outline glyph
 * while idle and its filled weight once selected, plus a short white bar under the active tab.
 */
@Composable
private fun ContainerTabRow(selected: ContainerTab, onSelect: (ContainerTab) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = FableBg,
        contentColor = FableText,
        indicator = { positions ->
            positions.getOrNull(selected.ordinal)?.let { position ->
                // Same 250 ms ease as TabRow's own slide, so the bar resizes as it moves.
                val width by animateDpAsState(position.contentWidth, Motion.inPlace(250), label = "tabIndicatorWidth")
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(position),
                    width = width,
                    color = FableAccent,
                )
            }
        },
        divider = { CardDivider(inset = false) },
    ) {
        ContainerTab.entries.forEach { tab ->
            val active = tab == selected
            LeadingIconTab(
                selected = active,
                onClick = { onSelect(tab) },
                text = {
                    // Fable's text styles carry a colour; use the tab's animated one instead.
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        color = LocalContentColor.current,
                        maxLines = 1,
                    )
                },
                icon = {
                    Icon(
                        imageVector = if (active) tab.activeIcon else tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                selectedContentColor = FableText,
                unselectedContentColor = FableTextDim,
            )
        }
    }
}

/**
 * What the Apps tab launches: the primary app (name, icon and the container's graphics summary)
 * with a filled play button, and the Wine desktop as a second row. Without a primary app the
 * first row explains how to get one and the desktop row carries the play button instead.
 */
@Composable
private fun LaunchCard(
    primaryName: String?,
    primaryIconPath: String?,
    subtitle: String,
    onLaunchPrimary: () -> Unit,
    onLaunchDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FableCard(modifier) {
        if (primaryName != null) {
            ListRow(
                title = primaryName,
                subtitle = subtitle,
                leading = { ExeIcon(name = primaryName, iconPath = primaryIconPath, size = LaunchIconSize) },
                showChevron = false,
                onClick = onLaunchPrimary,
                trailing = { PlayButton(contentDescription = "Launch $primaryName", onClick = onLaunchPrimary) },
            )
        } else {
            ListRow(
                title = "No app selected",
                subtitle = "Add an .exe with +, or tap an app below to make it the primary",
                subtitleMaxLines = 2,
                titleColor = FableTextDim,
                leading = { IconTile(icon = FableIcons.Apps, tint = FableTextFaint, size = LaunchIconSize) },
                showChevron = false,
            )
        }
        // Hairline aligned with the text, past the (larger than a row's) launch tile.
        CardDivider(afterIcon = true, modifier = Modifier.padding(start = LaunchIconSize - RowIconSize))
        val desktopPlay: (@Composable RowScope.() -> Unit)? =
            if (primaryName == null) {
                { PlayButton(contentDescription = "Launch desktop", onClick = onLaunchDesktop) }
            } else {
                null
            }
        ListRow(
            title = "Wine Desktop",
            subtitle = "Explorer, file manager and the Windows shell",
            leading = { IconTile(icon = FableIcons.Desktop, size = LaunchIconSize) },
            showChevron = primaryName != null,
            onClick = onLaunchDesktop,
            trailing = desktopPlay,
        )
    }
}

/** The one filled control on the Apps tab: a white disc with a black play glyph. */
@Composable
private fun PlayButton(contentDescription: String, onClick: () -> Unit) {
    FableIconButton(
        icon = FableIcons.Play,
        contentDescription = contentDescription,
        tint = FableOnAccent,
        containerColor = FableAccent,
        size = PlayButtonSize,
        onClick = onClick,
    )
}

/**
 * The built-in tools as a 2×2 grid of equal cards, so they stay out of the apps' way: tap one to
 * launch it. Each card is an icon tile, the short name and what the tool checks. A tool this
 * build doesn't bundle is dimmed and says so; launching it explains.
 */
@Composable
private fun ToolTiles(
    tools: List<ExeEntry>,
    unavailable: Set<String>,
    onLaunch: (ExeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        tools.chunked(TOOL_COLUMNS).forEach { rowTools ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowTools.forEach { exe ->
                    ToolTile(
                        exe = exe,
                        available = exe.id !in unavailable,
                        onClick = { onLaunch(exe) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // Keep a lone tile on the last row at half width, aligned with the grid.
                repeat(TOOL_COLUMNS - rowTools.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToolTile(
    exe: ExeEntry,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleTint = if (available) FableText else FableTextFaint
    val caption = if (available) toolCaption(exe) else "Not bundled in this build"
    FableCard(modifier, onClick = onClick) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconTile(icon = toolIcon(exe.toolId), tint = titleTint, size = ToolIconSize)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = toolLabel(exe),
                    style = MaterialTheme.typography.titleSmall,
                    color = titleTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (available) FableTextDim else FableTextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Tool grid geometry and the launch card's tile sizes, one step up from a list row's. */
private const val TOOL_COLUMNS = 2
private val LaunchIconSize = 44.dp
private val PlayButtonSize = 40.dp
private val ToolIconSize = 40.dp

/** Settings-tab group keys for [ExpansionState]. */
private const val GRAPHICS_KEY = "graphics"
private const val SYSTEM_KEY = "system"
private const val BOX64_KEY = "box64"
private const val BOX64_ADVANCED_KEY = "box64-advanced"

/** What the collapsed Graphics group shows: the resolution, and a flag when DXVK is off. */
private fun graphicsSummary(container: Container): String {
    val dxvkOff = container.dxvkVersion?.trim().equals(ContainerDefaults.DXVK_OFF, ignoreCase = true)
    return if (dxvkOff) "${container.screenResolution} · no DXVK" else container.screenResolution
}

/** "Box64" / "FEX" for the collapsed System group. */
private fun translatorLabel(translator: String): String =
    TRANSLATOR_OPTIONS.firstOrNull { it.value.equals(translator.trim(), ignoreCase = true) }?.label ?: translator

/**
 * Preset picker, the rc-file switch and, when settings were changed, a way back to the preset,
 * collapsed by default behind the active preset's name.
 */
@Composable
private fun Box64PresetSection(
    settings: Box64Settings,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (Box64Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    CollapsibleSection(
        title = "Box64",
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
        badge = { Pill(text = settings.preset.label) },
    ) {
        OptionSelector(
            label = "Preset",
            options = Box64Preset.entries.map { SelectOption(it, it.label, it.description) },
            selected = settings.preset,
            onSelect = { preset -> onChange(settings.withPreset(preset)) },
            hint = if (settings.isCustomized) "Choosing a preset replaces your ${settings.overrides.size} changed setting(s)" else null,
        )
        CardDivider()
        ToggleRow(
            title = "Use box64rc",
            subtitle = "Per-game fixes from the Box64 package or .box64rc in the container",
            checked = settings.useRcFile,
            onCheckedChange = { onChange(settings.copy(useRcFile = it)) },
        )
        if (settings.isCustomized) {
            CardDivider()
            ListRow(
                title = "Reset to ${settings.preset.label}",
                subtitle = settings.overrides.keys.mapNotNull { Box64Options.option(it)?.label }.joinToString(", "),
                showChevron = false,
                onClick = { onChange(settings.withPreset(settings.preset)) },
            )
        }
    }
}

/**
 * Every BOX64_* variable Fable exposes, collapsed by default. Each row shows the effective value
 * (the preset's, or the user's change); a change that matches the preset again is dropped.
 */
@Composable
private fun Box64OptionsSection(
    settings: Box64Settings,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (Box64Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    CollapsibleSection(
        title = "Advanced Box64 Settings",
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
        badge = { if (settings.isCustomized) Pill(text = "${settings.overrides.size} changed") },
    ) {
        Box64Options.all.forEachIndexed { index, option ->
            if (index > 0) CardDivider()
            val value = settings.value(option.key)
            val presetValue = settings.preset.variables[option.key].orEmpty()
            val changed = option.key in settings.overrides
            if (option.isToggle) {
                ToggleRow(
                    title = option.label,
                    subtitle = if (changed) "${option.description}. ${settings.preset.label}: ${option.labelFor(presetValue)}" else option.description,
                    checked = value == "1",
                    onCheckedChange = { on -> onChange(settings.with(option.key, if (on) "1" else "0")) },
                )
            } else {
                OptionSelector(
                    label = option.label,
                    options = option.choices.map { SelectOption(it.value, it.label) },
                    selected = value,
                    onSelect = { choice -> onChange(settings.with(option.key, choice)) },
                    hint = "${option.description}. ${settings.preset.label}: ${option.labelFor(presetValue)}" +
                        if (changed) " (changed)" else "",
                )
            }
        }
    }
}

/** VKD3D-Proton choices: the newest download (null), each downloaded build, or off (Wine's d3d12). */
private fun vkd3dOptions(builds: List<ComponentBuild>): List<SelectOption<String?>> = buildList {
    add(SelectOption(null, "Newest", builds.firstOrNull()?.label ?: "None downloaded yet (Assets)"))
    builds.forEach { add(SelectOption(it.id, it.label, it.archive.name)) }
    add(SelectOption(ContainerDefaults.VKD3D_OFF, "Off", "Wine's builtin d3d12"))
}

/** DXVK choices: the newest download (null), each downloaded build, or off (WineD3D). */
private fun dxvkOptions(builds: List<ComponentBuild>): List<SelectOption<String?>> = buildList {
    add(SelectOption(null, "Newest", builds.firstOrNull()?.label ?: "None downloaded yet (Assets)"))
    builds.forEach { add(SelectOption(it.id, it.label, it.archive.name)) }
    add(SelectOption(ContainerDefaults.DXVK_OFF, "Off", "WineD3D: needs OpenGL, most games won't render"))
}

/** Glyph for a built-in tool's tile. */
private fun toolIcon(toolId: String?): ImageVector = when (toolId) {
    ContainerTools.GPU_INFO.id -> FableIcons.GpuInfo
    else -> FableIcons.Test3d
}

/** Title for a built-in tool's tile, short enough for two tiles in a row. */
private fun toolLabel(exe: ExeEntry): String = when (exe.toolId) {
    ContainerTools.D3D9_TEST.id -> "Direct3D 9"
    ContainerTools.D3D11_TEST.id -> "Direct3D 11"
    ContainerTools.D3D12_TEST.id -> "Direct3D 12"
    else -> exe.name
}

/** One line under the title: what the tool renders through, from [ContainerTools]. */
private fun toolCaption(exe: ExeEntry): String = when (exe.toolId) {
    ContainerTools.GPU_INFO.id -> "Vulkan and Direct3D report"
    ContainerTools.D3D9_TEST.id -> "Test render via DXVK d3d9"
    ContainerTools.D3D11_TEST.id -> "Test render via DXVK d3d11"
    ContainerTools.D3D12_TEST.id -> "Test render via VKD3D-Proton"
    else -> "Built-in tool"
}
