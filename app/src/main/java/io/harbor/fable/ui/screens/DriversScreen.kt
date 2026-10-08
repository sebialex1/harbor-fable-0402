package io.harbor.fable.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.DriverInstallResult
import io.harbor.fable.data.DriverRepository
import io.harbor.fable.data.models.DriverFamily
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.VulkanSource
import io.harbor.fable.nativebridge.GpuIdentity
import io.harbor.fable.nativebridge.GpuKind
import io.harbor.fable.nativebridge.VulkanProbe
import io.harbor.fable.ui.theme.FableWarn
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
import io.harbor.fable.ui.theme.TileTone
import kotlinx.coroutines.launch
import io.harbor.fable.ui.icons.FableIcons

/**
 * The custom Vulkan drivers: the one active driver (with the latest release of the recommended
 * family as the install target or the update prompt), the Vulkan extensions link, the
 * recommended family's older builds, and the other family's builds.
 *
 * The recommendation follows the GPU ([DriverRepository.gpu]): Turnip on Qualcomm Adreno, RADV
 * Xclipse on Samsung Xclipse (and on anything not identified). The Turnip section only exists on
 * Adreno ([DriverRepository.visibleReleases]): on Xclipse, Mali or an unidentified GPU Turnip is
 * hidden and can't be installed, as it can't drive those GPUs.
 * A driver of the wrong family (RADV on an Adreno phone, which finds no device there and makes
 * Direct3D apps exit silently) is flagged, with a one-tap switch to the right one; installing a
 * mismatched driver by hand asks first. One driver is active at a time.
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
    val gpu by repository.gpu.collectAsStateWithLifecycle()
    // The system driver's Vulkan device is the most reliable GPU signal (sysfs may be hidden by
    // SELinux); the probe is cached, so this is cheap after the first time.
    LaunchedEffect(Unit) {
        val probe = runCatching { VulkanProbe.probe(VulkanSource.SYSTEM, null) }.getOrNull()
        repository.refineGpu(probe?.primaryDevice)
    }

    // Replacing an active driver and uninstalling both ask first; installing onto nothing does not.
    var pendingReplace by remember { mutableStateOf<RadvRelease?>(null) }
    var pendingMismatch by remember { mutableStateOf<RadvRelease?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }

    // Installs run on the app scope so leaving the tab mid-extraction does not cancel them. A
    // release that is not on disk yet is downloaded first and installed when the bytes land.
    fun install(release: RadvRelease) {
        fableUi.scope.launch {
            if (release.isDownloaded) {
                val result = repository.install(release.id)
                fableUi.showMessage(result.message, long = result is DriverInstallResult.Failed)
            } else {
                val task = repository.downloadAndInstall(release.id)
                fableUi.showMessage(
                    if (task != null) "Downloading ${release.label}" else "Installing ${release.label}",
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
        gpu = gpu,
        onRefresh = { refreshTrigger++ },
        onDownload = { release -> scope.launch { repository.download(release.id) } },
        onInstall = { release ->
            val current = installed
            when {
                // A driver built for another GPU line: say so before replacing anything.
                !gpu.matches(release.family) -> pendingMismatch = release
                current != null && !current.isFrom(release) -> pendingReplace = release
                else -> install(release)
            }
        },
        onUninstall = { confirmUninstall = true },
        onOpenVulkanExtensions = onOpenVulkanExtensions,
    )

    pendingReplace?.let { release ->
        ConfirmDialog(
            title = "Replace active driver?",
            message = "${installed?.let { "${it.family.displayName} ${it.tag}" } ?: "The current driver"} will be removed.",
            confirmLabel = "Replace",
            onConfirm = {
                pendingReplace = null
                install(release)
            },
            onDismiss = { pendingReplace = null },
        )
    }

    pendingMismatch?.let { release ->
        ConfirmDialog(
            title = "Install ${release.family.displayName}?",
            message = "${release.family.displayName} is built for ${release.family.targetGpus}. This device has " +
                "${gpuArticle(gpu)}, which needs ${gpu.recommendedFamily.displayName}; Vulkan and Direct3D games " +
                "will likely fail with this driver." +
                (installed?.let { " ${it.family.displayName} ${it.tag} will be removed." } ?: ""),
            confirmLabel = "Install Anyway",
            onConfirm = {
                pendingMismatch = null
                install(release)
            },
            onDismiss = { pendingMismatch = null },
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
                    fableUi.showMessage(if (removed != null) "Removed ${removed.family.displayName} ${removed.tag}" else "No driver installed")
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
    gpu: GpuIdentity,
    onRefresh: () -> Unit,
    onDownload: (RadvRelease) -> Unit,
    onInstall: (RadvRelease) -> Unit,
    onUninstall: () -> Unit,
    onOpenVulkanExtensions: () -> Unit = {},
) {
    val expansion = rememberExpansionState()
    val appear = rememberEntrance()
    val family = gpu.recommendedFamily
    // Turnip is hidden entirely unless this is an Adreno GPU.
    val shown = remember(releases, gpu) { DriverRepository.visibleReleases(releases, gpu) }
    val ownReleases = remember(shown, family) { shown.filter { it.family == family } }
    val otherReleases = remember(shown, family) { shown.filter { it.family != family } }
    val latest = remember(ownReleases) { ownReleases.firstOrNull { it.channel == ReleaseChannel.LATEST } }
    // The installed driver is for another GPU line (RADV Xclipse on an Adreno phone).
    val wrongFamily = installed != null && !gpu.matches(installed.family)
    val updateAvailable = latest != null && installed != null && !installed.isFrom(latest) &&
        (wrongFamily || isNewer(latest, installed, releases))
    // The latest release is shown once: as the active driver, as the install target of the empty
    // state, or as the update / switch prompt. Only when none of those apply does it join the list.
    val latestCovered = latest == null || installed == null || installed.isFrom(latest) || updateAvailable
    val older = remember(ownReleases, latestCovered) {
        if (latestCovered) ownReleases.filter { it.channel != ReleaseChannel.LATEST } else ownReleases
    }
    val installingLabel = installing?.let { id -> releases.firstOrNull { it.id == id }?.label ?: id }

    FableScreen(
        title = "Drivers",
        actions = {
            FableIconButton(
                icon = FableIcons.Refresh,
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
                installing = installingLabel,
                latest = latest,
                recommendation = recommendationLine(gpu),
                switchFamily = wrongFamily,
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

        if (wrongFamily && installed != null) {
            item(key = "wrong-family") {
                NoticeCard(
                    icon = FableIcons.Error,
                    title = "${installed.family.displayName} doesn't fit this GPU",
                    lines = listOf(
                        "It is built for ${installed.family.targetGpus}. This device has ${gpuArticle(gpu)}: " +
                            "install ${family.displayName} instead.",
                    ),
                    tint = FableWarn,
                    modifier = Modifier.animateItem().entrance(appear, 1),
                )
            }
        }

        if (refreshError != null && ownReleases.isEmpty()) {
            item(key = "error") {
                NoticeCard(
                    icon = FableIcons.Offline,
                    title = "Couldn't load releases",
                    lines = listOf(refreshError),
                    modifier = Modifier.animateItem().entrance(appear, 3),
                )
            }
        } else if (stale) {
            item(key = "stale") {
                NoticeCard(
                    icon = FableIcons.Offline,
                    title = "Offline",
                    lines = emptyList(),
                    modifier = Modifier.animateItem().entrance(appear, 3),
                )
            }
        }

        if (isRefreshing && shown.isEmpty()) {
            item(key = "refreshing") {
                LoadingCard(message = "Loading releases…", modifier = Modifier.animateItem().entrance(appear, 3))
            }
        }

        if (older.isNotEmpty()) {
            item(key = "older") {
                val downloadedCount = older.count { it.isDownloaded }
                CollapsibleSection(
                    title = if (latestCovered) "Previous ${family.displayName} Versions" else "All ${family.displayName} Versions",
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
                            installed = installed?.isFrom(release) == true,
                            installing = installing == release.id,
                            onDownload = { onDownload(release) },
                            onInstall = { onInstall(release) },
                        )
                    }
                }
            }
        }

        // The other family stays available where it can be used (RADV Xclipse on an Adreno phone
        // for a user who knows better), collapsed and labelled with the GPUs it is for. Turnip
        // never shows up here on a non-Adreno GPU: [shown] has already dropped it.
        if (otherReleases.isNotEmpty()) {
            val other = otherReleases.first().family
            item(key = "other-family") {
                val downloadedCount = otherReleases.count { it.isDownloaded }
                CollapsibleSection(
                    title = "${other.displayName} · ${other.targetGpus}",
                    expanded = expansion.isExpanded(OTHER_FAMILY_KEY, default = false),
                    onToggle = { expansion.toggle(OTHER_FAMILY_KEY, default = false) },
                    modifier = Modifier.animateItem().entrance(appear, 2).padding(top = Spacing.lg),
                    badge = {
                        Text(
                            text = if (downloadedCount > 0) "$downloadedCount of ${otherReleases.size}" else "${otherReleases.size}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ) {
                    otherReleases.forEachIndexed { index, release ->
                        if (index > 0) CardDivider()
                        DriverReleaseRow(
                            release = release,
                            task = tasks.taskFor(release),
                            installed = installed?.isFrom(release) == true,
                            installing = installing == release.id,
                            onDownload = { onDownload(release) },
                            onInstall = { onInstall(release) },
                        )
                    }
                }
            }
        }

        if (shown.isEmpty() && !isRefreshing && refreshError == null) {
            item(key = "empty") {
                EmptyState(
                    icon = FableIcons.Drivers,
                    title = "No releases",
                    tone = TileTone.Teal,
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
    recommendation: String? = null,
    switchFamily: Boolean = false,
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
    FableCard(modifier.fillMaxWidth(), glow = TileTone.Teal) {
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
                        Column(Modifier.weight(1f)) {
                            Text("Installing ${installing ?: lastInstalling[0].orEmpty()}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (installed != null) "Replacing ${installed.family.displayName} ${installed.tag}" else "Extracting the package",
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
                        if (recommendation != null) {
                            Text(
                                text = recommendation,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = Spacing.xxs),
                            )
                        }
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
                    switchFamily = switchFamily,
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
 * The installed face of [ActiveDriverCard]: the summary with the uninstall action at its top
 * right, then the update prompt on its own row when there is one. While the update is
 * downloading the prompt turns into progress.
 *
 * Uninstall used to share a row below the summary with the update prompt; with no update that
 * row was a wide empty gap with a lone button at the right edge. It now sits in the summary
 * row's trailing slot, and the row below only exists while an update is offered.
 */
