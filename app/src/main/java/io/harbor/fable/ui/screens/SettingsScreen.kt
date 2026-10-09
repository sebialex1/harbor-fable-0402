package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import io.harbor.fable.ui.icons.FableIcons
import io.harbor.fable.ui.theme.GlassBorder
import io.harbor.fable.ui.theme.PillRadius
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing
import io.harbor.fable.ui.theme.TileTone
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
fun SettingsScreen(onOpenControls: () -> Unit = {}) {
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
        onOpenControls = onOpenControls,
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
    onOpenControls: () -> Unit = {},
) {
    val resolutionOptions = ContainerDefaults.RESOLUTION_PRESETS.map { SelectOption(it, it) }
    val translatorOptions = TRANSLATOR_OPTIONS
    val framePacingOptions = FramePacing.entries.map { SelectOption(it, it.label) }
    val appear = rememberEntrance()

    FableScreen(title = "Settings") {
        // The device first, as a hero card; then what new containers start with, and the app.
        item(key = "device") {
            DeviceHeroCard(deviceInfo = deviceInfo, modifier = Modifier.animateItem().entrance(appear, 0))
        }
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

        item(key = "controls-label") { SectionLabel("Input", Modifier.animateItem().entrance(appear, 1)) }
        item(key = "controls") {
            FableCard(Modifier.animateItem().entrance(appear, 1)) {
                ListRow(
                    title = "Controls",
                    subtitle = "On-screen control presets: add, rename, import, export",
                    icon = FableIcons.Apps,
                    onClick = onOpenControls,
                )
            }
        }

        item(key = "about-label") { SectionLabel("About", Modifier.animateItem().entrance(appear, 1)) }
        item(key = "about") {
            FableCard(Modifier.animateItem().entrance(appear, 1)) {
                // Device, GPU and system live on the hero card above, not repeated here.
                InfoRow(label = "Version", value = versionName)
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

/**
 * The device as a hero: the GPU on an indigo-lit glass card with its tile, the model beneath,
 * and the system facts as small glass chips.
 */
@Composable
private fun DeviceHeroCard(deviceInfo: DeviceGpuInfo, modifier: Modifier = Modifier) {
    FableCard(modifier.fillMaxWidth(), glow = TileTone.Indigo) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = deviceInfo.gpu.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) } ?: "Unknown GPU",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(deviceInfo.device, deviceInfo.vendor).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs)) {
                    listOf(deviceInfo.abi, "SDK ${deviceInfo.sdk}").filter { it.isNotBlank() }.forEach { fact ->
                        Text(
                            text = fact,
                            style = MaterialTheme.typography.labelSmall,
                            color = TileTone.Indigo.glyph,
                            modifier = Modifier
                                .glassOverlay(RoundedCornerShape(PillRadius), border = GlassBorder)
                                .padding(horizontal = Spacing.sm, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

