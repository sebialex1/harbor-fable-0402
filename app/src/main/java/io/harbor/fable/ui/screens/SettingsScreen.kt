package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
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
import io.harbor.fable.data.FramePacing
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableError
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val settingsRepo = app.settingsRepository
    val settings by settingsRepo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val deviceInfo = remember { DeviceProbe.read() }
    var showResetConfirm by remember { mutableStateOf(false) }

    val resolutionOptions = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) }
    val framePacingOptions = FramePacing.entries.map {
        SelectOption(it, it.label)
    }

    FableScreen(
        title = "Settings",
        subtitle = "App-wide configuration",
    ) {
        // Default container settings
        item { SectionLabel("Container Defaults") }
        item {
            GlassCard {
                InfoRow(
                    label = "Wine version",
                    value = settings.defaultWineVersion,
                    icon = Icons.Outlined.WineBar,
                )
                CardDivider()
                OptionSelector(
                    label = "Default resolution",
                    options = resolutionOptions,
                    selected = settings.defaultResolution,
                    onSelect = { res ->
                        settingsRepo.update { it.copy(defaultResolution = res) }
                    },
                    icon = Icons.Outlined.AspectRatio,
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen by default",
                    subtitle = "New containers start in fullscreen",
                    checked = settings.defaultFullscreen,
                    onCheckedChange = { fs ->
                        settingsRepo.update { it.copy(defaultFullscreen = fs) }
                    },
                    icon = Icons.Outlined.Fullscreen,
                )
            }
        }

        // Graphics
        item { SectionLabel("Graphics") }
        item {
            GlassCard {
                InfoRow(
                    label = "Default driver",
                    value = settings.defaultGraphicsDriver,
                    icon = Icons.Outlined.Memory,
                )
                CardDivider()
                ToggleRow(
                    title = "VSync",
                    subtitle = "Synchronize frames to display refresh",
                    checked = settings.vsync,
                    onCheckedChange = { v ->
                        settingsRepo.update { it.copy(vsync = v) }
                    },
                    icon = Icons.Outlined.Sync,
                )
                CardDivider()
                OptionSelector(
                    label = "Frame pacing",
                    options = framePacingOptions,
                    selected = settings.framePacing,
                    onSelect = { fp ->
                        settingsRepo.update { it.copy(framePacing = fp) }
                    },
                    icon = Icons.Outlined.Speed,
                )
            }
        }

        // Catalog
        item { SectionLabel("Catalog") }
        item {
            GlassCard {
                ToggleRow(
                    title = "Refresh on launch",
                    subtitle = "Fetch latest catalog when the app starts",
                    checked = settings.refreshCatalogOnLaunch,
                    onCheckedChange = { r ->
                        settingsRepo.update { it.copy(refreshCatalogOnLaunch = r) }
                    },
                    icon = Icons.Outlined.Refresh,
                )
            }
        }

        // Device info
        item { SectionLabel("Device") }
        item {
            GlassCard {
                InfoRow(
                    label = "GPU",
                    value = deviceInfo.gpu,
                    icon = Icons.Outlined.Memory,
                )
                CardDivider()
                InfoRow(
                    label = "Vendor",
                    value = deviceInfo.vendor,
                    icon = Icons.Outlined.Business,
                )
                CardDivider()
                InfoRow(
                    label = "Device",
                    value = deviceInfo.device,
                    icon = Icons.Outlined.Smartphone,
                )
                CardDivider()
                InfoRow(
                    label = "Architecture",
                    value = deviceInfo.abi,
                    icon = Icons.Outlined.Architecture,
                )
                CardDivider()
                InfoRow(
                    label = "SDK",
                    value = deviceInfo.sdk,
                    icon = Icons.Outlined.Code,
                )
                CardDivider()
                InfoRow(
                    label = "Adrenotools",
                    value = if (deviceInfo.adrenoToolsSupported) "Supported" else "Not available",
                    icon = Icons.Outlined.Verified,
                )
            }
        }

        // About
        item { SectionLabel("About") }
        item {
            GlassCard {
                InfoRow(
                    label = "Version",
                    value = "0.1.0",
                    icon = Icons.Outlined.Info,
                )
                CardDivider()
                InfoRow(
                    label = "License",
                    value = "MIT",
                    icon = Icons.Outlined.Description,
                )
            }
        }

        // Reset
        item { SectionLabel("Reset") }
        item {
            GlassCard {
                ListRow(
                    title = "Reset to defaults",
                    subtitle = "Restore all settings to their default values",
                    icon = Icons.Outlined.Restore,
                    iconTint = FableError,
                    showChevron = true,
                    onClick = { showResetConfirm = true },
                )
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = "Reset settings?",
            message = "All app settings will be restored to their default values. Your containers are not affected.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = {
                showResetConfirm = false
                settingsRepo.reset()
            },
            onDismiss = { showResetConfirm = false },
        )
    }
}
