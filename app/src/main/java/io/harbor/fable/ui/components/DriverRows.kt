package io.harbor.fable.ui.components

import io.harbor.fable.ui.theme.TileTone
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.formatBytes
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import io.harbor.fable.ui.icons.FableIcons

/** Where a release stands on this device. Drives the trailing control of [DriverReleaseRow]. */
enum class ReleaseUiState { AVAILABLE, QUEUED, DOWNLOADING, VERIFYING, DOWNLOADED, INSTALLING, INSTALLED }

internal fun releaseUiState(release: RadvRelease, task: DownloadTask?, installed: Boolean, installing: Boolean): ReleaseUiState =
    when {
        installing -> ReleaseUiState.INSTALLING
        installed -> ReleaseUiState.INSTALLED
        release.isDownloaded || task?.status == DownloadStatus.COMPLETED -> ReleaseUiState.DOWNLOADED
        task?.status == DownloadStatus.QUEUED -> ReleaseUiState.QUEUED
        task?.status == DownloadStatus.DOWNLOADING -> ReleaseUiState.DOWNLOADING
        task?.status == DownloadStatus.VERIFYING -> ReleaseUiState.VERIFYING
        else -> ReleaseUiState.AVAILABLE
    }

/** "Mesa 26.3.0-devel · 4.2 MB · 5 Oct 2026", dropping whatever is unknown. */
internal fun releaseSubtitle(release: RadvRelease, note: String? = null): String = listOfNotNull(
    release.mesaVersion?.let { "Mesa $it" },
    formatBytes(release.asset.sizeBytes).takeIf { release.asset.sizeBytes > 0 },
    release.publishedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
    note,
).joinToString(" · ")

/**
 * One RADV Xclipse release. The title is the tag with a channel badge ("Latest", "Pre-release");
 * the trailing control is one [MorphPill] that walks through download → progress → downloaded →
 * active, stretching and filling as it goes (Install appears as a button once the zip is on disk).
 *
 * [onInstall] is offered once the zip is on disk; pass null to hide the install action.
 */
@Composable
fun DriverReleaseRow(
    release: RadvRelease,
    task: DownloadTask?,
    installed: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    installing: Boolean = false,
    onInstall: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val ui = releaseUiState(release, task, installed, installing)
    val note = when (task?.status) {
        DownloadStatus.FAILED -> if (ui == ReleaseUiState.AVAILABLE) "Failed" else null
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.CANCELLED -> if (ui == ReleaseUiState.AVAILABLE) "Cancelled" else null
        else -> null
    }
    val percent = ((task?.progressFraction ?: 0f) * 100).toInt()
    val progress: Float? = when {
        ui == ReleaseUiState.DOWNLOADING && (task?.totalBytes ?: 0L) > 0 -> task?.progressFraction
        else -> null
    }
    Box(modifier) {
        ListRow(
            title = release.tag,
            subtitle = releaseSubtitle(release, note),
            onClick = onClick,
            showChevron = false,
            // Mesa version, size and date do not fit one line on narrow screens.
            subtitleMaxLines = 2,
            titleBadge = {
                when (release.channel) {
                    ReleaseChannel.LATEST -> Pill(text = "Latest")
                    ReleaseChannel.PRERELEASE -> Pill(text = "Pre-release")
                    ReleaseChannel.VERSIONED -> Unit
                }
            },
            trailing = {
                // Download, progress and verification are one morphing pill (see MorphPill);
                // only Install swaps in a real button once the zip is on disk.
                AnimatedContent(
                    targetState = ui == ReleaseUiState.DOWNLOADED && onInstall != null,
                    transitionSpec = {
                        (fadeIn(Motion.enter(Motion.Quick)) + scaleIn(Motion.pop(), initialScale = 0.85f)) togetherWith
                            fadeOut(Motion.exit(Motion.Fast))
                    },
                    label = "releaseState",
                ) { showInstall ->
                    if (showInstall && onInstall != null) {
                        FableButton(text = "Install", onClick = onInstall, compact = true)
                    } else {
                        MorphPill(
                            phase = when (ui) {
                                ReleaseUiState.AVAILABLE -> MorphPhase.Idle
                                ReleaseUiState.QUEUED, ReleaseUiState.INSTALLING -> MorphPhase.Waiting
                                ReleaseUiState.DOWNLOADING -> MorphPhase.Active
                                ReleaseUiState.VERIFYING -> MorphPhase.Verifying
                                ReleaseUiState.DOWNLOADED, ReleaseUiState.INSTALLED -> MorphPhase.Done
                            },
                            progress = progress,
                            label = when (ui) {
                                ReleaseUiState.AVAILABLE -> ""
                                ReleaseUiState.QUEUED -> "Queued"
                                ReleaseUiState.DOWNLOADING -> if (progress != null) "$percent%" else "Starting"
                                ReleaseUiState.VERIFYING -> "Verifying"
                                ReleaseUiState.DOWNLOADED -> "Downloaded"
                                ReleaseUiState.INSTALLING -> "Installing"
                                ReleaseUiState.INSTALLED -> "Active"
                            },
                            contentDescription = if (ui == ReleaseUiState.AVAILABLE) "Download ${release.tag}" else null,
                            onClick = onDownload,
                        )
                    }
                }
            },
        )
    }
}

/**
 * Compact summary used for the active driver and the probed Vulkan device: a title that may
 * wrap to two lines, short detail [lines] beneath it, and an optional [trailing] pill. No card
 * of its own so it can share a surface with its actions.
 *
 * The trailing content is measured first and the text column takes what is left, so a long
 * title truncates instead of running under the pill.
 */
@Composable
fun DriverSummaryRow(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    icon: ImageVector = FableIcons.Drivers,
    iconTint: Color = FableText,
    titleMaxLines: Int = 2,
    tone: TileTone = TileTone.Teal,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            lines.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            Box(contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}

/** Trailing state of a row ("Downloaded", "42%") as quiet grey text, never a badge. */
@Composable
internal fun StateText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = FableTextDim,
        maxLines = 1,
        modifier = modifier,
    )
}
