package io.harbor.fable.ui.screens

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
import io.harbor.fable.data.LaunchLog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.harbor.fable.data.models.Box64Preset
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
    // Newest launch/crash log under filesDir/logs, re-read each time Settings opens.
    val latestLog = remember { LaunchLog.latest(context) }
    var logText by remember { mutableStateOf<String?>(null) }

    SettingsContent(
        latestLogName = latestLog?.name,
        onShowLog = {
            logText = latestLog?.let { LaunchLog.tail(it, 48 * 1024) } ?: "No launch log yet"
        },
        settings = settings,
        deviceInfo = deviceInfo,
        versionName = BuildConfig.VERSION_NAME,
        onUpdate = { transform -> settingsRepo.update(transform) },
        onResetClick = { showResetConfirm = true },
    )

    logText?.let { text ->
        FableSheet(
            title = "Last Launch Log",
            subtitle = latestLog?.absolutePath,
            onDismiss = { logText = null },
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = "Reset settings?",
            message = "Containers are not affected.",
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
    latestLogName: String? = null,
    onShowLog: () -> Unit = {},
    settings: AppSettings,
    deviceInfo: DeviceGpuInfo,
    versionName: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onResetClick: () -> Unit,
) {
    val resolutionOptions = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) }
    val translatorOptions = TRANSLATOR_OPTIONS
    val framePacingOptions = FramePacing.entries.map { SelectOption(it, it.label) }
    val appear = rememberEntrance()

    FableScreen(title = "Settings") {
        // Two sections: what new containers start with, and the app itself.
        item(key = "defaults-label") { SectionLabel("New Containers", Modifier.animateItem().entrance(appear, 0)) }
        item(key = "defaults") {
            FableCard(Modifier.animateItem().entrance(appear, 0)) {
                InfoRow(label = "Wine", value = settings.defaultWineVersion)
                CardDivider()
                InfoRow(label = "Driver", value = settings.defaultGraphicsDriver)
                CardDivider()
                OptionSelector(
                    label = "Resolution",
                    options = resolutionOptions,
                    selected = settings.defaultResolution,
                    onSelect = { res -> onUpdate { it.copy(defaultResolution = res) } },
                )
                CardDivider()
                OptionSelector(
                    label = "Translator",
                    options = translatorOptions,
                    selected = settings.defaultTranslator,
                    onSelect = { t -> onUpdate { it.copy(defaultTranslator = t) } },
                )
                CardDivider()
                OptionSelector(
                    label = "Box64 preset",
                    options = Box64Preset.entries.map { SelectOption(it, it.label, it.description) },
                    selected = settings.defaultBox64Preset,
                    onSelect = { preset -> onUpdate { it.copy(defaultBox64Preset = preset) } },
                )
                CardDivider()
                OptionSelector(
                    label = "Frame pacing",
                    options = framePacingOptions,
                    selected = settings.framePacing,
                    onSelect = { fp -> onUpdate { it.copy(framePacing = fp) } },
                )
                CardDivider()
                ToggleRow(
                    title = "Fullscreen",
                    checked = settings.defaultFullscreen,
                    onCheckedChange = { fs -> onUpdate { it.copy(defaultFullscreen = fs) } },
                )
                CardDivider()
                ToggleRow(
                    title = "VSync",
                    checked = settings.vsync,
                    onCheckedChange = { v -> onUpdate { it.copy(vsync = v) } },
                )
            }
        }

        item(key = "about-label") { SectionLabel("About", Modifier.animateItem().entrance(appear, 1)) }
        item(key = "about") {
            FableCard(Modifier.animateItem().entrance(appear, 1)) {
                InfoRow(label = "Version", value = versionName)
                CardDivider()
                InfoRow(label = "Device", value = listOf(deviceInfo.device, deviceInfo.vendor).filter { it.isNotBlank() }.distinct().joinToString(" · "))
                CardDivider()
                InfoRow(label = "GPU", value = deviceInfo.gpu)
                CardDivider()
                InfoRow(label = "System", value = listOf(deviceInfo.abi, "SDK ${deviceInfo.sdk}").filter { it.isNotBlank() }.joinToString(" · "))
                CardDivider()
                InfoRow(label = "License", value = "MIT")
                CardDivider()
                ToggleRow(
                    title = "Refresh on Launch",
                    checked = settings.refreshCatalogOnLaunch,
                    onCheckedChange = { r -> onUpdate { it.copy(refreshCatalogOnLaunch = r) } },
                )
                CardDivider()
                ListRow(
                    title = "Last Launch Log",
                    subtitle = latestLogName ?: "None yet",
                    onClick = onShowLog,
                )
                CardDivider()
                ListRow(
                    title = "Reset Settings",
                    titleColor = FableError,
                    showChevron = false,
                    onClick = onResetClick,
                )
            }
        }
    }
}
