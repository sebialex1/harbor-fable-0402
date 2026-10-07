package io.harbor.fable.ui.screens

import androidx.compose.animation.core.animateDpAsState
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.text.font.FontFamily
import io.harbor.fable.data.models.HudPosition
import io.harbor.fable.data.models.HudSettings
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.harbor.fable.ui.theme.FableBlue
import io.harbor.fable.ui.theme.PillRadius
import io.harbor.fable.ui.theme.PlayGradientBottom
import io.harbor.fable.ui.theme.PlayGradientTop
import io.harbor.fable.ui.theme.TileTone
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
        onHudChange = { hud ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(hud = hud)) } }
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
    onHudChange: (HudSettings) -> Unit = {},
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
                                val isPrimary = container.exePath == exe.path
                                ListRow(
                                    title = exe.name,
                                    // The launch card above already names the primary app; here a
                                    // blue dot marks it instead of repeating the word on the row.
                                    titleBadge = if (isPrimary) {
                                        { PrimaryDot() }
                                    } else {
                                        null
                                    },
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
                        leading = { ToneIconTile(icon = FableIcons.GpuInfo, tone = TileTone.Blue) },
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

                // The display screen's performance overlay: which lines it shows and where.
                item(key = "overlay") {
                    PerformanceOverlaySection(
                        hud = container.hud,
                        resolution = container.screenResolution,
                        expanded = expansion.isExpanded(OVERLAY_KEY, default = false),
                        onToggle = { expansion.toggle(OVERLAY_KEY, default = false) },
                        onChange = onHudChange,
                        modifier = Modifier.animateItem().entrance(appear, 1),
                    )
                }

                // What runs the container: the Wine build and driver it was created with (fixed)
                // and the x86 translator.
                item(key = "system") {
                    CollapsibleSection(
                        title = "System",
                        expanded = expansion.isExpanded(SYSTEM_KEY, default = false),
                        onToggle = { expansion.toggle(SYSTEM_KEY, default = false) },
                        modifier = Modifier.animateItem().entrance(appear, 1),
                        leading = { MonogramTile(text = "Wi", tone = TileTone.Violet) },
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

/** Marks the primary app in the Apps list: a small blue dot with a soft glow. */
@Composable
private fun PrimaryDot() {
    Box(
        Modifier
            .size(14.dp)
            .semantics { contentDescription = "Primary app" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(FableBlue.copy(alpha = 0.18f)))
        Box(Modifier.size(6.dp).clip(CircleShape).background(FableBlue))
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
        // Transparent: the tabs sit on the top bar's glass (FableScreen draws one sheet for both).
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
 * with a glass play button, and the Wine desktop as a second row. Without a primary app the
 * first row explains how to get one and the desktop row carries the play button instead.
 *
 * On Android 12+ the primary row sits on a faint, heavily blurred wash of the app's own icon, so
 * the card takes on the game's colours and the hollow play button blends straight into it.
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
    val primaryBitmap = rememberExeBitmap(primaryIconPath)
    FableCard(modifier) {
        if (primaryName != null) {
            Box(Modifier.fillMaxWidth()) {
                if (primaryBitmap != null && BlurSupported) {
                    Image(
                        bitmap = primaryBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = 0.22f }
                            .blur(40.dp),
                    )
                }
                ListRow(
                    title = primaryName,
                    subtitle = subtitle,
                    leading = { ExeIcon(name = primaryName, iconPath = primaryIconPath, size = LaunchIconSize) },
                    showChevron = false,
                    onClick = onLaunchPrimary,
                    trailing = {
                        GlassPlayButton(
                            contentDescription = "Launch $primaryName",
                            filler = primaryBitmap,
                            tone = TileTone.Blue,
                            onClick = onLaunchPrimary,
                        )
                    },
                )
            }
        } else {
            ListRow(
                title = "Choose an app",
                subtitle = "Add an .exe with +, or tap one below",
                subtitleMaxLines = 2,
                titleColor = FableTextDim,
                leading = { ToneIconTile(icon = FableIcons.Apps, tone = TileTone.Graphite, size = LaunchIconSize, dimmed = true) },
                showChevron = false,
            )
        }
        // Hairline aligned with the text, past the (larger than a row's) launch tile.
        CardDivider(afterIcon = true, modifier = Modifier.padding(start = LaunchIconSize - RowIconSize))
        val desktopPlay: (@Composable RowScope.() -> Unit)? =
            if (primaryName == null) {
                {
                    GlassPlayButton(
                        contentDescription = "Launch desktop",
                        filler = null,
                        tone = TileTone.Indigo,
                        onClick = onLaunchDesktop,
                    )
                }
            } else {
                null
            }
        ListRow(
            title = "Wine Desktop",
            subtitle = "Explorer and the Windows shell",
            leading = { ToneIconTile(icon = FableIcons.Desktop, tone = TileTone.Indigo, size = LaunchIconSize) },
            showChevron = primaryName != null,
            onClick = onLaunchDesktop,
            trailing = desktopPlay,
        )
    }
}

/**
 * The play control: hollow glass instead of a solid white disc. Behind a white sheen and a
 * light-catching rim sits [filler] — the app's own icon, enlarged and blurred into a soft field
 * of its colours (Android 12+; older versions show it faded instead) — or, with no icon, the
 * [tone] gradient. So the button looks cut from the same material as the tile it sits on.
 */
@Composable
private fun GlassPlayButton(
    contentDescription: String,
    filler: Bitmap?,
    tone: TileTone,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.press(), label = "playPress")
    Box(
        Modifier
            .size(PlayButtonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (filler != null) {
            Image(
                bitmap = filler.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.8f
                        scaleY = 1.8f
                        alpha = if (BlurSupported) 0.95f else 0.4f
                    }
                    .blur(10.dp),
            )
        } else {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(tone.start, tone.end))))
        }
        // Hollow glass: a white sheen falling off towards the bottom, a slight darkening so the
        // glyph reads on bright icons, and a rim brighter at the top.
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x2E000000))
                .background(Brush.verticalGradient(listOf(PlayGradientTop, PlayGradientBottom)))
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x8CFFFFFF), Color(0x14FFFFFF))), CircleShape),
        )
        Icon(
            imageVector = FableIcons.Play,
            contentDescription = null,
            tint = FableText,
            // Optical centring: a play triangle looks left-heavy when centred by its bounds.
            modifier = Modifier.size(PlayButtonSize * 0.42f).offset(x = 1.dp),
        )
    }
}

