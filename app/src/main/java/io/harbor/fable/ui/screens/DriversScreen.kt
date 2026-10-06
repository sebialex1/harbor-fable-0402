package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The RADV Xclipse driver: what is active, the latest release, and the older builds. One
 * driver is active at a time; releases are downloaded here and installed as the active one.
 */
@Composable
fun DriversScreen() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.driverRepository
    val releases by repository.releases.collectAsStateWithLifecycle()
    val installed by repository.installed.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val refreshError by repository.refreshError.collectAsStateWithLifecycle()
    val stale by repository.stale.collectAsStateWithLifecycle()
    val downloadSnapshot by app.downloadManager.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-runs on every button press; the initial value (0) refreshes from cache on entry.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTrigger) { repository.refresh(forceRefresh = refreshTrigger > 0) }

    val deviceInfo = remember { DeviceProbe.read() }

    DriversContent(
        releases = releases,
        installed = installed,
        isRefreshing = isRefreshing,
        refreshError = refreshError,
        stale = stale,
        tasks = downloadSnapshot.tasks,
        deviceInfo = deviceInfo,
        onRefresh = { refreshTrigger++ },
        onDownload = { release -> scope.launch { repository.download(release.tag) } },
    )
}

@Composable
internal fun DriversContent(
    releases: List<RadvRelease>,
    installed: InstalledDriver?,
    isRefreshing: Boolean,
    refreshError: String?,
    stale: Boolean,
    tasks: List<DownloadTask>,
    deviceInfo: DeviceGpuInfo,
    onRefresh: () -> Unit,
    onDownload: (RadvRelease) -> Unit,
) {
    val expansion = rememberExpansionState()
    val latest = remember(releases) { releases.firstOrNull { it.channel == ReleaseChannel.LATEST } }
    val older = remember(releases) { releases.filter { it.channel != ReleaseChannel.LATEST } }

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

        item(key = "active-label") { SectionLabel("Active Driver", Modifier.animateItem()) }
        item(key = "active") {
            ActiveDriverCard(installed = installed, modifier = Modifier.animateItem())
        }

        if (refreshError != null && releases.isEmpty()) {
            item(key = "error") {
                NoticeCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load releases",
                    lines = listOf(refreshError),
                    modifier = Modifier.animateItem(),
                )
            }
        } else if (stale) {
            item(key = "stale") {
                NoticeCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Offline",
                    lines = listOf("Showing the last release list that was fetched"),
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (isRefreshing && releases.isEmpty()) {
            item(key = "refreshing") { LoadingCard(message = "Loading releases…", modifier = Modifier.animateItem()) }
        }

        if (latest != null) {
            item(key = "latest-label") { SectionLabel("Latest Release", Modifier.animateItem()) }
            item(key = "latest") {
                GlassCard(Modifier.animateItem()) {
                    DriverReleaseRow(
                        release = latest,
                        task = tasks.taskFor(latest),
                        installed = installed?.tag == latest.tag,
                        onDownload = { onDownload(latest) },
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
                    modifier = Modifier.animateItem(),
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
                            onDownload = { onDownload(release) },
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
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** The single active driver, or the empty state explaining that one driver is active at a time. */
@Composable
internal fun ActiveDriverCard(
    installed: InstalledDriver?,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    GlassCard(modifier.fillMaxWidth()) {
        if (installed == null) {
            Column(Modifier.padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingHorizontal)) {
                Text("No driver installed", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Download the latest RADV Xclipse release below and install it. One driver is active at a time; installing another replaces it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
        } else {
            DriverSummaryRow(
                title = installed.name ?: "RADV Xclipse ${installed.tag}",
                lines = listOf(
                    listOfNotNull(
                        installed.tag,
                        installed.mesaVersion?.let { "Mesa $it" },
                        installed.vulkanVersion?.let { "Vulkan $it" },
                    ).joinToString(" · "),
                ),
                trailing = { Pill(text = "Active", color = FableSuccess, icon = Icons.Outlined.Check) },
            )
        }
        actions?.invoke()
    }
}

private const val OLDER_KEY = "older-releases"

private fun List<DownloadTask>.taskFor(release: RadvRelease): DownloadTask? =
    filter { it.assetId == release.id }.maxByOrNull { it.updatedAt }
