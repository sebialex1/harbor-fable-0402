package io.harbor.fable.ui.screens

import io.harbor.fable.ui.theme.TileTone
import io.harbor.fable.ui.theme.FableBlue
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.VulkanExtension
import io.harbor.fable.data.models.VulkanExtensionFamily
import io.harbor.fable.data.models.VulkanProbeResult
import io.harbor.fable.data.models.VulkanSource
import io.harbor.fable.nativebridge.VulkanProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing
import io.harbor.fable.ui.icons.FableIcons

/**
 * Vulkan extensions as the driver reports them on this device, read with
 * `vkEnumerateInstanceExtensionProperties` and `vkEnumerateDeviceExtensionProperties` through
 * the native probe. The user picks the source: the installed RADV Xclipse driver (opened through
 * the adrenotools loader, exactly as Wine will see it) or the system Vulkan driver.
 *
 * The list is honest about what it is: the extensions an implementation advertises, not what a
 * particular game ends up using. Results are cached per source in [VulkanProbe].
 */
@Composable
fun VulkanExtensionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val installed by app.driverRepository.installed.collectAsStateWithLifecycle()
    val results by VulkanProbe.results.collectAsStateWithLifecycle()
    val probing by VulkanProbe.probing.collectAsStateWithLifecycle()

    // The installed driver is what matters most; fall back to the system when there is none.
    var source by rememberSaveable(installed != null) {
        mutableStateOf(if (installed != null) VulkanSource.INSTALLED_DRIVER else VulkanSource.SYSTEM)
    }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val libraryPath = installed?.libraryPath

    LaunchedEffect(source, libraryPath, refreshTrigger) {
        VulkanProbe.probe(source, libraryPath, force = refreshTrigger > 0)
    }

    VulkanExtensionsContent(
        source = source,
        installed = installed,
        result = results[source]?.takeIf { source == VulkanSource.SYSTEM || it.library == libraryPath },
        probing = source in probing,
        onSelectSource = { source = it },
        onRefresh = { refreshTrigger++ },
        onBack = onBack,
    )
}

@Composable
internal fun VulkanExtensionsContent(
    source: VulkanSource,
    installed: InstalledDriver?,
    result: VulkanProbeResult?,
    probing: Boolean,
    onSelectSource: (VulkanSource) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val expansion = rememberExpansionState()
    var query by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<VulkanExtensionFamily?>(null) }

    val device = result?.primaryDevice
    val deviceExtensions = remember(result, query, family) { result?.deviceExtensions.orEmpty().filtered(query, family) }
    val instanceExtensions = remember(result, query, family) { result?.instanceExtensions.orEmpty().filtered(query, family) }
    val filtering = query.isNotBlank() || family != null

    FableScreen(
        title = "Vulkan Extensions",
        onBack = onBack,
        actions = {
            FableIconButton(
                icon = FableIcons.Refresh,
                contentDescription = "Probe again",
                enabled = !probing,
                onClick = onRefresh,
            )
        },
    ) {
        item(key = "source") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                VulkanSource.entries.forEach { candidate ->
                    FableChip(
                        text = candidate.label,
                        selected = candidate == source,
                        onClick = { onSelectSource(candidate) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "summary") {
            VulkanSummaryCard(
                source = source,
                installed = installed,
                result = result,
                probing = probing,
                modifier = Modifier.animateItem(),
            )
        }

        if (result != null && !result.ok) {
            item(key = "error") {
                NoticeCard(
                    icon = FableIcons.Error,
                    title = if (source == VulkanSource.INSTALLED_DRIVER) "Couldn't read the driver" else "Couldn't read the system driver",
                    lines = listOfNotNull(result.error),
                    tint = FableError,
                    modifier = Modifier.animateItem(),
                )
            }
        } else if (result?.deviceError != null) {
            item(key = "device-error") {
                NoticeCard(
                    icon = FableIcons.Warning,
                    title = "No device extensions",
                    lines = listOf(result.deviceError),
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (result?.ok == true && result.totalCount > 0) {
            item(key = "filters") {
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FableTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FableChip(text = "All", selected = family == null, onClick = { family = null })
                        VulkanExtensionFamily.entries.forEach { candidate ->
                            FableChip(
                                text = candidate.label,
                                selected = family == candidate,
                                onClick = { family = if (family == candidate) null else candidate },
                            )
                        }
                    }
                }
            }

            if (device != null) {
                item(key = "device-extensions") {
                    val label = if (device.apiGeneration != "0.0") "Device · Vulkan ${device.apiGeneration}" else "Device"
                    ExtensionSection(
                        title = label,
                        extensions = deviceExtensions,
                        total = result.deviceExtensions.size,
                        expanded = expansion.isExpanded(DEVICE_KEY, default = true),
                        onToggle = { expansion.toggle(DEVICE_KEY, default = true) },
                        filtering = filtering,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "instance-extensions") {
                val label = result.instanceVersion?.let { "Instance · Vulkan ${it.split('.').take(2).joinToString(".")}" }
                    ?: "Instance"
                ExtensionSection(
                    title = label,
                    extensions = instanceExtensions,
                    total = result.instanceExtensions.size,
                    expanded = expansion.isExpanded(INSTANCE_KEY, default = device == null),
                    onToggle = { expansion.toggle(INSTANCE_KEY, default = device == null) },
                    filtering = filtering,
                    modifier = Modifier.animateItem(),
                )
            }
        } else if (result?.ok == true) {
            item(key = "empty") {
                EmptyState(
                    icon = FableIcons.Extension,
                    tone = TileTone.Blue,
                    title = "No extensions reported",
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Device, API version, driver and library of the probed implementation; a loading state while it runs. */
@Composable
private fun VulkanSummaryCard(
    source: VulkanSource,
    installed: InstalledDriver?,
    result: VulkanProbeResult?,
    probing: Boolean,
    modifier: Modifier = Modifier,
) {
    FableCard(modifier.fillMaxWidth(), glow = TileTone.Blue) {
        // Each face is a single Column: AnimatedContent lays its content out in a Box, so sibling
        // rows placed directly in the lambda would stack on top of each other.
        AnimatedContent(
            targetState = Triple(probing && result == null, result?.ok, result?.primaryDevice?.name),
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            contentAlignment = Alignment.TopStart,
            modifier = Modifier.fillMaxWidth(),
            label = "vulkanSummary",
        ) { (loading, ok, _) ->
            when {
                loading -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RowPaddingHorizontal, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = FableBlue, strokeWidth = 2.dp)
                    Text(
                        text = "Reading driver…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = Spacing.md),
                    )
                }
                result == null || ok != true -> {
                    val title = when {
                        source == VulkanSource.INSTALLED_DRIVER && installed == null -> "No driver installed"
                        source == VulkanSource.INSTALLED_DRIVER -> installed?.name ?: "RADV Xclipse ${installed?.tag}"
                        else -> "System Vulkan"
                    }
                    val subtitle = when {
                        source == VulkanSource.INSTALLED_DRIVER && installed == null -> "Switch to System to read the built-in driver"
                        source == VulkanSource.INSTALLED_DRIVER -> "Couldn't open the driver"
                        else -> "Couldn't open libvulkan.so"
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingHorizontal),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.xxs))
                    }
                }
                else -> Column(Modifier.fillMaxWidth()) {
                    val device = result.primaryDevice
                    // Hero: the device on its tile, the extension count beneath, and the Vulkan
                    // generation as an oversized figure on the right.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToneIconTile(icon = FableIcons.GpuInfo, tone = TileTone.Blue, size = 44.dp)
                        Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                            Text(
                                text = device?.name ?: "No physical device",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("${result.totalCount} extensions", style = MaterialTheme.typography.bodySmall)
                        }
                        if (device != null) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = device.apiGeneration,
                                    style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-1).sp),
                                    color = TileTone.Blue.glyph,
                                )
                                Text("VULKAN", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp))
                            }
                        }
                    }
                    CardDivider()
                    if (device != null) {
                        InfoRow(label = "Vulkan API", value = device.apiVersion)
                        CardDivider()
                        InfoRow(
                            label = "Driver",
                            value = listOfNotNull(device.driverName, device.driverInfo).joinToString(" · ")
                                .ifBlank { device.driverVersion },
                            // Driver info strings carry the full Mesa version and commit; too long for one line.
                            stacked = true,
                        )
                        CardDivider()
                        InfoRow(
                            label = "Vendor",
                            value = "${device.vendorName} · ${device.deviceType}",
                        )
                        if (device.conformanceVersion != null && device.conformanceVersion != "0.0.0.0") {
                            CardDivider()
                            InfoRow(label = "Conformance", value = device.conformanceVersion)
                        }
                        CardDivider()
                    } else if (result.instanceVersion != null) {
                        InfoRow(label = "Instance version", value = result.instanceVersion)
                        CardDivider()
                    }
                    InfoRow(
                        label = "Library",
                        value = result.library ?: "libvulkan.so",
                        stacked = true,
                    )
                }
            }
        }
    }
}

