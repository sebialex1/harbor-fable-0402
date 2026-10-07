package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.ComponentBuild
import io.harbor.fable.data.ContainerRepository
import io.harbor.fable.data.models.Box64Options
import io.harbor.fable.data.models.Box64Preset
import io.harbor.fable.data.models.Box64Settings
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

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
    // Downloaded DXVK builds, re-read whenever the asset list changes (a download finished).
    val assetEntries by app.assetRepository.assets.collectAsStateWithLifecycle()
    var dxvkBuilds by remember { mutableStateOf<List<ComponentBuild>>(emptyList()) }
    LaunchedEffect(assetEntries) { dxvkBuilds = repository.availableDxvkBuilds() }

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
        dxvkBuilds = dxvkBuilds,
        onBack = onBack,
        onLaunchPrimary = { launchExe(null) },
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
        onSelectResolution = { res ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(screenResolution = res)) } }
        },
        onSelectTranslator = { translator ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(translator = translator)) } }
        },
        onSelectDxvk = { dxvk ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(dxvkVersion = dxvk)) } }
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
}

@Composable
internal fun ContainerDetailContent(
    container: Container?,
    exes: List<ExeEntry>,
    dxvkBuilds: List<ComponentBuild>,
    onBack: () -> Unit,
    onLaunchPrimary: () -> Unit,
    onLaunchDesktop: () -> Unit,
    onSetPrimary: (ExeEntry) -> Unit,
    onSelectResolution: (String) -> Unit,
    onSelectTranslator: (String) -> Unit,
    onSelectDxvk: (String?) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onBox64Change: (Box64Settings) -> Unit,
    onAddExe: () -> Unit,
    onDelete: () -> Unit,
) {
    val appear = rememberEntrance()
    var box64Expanded by rememberSaveable { mutableStateOf(false) }

    FableScreen(
        title = container?.name ?: "Container",
        onBack = onBack,
        actions = {
            if (container != null) {
                FableIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "Add app",
                    onClick = onAddExe,
                )
            }
        },
    ) {
        if (container == null) {
            item(key = "missing") {
                EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Container not found",
                    modifier = Modifier.animateItem().entrance(appear, 0),
                )
            }
            return@FableScreen
        }

        // Launch controls: the primary app and the desktop side by side.
        item(key = "launch") {
            val primaryName = container.exeName?.takeIf { !container.exePath.isNullOrBlank() }
            Row(
                Modifier.fillMaxWidth().animateItem().entrance(appear, 0),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (primaryName != null) {
                    FableButton(
                        text = primaryName,
                        icon = Icons.Outlined.PlayArrow,
                        primary = true,
                        modifier = Modifier.weight(1f),
                        onClick = onLaunchPrimary,
                    )
                }
                FableButton(
                    text = if (primaryName != null) "Desktop" else "Launch Desktop",
                    icon = Icons.Outlined.DesktopWindows,
                    primary = primaryName == null,
                    modifier = Modifier.weight(1f),
                    onClick = onLaunchDesktop,
                )
            }
        }

        // Everything about the container itself is one section: what it runs, then how.
        item(key = "settings") {
            FableCard(Modifier.animateItem().entrance(appear, 1)) {
                if (container.status == ContainerStatus.ERROR) {
                    InfoRow(
                        label = "Status",
                        value = container.status.name.lowercase(),
                        valueContent = { StatusPill(container.status) },
                    )
                    CardDivider()
                }
                InfoRow(label = "Wine", value = container.wineVersion)
                CardDivider()
                InfoRow(label = "Driver", value = container.graphicsDriver)
                CardDivider()
                // DXVK goes into the prefix on the next launch (DxWrappers); off = WineD3D.
                OptionSelector(
                    label = "DXVK",
                    options = dxvkOptions(dxvkBuilds),
                    selected = container.dxvkVersion,
                    onSelect = onSelectDxvk,
                    hint = "Direct3D 8-11 through Vulkan. Applied on the next launch",
                )
                CardDivider()
                OptionSelector(
                    label = "Resolution",
                    options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                    selected = container.screenResolution,
                    onSelect = onSelectResolution,
                )
                CardDivider()
                OptionSelector(
                    label = "Translator",
                    options = TRANSLATOR_OPTIONS,
                    selected = container.translator,
                    onSelect = onSelectTranslator,
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen",
                    checked = container.isFullscreen,
                    onCheckedChange = onFullscreenChange,
                )
            }
        }

        // Box64 presets (Winlator's Box64PresetManager) and the individual BOX64_* switches. FEX
        // containers don't run Box64, so the section is hidden for them.
        if (!ContainerRepository.usesFex(container)) {
            item(key = "box64-label") { SectionLabel("Box64", Modifier.animateItem().entrance(appear, 2)) }
            item(key = "box64") {
                Box64PresetCard(
                    settings = container.box64,
                    onChange = onBox64Change,
                    modifier = Modifier.animateItem().entrance(appear, 2),
                )
            }
            item(key = "box64-options") {
                Box64OptionsSection(
                    settings = container.box64,
                    expanded = box64Expanded,
                    onToggle = { box64Expanded = !box64Expanded },
                    onChange = onBox64Change,
                    modifier = Modifier.animateItem().entrance(appear, 2),
                )
            }
        }

        item(key = "apps-label") { SectionLabel("Apps", Modifier.animateItem().entrance(appear, 2)) }
        item(key = "apps") {
            FableCard(Modifier.animateItem().entrance(appear, 2)) {
                exes.forEach { exe ->
                    ListRow(
                        title = exe.name,
                        subtitle = if (container.exePath == exe.path) "Primary" else null,
                        leading = { ExeIcon(name = exe.name, iconPath = exe.icon) },
                        showChevron = false,
                        // No per-row play button: tapping a row makes it the primary app, and the
                        // launch button at the top starts the primary app, so a second launch
                        // control on every row was redundant. "Primary" marks what will launch.
                        onClick = { onSetPrimary(exe) },
                    )
                    CardDivider(afterIcon = true)
                }
            }
        }

        item(key = "delete") {
            FableCard(Modifier.animateItem().entrance(appear, 3).padding(top = Spacing.xl)) {
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

/** Preset picker, the rc-file switch and, when settings were changed, a way back to the preset. */
@Composable
private fun Box64PresetCard(
    settings: Box64Settings,
    onChange: (Box64Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    FableCard(modifier) {
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

/** DXVK choices: the newest download (null), each downloaded build, or off (WineD3D). */
private fun dxvkOptions(builds: List<ComponentBuild>): List<SelectOption<String?>> = buildList {
    add(SelectOption(null, "Newest", builds.firstOrNull()?.label ?: "None downloaded yet (Assets)"))
    builds.forEach { add(SelectOption(it.id, it.label, it.archive.name)) }
    add(SelectOption(ContainerDefaults.DXVK_OFF, "Off", "WineD3D: needs OpenGL, most games won't render"))
}
