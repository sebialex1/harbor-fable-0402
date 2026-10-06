package io.harbor.fable.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.DriverInstallResult
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The RADV Xclipse driver: what is active, the latest release, and the older builds. One
 * driver is active at a time; releases are downloaded here and installed as the active one.
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

    // Installs run on the app scope so leaving the tab mid-extraction does not cancel them.
    fun install(release: RadvRelease) {
        fableUi.scope.launch {
            val result = repository.install(release.tag)
            fableUi.showMessage(result.message, long = result is DriverInstallResult.Failed)
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
            message = "${installed?.tag ?: "The current driver"} will be removed and ${release.tag} becomes the active driver. Only one driver can be active at a time.",
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
            message = "${installed?.tag ?: "The active driver"} will be removed. Containers fall back to the system Vulkan driver until another release is installed. Downloaded packages are kept.",
            confirmLabel = "Uninstall",
            destructive = true,
            onConfirm = {
                confirmUninstall = false
                fableUi.scope.launch {
                    val removed = repository.uninstall()
                    fableUi.showMessage(if (removed != null) "Removed ${removed.tag}" else "No driver was installed")
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
    val appear = rememberLiquidAppear()
    val latest = remember(releases) { releases.firstOrNull { it.channel == ReleaseChannel.LATEST } }
    val older = remember(releases) { releases.filter { it.channel != ReleaseChannel.LATEST } }
    val updateAvailable = latest != null && installed != null && installed.tag != latest.tag &&
        RadvRelease.versionComparator.compare(latest.versionParts, RadvRelease.parseVersion(installed.tag)) > 0

    FableScreen(
        title = "Drivers",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Refresh releases",
                enabled = !isRefreshing,
                onClick = onRefresh,
            )
        },
    ) {
        item(key = "device") {
            GlassCard(Modifier.animateItem().liquidAppear(appear, 0)) {
                InfoRow(label = "Device", value = deviceInfo.device, icon = Icons.Outlined.Smartphone)
                CardDivider()
                InfoRow(label = "Vendor", value = deviceInfo.vendor, icon = Icons.Outlined.Business)
                CardDivider()
                InfoRow(label = "ABI", value = deviceInfo.abi, icon = Icons.Outlined.Architecture)
                CardDivider()
                InfoRow(label = "Graphics", value = deviceInfo.gpu, icon = Icons.Outlined.Memory)
            }
        }

        item(key = "active-label") { SectionLabel("Active Driver", Modifier.animateItem().liquidAppear(appear, 1)) }
        item(key = "active") {
            ActiveDriverCard(
                installed = installed,
                installing = installing,
                updateAvailable = if (updateAvailable) latest else null,
                onInstallUpdate = { latest?.let(onInstall) },
                onUninstall = onUninstall,
                modifier = Modifier.animateItem().liquidAppear(appear, 1),
            )
        }
        item(key = "vulkan") {
            GlassCard(Modifier.animateItem().liquidAppear(appear, 2), onClick = onOpenVulkanExtensions) {
                ListRow(
                    title = "Vulkan extensions",
                    subtitle = when {
                        installed?.vulkanVersion != null -> "What ${installed.tag} reports · Vulkan ${installed.vulkanVersion}"
                        installed != null -> "What ${installed.tag} reports through Vulkan"
                        else -> "What the system driver reports through Vulkan"
                    },
                    icon = Icons.Outlined.Extension,
                    showChevron = true,
                )
            }
        }

        if (refreshError != null && releases.isEmpty()) {
            item(key = "error") {
                NoticeCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load releases",
                    lines = listOf(refreshError),
                    modifier = Modifier.animateItem().liquidAppear(appear, 3),
                )
            }
        } else if (stale) {
            item(key = "stale") {
                NoticeCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Offline",
                    lines = listOf("Showing the last release list that was fetched"),
                    modifier = Modifier.animateItem().liquidAppear(appear, 3),
                )
            }
        }

        if (isRefreshing && releases.isEmpty()) {
            item(key = "refreshing") {
                LoadingCard(message = "Loading releases…", modifier = Modifier.animateItem().liquidAppear(appear, 3))
            }
        }

        if (latest != null) {
            item(key = "latest-label") { SectionLabel("Latest Release", Modifier.animateItem().liquidAppear(appear, 3)) }
            item(key = "latest") {
                GlassCard(Modifier.animateItem().liquidAppear(appear, 3)) {
                    DriverReleaseRow(
                        release = latest,
                        task = tasks.taskFor(latest),
                        installed = installed?.tag == latest.tag,
                        installing = installing == latest.tag,
                        onDownload = { onDownload(latest) },
                        onInstall = { onInstall(latest) },
                    )
                }
            }
        }

        if (older.isNotEmpty()) {
            item(key = "older") {
                val downloadedCount = older.count { it.isDownloaded }
                CollapsibleSection(
                    title = "Previous Versions",
                    expanded = expansion.isExpanded(OLDER_KEY, default = false),
                    onToggle = { expansion.toggle(OLDER_KEY, default = false) },
                    modifier = Modifier.animateItem().liquidAppear(appear, 4),
                    badge = {
                        Pill(
                            text = if (downloadedCount > 0) "$downloadedCount/${older.size}" else "${older.size}",
                            color = if (downloadedCount > 0) FableSuccess else FableTextDim,
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
                    message = "Refresh to load the RADV Xclipse releases",
                    modifier = Modifier.animateItem().liquidAppear(appear, 3),
                )
            }
        }
    }
}

/**
 * The single active driver with its actions, or the empty state explaining that one driver is
 * active at a time. While [installing] is set the card shows the extraction in progress. The
 * three states cross-fade and the card resizes between them instead of snapping.
 */
@Composable
internal fun ActiveDriverCard(
    installed: InstalledDriver?,
    installing: String?,
    updateAvailable: RadvRelease?,
    onInstallUpdate: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
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
    GlassCard(modifier.fillMaxWidth()) {
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
                        IconTile(icon = Icons.Outlined.Memory, tint = FableAccent)
                        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                            Text("Installing ${installing ?: lastInstalling[0].orEmpty()}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (installed != null) "Removing ${installed.tag}, then extracting the new package" else "Extracting the driver package",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    ThinProgressBar(progress = null, modifier = Modifier.padding(horizontal = RowPaddingHorizontal).padding(bottom = Spacing.sm))
                }
                ActiveDriverState.Empty -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingHorizontal),
                ) {
                    Text("No driver installed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Install the latest RADV Xclipse release below. One driver is active at a time; installing another replaces it.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                }
                ActiveDriverState.Installed -> InstalledDriverFace(
                    driver = installed ?: lastInstalled[0],
                    updateAvailable = updateAvailable,
                    onInstallUpdate = onInstallUpdate,
                    onUninstall = onUninstall,
                )
            }
        }
    }
}

/** The installed face of [ActiveDriverCard]: summary, then update/uninstall actions. */
@Composable
private fun InstalledDriverFace(
    driver: InstalledDriver?,
    updateAvailable: RadvRelease?,
    onInstallUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (driver == null) return
    Column(Modifier.fillMaxWidth()) {
        DriverSummaryRow(
            title = driver.name ?: "RADV Xclipse ${driver.tag}",
            lines = listOf(
                listOfNotNull(
                    driver.tag,
                    driver.mesaVersion?.let { "Mesa $it" },
                    driver.vulkanVersion?.let { "Vulkan $it" },
                ).joinToString(" · "),
            ),
            trailing = { Pill(text = "Active", color = FableSuccess, icon = Icons.Outlined.Check) },
        )
        CardDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Up to date" and the update button swap in place when a release lands.
            AnimatedContent(
                targetState = updateAvailable,
                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
                label = "driverUpdate",
            ) { update ->
                if (update != null) {
                    GlassButton(
                        text = "Update to ${update.tag}",
                        icon = Icons.Outlined.Upgrade,
                        primary = true,
                        compact = true,
                        onClick = onInstallUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "Up to date",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            GlassButton(
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
