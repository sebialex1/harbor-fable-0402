package io.harbor.fable.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.*
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
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing

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
        subtitle = when (source) {
            VulkanSource.INSTALLED_DRIVER -> installed?.let { "RADV Xclipse ${it.tag}" } ?: "No driver installed"
            VulkanSource.SYSTEM -> "System driver"
        },
        onBack = onBack,
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
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
                    GlassChip(
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
                    icon = Icons.Outlined.ErrorOutline,
                    title = if (source == VulkanSource.INSTALLED_DRIVER) "Couldn't read the installed driver" else "Couldn't read the system driver",
                    lines = listOfNotNull(result.error),
                    tint = FableError,
                    modifier = Modifier.animateItem(),
                )
            }
        } else if (result?.deviceError != null) {
            item(key = "device-error") {
                NoticeCard(
                    icon = Icons.Outlined.WarningAmber,
                    title = "No device extensions",
                    lines = listOf(result.deviceError),
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (result?.ok == true && result.totalCount > 0) {
            item(key = "filters") {
                Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GlassTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search extensions",
                        placeholder = "swapchain, dynamic_state, …",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        GlassChip(text = "All", selected = family == null, onClick = { family = null })
                        VulkanExtensionFamily.entries.forEach { candidate ->
                            GlassChip(
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
                    val label = if (device.apiGeneration != "0.0") "Vulkan ${device.apiGeneration} Device Extensions" else "Device Extensions"
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
                val label = result.instanceVersion?.let { "Vulkan ${it.split('.').take(2).joinToString(".")} Instance Extensions" }
                    ?: "Instance Extensions"
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
                    icon = Icons.Outlined.Extension,
                    title = "No extensions reported",
                    message = "The driver advertised no extensions",
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
    GlassCard(modifier.fillMaxWidth()) {
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
                    IconTile(icon = Icons.Outlined.Extension, tint = FableAccent)
                    Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                        Text(
                            text = if (source == VulkanSource.INSTALLED_DRIVER) "Loading the installed driver…" else "Opening the system Vulkan…",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Enumerating devices",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                result == null || ok != true -> {
                    val title = when {
                        source == VulkanSource.INSTALLED_DRIVER && installed == null -> "No driver installed"
                        source == VulkanSource.INSTALLED_DRIVER -> installed?.name ?: "RADV Xclipse ${installed?.tag}"
                        else -> "System Vulkan"
                    }
                    val subtitle = when {
                        source == VulkanSource.INSTALLED_DRIVER && installed == null -> "Install a driver or switch to the system driver"
                        source == VulkanSource.INSTALLED_DRIVER -> "The driver could not be opened"
                        else -> "libvulkan.so could not be opened"
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
                    val sourceLine = when (source) {
                        VulkanSource.INSTALLED_DRIVER -> "Installed driver · adrenotools loader"
                        VulkanSource.SYSTEM -> "System driver · libvulkan.so"
                    }
                    DriverSummaryRow(
                        title = device?.name ?: "No physical device",
                        lines = listOf(sourceLine),
                        icon = Icons.Outlined.Extension,
                        iconTint = if (device != null) FableSuccess else FableWarn,
                        titleMaxLines = 1,
                        trailing = {
                            Pill(
                                text = "${result.totalCount} ext",
                                color = if (device != null) FableSuccess else FableWarn,
                                icon = Icons.Outlined.Extension,
                            )
                        },
                    )
                    CardDivider()
                    if (device != null) {
                        InfoRow(label = "Vulkan API", value = device.apiVersion, icon = Icons.Outlined.Verified)
                        CardDivider()
                        InfoRow(
                            label = "Driver",
                            value = listOfNotNull(device.driverName, device.driverInfo).joinToString(" · ")
                                .ifBlank { device.driverVersion },
                            icon = Icons.Outlined.Memory,
                            // Driver info strings carry the full Mesa version and commit; too long for one line.
                            stacked = true,
                        )
                        CardDivider()
                        InfoRow(
                            label = "Vendor",
                            value = "${device.vendorName} · ${device.deviceType}",
                            icon = Icons.Outlined.Business,
                        )
                        if (device.conformanceVersion != null && device.conformanceVersion != "0.0.0.0") {
                            CardDivider()
                            InfoRow(label = "Conformance", value = device.conformanceVersion, icon = Icons.AutoMirrored.Outlined.FactCheck)
                        }
                        CardDivider()
                    } else if (result.instanceVersion != null) {
                        InfoRow(label = "Instance version", value = result.instanceVersion, icon = Icons.Outlined.Verified)
                        CardDivider()
                    }
                    InfoRow(
                        label = "Library",
                        value = result.library ?: "libvulkan.so",
                        icon = Icons.Outlined.Code,
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
            Pill(
                text = if (filtering) "${extensions.size}/$total" else "$total",
                color = if (extensions.isNotEmpty()) FableAccent else FableTextDim,
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

/** One extension: the name with its vendor tag coloured, and the revision as a pill. */
@Composable
private fun ExtensionRow(extension: VulkanExtension, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            text = extension.vendorTag,
            color = when (extension.family) {
                VulkanExtensionFamily.KHR -> FableAccent
                VulkanExtensionFamily.EXT -> FableSuccess
                VulkanExtensionFamily.VENDOR -> FableWarn
            },
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = extension.shortName.substringAfter('_'),
            style = MaterialTheme.typography.bodyLarge,
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
