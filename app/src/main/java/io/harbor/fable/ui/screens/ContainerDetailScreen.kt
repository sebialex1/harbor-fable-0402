package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
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

    // Launching can take a while on first use, and a delete must finish even after the screen
    // is gone, so both run on the app-level scope.
    fun launchExe(exeId: String?) {
        fableUi.scope.launch {
            fableUi.showMessage(repository.launch(containerId, exeId).message(), long = true)
        }
    }

    ContainerDetailContent(
        container = container,
        exes = containerExes,
        onBack = onBack,
        onLaunchPrimary = { launchExe(null) },
        onLaunchDesktop = {
            fableUi.scope.launch {
                fableUi.showMessage(repository.launchDesktop(containerId).message(), long = true)
            }
        },
        onLaunchExe = { exe -> launchExe(exe.id) },
        onSetPrimary = { exe ->
            fableUi.scope.launch {
                repository.setPrimaryExe(exe.id)
                fableUi.showMessage("${exe.name} set as primary")
            }
        },
        onSelectResolution = { res ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(screenResolution = res)) } }
        },
        onFullscreenChange = { fullscreen ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(isFullscreen = fullscreen)) } }
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
            message = "This will permanently delete \"${container?.name}\" and all its files. This cannot be undone.",
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
    onBack: () -> Unit,
    onLaunchPrimary: () -> Unit,
    onLaunchDesktop: () -> Unit,
    onLaunchExe: (ExeEntry) -> Unit,
    onSetPrimary: (ExeEntry) -> Unit,
    onSelectResolution: (String) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onAddExe: () -> Unit,
    onDelete: () -> Unit,
) {
    FableScreen(
        title = container?.name ?: "Container",
        onBack = onBack,
    ) {
        if (container == null) {
            item(key = "missing") {
                EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Container not found",
                    actionLabel = "Go Back",
                    actionIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                    onAction = onBack,
                )
            }
            return@FableScreen
        }

        // Launch controls: the primary app and the desktop side by side.
        item(key = "launch") {
            val primaryName = container.exeName?.takeIf { !container.exePath.isNullOrBlank() }
            Row(
                Modifier.fillMaxWidth().animateItem(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (primaryName != null) {
                    GlassButton(
                        text = primaryName,
                        icon = Icons.Outlined.PlayArrow,
                        primary = true,
                        modifier = Modifier.weight(1f),
                        onClick = onLaunchPrimary,
                    )
                }
                GlassButton(
                    text = if (primaryName != null) "Desktop" else "Launch Desktop",
                    icon = Icons.Outlined.DesktopWindows,
                    primary = primaryName == null,
                    modifier = Modifier.weight(1f),
                    onClick = onLaunchDesktop,
                )
            }
        }

        item(key = "overview-label") { SectionLabel("Overview", Modifier.animateItem()) }
        item(key = "overview") {
            GlassCard(Modifier.animateItem()) {
                InfoRow(
                    label = "Status",
                    value = container.status.name.lowercase(),
                    icon = Icons.Outlined.Info,
                    valueContent = { StatusPill(container.status) },
                )
                CardDivider()
                InfoRow(label = "Wine", value = container.wineVersion, icon = Icons.Outlined.WineBar)
                CardDivider()
                InfoRow(label = "Driver", value = container.graphicsDriver, icon = Icons.Outlined.Memory)
                if (container.dxvkVersion != null) {
                    CardDivider()
                    InfoRow(label = "DXVK", value = container.dxvkVersion, icon = Icons.Outlined.Layers)
                }
            }
        }

        item(key = "settings-label") { SectionLabel("Display", Modifier.animateItem()) }
        item(key = "settings") {
            GlassCard(Modifier.animateItem()) {
                OptionSelector(
                    label = "Resolution",
                    options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                    selected = container.screenResolution,
                    onSelect = onSelectResolution,
                    icon = Icons.Outlined.AspectRatio,
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen",
                    checked = container.isFullscreen,
                    onCheckedChange = onFullscreenChange,
                    icon = Icons.Outlined.Fullscreen,
                )
            }
        }

        item(key = "apps-label") { SectionLabel("Apps", Modifier.animateItem()) }
        item(key = "apps") {
            GlassCard(Modifier.animateItem()) {
                if (exes.isEmpty()) {
                    ListRow(
                        title = "No apps",
                        icon = Icons.Outlined.FileOpen,
                        showChevron = false,
                    )
                    CardDivider()
                } else {
                    exes.forEach { exe ->
                        ListRow(
                            title = exe.name,
                            subtitle = if (container.exePath == exe.path) "Primary" else null,
                            icon = Icons.Outlined.SportsEsports,
                            showChevron = false,
                            trailing = {
                                GlassButton(
                                    text = "Launch",
                                    icon = Icons.Outlined.PlayArrow,
                                    primary = true,
                                    compact = true,
                                    onClick = { onLaunchExe(exe) },
                                )
                            },
                            onClick = { onSetPrimary(exe) },
                        )
                        CardDivider()
                    }
                }
                ListRow(
                    title = "Add executable",
                    icon = Icons.Outlined.Add,
                    showChevron = true,
                    onClick = onAddExe,
                )
            }
        }

        item(key = "delete") {
            GlassCard(Modifier.animateItem()) {
                ListRow(
                    title = "Delete container",
                    titleColor = FableError,
                    icon = Icons.Outlined.Delete,
                    iconTint = FableError,
                    showChevron = false,
                    onClick = onDelete,
                )
            }
        }
    }
}
