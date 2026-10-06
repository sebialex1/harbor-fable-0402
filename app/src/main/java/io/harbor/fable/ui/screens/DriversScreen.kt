package io.harbor.fable.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.DriverInstallResult
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The RADV Xclipse driver: device, the one active driver (with the latest release as the install
 * target or the update prompt), the Vulkan extensions link, and the older builds. One driver is
 * active at a time; releases are downloaded here and installed as the active one.
 * [onOpenVulkanExtensions] pushes the screen that lists what the driver reports through Vulkan.
 */
@Composable
fun DriversScreen(onOpenVulkanExtensions: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.driverRepository
    val releases by repository.releases.collectAsStateWithLifecycle()
    val installed by repository.installed.collectAsStateWithLifecycle()
    val installing by repository.installing.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val refreshError by repository.refreshError.collectAsStateWithLifecycle()
    val stale by repository.stale.collectAsStateWithLifecycle()
    val downloadSnapshot by app.downloadManager.snapshot.collectAsStateWithLifecycle()
    val fableUi = LocalFableUi.current
    val scope = rememberCoroutineScope()

    // Re-runs on every button press; the initial value (0) refreshes from cache on entry.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger) { repository.refresh(forceRefresh = refreshTrigger > 0) }

    val deviceInfo = remember { DeviceProbe.read() }

    // Replacing an active driver and uninstalling both ask first; installing onto nothing does not.
    var pendingReplace by remember { mutableStateOf<RadvRelease?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }

    // Installs run on the app scope so leaving the tab mid-extraction does not cancel them. A
    // release that is not on disk yet is downloaded first and installed when the bytes land.
    fun install(release: RadvRelease) {
        fableUi.scope.launch {
            if (release.isDownloaded) {
                val result = repository.install(release.tag)
                fableUi.showMessage(result.message, long = result is DriverInstallResult.Failed)
            } else {
                val task = repository.downloadAndInstall(release.tag)
                fableUi.showMessage(
                    if (task != null) "Downloading ${release.tag}" else "Installing ${release.tag}",
                )
            }
        }
    }

    DriversContent(
        releases = releases,
        installed = installed,
        installing = installing,
        isRefreshing = isRefreshing,
        refreshError = refreshError,
        stale = stale,
        tasks = downloadSnapshot.tasks,
        deviceInfo = deviceInfo,
        onRefresh = { refreshTrigger++ },
        onDownload = { release -> scope.launch { repository.download(release.tag) } },
        onInstall = { release ->
            val current = installed
            if (current != null && current.tag != release.tag) pendingReplace = release else install(release)
        },
        onUninstall = { confirmUninstall = true },
        onOpenVulkanExtensions = onOpenVulkanExtensions,
    )

    pendingReplace?.let { release ->
        ConfirmDialog(
            title = "Replace active driver?",
            message = "${installed?.tag ?: "The current driver"} will be removed.",
            confirmLabel = "Replace",
            onConfirm = {
                pendingReplace = null
                install(release)
            },
            onDismiss = { pendingReplace = null },
        )
    }

    if (confirmUninstall) {
        ConfirmDialog(
            title = "Uninstall driver?",
            message = "Containers will use the system driver.",
            confirmLabel = "Uninstall",
            destructive = true,
            onConfirm = {
                confirmUninstall = false
                fableUi.scope.launch {
                    val removed = repository.uninstall()
                    fableUi.showMessage(if (removed != null) "Removed ${removed.tag}" else "No driver installed")
                }
            },
            onDismiss = { confirmUninstall = false },
        )
    }
}

