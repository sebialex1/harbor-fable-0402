package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun ContainersScreen(
    onContainerClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.containerRepository
    val settings = app.settingsRepository
    val containers by repository.containers.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }

    FableScreen(
        title = "Containers",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "New container",
                onClick = { showCreate = true },
            )
        },
    ) {
        if (containers.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Apps,
                    title = "No containers yet",
                    message = "Create a container to get started",
                    actionLabel = "Create Container",
                    actionIcon = Icons.Outlined.Add,
                    onAction = { showCreate = true },
                )
            }
        } else {
            item { SectionLabel("Your Containers") }
            items(containers) { container ->
                ContainerCard(
                    container = container,
                    onClick = { onContainerClick(container.id) },
                )
            }
        }
    }

    if (showCreate) {
        CreateContainerSheet(
            defaultResolution = appSettings.defaultResolution,
            defaultWineVersion = appSettings.defaultWineVersion,
            defaultFullscreen = appSettings.defaultFullscreen,
            onDismiss = { showCreate = false },
            onCreate = { name, resolution, wineVersion, fullscreen ->
                scope.launch {
                    repository.create(
                        name = name,
                        screenResolution = resolution,
                        wineVersion = wineVersion,
                        isFullscreen = fullscreen,
                        graphicsDriver = appSettings.defaultGraphicsDriver,
                        dxvkVersion = appSettings.defaultDxvkVersion,
                        driverId = appSettings.defaultDriverId,
                    )
                }
                showCreate = false
            },
        )
    }
}

@Composable
private fun ContainerCard(
    container: Container,
    onClick: () -> Unit,
) {
    GlassCard(onClick = onClick) {
        ListRow(
            title = container.name,
            subtitle = container.wineVersion,
            icon = Icons.Outlined.Apps,
            iconTint = containerStatusColor(container.status),
            showChevron = true,
            trailing = {
                Pill(
                    text = container.status.name.lowercase(),
                    color = containerStatusColor(container.status),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateContainerSheet(
    defaultResolution: String,
    defaultWineVersion: String,
    defaultFullscreen: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, resolution: String, wineVersion: String, fullscreen: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf(defaultResolution) }
    var fullscreen by remember { mutableStateOf(defaultFullscreen) }
    val wineVersion = defaultWineVersion

    FableSheet(
        title = "New Container",
        onDismiss = onDismiss,
    ) { close ->
        GlassTextField(
            value = name,
            onValueChange = { name = it },
            label = "Container name",
        )

        GlassCard {
            OptionSelector(
                label = "Resolution",
                options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                selected = resolution,
                onSelect = { resolution = it },
                icon = Icons.Outlined.AspectRatio,
            )
        }

        GlassCard {
            ToggleRow(
                title = "Fullscreen",
                checked = fullscreen,
                onCheckedChange = { fullscreen = it },
                icon = Icons.Outlined.Fullscreen,
            )
        }

        GlassButton(
            text = "Create",
            primary = true,
            icon = Icons.Outlined.Add,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (name.isNotBlank()) {
                    close { onCreate(name.trim(), resolution, wineVersion, fullscreen) }
                }
            },
        )
    }
}
