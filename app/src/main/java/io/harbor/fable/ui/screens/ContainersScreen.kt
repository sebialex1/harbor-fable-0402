package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Selectable x86 translation layers. Values match [Container.translator]. */
internal val TRANSLATOR_OPTIONS = listOf(SelectOption("box64", "Box64"), SelectOption("fex", "FEX"))

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

    ContainersContent(
        containers = containers,
        onContainerClick = onContainerClick,
        onCreateClick = { showCreate = true },
    )

    if (showCreate) {
        CreateContainerSheet(
            defaultResolution = appSettings.defaultResolution,
            defaultWineVersion = appSettings.defaultWineVersion,
            defaultFullscreen = appSettings.defaultFullscreen,
            defaultTranslator = appSettings.defaultTranslator,
            onDismiss = { showCreate = false },
            onCreate = { name, resolution, wineVersion, fullscreen, translator ->
                scope.launch {
                    repository.create(
                        name = name,
                        screenResolution = resolution,
                        wineVersion = wineVersion,
                        isFullscreen = fullscreen,
                        graphicsDriver = appSettings.defaultGraphicsDriver,
                        dxvkVersion = appSettings.defaultDxvkVersion,
                        driverId = appSettings.defaultDriverId,
                        translator = translator,
                    )
                }
                showCreate = false
            },
        )
    }
}

@Composable
internal fun ContainersContent(
    containers: List<Container>,
    onContainerClick: (String) -> Unit,
    onCreateClick: () -> Unit,
) {
    val appear = rememberLiquidAppear()

    FableScreen(
        title = "Containers",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "New container",
                onClick = onCreateClick,
            )
        },
    ) {
        if (containers.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Apps,
                    title = "No containers yet",
                    message = "Create a container to get started",
                    actionLabel = "Create Container",
                    actionIcon = Icons.Outlined.Add,
                    onAction = onCreateClick,
                    modifier = Modifier.animateItem().liquidAppear(appear, 0),
                )
            }
        } else {
            // One continuous surface: every container is a row of the same card. The card
            // settles first and the rows arrive one after another inside it.
            item(key = "list") {
                GlassCard(Modifier.fillMaxWidth().animateItem().liquidAppear(appear, 0)) {
                    containers.forEachIndexed { index, container ->
                        if (index > 0) CardDivider()
                        ListRow(
                            modifier = Modifier.liquidAppear(appear, index + 1),
                            title = container.name,
                            subtitle = container.wineVersion,
                            icon = Icons.Outlined.Apps,
                            iconTint = containerStatusColor(container.status),
                            trailing = { StatusPill(container.status) },
                            onClick = { onContainerClick(container.id) },
                        )
                    }
                }
            }

            // When the list is short, fill the space with quick actions instead of void.
            if (containers.size < 3) {
                item(key = "quick-actions-label") {
                    SectionLabel("Quick Actions", Modifier.animateItem().liquidAppear(appear, containers.size + 1))
                }
                item(key = "quick-actions") {
                    GlassCard(Modifier.fillMaxWidth().animateItem().liquidAppear(appear, containers.size + 2)) {
                        ListRow(
                            title = "Create Container",
                            subtitle = "Set up a new Wine environment",
                            icon = Icons.Outlined.Add,
                            iconTint = FableAccent,
                            showChevron = false,
                            onClick = onCreateClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateContainerSheet(
    defaultResolution: String,
    defaultWineVersion: String,
    defaultFullscreen: Boolean,
    defaultTranslator: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, resolution: String, wineVersion: String, fullscreen: Boolean, translator: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf(defaultResolution) }
    var fullscreen by remember { mutableStateOf(defaultFullscreen) }
    var translator by remember { mutableStateOf(defaultTranslator) }
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

        // Both settings share one surface instead of two stacked cards.
        GlassCard {
            OptionSelector(
                label = "Resolution",
                options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                selected = resolution,
                onSelect = { resolution = it },
                icon = Icons.Outlined.AspectRatio,
            )
            CardDivider()
            OptionSelector(
                label = "Translation layer",
                options = TRANSLATOR_OPTIONS,
                selected = translator,
                onSelect = { translator = it },
                icon = Icons.Outlined.DeveloperBoard,
            )
            CardDivider()
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
                    close { onCreate(name.trim(), resolution, wineVersion, fullscreen, translator) }
                }
            },
        )
    }
}
