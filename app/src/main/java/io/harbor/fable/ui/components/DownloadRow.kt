package io.harbor.fable.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.formatBytes

private enum class DownloadUi { AVAILABLE, QUEUED, DOWNLOADING, VERIFYING, DONE }

private val ArchiveSuffixes = listOf(".tar.xz", ".tar.gz", ".tar.bz2", ".tar.zst", ".tgz", ".txz", ".zip", ".7z", ".wcp", ".rat")

/** Catalog file name without its archive extension, which only adds noise in a list. */
internal fun displayAssetName(fileName: String): String {
    val suffix = ArchiveSuffixes.firstOrNull { fileName.endsWith(it, ignoreCase = true) } ?: return fileName
    return fileName.dropLast(suffix.length).ifBlank { fileName }
}

/**
 * A catalog row with a live download state. The trailing control is a [MorphPill]: a round
 * download button that, once tapped, stretches into a pill and fills with the progress itself
 * (percentage inside), breathes while verifying, then settles into a check and "Downloaded".
 * The progress extends out of the button; there is no separate bar under the row.
 */
@Composable
fun DownloadRow(
    title: String,
    version: String,
    sizeBytes: Long,
    isDownloaded: Boolean,
    task: DownloadTask?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    titleBadge: (@Composable RowScope.() -> Unit)? = null,
) {
    val status = task?.status
    val done = isDownloaded || status == DownloadStatus.COMPLETED
    val ui = when {
        done -> DownloadUi.DONE
        status == DownloadStatus.QUEUED -> DownloadUi.QUEUED
        status == DownloadStatus.DOWNLOADING -> DownloadUi.DOWNLOADING
        status == DownloadStatus.VERIFYING -> DownloadUi.VERIFYING
        else -> DownloadUi.AVAILABLE
    }
    val note = when {
        done -> null
        status == DownloadStatus.FAILED -> "Failed"
        status == DownloadStatus.PAUSED -> "Paused"
        status == DownloadStatus.CANCELLED -> "Cancelled"
        else -> null
    }
    val subtitle = listOfNotNull(
        version.takeIf { it.isNotBlank() },
        formatBytes(sizeBytes).takeIf { sizeBytes > 0 },
        note,
    ).joinToString(" · ")
    val percent = ((task?.progressFraction ?: 0f) * 100).toInt()
    val progress: Float? = when {
        ui == DownloadUi.DOWNLOADING && (task?.totalBytes ?: 0L) > 0 -> task?.progressFraction
        else -> null
    }
    val phase = when (ui) {
        DownloadUi.AVAILABLE -> MorphPhase.Idle
        DownloadUi.QUEUED -> MorphPhase.Waiting
        DownloadUi.DOWNLOADING -> MorphPhase.Active
        DownloadUi.VERIFYING -> MorphPhase.Verifying
        DownloadUi.DONE -> MorphPhase.Done
    }
    val label = when (ui) {
        DownloadUi.AVAILABLE -> ""
        DownloadUi.QUEUED -> "Queued"
        DownloadUi.DOWNLOADING -> if (progress != null) "$percent%" else "Starting"
        DownloadUi.VERIFYING -> "Verifying"
        DownloadUi.DONE -> "Downloaded"
    }

    ListRow(
        title = displayAssetName(title),
        subtitle = subtitle,
        onClick = onClick,
        titleBadge = titleBadge,
        showChevron = false,
        modifier = modifier,
        trailing = {
            MorphPill(
                phase = phase,
                progress = progress,
                label = label,
                contentDescription = if (phase == MorphPhase.Idle) "Download $title" else "$title: $label",
                onClick = onDownload,
            )
        },
    )
}
