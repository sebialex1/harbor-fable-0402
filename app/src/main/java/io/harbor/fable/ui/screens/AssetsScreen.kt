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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateFloatAsState
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
    // Build the user picked per version ("WINE/11.19" -> asset id); unpicked versions use the default.
    val selectedVariants = rememberSaveable(saver = SelectionSaver) { mutableStateMapOf() }
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
            // "Download recommended" lives in the setup row below, which only shows when needed.
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
                    val versions = groupAssetVersions(type, typeAssets)

                    item(key = "section-$typeKey") {
                        val expanded = expansion.isExpanded(typeKey, default = true)
                        CollapsibleSection(
                            title = typeDisplayName(type),
                            expanded = expanded,
                            onToggle = { expansion.toggle(typeKey, default = true) },
                            modifier = Modifier.animateItem().liquidAppear(appear, typeIndex + 1),
                        ) {
                            versions.forEachIndexed { index, group ->
                                AssetVersionRow(
                                    group = group,
                                    tasks = tasks,
                                    selectedId = selectedVariants[group.key],
                                    onSelect = { selectedVariants[group.key] = it.id },
                                    onDownload = onDownload,
                                )
                                if (index != versions.lastIndex) {
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

/**
 * One version of a component. The row's download control acts on the selected build. When the
 * version has several builds (Wine: staging, tkg, wow64) tapping the row reveals them: one chip
 * per flavor (Standard, Staging, Staging TkG) and a WoW64 chip that toggles the mode. The plain
 * amd64 build is selected unless another one is already downloaded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetVersionRow(
    group: AssetVersionGroup,
    tasks: List<DownloadTask>,
    selectedId: String?,
    onSelect: (AssetEntry) -> Unit,
    onDownload: (AssetEntry) -> Unit,
) {
    val selected = group.variants.firstOrNull { it.asset.id == selectedId } ?: group.defaultVariant
    val asset = selected.asset
    val task = tasks.filter { it.assetId == asset.id }.maxByOrNull { it.updatedAt }
    val choosable = group.variants.size > 1
    var open by rememberSaveable(group.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        DownloadRow(
            title = "${typeShortName(group.type)} ${group.version}",
            version = if (choosable) selected.label else "",
            sizeBytes = asset.fileSizeBytes,
            isDownloaded = asset.isDownloaded,
            task = task,
            onDownload = { onDownload(asset) },
            onClick = if (choosable) ({ open = !open }) else null,
            titleBadge = if (choosable) {
                {
                    val rotation by animateFloatAsState(if (open) 180f else 0f, Motion.inPlace(), label = "buildsChevron")
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = if (open) "Hide builds" else "Show builds",
                        tint = FableTextDim,
                        modifier = Modifier.size(16.dp).rotate(rotation),
                    )
                }
            } else {
                null
            },
        )
        AnimatedVisibility(
            visible = choosable && open,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit()),
        ) {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = RowPaddingHorizontal, end = RowPaddingHorizontal, bottom = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                group.flavors.forEach { flavor ->
                    GlassChip(
                        text = flavor,
                        selected = selected.flavor == flavor,
                        onClick = { onSelect(group.pick(flavor, selected.wow64).asset) },
                    )
                }
                if (group.hasWow64Choice) {
                    GlassChip(
                        text = "WoW64",
                        selected = selected.wow64,
                        onClick = { onSelect(group.pick(selected.flavor, !selected.wow64).asset) },
                    )
                }
            }
        }
    }
}

/** First-run row: what is missing, one compact button to get it, and live progress. */
@Composable
internal fun SetupBanner(
    state: SetupState,
    installing: Boolean,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = installing || state.isDownloading
    val detail = when {
        state.isDownloading -> "${(state.progress * 100).toInt()}%"
        installing -> "Preparing…"
        else -> state.pending.joinToString(", ") { it.kind.label } +
            if (state.pendingBytes > 0) " · ${formatBytes(state.pendingBytes)}" else ""
    }
    GlassCard(modifier = modifier.fillMaxWidth()) {
        ListRow(
            title = if (busy) "Downloading" else "Recommended",
            subtitle = detail,
            subtitleMaxLines = 2,
            showChevron = false,
            trailing = {
                if (!busy) {
                    GlassButton(text = "Get", primary = true, compact = true, onClick = onDownloadAll)
                }
            },
        )
        if (busy) {
            ThinProgressBar(
                progress = if (state.isDownloading) state.progress else null,
                modifier = Modifier.padding(horizontal = RowPaddingHorizontal).padding(bottom = Spacing.sm),
            )
        }
    }
}

/** Prefix for a version row ("Wine 11.19", "DXVK 2.7"). */
private fun typeShortName(type: AssetType): String = when (type) {
    AssetType.WINE -> "Wine"
    AssetType.VULKAN_DRIVER -> "Driver"
    AssetType.RUNTIME -> "Runtime"
    AssetType.OTHER -> "Version"
    else -> typeDisplayName(type)
}

/** Saves the per-version build selection as "key=id" strings. */
private val SelectionSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, String>(
    save = { map -> map.map { (key, id) -> "$key=$id" } },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.forEach { entry -> put(entry.substringBefore('='), entry.substringAfter('=')) }
        }
    },
)

private fun typeDisplayName(type: AssetType): String = when (type) {
    AssetType.WINE -> "Wine"
    AssetType.BOX64 -> "Box64"
    AssetType.FEX -> "FEX"
    AssetType.DXVK -> "DXVK"
    AssetType.VULKAN_DRIVER -> "Vulkan Drivers"
    AssetType.PROTON -> "Proton"
    AssetType.RUNTIME -> "Runtimes"
    AssetType.OTHER -> "Other"
}
