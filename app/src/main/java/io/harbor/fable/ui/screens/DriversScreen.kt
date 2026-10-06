package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.models.DriverPackage
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import kotlinx.coroutines.launch

@Composable
fun DriversScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.assetRepository
    val drivers by repository.drivers.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val downloadSnapshot by app.downloadManager.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-runs on every button press; the initial value (0) refreshes from cache on entry.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger) { repository.refresh(forceRefresh = refreshTrigger > 0) }

    val deviceInfo = remember { DeviceProbe.read() }

    DriversContent(
        drivers = drivers,
        isRefreshing = isRefreshing,
        tasks = downloadSnapshot.tasks,
        deviceInfo = deviceInfo,
        onRefresh = { refreshTrigger++ },
        onDownload = { driver -> scope.launch { repository.downloadDriver(driver.id) } },
    )
}

@Composable
internal fun DriversContent(
    drivers: List<DriverPackage>,
    isRefreshing: Boolean,
    tasks: List<DownloadTask>,
    deviceInfo: DeviceGpuInfo,
    onRefresh: () -> Unit,
    onDownload: (DriverPackage) -> Unit,
) {
    val expansion = rememberExpansionState()

    // One source (RADV Xclipse) is listed flat; extra sources get a collapsible group each,
    // with RADV Xclipse first.
    val groups = remember(drivers) {
        drivers.groupBy { driverTitle(it) }
            .toList()
            .sortedWith(compareBy({ if (it.first == XCLIPSE_TITLE) 0 else 1 }, { it.first }))
    }

    FableScreen(
        title = "Drivers",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Refresh drivers",
                enabled = !isRefreshing,
                onClick = onRefresh,
            )
        },
    ) {
        item(key = "device") {
            GlassCard(Modifier.animateItem()) {
                InfoRow(label = "Device", value = deviceInfo.device, icon = Icons.Outlined.Smartphone)
                CardDivider()
                InfoRow(label = "Vendor", value = deviceInfo.vendor, icon = Icons.Outlined.Business)
                CardDivider()
                InfoRow(label = "ABI", value = deviceInfo.abi, icon = Icons.Outlined.Architecture)
                CardDivider()
                InfoRow(label = "Graphics", value = deviceInfo.gpu, icon = Icons.Outlined.Memory)
            }
        }

        if (isRefreshing) {
            item(key = "refreshing") { LoadingCard(message = "Refreshing…", modifier = Modifier.animateItem()) }
        }

        if (drivers.isEmpty() && !isRefreshing) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Memory,
                    title = "No drivers",
                    message = "Refresh to load the catalog",
                    modifier = Modifier.animateItem(),
                )
            }
        } else if (drivers.isNotEmpty()) {
            item(key = "packages-label") { SectionLabel("Packages", Modifier.animateItem()) }
            if (groups.size == 1) {
                item(key = "packages") {
                    GlassCard(Modifier.animateItem()) {
                        DriverRows(groups.first().second, tasks, onDownload)
                    }
                }
            } else {
                groups.forEach { (groupName, groupDrivers) ->
                    item(key = "group-$groupName") {
                        val downloadedCount = groupDrivers.count { it.isDownloaded }
                        CollapsibleSection(
                            title = groupName,
                            expanded = expansion.isExpanded(groupName, default = true),
                            onToggle = { expansion.toggle(groupName, default = true) },
                            modifier = Modifier.animateItem(),
                            badge = {
                                Pill(
                                    text = "$downloadedCount/${groupDrivers.size}",
                                    color = if (downloadedCount > 0) FableSuccess else FableTextDim,
                                )
                            },
                        ) {
                            DriverRows(groupDrivers, tasks, onDownload)
                        }
                    }
                }
            }
        }
    }
}

/** Driver rows separated by dividers, each showing the latest download task for its package. */
@Composable
private fun ColumnScope.DriverRows(
    drivers: List<DriverPackage>,
    tasks: List<DownloadTask>,
    onDownload: (DriverPackage) -> Unit,
) {
    drivers.forEachIndexed { index, driver ->
        val task = tasks
            .filter { it.assetId == driver.id }
            .maxByOrNull { it.updatedAt }
        DownloadRow(
            title = driverTitle(driver),
            version = driver.version,
            sizeBytes = driver.fileSizeBytes,
            icon = Icons.Outlined.Memory,
            isDownloaded = driver.isDownloaded,
            task = task,
            onDownload = { onDownload(driver) },
        )
        if (index != drivers.lastIndex) {
            CardDivider()
        }
    }
}

private const val XCLIPSE_TITLE = "RADV Xclipse"

private fun driverTitle(driver: DriverPackage): String = when {
    driver.sourceRepo.orEmpty().contains("radv-xclipse", ignoreCase = true) -> XCLIPSE_TITLE
    else -> driver.sourceRepo ?: driver.name
}
