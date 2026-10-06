package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
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
import io.harbor.fable.ui.theme.FableWarn
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
    val expansion = rememberExpansionState()

    val deviceInfo = remember { DeviceProbe.read() }

    // RADV Xclipse first (Samsung Xclipse/RDNA2 is the primary target), then Turnip, then the rest.
    val grouped = remember(drivers) {
        val groupOrder = listOf("RADV Xclipse", "Turnip (Adreno)", "Other")
        drivers.groupBy { driver ->
            when {
                driver.sourceRepo.orEmpty().contains("radv-xclipse", ignoreCase = true) -> "RADV Xclipse"
                driver.sourceRepo.orEmpty().contains("AdrenoToolsDrivers", ignoreCase = true) -> "Turnip (Adreno)"
                else -> "Other"
            }
        }.toList().sortedBy { (name, _) -> groupOrder.indexOf(name) }.toMap()
    }

    FableScreen(
        title = "Drivers",
        subtitle = "Vulkan driver packages",
    ) {
        // Device info card
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
                    label = "ABI",
                    value = deviceInfo.abi,
                    icon = Icons.Outlined.Architecture,
                )
                CardDivider()
                InfoRow(
                    label = "Adrenotools",
                    value = if (deviceInfo.adrenoToolsSupported) "Supported" else "Not available",
                    icon = Icons.Outlined.Verified,
                    valueColor = if (deviceInfo.adrenoToolsSupported) FableSuccess else FableWarn,
                )
            }
        }

        if (isRefreshing) {
            item { LoadingCard(message = "Refreshing driver catalog…") }
        }

        if (drivers.isEmpty() && !isRefreshing) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Memory,
                    title = "No drivers available",
                    message = "Driver packages will appear here once the catalog is refreshed.",
                )
            }
        } else {
            item { SectionLabel("Available Packages") }
            items(grouped.keys.toList()) { groupName ->
                val groupDrivers = grouped[groupName].orEmpty()
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
                    groupDrivers.forEachIndexed { index, driver ->
                        val task = downloadSnapshot.tasks
                            .filter { it.assetId == driver.id }
                            .maxByOrNull { it.updatedAt }
                        DriverRow(
                            driver = driver,
                            task = task,
                            onDownload = {
                                scope.launch { repository.downloadDriver(driver.id) }
                            },
                        )
                        if (index != groupDrivers.lastIndex) {
                            CardDivider()
                        }
                    }
                }
            }
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

private fun driverTitle(driver: DriverPackage): String = when {
    driver.sourceRepo.orEmpty().contains("AdrenoToolsDrivers", ignoreCase = true) -> "Turnip (Adreno)"
    driver.sourceRepo.orEmpty().contains("radv-xclipse", ignoreCase = true) -> "RADV Xclipse"
    else -> driver.sourceRepo ?: driver.name
}