@Composable
private fun InstalledDriverFace(
    driver: InstalledDriver?,
    updateAvailable: RadvRelease?,
    switchFamily: Boolean,
    updateTask: DownloadTask?,
    onInstallUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (driver == null) return
    val transferring = updateAvailable != null && updateTask?.status in setOf(
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING,
    )
    Column(Modifier.fillMaxWidth()) {
        // Short title and short facts: "v1.5.0 · Vulkan 1.4" fits one line next to the button, and
        // the full Mesa build string gets its own line instead of being cut off.
        DriverSummaryRow(
            title = driver.displayName,
            lines = listOf(
                listOfNotNull(
                    driver.family.displayName.takeIf { driver.name?.contains(it, ignoreCase = true) != true },
                    driver.tag,
                    driver.vulkanVersion?.let { "Vulkan ${it.split('.').take(2).joinToString(".")}" },
                ).joinToString(" · "),
                driver.mesaVersion?.let { "Mesa $it" }.orEmpty(),
            ),
            trailing = {
                FableButton(
                    text = "Uninstall",
                    destructive = true,
                    compact = true,
                    onClick = onUninstall,
                )
            },
        )
        if (updateAvailable != null) {
            CardDivider()
            // The update button and its download progress swap in place, across the full width.
            AnimatedContent(
                targetState = transferring,
                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.sm),
                label = "driverUpdate",
            ) { downloading ->
                if (downloading) {
                    Column(Modifier.fillMaxWidth()) {
                        val percent = ((updateTask?.progressFraction ?: 0f) * 100).toInt()
                        Text(
                            text = if (updateTask?.status == DownloadStatus.VERIFYING) {
                                "Verifying ${updateAvailable.tag}"
                            } else {
                                "Downloading ${updateAvailable.tag} · $percent%"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        ThinProgressBar(
                            progress = updateTask?.takeIf { it.totalBytes > 0 }?.progressFraction,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                } else {
                    FableButton(
                        text = if (switchFamily) "Switch to ${updateAvailable.label}" else "Update to ${updateAvailable.tag}",
                        primary = true,
                        compact = true,
                        onClick = onInstallUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Which of the three faces [ActiveDriverCard] shows; drives its cross-fade. */
private enum class ActiveDriverState { Installing, Empty, Installed }

private const val OLDER_KEY = "older-releases"
private const val OTHER_FAMILY_KEY = "other-family-releases"

/** "an Adreno GPU (Adreno740v2)", "a Samsung Xclipse GPU", "this GPU". */
private fun gpuArticle(gpu: GpuIdentity): String = when (gpu.kind) {
    GpuKind.ADRENO -> "an Adreno GPU" + (gpu.model?.let { " ($it)" } ?: "")
    GpuKind.XCLIPSE -> "a Samsung Xclipse GPU" + (gpu.model?.let { " ($it)" } ?: "")
    GpuKind.OTHER -> "a GPU neither driver is built for" + (gpu.model?.let { " ($it)" } ?: "")
    GpuKind.UNKNOWN -> "an unidentified GPU"
}

/** One line under "None": which driver this GPU wants. */
private fun recommendationLine(gpu: GpuIdentity): String = when (gpu.kind) {
    GpuKind.ADRENO -> "Adreno GPU: Turnip recommended"
    GpuKind.XCLIPSE -> "Xclipse GPU: RADV Xclipse recommended"
    GpuKind.OTHER -> "Neither driver targets this GPU; the system driver is used"
    GpuKind.UNKNOWN -> "GPU not identified: RADV Xclipse suggested"
}

/**
 * True when [latest] is newer than the [installed] driver of the same family. RADV tags are
 * semantic versions; Turnip tags differ in shape between repositories, so Turnip compares the
 * publish dates of the two releases (false when the installed one is no longer listed).
 */
private fun isNewer(latest: RadvRelease, installed: InstalledDriver, releases: List<RadvRelease>): Boolean {
    if (latest.family != installed.family) return false
    return when (latest.family) {
        DriverFamily.RADV_XCLIPSE ->
            RadvRelease.versionComparator.compare(latest.versionParts, RadvRelease.parseVersion(installed.tag)) > 0
        DriverFamily.TURNIP -> {
            val current = releases.firstOrNull { installed.isFrom(it) } ?: return false
            latest.publishedAt > current.publishedAt
        }
    }
}

private fun List<DownloadTask>.taskFor(release: RadvRelease): DownloadTask? =
    filter { it.assetId == release.id }.maxByOrNull { it.updatedAt }
