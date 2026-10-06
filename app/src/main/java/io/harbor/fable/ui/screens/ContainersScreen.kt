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
import io.harbor.fable.ui.theme.FableText
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

    var showAddApp by remember { mutableStateOf(false) }

    ContainersContent(
        containers = containers,
        onContainerClick = onContainerClick,
        onCreateClick = { showCreate = true },
        onAddApp = { showAddApp = true },
    )

    if (showAddApp) {
        AddAppSheet(onDismiss = { showAddApp = false })
    }

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
    onAddApp: () -> Unit = {},
) {
    val appear = rememberEntrance()

    // Creating a container lives in the top bar only; the empty list does not repeat it.
    FableScreen(
        title = "Containers",
        actions = {
            FableIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "New container",
                onClick = onCreateClick,
            )
        },
    ) {
        if (containers.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "No containers",
                    modifier = Modifier.animateItem().entrance(appear, 0),
                )
            }
        } else {
            item(key = "list") {
                FableCard(Modifier.fillMaxWidth().animateItem().entrance(appear, 0)) {
                    containers.forEachIndexed { index, container ->
                        if (index > 0) CardDivider(afterIcon = true)
                        ListRow(
                            title = container.name,
                            subtitle = container.wineVersion,
                            icon = Icons.Outlined.Inventory2,
                            trailing = { StatusPill(container.status) },
                            onClick = { onContainerClick(container.id) },
                        )
                    }
                }
            }

            // A short list gets one quick action that the top bar does not already offer.
            if (containers.size < 3) {
                item(key = "quick-actions") {
                    FableCard(Modifier.fillMaxWidth().animateItem().entrance(appear, 1)) {
                        ListRow(
                            title = "Add App",
                            titleColor = FableText,
                            icon = Icons.Outlined.Add,
                            showChevron = false,
                            onClick = onAddApp,
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
        FableTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
        )

        // All settings share one section.
        FableCard {
            OptionSelector(
                label = "Resolution",
                options = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) },
                selected = resolution,
                onSelect = { resolution = it },
            )
            CardDivider()
            OptionSelector(
                label = "Translator",
                options = TRANSLATOR_OPTIONS,
                selected = translator,
                onSelect = { translator = it },
            )
            CardDivider()
            ToggleRow(
                title = "Fullscreen",
                checked = fullscreen,
                onCheckedChange = { fullscreen = it },
            )
        }

        FableButton(
            text = "Create",
            primary = true,
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (name.isNotBlank()) {
                    close { onCreate(name.trim(), resolution, wineVersion, fullscreen, translator) }
                }
            },
        )
    }
}