@Composable
internal fun DriversContent(
    releases: List<RadvRelease>,
    installed: InstalledDriver?,
    installing: String?,
    isRefreshing: Boolean,
    refreshError: String?,
    stale: Boolean,
    tasks: List<DownloadTask>,
    deviceInfo: DeviceGpuInfo,
    onRefresh: () -> Unit,
    onDownload: (RadvRelease) -> Unit,
    onInstall: (RadvRelease) -> Unit,
    onUninstall: () -> Unit,
    onOpenVulkanExtensions: () -> Unit = {},
) {
    val expansion = rememberExpansionState()
    val appear = rememberEntrance()
    val latest = remember(releases) { releases.firstOrNull { it.channel == ReleaseChannel.LATEST } }
    val updateAvailable = latest != null && installed != null && installed.tag != latest.tag &&
        RadvRelease.versionComparator.compare(latest.versionParts, RadvRelease.parseVersion(installed.tag)) > 0
    // The latest release is shown once: as the active driver, as the install target of the empty
    // state, or as the update prompt. Only when none of those apply does it join the list below.
    val latestCovered = latest == null || installed == null || installed.tag == latest.tag || updateAvailable
    val older = remember(releases, latestCovered) {
        if (latestCovered) releases.filter { it.channel != ReleaseChannel.LATEST } else releases
    }

    FableScreen(
        title = "Drivers",
        actions = {
            FableIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Refresh releases",
                enabled = !isRefreshing,
                onClick = onRefresh,
            )
        },
    ) {
        item(key = "active-label") { SectionLabel("Active Driver", Modifier.animateItem().entrance(appear, 0)) }
        item(key = "active") {
            // The driver, its actions and its Vulkan extensions share one section.
            ActiveDriverCard(
                installed = installed,
                installing = installing,
                latest = latest,
                latestTask = latest?.let { tasks.taskFor(it) },
                updateAvailable = if (updateAvailable) latest else null,
                onDownloadLatest = { latest?.let(onDownload) },
                onInstallLatest = { latest?.let(onInstall) },
                onUninstall = onUninstall,
                modifier = Modifier.animateItem().entrance(appear, 0),
                footer = {
                    CardDivider()
                    ListRow(
                        title = "Vulkan Extensions",
                        trailing = {
                            Text(
                                text = deviceInfo.gpu.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) } ?: "System",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        },
                        onClick = onOpenVulkanExtensions,
                    )
                },
            )
        }

        if (refreshError != null && releases.isEmpty()) {
            item(key = "error") {
                NoticeCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load releases",
                    lines = listOf(refreshError),
                    modifier = Modifier.animateItem().entrance(appear, 3),
                )
            }
        } else if (stale) {
            item(key = "stale") {
                NoticeCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Offline",
                    lines = emptyList(),
                    modifier = Modifier.animateItem().entrance(appear, 3),
                )
            }
        }

        if (isRefreshing && releases.isEmpty()) {
            item(key = "refreshing") {
                LoadingCard(message = "Loading releases…", modifier = Modifier.animateItem().entrance(appear, 3))
            }
        }

        if (older.isNotEmpty()) {
            item(key = "older") {
                val downloadedCount = older.count { it.isDownloaded }
                CollapsibleSection(
                    title = if (latestCovered) "Previous Versions" else "All Versions",
                    expanded = expansion.isExpanded(OLDER_KEY, default = false),
                    onToggle = { expansion.toggle(OLDER_KEY, default = false) },
                    modifier = Modifier.animateItem().entrance(appear, 2).padding(top = Spacing.lg),
                    badge = {
                        Text(
                            text = if (downloadedCount > 0) "$downloadedCount of ${older.size}" else "${older.size}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ) {
                    older.forEachIndexed { index, release ->
                        if (index > 0) CardDivider()
                        DriverReleaseRow(
                            release = release,
                            task = tasks.taskFor(release),
                            installed = installed?.tag == release.tag,
                            installing = installing == release.tag,
                            onDownload = { onDownload(release) },
                            onInstall = { onInstall(release) },
                        )
                    }
                }
            }
        }

        if (releases.isEmpty() && !isRefreshing && refreshError == null) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Memory,
                    title = "No releases",
                    
                    modifier = Modifier.animateItem().entrance(appear, 3),
                )
            }
        }
    }
}

/**
 * The single active driver with its actions, or the empty state offering the [latest] release as
 * the install target. While [installing] is set the card shows the extraction in progress. When
 * [updateAvailable] is set the installed face carries a compact update prompt (with the download
 * progress of [latestTask] once it starts) instead of the screen listing the release twice. The
 * three states cross-fade and the card resizes between them instead of snapping.
 */
