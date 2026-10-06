package io.harbor.fable.ui.screens

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
import io.harbor.fable.BuildConfig
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.AppSettings
import io.harbor.fable.data.FramePacing
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableError

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val settingsRepo = app.settingsRepository
    val settings by settingsRepo.settings.collectAsStateWithLifecycle()
    val deviceInfo = remember { DeviceProbe.read() }
    var showResetConfirm by remember { mutableStateOf(false) }

    SettingsContent(
        settings = settings,
        deviceInfo = deviceInfo,
        versionName = BuildConfig.VERSION_NAME,
        onUpdate = { transform -> settingsRepo.update(transform) },
        onResetClick = { showResetConfirm = true },
    )

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

@Composable
internal fun SettingsContent(
    settings: AppSettings,
    deviceInfo: DeviceGpuInfo,
    versionName: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onResetClick: () -> Unit,
) {
    val resolutionOptions = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) }
    val framePacingOptions = FramePacing.entries.map { SelectOption(it, it.label) }

    FableScreen(title = "Settings") {
        item(key = "defaults-label") { SectionLabel("Container Defaults", Modifier.animateItem()) }
        item(key = "defaults") {
            GlassCard(Modifier.animateItem()) {
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
                    onSelect = { res -> onUpdate { it.copy(defaultResolution = res) } },
                    icon = Icons.Outlined.AspectRatio,
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen by default",
                    checked = settings.defaultFullscreen,
                    onCheckedChange = { fs -> onUpdate { it.copy(defaultFullscreen = fs) } },
                    icon = Icons.Outlined.Fullscreen,
                )
            }
        }

        item(key = "graphics-label") { SectionLabel("Graphics", Modifier.animateItem()) }
        item(key = "graphics") {
            GlassCard(Modifier.animateItem()) {
                InfoRow(
                    label = "Default driver",
                    value = settings.defaultGraphicsDriver,
                    icon = Icons.Outlined.Memory,
                )
                CardDivider()
                ToggleRow(
                    title = "VSync",
                    checked = settings.vsync,
                    onCheckedChange = { v -> onUpdate { it.copy(vsync = v) } },
                    icon = Icons.Outlined.Sync,
                )
                CardDivider()
                OptionSelector(
                    label = "Frame pacing",
                    options = framePacingOptions,
                    selected = settings.framePacing,
                    onSelect = { fp -> onUpdate { it.copy(framePacing = fp) } },
                    icon = Icons.Outlined.Speed,
                )
            }
        }

        item(key = "catalog-label") { SectionLabel("Catalog", Modifier.animateItem()) }
        item(key = "catalog") {
            GlassCard(Modifier.animateItem()) {
                ToggleRow(
                    title = "Refresh on launch",
                    checked = settings.refreshCatalogOnLaunch,
                    onCheckedChange = { r -> onUpdate { it.copy(refreshCatalogOnLaunch = r) } },
                    icon = Icons.Outlined.Refresh,
                )
            }
        }

        item(key = "device-label") { SectionLabel("Device", Modifier.animateItem()) }
        item(key = "device") {
            GlassCard(Modifier.animateItem()) {
                InfoRow(label = "GPU", value = deviceInfo.gpu, icon = Icons.Outlined.Memory)
                CardDivider()
                InfoRow(label = "Vendor", value = deviceInfo.vendor, icon = Icons.Outlined.Business)
                CardDivider()
                InfoRow(label = "Device", value = deviceInfo.device, icon = Icons.Outlined.Smartphone)
                CardDivider()
                InfoRow(label = "Architecture", value = deviceInfo.abi, icon = Icons.Outlined.Architecture)
                CardDivider()
                InfoRow(label = "SDK", value = deviceInfo.sdk, icon = Icons.Outlined.Code)
            }
        }

        item(key = "about-label") { SectionLabel("About", Modifier.animateItem()) }
        item(key = "about") {
            GlassCard(Modifier.animateItem()) {
                InfoRow(label = "Version", value = versionName, icon = Icons.Outlined.Info)
                CardDivider()
                InfoRow(label = "License", value = "MIT", icon = Icons.Outlined.Description)
                CardDivider()
                ListRow(
                    title = "Reset to defaults",
                    titleColor = FableError,
                    icon = Icons.Outlined.Restore,
                    iconTint = FableError,
                    showChevron = false,
                    onClick = onResetClick,
                )
            }
        }
    }
}
