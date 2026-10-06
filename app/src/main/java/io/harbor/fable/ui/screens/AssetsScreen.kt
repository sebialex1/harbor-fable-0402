package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
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
import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetType
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import kotlinx.coroutines.launch

@Composable
fun AssetsScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.assetRepository
    val downloadManager = app.downloadManager
    val assets by repository.assets.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val downloadSnapshot by downloadManager.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-runs on every button press; the initial value (0) refreshes from cache on entry.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger) { repository.refresh(forceRefresh = refreshTrigger > 0) }
    val expansion = rememberExpansionState()

    val grouped = remember(assets) {
        assets.groupBy { it.type }
    }
    val orderedTypes = remember {
        listOf(
            AssetType.WINE,
            AssetType.DXVK,
            AssetType.VULKAN_DRIVER,
            AssetType.PROTON,
            AssetType.RUNTIME,
            AssetType.OTHER,
        )
    }

    FableScreen(
        title = "Assets",
        subtitle = "Wine, DXVK, Proton and more",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Refresh assets",
                enabled = !isRefreshing,
                onClick = { refreshTrigger++ },
            )
        },
    ) {
        if (isRefreshing) {
            item { LoadingCard(message = "Refreshing asset catalog…") }
        }

        if (assets.isEmpty() && !isRefreshing) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Download,
                    title = "No assets found",
                    message = "Assets will appear here once the catalog is refreshed.",
                )
            }
        } else {
            orderedTypes.forEach { type ->
                val typeAssets = grouped[type].orEmpty()
                if (typeAssets.isNotEmpty()) {
                    val typeKey = type.name
                    val downloadedCount = typeAssets.count { it.isDownloaded }

                    item { SectionLabel(typeDisplayName(type)) }
                    item {
                        CollapsibleCard(
                            expanded = expansion.isExpanded(typeKey, default = true),
                            onToggle = { expansion.toggle(typeKey, default = true) },
                            header = {
                                Pill(
                                    text = "$downloadedCount/${typeAssets.size}",
                                    color = if (downloadedCount > 0) FableSuccess else FableTextDim,
                                )
                                Spacer(Modifier.weight(1f))
                                if (typeAssets.size > 3) {
                                    SectionAction(
                                        text = if (expansion.isExpanded(typeKey, default = true)) "Collapse" else "Expand",
                                        onClick = { expansion.toggle(typeKey, default = true) },
                                    )
                                }
                            },
                        ) {
                            typeAssets.forEachIndexed { index, asset ->
                                val task = downloadSnapshot.tasks
                                    .filter { it.assetId == asset.id }
                                    .maxByOrNull { it.updatedAt }
                                AssetRow(
                                    asset = asset,
                                    task = task,
                                    onDownload = {
                                        scope.launch { repository.download(asset.id) }
                                    },
                                )
                                if (index != typeAssets.lastIndex) {
                                    CardDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetRow(
    asset: AssetEntry,
    task: DownloadTask?,
    onDownload: () -> Unit,
) {
    val isDownloaded = asset.isDownloaded || task?.status == DownloadStatus.COMPLETED
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

    ListRow(
        title = asset.name,
        subtitle = "${asset.version} · ${formatBytes(asset.fileSizeBytes)} · $statusText",
        icon = assetTypeIcon(asset.type),
        iconTint = if (isDownloaded) FableSuccess else FableAccent,
        showChevron = false,
        trailing = {
            if (!isDownloaded && !isActive) {
                GlassIconButton(
                    icon = Icons.Outlined.Download,
                    contentDescription = "Download ${asset.name}",
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

private fun typeDisplayName(type: AssetType): String = when (type) {
    AssetType.WINE -> "Wine Builds"
    AssetType.DXVK -> "DXVK"
    AssetType.VULKAN_DRIVER -> "Vulkan Drivers"
    AssetType.PROTON -> "Proton"
    AssetType.RUNTIME -> "Runtimes"
    AssetType.OTHER -> "Other"
}

private fun assetTypeIcon(type: AssetType) = when (type) {
    AssetType.WINE -> Icons.Outlined.WineBar
    AssetType.DXVK -> Icons.Outlined.Layers
    AssetType.VULKAN_DRIVER -> Icons.Outlined.Memory
    AssetType.PROTON -> Icons.Outlined.RocketLaunch
    AssetType.RUNTIME -> Icons.Outlined.SettingsInputComponent
    AssetType.OTHER -> Icons.Outlined.Extension
}