/**
 * The built-in tools as a compact 2×2 cluster: four gradient tiles whose outer corners are round
 * and inner corners tight, so the grid reads as one shaped block. Each tool has its own tone and
 * a mini-graphic instead of a repeated generic glyph — the Direct3D version as an oversized
 * numeral, GPU Info as a little bar graph. Tap one to launch it. A tool this build doesn't bundle
 * is dimmed and says so. Under the grid, a note explains that the X server can't present
 * Direct3D 12.
 */
@Composable
private fun ToolTiles(
    tools: List<ExeEntry>,
    unavailable: Set<String>,
    onLaunch: (ExeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = tools.chunked(TOOL_COLUMNS)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ToolGap)) {
        rows.forEachIndexed { rowIndex, rowTools ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ToolGap),
            ) {
                rowTools.forEachIndexed { colIndex, exe ->
                    ToolTile(
                        exe = exe,
                        available = exe.id !in unavailable,
                        shape = clusterShape(rowIndex, colIndex, rows.size, TOOL_COLUMNS),
                        onClick = { onLaunch(exe) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep a lone tile on the last row at half width, aligned with the grid.
                repeat(TOOL_COLUMNS - rowTools.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (tools.any { it.toolId == ContainerTools.D3D12_TEST.id }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, start = Spacing.xs, end = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = FableIcons.Warning,
                    contentDescription = null,
                    tint = TileTone.Violet.glyph,
                    modifier = Modifier.size(14.dp).padding(top = 1.dp),
                )
                Text(
                    text = "XServer doesn't support DX12. The Direct3D 12 test may start but not present frames.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FableTextDim,
                )
            }
        }
    }
}

@Composable
private fun ToolTile(
    exe: ExeEntry,
    available: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = toolLook(exe.toolId)
    val tone = look.tone
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.press(), label = "toolPress")
    val caption = when {
        !available -> "Not bundled"
        else -> toolCaption(exe)
    }
    Box(
        modifier
            .height(ToolTileHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (available) 1f else 0.55f
            }
            .gradientTile(shape = shape, start = tone.start, end = tone.end)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        ToolGraphic(look = look, modifier = Modifier.align(Alignment.BottomEnd))
        if (exe.toolId == ContainerTools.D3D12_TEST.id) {
            Text(
                text = "No DX12",
                style = MaterialTheme.typography.labelSmall,
                color = tone.glyph,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.sm)
                    .clip(RoundedCornerShape(PillRadius))
                    .background(Color(0x33000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x26FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = tone.glyph, modifier = Modifier.size(15.dp))
            }
            Column {
                Text(
                    text = toolLabel(exe),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    color = FableText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = tone.glyph.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A tool's identity: its tone, its small glyph and its background mini-graphic. */
private class ToolLook(val tone: TileTone, val icon: ImageVector, val numeral: String?)

private fun toolLook(toolId: String?): ToolLook = when (toolId) {
    ContainerTools.GPU_INFO.id -> ToolLook(TileTone.Teal, FableIcons.GpuInfo, numeral = null)
    ContainerTools.D3D9_TEST.id -> ToolLook(TileTone.Amber, FableIcons.Test3d, numeral = "9")
    ContainerTools.D3D11_TEST.id -> ToolLook(TileTone.Blue, FableIcons.Test3d, numeral = "11")
    ContainerTools.D3D12_TEST.id -> ToolLook(TileTone.Violet, FableIcons.Test3d, numeral = "12")
    else -> ToolLook(TileTone.Graphite, FableIcons.Test3d, numeral = null)
}

/**
 * The tile's background graphic, bleeding off its bottom-end corner: an oversized numeral for
 * the Direct3D tests, a small bar graph for GPU Info.
 */
@Composable
private fun ToolGraphic(look: ToolLook, modifier: Modifier = Modifier) {
    if (look.numeral != null) {
        Text(
            text = look.numeral,
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = 64.sp,
                lineHeight = 64.sp,
                letterSpacing = (-3).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = look.tone.glyph.copy(alpha = 0.16f),
            maxLines = 1,
            modifier = modifier.offset(x = 4.dp, y = 14.dp).padding(end = Spacing.sm),
        )
    } else {
        val bar = look.tone.glyph.copy(alpha = 0.22f)
        Canvas(modifier.padding(end = Spacing.md, bottom = Spacing.md).size(width = 44.dp, height = 30.dp)) {
            val heights = floatArrayOf(0.45f, 0.8f, 0.55f, 1f, 0.7f)
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (heights.size - 1)) / heights.size
            heights.forEachIndexed { i, h ->
                val barHeight = size.height * h
                drawRoundRect(
                    color = bar,
                    topLeft = Offset(i * (w + gap), size.height - barHeight),
                    size = Size(w, barHeight),
                    cornerRadius = CornerRadius(w / 2f),
                )
            }
        }
    }
}

/**
 * Corners for a tile in an [rows]×[cols] cluster: round on the cluster's outside corners,
 * tight where tiles meet, so the grid reads as one rounded block cut into pieces.
 */
private fun clusterShape(row: Int, col: Int, rows: Int, cols: Int): Shape {
    val outer = ToolOuterRadius
    val inner = ToolInnerRadius
    val top = row == 0
    val bottom = row == rows - 1
    val start = col == 0
    val end = col == cols - 1
    return RoundedCornerShape(
        topStart = if (top && start) outer else inner,
        topEnd = if (top && end) outer else inner,
        bottomEnd = if (bottom && end) outer else inner,
        bottomStart = if (bottom && start) outer else inner,
    )
}

/** Real blur (RenderEffect) is only available from Android 12. */
private val BlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Tool grid geometry and the launch card's tile sizes, one step up from a list row's. */
private const val TOOL_COLUMNS = 2
private val LaunchIconSize = 44.dp
private val PlayButtonSize = 40.dp
private val ToolTileHeight = 88.dp
private val ToolGap = 6.dp
private val ToolOuterRadius = 20.dp
private val ToolInnerRadius = 8.dp

/** Settings-tab group keys for [ExpansionState]. */
private const val GRAPHICS_KEY = "graphics"
private const val SYSTEM_KEY = "system"
private const val BOX64_KEY = "box64"
private const val BOX64_ADVANCED_KEY = "box64-advanced"
private const val OVERLAY_KEY = "overlay"

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
        leading = { MonogramTile(text = "64", tone = TileTone.Amber) },
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
        leading = { ToneIconTile(icon = FableIcons.Checklist, tone = TileTone.Amber) },
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

/** Title for a built-in tool's tile, short enough for two tiles in a row. */
private fun toolLabel(exe: ExeEntry): String = when (exe.toolId) {
    ContainerTools.D3D9_TEST.id -> "Direct3D 9"
    ContainerTools.D3D11_TEST.id -> "Direct3D 11"
    ContainerTools.D3D12_TEST.id -> "Direct3D 12"
    else -> exe.name
}

/** One line under the title: what the tool renders through, from [ContainerTools]. */
private fun toolCaption(exe: ExeEntry): String = when (exe.toolId) {
    ContainerTools.GPU_INFO.id -> "Vulkan report"
    ContainerTools.D3D9_TEST.id -> "via DXVK"
    ContainerTools.D3D11_TEST.id -> "via DXVK"
    ContainerTools.D3D12_TEST.id -> "via VKD3D-Proton"
    else -> "Built-in tool"
}

/**
 * Performance overlay settings: a live preview (a little game frame with the HUD chip in its
 * corner, showing exactly the lines that are on), a master switch, one switch per line and the
 * corner. The chip glides between corners and grows or shrinks as lines are toggled.
 */
@Composable
private fun PerformanceOverlaySection(
    hud: HudSettings,
    resolution: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (HudSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    CollapsibleSection(
        title = "Performance Overlay",
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
        leading = { MonogramTile(text = "fps", tone = TileTone.Teal) },
        badge = { Pill(text = if (!hud.enabled || hud.isEmpty) "Off" else hudSummary(hud)) },
    ) {
        HudPreview(hud = hud, resolution = resolution)
        CardDivider()
        ToggleRow(
            title = "Show Overlay",
            subtitle = "On the display screen while the container runs",
            checked = hud.enabled,
            onCheckedChange = { onChange(hud.copy(enabled = it)) },
        )
        AnimatedVisibility(
            visible = hud.enabled,
            enter = expandVertically(Motion.morph()) + fadeIn(Motion.enter()),
            exit = shrinkVertically(Motion.morph()) + fadeOut(Motion.exit()),
        ) {
            Column {
                CardDivider()
                ToggleRow(
                    title = "Frame Rate",
                    subtitle = "Frames the display drew each second",
                    checked = hud.showFps,
                    onCheckedChange = { onChange(hud.copy(showFps = it)) },
                )
                CardDivider()
                ToggleRow(
                    title = "Resolution",
                    subtitle = "The X screen size",
                    checked = hud.showResolution,
                    onCheckedChange = { onChange(hud.copy(showResolution = it)) },
                )
                CardDivider()
                ToggleRow(
                    title = "CPU Usage",
                    subtitle = "System-wide when Android allows it, otherwise Fable's own",
                    checked = hud.showCpu,
                    onCheckedChange = { onChange(hud.copy(showCpu = it)) },
                )
                CardDivider()
                OptionSelector(
                    label = "Position",
                    options = HudPosition.entries.map { SelectOption(it, it.label) },
                    selected = hud.position,
                    onSelect = { onChange(hud.copy(position = it)) },
                )
            }
        }
    }
}

/** A miniature display: a dim game-frame gradient with the HUD chip as it will look. */
@Composable
private fun HudPreview(hud: HudSettings, resolution: String) {
    val (hTarget, vTarget) = when (hud.position) {
        HudPosition.TOP_START -> -1f to -1f
        HudPosition.TOP_END -> 1f to -1f
        HudPosition.BOTTOM_START -> -1f to 1f
        HudPosition.BOTTOM_END -> 1f to 1f
    }
    val h by animateFloatAsState(hTarget, Motion.settle(), label = "hudPreviewX")
    val v by animateFloatAsState(vTarget, Motion.settle(), label = "hudPreviewY")
    val visible = hud.enabled && !hud.isEmpty
    val chipAlpha by animateFloatAsState(if (visible) 1f else 0f, Motion.inPlace(), label = "hudPreviewAlpha")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(RowPaddingHorizontal)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2735), Color(0xFF090A0F), Color(0xFF2A1B3D))))
            .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .padding(Spacing.sm),
    ) {
        // A faint horizon so the frame reads as a scene, not a grey box.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.7f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x40FFFFFF), Color.Transparent))),
        )
        Column(
            Modifier
                .align(BiasAlignment(h, v))
                .graphicsLayer { alpha = chipAlpha }
                .glassSurface(shape = RoundedCornerShape(8.dp), fill = Color(0x99000000), blurRadius = 0)
                .animateContentSize(Motion.morph())
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            val lines = buildList {
                if (hud.showFps) add("FPS: 60")
                if (hud.showResolution) add(resolution)
                if (hud.showCpu) add("CPU: 34%")
            }
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    color = Color(0xCCFFFFFF),
                    maxLines = 1,
                )
            }
        }
    }
}

/** "FPS · CPU" for the collapsed badge. */
private fun hudSummary(hud: HudSettings): String = listOfNotNull(
    "FPS".takeIf { hud.showFps },
    "Res".takeIf { hud.showResolution },
    "CPU".takeIf { hud.showCpu },
).joinToString(" · ")

