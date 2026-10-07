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
import io.harbor.fable.ui.theme.TileTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.harbor.fable.ui.icons.FableIcons

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
    val appear = rememberEntrance()
    val grouped = remember(assets) { assets.groupBy { it.type } }
    // Build the user picked per version ("WINE/11.19" -> asset id); unpicked versions use the default.
    val selectedVariants = rememberSaveable(saver = SelectionSaver) { mutableStateMapOf() }
    val orderedTypes = remember {
        listOf(
            AssetType.WINE,
            AssetType.BOX64,
            AssetType.FEX,
            AssetType.DXVK,
            AssetType.VKD3D,
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
            FableIconButton(
                icon = FableIcons.Refresh,
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
                    modifier = Modifier.entrance(appear, 0),
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
                LoadingCard(message = "Refreshing…", modifier = Modifier.animateItem().entrance(appear, 0))
            }
        }

        if (assets.isEmpty() && !isRefreshing) {
            item(key = "empty") {
                EmptyState(
                    icon = FableIcons.Download,
                    title = "No assets",
                    tone = TileTone.Blue,
                    modifier = Modifier.animateItem().entrance(appear, 1),
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
                            modifier = Modifier.animateItem().entrance(appear, typeIndex + 1),
                            leading = { MonogramTile(text = typeMonogram(type), tone = typeTone(type)) },
                            badge = { Pill(text = "${versions.count { v -> v.variants.any { it.asset.isDownloaded } }}/${versions.size}") },
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
                        imageVector = FableIcons.ExpandMore,
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
                    .padding(start = RowPaddingHorizontal, end = RowPaddingHorizontal, top = Spacing.sm, bottom = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                group.flavors.forEach { flavor ->
                    FableChip(
                        text = flavor,
                        selected = selected.flavor == flavor,
                        onClick = { onSelect(group.pick(flavor, selected.wow64).asset) },
                    )
                }
                if (group.hasWow64Choice) {
                    FableChip(
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
    // The Get button morphs into the download's own progress pill, like every download row.
    FableCard(modifier = modifier.fillMaxWidth(), glow = TileTone.Blue) {
        ListRow(
            title = if (busy) "Downloading" else "Recommended",
            subtitle = detail,
            subtitleMaxLines = 2,
            showChevron = false,
            leading = { ToneIconTile(icon = FableIcons.Download, tone = TileTone.Blue) },
            trailing = {
                AnimatedContent(
                    targetState = busy,
                    transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
                    label = "setupBannerAction",
                ) { working ->
                    if (!working) {
                        FableButton(text = "Get", primary = true, compact = true, onClick = onDownloadAll)
                    } else {
                        MorphPill(
                            phase = if (state.isDownloading) MorphPhase.Active else MorphPhase.Waiting,
                            progress = if (state.isDownloading) state.progress else null,
                            label = if (state.isDownloading) "${(state.progress * 100).toInt()}%" else "Preparing",
                        )
                    }
                }
            },
        )
    }
}

/** Two-letter mark for an asset kind's section tile. */
private fun typeMonogram(type: AssetType): String = when (type) {
    AssetType.WINE -> "Wi"
    AssetType.BOX64 -> "64"
    AssetType.FEX -> "Fx"
    AssetType.DXVK -> "DX"
    AssetType.VKD3D -> "12"
    AssetType.VULKAN_DRIVER -> "Vk"
    AssetType.PROTON -> "Pr"
    AssetType.RUNTIME -> "Rt"
    AssetType.OTHER -> "··"
}

/** Each asset kind's tone: Wine and Proton share one, the D3D layers sit in blues. */
private fun typeTone(type: AssetType): TileTone = when (type) {
    AssetType.WINE, AssetType.PROTON -> TileTone.Violet
    AssetType.BOX64, AssetType.FEX -> TileTone.Amber
    AssetType.DXVK -> TileTone.Blue
    AssetType.VKD3D -> TileTone.Indigo
    AssetType.VULKAN_DRIVER -> TileTone.Teal
    AssetType.RUNTIME, AssetType.OTHER -> TileTone.Graphite
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
    AssetType.VKD3D -> "VKD3D-Proton"
    AssetType.VULKAN_DRIVER -> "Vulkan Drivers"
    AssetType.PROTON -> "Proton"
    AssetType.RUNTIME -> "Runtimes"
    AssetType.OTHER -> "Other"
}
