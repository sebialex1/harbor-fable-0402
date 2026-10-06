package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableSuccess
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

    val scope = rememberCoroutineScope()
    var showAddExe by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    FableScreen(
        title = container?.name ?: "Container",
        subtitle = container?.wineVersion,
        onBack = onBack,
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.PlayArrow,
                contentDescription = "Launch",
                onClick = {
                    scope.launch {
                        fableUi.showMessage(repository.launch(containerId).message(), long = true)
                    }
                },
            )
        },
    ) {
        if (container == null) {
            item {
                EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Container not found",
                    message = "This container may have been deleted.",
                    actionLabel = "Go Back",
                    actionIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                    onAction = onBack,
                )
            }
            return@FableScreen
        }

        // Overview section
        item { SectionLabel("Overview") }
        item {
            GlassCard {
                InfoRow(
                    label = "Status",
                    value = container.status.name.lowercase(),
                    icon = Icons.Outlined.Info,
                    valueColor = containerStatusColor(container.status),
                )
                CardDivider()
                InfoRow(
                    label = "Wine",
                    value = container.wineVersion,
                    icon = Icons.Outlined.WineBar,
                )
                CardDivider()
                InfoRow(
                    label = "Driver",
                    value = container.graphicsDriver,
                    icon = Icons.Outlined.Memory,
                )
                if (container.dxvkVersion != null) {
                    CardDivider()
                    InfoRow(
                        label = "DXVK",
                        value = container.dxvkVersion,
                        icon = Icons.Outlined.Layers,
                    )
                }
                CardDivider()
                InfoRow(
                    label = "Resolution",
                    value = container.screenResolution,
                    icon = Icons.Outlined.AspectRatio,
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen",
                    checked = container.isFullscreen,
                    onCheckedChange = { fullscreen ->
                        scope.launch {
                            repository.update(container.copy(isFullscreen = fullscreen))
                        }
                    },
                    icon = Icons.Outlined.Fullscreen,
                )
            }
        }

        // Apps assigned to this container
        item { SectionLabel("Apps") }
        item {
            GlassCard {
                if (containerExes.isEmpty()) {
                    ListRow(
                        title = "No apps assigned",
                        subtitle = "Add an app or game to run it in this container",
                        icon = Icons.Outlined.FileOpen,
                        showChevron = false,
                    )
                } else {
                    containerExes.forEach { exe ->
                        ListRow(
                            title = exe.name,
                            subtitle = if (container.exePath == exe.path) "Primary app" else "Tap to make primary",
                            icon = Icons.Outlined.SportsEsports,
                            showChevron = false,
                            trailing = {
                                GlassButton(
                                    text = "Launch",
                                    icon = Icons.Outlined.PlayArrow,
                                    primary = true,
                                    compact = true,
                                    onClick = {
                                        scope.launch {
                                            fableUi.showMessage(
                                                repository.launch(containerId, exe.id).message(),
                                                long = true,
                                            )
                                        }
                                    },
                                )
                            },
                            onClick = {
                                scope.launch {
                                    repository.setPrimaryExe(exe.id)
                                    fableUi.showMessage("${exe.name} set as primary")
                                }
                            },
                        )
                        CardDivider()
                    }
                }
                ListRow(
                    title = "Add executable",
                    subtitle = "Pick an app or game file",
                    icon = Icons.Outlined.Add,
                    showChevron = true,
                    onClick = { showAddExe = true },
                )
            }
        }

        // Container settings
        item { SectionLabel("Container Settings") }
        item {
            GlassCard {
                OptionSelector(
                    label = "Resolution",
                    options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                    selected = container.screenResolution,
                    onSelect = { res ->
                        scope.launch {
                            repository.update(container.copy(screenResolution = res))
                        }
                    },
                    icon = Icons.Outlined.AspectRatio,
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen",
                    subtitle = "Run in fullscreen mode",
                    checked = container.isFullscreen,
                    onCheckedChange = { fs ->
                        scope.launch {
                            repository.update(container.copy(isFullscreen = fs))
                        }
                    },
                    icon = Icons.Outlined.Fullscreen,
                )
            }
        }

        // Danger zone
        item { SectionLabel("Danger Zone") }
        item {
            GlassCard {
                ListRow(
                    title = "Delete container",
                    subtitle = "Permanently remove this container and its files",
                    icon = Icons.Outlined.Delete,
                    iconTint = FableError,
                    showChevron = true,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }

        item { Spacer(modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)) }
    }

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
                scope.launch {
                    repository.delete(containerId)
                }
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
