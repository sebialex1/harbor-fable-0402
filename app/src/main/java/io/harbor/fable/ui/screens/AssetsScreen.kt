package io.harbor.fable.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.SetupState
import io.harbor.fable.data.formatBytes
import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetType
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AssetsScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.assetRepository
    val assets by repository.assets.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val downloadSnapshot by app.downloadManager.snapshot.collectAsStateWithLifecycle()
    val setupManager = app.setupManager
    val setup by setupManager.state.collectAsStateWithLifecycle()
    val installing by setupManager.installing.collectAsStateWithLifecycle()
    val fableUi = LocalFableUi.current
    val scope = rememberCoroutineScope()

    // Re-runs on every button press; the initial value (0) refreshes from cache on entry.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger) { repository.refresh(forceRefresh = refreshTrigger > 0) }

    AssetsContent(
        assets = assets,
        isRefreshing = isRefreshing,
        tasks = downloadSnapshot.tasks,
        setup = setup,
        installing = installing,
        onRefresh = { refreshTrigger++ },
        // Runs on the app-level scope so leaving the screen does not cancel the catalog refresh.
        onDownloadRecommended = {
            fableUi.scope.launch { fableUi.showMessage(setupManager.installRecommended().message, long = true) }
        },
        onDownload = { asset -> scope.launch { repository.download(asset.id) } },
    )
}

@Composable
internal fun AssetsContent(
    assets: List<AssetEntry>,
    isRefreshing: Boolean,
    tasks: List<DownloadTask>,
    setup: SetupState,
    installing: Boolean,
    onRefresh: () -> Unit,
    onDownloadRecommended: () -> Unit,
    onDownload: (AssetEntry) -> Unit,
) {
    val expansion = rememberExpansionState()
    val appear = rememberLiquidAppear()
    val grouped = remember(assets) { assets.groupBy { it.type } }
    val orderedTypes = remember {
        listOf(
            AssetType.WINE,
            AssetType.BOX64,
            AssetType.FEX,
            AssetType.DXVK,
            AssetType.VULKAN_DRIVER,
            AssetType.PROTON,
            AssetType.RUNTIME,
            AssetType.OTHER,
        )
    }

    // The banner stays in the list just long enough to slide away once everything is downloaded.
    val bannerVisible = setup.needsSetup
    var bannerInList by remember { mutableStateOf(bannerVisible) }
    LaunchedEffect(bannerVisible) {
        if (bannerVisible) {
            bannerInList = true
        } else {
            delay(Motion.Slow.toLong())
            bannerInList = false
        }
    }

    FableScreen(
        title = "Assets",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.CloudDownload,
                contentDescription = "Download recommended",
                enabled = setup.needsSetup && !setup.isDownloading && !installing,
                onClick = onDownloadRecommended,
            )
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Refresh assets",
                enabled = !isRefreshing,
                onClick = onRefresh,
            )
        },
    ) {
        if (bannerInList || bannerVisible) {
            item(key = "setup-banner") {
                AnimatedVisibility(
                    visible = bannerVisible,
                    modifier = Modifier.liquidAppear(appear, 0),
                    enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
                    exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit(Motion.Standard)) { -it / 2 } +
                        shrinkVertically(Motion.exit(Motion.Standard)),
                ) {
                    SetupBanner(state = setup, installing = installing, onDownloadAll = onDownloadRecommended)
                }
            }
        }

        if (isRefreshing) {
            item(key = "refreshing") {
                LoadingCard(message = "Refreshing…", modifier = Modifier.animateItem().liquidAppear(appear, 0))
            }
        }

        if (assets.isEmpty() && !isRefreshing) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Download,
                    title = "No assets",
                    message = "Refresh to load the catalog",
                    modifier = Modifier.animateItem().liquidAppear(appear, 1),
                )
            }
        } else {
            orderedTypes.forEachIndexed { typeIndex, type ->
                val typeAssets = grouped[type].orEmpty()
                if (typeAssets.isNotEmpty()) {
                    val typeKey = type.name
                    val downloadedCount = typeAssets.count { it.isDownloaded }

                    item(key = "section-$typeKey") {
                        val expanded = expansion.isExpanded(typeKey, default = true)
                        CollapsibleSection(
                            title = typeDisplayName(type),
                            expanded = expanded,
                            onToggle = { expansion.toggle(typeKey, default = true) },
                            modifier = Modifier.animateItem().liquidAppear(appear, typeIndex + 1),
                            badge = {
                                Pill(
                                    text = "$downloadedCount/${typeAssets.size}",
                                    color = if (downloadedCount > 0) FableSuccess else FableTextDim,
                                )
                            },
                        ) {
                            typeAssets.forEachIndexed { index, asset ->
                                val task = tasks
                                    .filter { it.assetId == asset.id }
                                    .maxByOrNull { it.updatedAt }
                                DownloadRow(
                                    title = asset.name,
                                    version = asset.version,
                                    sizeBytes = asset.fileSizeBytes,
                                    icon = assetTypeIcon(asset.type),
                                    isDownloaded = asset.isDownloaded,
                                    task = task,
                                    onDownload = { onDownload(asset) },
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

/** First-run card: what is missing, one button to download it, and live progress. */
@Composable
internal fun SetupBanner(
    state: SetupState,
    installing: Boolean,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = installing || state.isDownloading
    val title = when {
        busy -> "Downloading"
        state.nothingInstalled -> "Get Started"
        else -> "Finish Setup"
    }
    val detail = when {
        state.isDownloading -> "${(state.progress * 100).toInt()}%"
        installing -> "Preparing…"
        else -> state.pending.joinToString(", ") { it.kind.label } +
            if (state.pendingBytes > 0) " (${formatBytes(state.pendingBytes)})" else ""
    }
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(RowPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = Icons.Outlined.RocketLaunch, tint = FableAccent)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            AnimatedContent(
                targetState = busy,
                transitionSpec = { fadeIn(Motion.enter(Motion.Quick)) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
                label = "setupAction",
            ) { working ->
                if (working) {
                    ThinProgressBar(progress = if (state.isDownloading) state.progress else null)
                } else {
                    GlassButton(
                        text = "Download All",
                        icon = Icons.Outlined.Download,
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDownloadAll,
                    )
                }
            }
            if (state.unavailable.isNotEmpty() && !busy) {
                Text(
                    text = "No build available for ${state.unavailable.joinToString(", ") { it.kind.label }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FableTextDim,
                )
            }
        }
    }
}

private fun typeDisplayName(type: AssetType): String = when (type) {
    AssetType.WINE -> "Wine Builds"
    AssetType.BOX64 -> "Box64"
    AssetType.FEX -> "FEX"
    AssetType.DXVK -> "DXVK"
    AssetType.VULKAN_DRIVER -> "Vulkan Drivers"
    AssetType.PROTON -> "Proton"
    AssetType.RUNTIME -> "Runtimes"
    AssetType.OTHER -> "Other"
}

private fun assetTypeIcon(type: AssetType) = when (type) {
    AssetType.WINE -> Icons.Outlined.WineBar
    AssetType.BOX64 -> Icons.Outlined.Terminal
    AssetType.FEX -> Icons.Outlined.DeveloperBoard
    AssetType.DXVK -> Icons.Outlined.Layers
    AssetType.VULKAN_DRIVER -> Icons.Outlined.Memory
    AssetType.PROTON -> Icons.Outlined.RocketLaunch
    AssetType.RUNTIME -> Icons.Outlined.SettingsInputComponent
    AssetType.OTHER -> Icons.Outlined.Extension
}