/**
 * Collapsible card of extension rows under a label that carries the count. While a search or
 * family filter is active the badge shows "matching/total".
 *
 * Only the first [EXTENSION_PAGE] rows are composed when the group opens; a "Show all" row at
 * the bottom brings in the rest. Composing all 200-odd rows of a device list at once inside the
 * expand animation is what made opening a group stutter.
 */
@Composable
private fun ExtensionSection(
    title: String,
    extensions: List<VulkanExtension>,
    total: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    filtering: Boolean,
    modifier: Modifier = Modifier,
) {
    CollapsibleSection(
        title = title,
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
        badge = {
            Text(
                text = if (filtering) "${extensions.size} of $total" else "$total",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    ) {
        if (extensions.isEmpty()) {
            Text(
                text = if (filtering) "No extensions match" else "None reported",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = RowPaddingHorizontal, vertical = Spacing.md),
            )
        } else {
            // Collapsing the group or changing the list resets back to the first page.
            var showAll by remember(expanded, extensions.size) { mutableStateOf(false) }
            val visible = if (showAll) extensions else extensions.take(EXTENSION_PAGE)
            visible.forEachIndexed { index, extension ->
                if (index > 0) CardDivider()
                ExtensionRow(extension)
            }
            if (!showAll && extensions.size > visible.size) {
                CardDivider()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    SectionAction(text = "Show all ${extensions.size}", onClick = { showAll = true })
                }
            }
        }
    }
}

/** How many extension rows a group composes before asking the user to show the rest. */
private const val EXTENSION_PAGE = 50

/** One extension: the vendor tag in a grey column, the name, and the revision. */
@Composable
private fun ExtensionRow(extension: VulkanExtension, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = extension.vendorTag,
            style = MaterialTheme.typography.bodySmall,
            color = FableTextDim,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = extension.shortName.substringAfter('_'),
            style = MaterialTheme.typography.bodyMedium.copy(color = FableText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "rev ${extension.specVersion}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun List<VulkanExtension>.filtered(query: String, family: VulkanExtensionFamily?): List<VulkanExtension> {
    val needle = query.trim().lowercase()
    return filter { extension ->
        (family == null || extension.family == family) &&
            (needle.isEmpty() || extension.name.lowercase().contains(needle))
    }
}

private const val DEVICE_KEY = "vulkan-device-extensions"
private const val INSTANCE_KEY = "vulkan-instance-extensions"