@Composable
internal fun ActiveDriverCard(
    installed: InstalledDriver?,
    installing: String?,
    latest: RadvRelease?,
    latestTask: DownloadTask?,
    updateAvailable: RadvRelease?,
    onDownloadLatest: () -> Unit,
    onInstallLatest: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val state = when {
        installing != null -> ActiveDriverState.Installing
        installed == null -> ActiveDriverState.Empty
        else -> ActiveDriverState.Installed
    }
    // Outgoing faces keep their last content while they fade out: the driver that was just
    // removed, or the tag that was just installed, instead of collapsing to blanks mid-transition.
    val lastInstalled = remember { arrayOfNulls<InstalledDriver>(1) }
    val lastInstalling = remember { arrayOfNulls<String>(1) }
    if (installed != null) lastInstalled[0] = installed
    if (installing != null) lastInstalling[0] = installing
    FableCard(modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 10 }) togetherWith
                    fadeOut(Motion.exit())
            },
            modifier = Modifier.animateContentSize(Motion.settle()),
            label = "activeDriver",
        ) { target ->
            when (target) {
                ActiveDriverState.Installing -> Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(icon = Icons.Outlined.Memory)
                        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                            Text("Installing ${installing ?: lastInstalling[0].orEmpty()}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (installed != null) "Replacing ${installed.tag}" else "Extracting the package",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    ThinProgressBar(progress = null, modifier = Modifier.padding(horizontal = RowPaddingHorizontal).padding(bottom = Spacing.sm))
                }
                ActiveDriverState.Empty -> Column(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingHorizontal),
                    ) {
                        Text("None", style = MaterialTheme.typography.titleSmall)
                        if (latest == null) {
                            Text(
                                text = "Refresh to load releases",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = Spacing.xxs),
                            )
                        }
                    }
                    if (latest != null) {
                        CardDivider()
                        DriverReleaseRow(
                            release = latest,
                            task = latestTask,
                            installed = false,
                            installing = false,
                            onDownload = onDownloadLatest,
                            onInstall = onInstallLatest,
                        )
                    }
                }
                ActiveDriverState.Installed -> InstalledDriverFace(
                    driver = installed ?: lastInstalled[0],
                    updateAvailable = updateAvailable,
                    updateTask = latestTask,
                    onInstallUpdate = onInstallLatest,
                    onUninstall = onUninstall,
                )
            }
        }
        footer()
    }
}

/**
 * The installed face of [ActiveDriverCard]: summary, then the update prompt (when there is one)
 * beside the uninstall action. While the update is downloading the prompt turns into progress.
 */
@Composable
private fun InstalledDriverFace(
    driver: InstalledDriver?,
    updateAvailable: RadvRelease?,
    updateTask: DownloadTask?,
    onInstallUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (driver == null) return
    val transferring = updateAvailable != null && updateTask?.status in setOf(
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING,
    )
    Column(Modifier.fillMaxWidth()) {
        // Short title and short facts: "v1.5.0 · Vulkan 1.4" fits one line next to the pill, and
        // the full Mesa build string gets its own line instead of being cut off.
        DriverSummaryRow(
            title = "RADV Xclipse",
            lines = listOf(
                listOfNotNull(
                    driver.tag,
                    driver.vulkanVersion?.let { "Vulkan ${it.split('.').take(2).joinToString(".")}" },
                ).joinToString(" · "),
                driver.mesaVersion?.let { "Mesa $it" }.orEmpty(),
            ),
        )
        CardDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The update button and its download progress swap in place; nothing when current.
            AnimatedContent(
                targetState = updateAvailable to transferring,
                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
                label = "driverUpdate",
            ) { (update, downloading) ->
                when {
                    update != null && downloading -> Column(Modifier.fillMaxWidth()) {
                        val percent = ((updateTask?.progressFraction ?: 0f) * 100).toInt()
                        Text(
                            text = if (updateTask?.status == DownloadStatus.VERIFYING) "Verifying ${update.tag}" else "Downloading ${update.tag} · $percent%",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        ThinProgressBar(
                            progress = updateTask?.takeIf { it.totalBytes > 0 }?.progressFraction,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                    update != null -> FableButton(
                        text = "Update to ${update.tag}",
                        primary = true,
                        compact = true,
                        onClick = onInstallUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Spacer(Modifier.fillMaxWidth())
                }
            }
            FableButton(
                text = "Uninstall",
                destructive = true,
                compact = true,
                onClick = onUninstall,
            )
        }
    }
}

/** Which of the three faces [ActiveDriverCard] shows; drives its cross-fade. */
private enum class ActiveDriverState { Installing, Empty, Installed }

private const val OLDER_KEY = "older-releases"

private fun List<DownloadTask>.taskFor(release: RadvRelease): DownloadTask? =
    filter { it.assetId == release.id }.maxByOrNull { it.updatedAt }
