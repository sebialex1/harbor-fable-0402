package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.ControlHeight
import io.harbor.fable.ui.theme.FableAccent
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
        onSelectTranslator = { translator ->
            container?.let { fableUi.scope.launch { repository.update(it.copy(translator = translator)) } }
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
    onBack: () -> Unit,
    onLaunchPrimary: () -> Unit,
    onLaunchDesktop: () -> Unit,
    onLaunchExe: (ExeEntry) -> Unit,
    onSetPrimary: (ExeEntry) -> Unit,
    onSelectResolution: (String) -> Unit,
    onSelectTranslator: (String) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onAddExe: () -> Unit,
    onDelete: () -> Unit,
) {
    val appear = rememberEntrance()

    FableScreen(
        title = container?.name ?: "Container",
        onBack = onBack,
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
                InfoRow(
                    label = "Status",
                    value = container.status.name.lowercase(),
                    valueContent = { StatusPill(container.status) },
                )
                CardDivider()
                InfoRow(label = "Wine", value = container.wineVersion)
                CardDivider()
                InfoRow(label = "Driver", value = container.graphicsDriver)
                if (container.dxvkVersion != null) {
                    CardDivider()
                    InfoRow(label = "DXVK", value = container.dxvkVersion)
                }
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

        item(key = "apps-label") { SectionLabel("Apps", Modifier.animateItem().entrance(appear, 2)) }
        item(key = "apps") {
            FableCard(Modifier.animateItem().entrance(appear, 2)) {
                exes.forEach { exe ->
                    ListRow(
                        title = exe.name,
                        subtitle = if (container.exePath == exe.path) "Primary" else null,
                        showChevron = false,
                        trailing = {
                            FableIconButton(
                                icon = Icons.Outlined.PlayArrow,
                                contentDescription = "Launch ${exe.name}",
                                tint = Color.Black,
                                containerColor = FableAccent,
                                bordered = false,
                                size = ControlHeight.Compact,
                                onClick = { onLaunchExe(exe) },
                            )
                        },
                        onClick = { onSetPrimary(exe) },
                    )
                    CardDivider()
                }
                ListRow(
                    title = "Add App",
                    showChevron = false,
                    onClick = onAddExe,
                )
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
