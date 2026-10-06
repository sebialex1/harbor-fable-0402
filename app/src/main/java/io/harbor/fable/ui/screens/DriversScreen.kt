package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.formatBytes
import io.harbor.fable.data.models.DriverPackage
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import kotlinx.coroutines.launch

@Composable
fun DriversScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.assetRepository
    val downloadManager = app.downloadManager
    val drivers by repository.drivers.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val downloadSnapshot by downloadManager.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-runs on every button press; the initial value (0) refreshes from cache on entry.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger) { repository.refresh(forceRefresh = refreshTrigger > 0) }
    val expansion = rememberExpansionState()

    val deviceInfo = remember { DeviceProbe.read() }

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
                onClick = { refreshTrigger++ },
            )
        },
    ) {
        // Device info card
        item {
            GlassCard {
                InfoRow(
                    label = "Device",
                    value = deviceInfo.device,
                    icon = Icons.Outlined.Smartphone,
                )
                CardDivider()
                InfoRow(
                    label = "Vendor",
                    value = deviceInfo.vendor,
                    icon = Icons.Outlined.Business,
                )
                CardDivider()
                InfoRow(
                    label = "ABI",
                    value = deviceInfo.abi,
                    icon = Icons.Outlined.Architecture,
                )
                CardDivider()
                InfoRow(
                    label = "Graphics",
                    value = deviceInfo.gpu,
                    icon = Icons.Outlined.Memory,
                )
            }
        }

        if (isRefreshing) {
            item { LoadingCard(message = "Refreshing…") }
        }

        if (drivers.isEmpty() && !isRefreshing) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Memory,
                    title = "No drivers",
                    message = "Refresh to load the catalog",
                )
            }
        } else {
            item { SectionLabel("Available Packages") }
            if (groups.size == 1) {
                item {
                    GlassCard {
                        DriverRows(groups.first().second, downloadSnapshot.tasks) { driver ->
                            scope.launch { repository.downloadDriver(driver.id) }
                        }
                    }
                }
            } else {
                items(groups, key = { it.first }) { (groupName, groupDrivers) ->
                    val downloadedCount = groupDrivers.count { it.isDownloaded }
                    CollapsibleCard(
                        expanded = expansion.isExpanded(groupName, default = true),
                        onToggle = { expansion.toggle(groupName, default = true) },
                        header = {
                            Pill(text = groupName, color = FableAccent)
                            Spacer(Modifier.weight(1f))
                            Pill(
                                text = "$downloadedCount/${groupDrivers.size}",
                                color = FableSuccess,
                            )
                        },
                    ) {
                        DriverRows(groupDrivers, downloadSnapshot.tasks) { driver ->
                            scope.launch { repository.downloadDriver(driver.id) }
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
        DriverRow(
            driver = driver,
            task = task,
            onDownload = { onDownload(driver) },
        )
        if (index != drivers.lastIndex) {
            CardDivider()
        }
    }
}

@Composable
private fun DriverRow(
    driver: DriverPackage,
    task: DownloadTask?,
    onDownload: () -> Unit,
) {
    val title = driverTitle(driver)
    val isDownloaded = driver.isDownloaded || task?.status == DownloadStatus.COMPLETED
    val isActive = task?.status == DownloadStatus.QUEUED ||
        task?.status == DownloadStatus.DOWNLOADING ||
        task?.status == DownloadStatus.VERIFYING
    val statusText = when {
        isDownloaded -> "Downloaded"
        task?.status == DownloadStatus.QUEUED -> "Queued"
        task?.status == DownloadStatus.DOWNLOADING -> {
            val percent = (task.progressFraction * 100).toInt()
            "Downloading · $percent%"
        }
        task?.status == DownloadStatus.PAUSED -> "Paused"
        task?.status == DownloadStatus.VERIFYING -> "Verifying"
        task?.status == DownloadStatus.FAILED -> "Failed"
        task?.status == DownloadStatus.CANCELLED -> "Cancelled"
        else -> "Available"
    }
    val progress = if (task?.status == DownloadStatus.DOWNLOADING && task.totalBytes > 0) {
        task.progressFraction
    } else if (isActive) {
        null
    } else {
        null
    }

    ListRow(
        title = title,
        subtitle = "${driver.version} · ${formatBytes(driver.fileSizeBytes)} · $statusText",
        icon = Icons.Outlined.Memory,
        iconTint = if (isDownloaded) FableSuccess else FableAccent,
        showChevron = false,
        trailing = {
            if (!isDownloaded && !isActive) {
                GlassIconButton(
                    icon = Icons.Outlined.Download,
                    contentDescription = "Download ${driver.name}",
                    size = 36.dp,
                    onClick = onDownload,
                )
            } else if (isDownloaded) {
                Pill(text = "Ready", color = FableSuccess, icon = Icons.Outlined.Check)
            } else if (isActive) {
                Pill(text = statusText, color = FableAccent)
            }
        },
    )
}

private const val XCLIPSE_TITLE = "RADV Xclipse"

private fun driverTitle(driver: DriverPackage): String = when {
    driver.sourceRepo.orEmpty().contains("radv-xclipse", ignoreCase = true) -> XCLIPSE_TITLE
    else -> driver.sourceRepo ?: driver.name
}
