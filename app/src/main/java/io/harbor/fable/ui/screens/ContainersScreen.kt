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
import androidx.compose.runtime.LaunchedEffect
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
import io.harbor.fable.data.WineBuild
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.ui.components.*
import kotlinx.coroutines.launch

/** Selectable x86 translation layers. Values match [Container.translator]. */
internal val TRANSLATOR_OPTIONS = listOf(SelectOption("box64", "Box64"), SelectOption("fex", "FEX"))

@Composable
fun ContainersScreen(
    onContainerClick: (String) -> Unit,
    onOpenAssets: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.containerRepository
    val settings = app.settingsRepository
    val containers by repository.containers.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    // Re-read the downloaded Wine packages whenever the sheet opens or the asset list changes.
    val assetEntries by app.assetRepository.assets.collectAsStateWithLifecycle()
    var wineBuilds by remember { mutableStateOf<List<WineBuild>>(emptyList()) }
    LaunchedEffect(showCreate, assetEntries) {
        if (showCreate) wineBuilds = repository.availableWineBuilds()
    }

    ContainersContent(
        containers = containers,
        onContainerClick = onContainerClick,
        onCreateClick = { showCreate = true },
    )

    if (showCreate) {
        CreateContainerSheet(
            defaultResolution = appSettings.defaultResolution,
            defaultWineVersion = appSettings.defaultWineVersion,
            wineBuilds = wineBuilds,
            defaultFullscreen = appSettings.defaultFullscreen,
            defaultTranslator = appSettings.defaultTranslator,
            onDismiss = { showCreate = false },
            onOpenAssets = {
                showCreate = false
                onOpenAssets()
            },
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
        if (containers.isNotEmpty()) {
            item(key = "list") {
                FableCard(Modifier.fillMaxWidth().animateItem().entrance(appear, 0)) {
                    containers.forEachIndexed { index, container ->
                        if (index > 0) CardDivider(afterIcon = true)
                        ListRow(
                            title = container.name,
                            subtitle = container.wineVersion.ifBlank { "No Wine chosen" },
                            icon = Icons.Outlined.Inventory2,
                            trailing = { StatusPill(container.status) },
                            onClick = { onContainerClick(container.id) },
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
    wineBuilds: List<WineBuild>,
    defaultFullscreen: Boolean,
    defaultTranslator: String,
    onDismiss: () -> Unit,
    onOpenAssets: () -> Unit,
    onCreate: (name: String, resolution: String, wineVersion: String, fullscreen: Boolean, translator: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf(defaultResolution) }
    var fullscreen by remember { mutableStateOf(defaultFullscreen) }
    var translator by remember { mutableStateOf(defaultTranslator) }
    // The user's default when it is downloaded, else the newest bionic package.
    var wineChoice by remember { mutableStateOf<String?>(null) }
    val wineVersion = wineChoice
        ?: wineBuilds.firstOrNull { it.id.equals(defaultWineVersion, ignoreCase = true) }?.id
        ?: wineBuilds.firstOrNull()?.id
        ?: ""

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
            if (wineBuilds.isNotEmpty()) {
                OptionSelector(
                    label = "Wine",
                    options = wineBuilds.map { SelectOption(it.id, it.label, it.archive.name) },
                    selected = wineVersion,
                    onSelect = { wineChoice = it },
                )
            } else {
                // Only bionic (Winlator .wcp) Wine runs in Fable; glibc builds are never listed.
                InfoRow(
                    label = "Wine",
                    value = "Download one in Assets",
                    onClick = { close(onOpenAssets) },
                )
            }
            CardDivider()
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
