package io.harbor.fable.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.formatBytes
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.icons.FableIcons

private enum class DownloadUi { AVAILABLE, QUEUED, DOWNLOADING, VERIFYING, DONE }

private val ArchiveSuffixes = listOf(".tar.xz", ".tar.gz", ".tar.bz2", ".tar.zst", ".tgz", ".txz", ".zip", ".7z", ".wcp", ".rat")

/** Catalog file name without its archive extension, which only adds noise in a list. */
internal fun displayAssetName(fileName: String): String {
    val suffix = ArchiveSuffixes.firstOrNull { fileName.endsWith(it, ignoreCase = true) } ?: return fileName
    return fileName.dropLast(suffix.length).ifBlank { fileName }
}

/**
 * A catalog row with a live download state. The trailing control animates between the
 * download button, the progress and a quiet "Downloaded" as the [task] moves along, and a thin
 * progress line runs along the bottom edge while bytes are coming in.
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
    val active = ui == DownloadUi.QUEUED || ui == DownloadUi.DOWNLOADING || ui == DownloadUi.VERIFYING
    val progress: Float? = when {
        ui == DownloadUi.DOWNLOADING && (task?.totalBytes ?: 0L) > 0 -> task?.progressFraction
        ui == DownloadUi.VERIFYING -> 1f
        else -> null
    }

    Box(modifier) {
        ListRow(
            title = displayAssetName(title),
            subtitle = subtitle,
            onClick = onClick,
            titleBadge = titleBadge,
            showChevron = false,
            trailing = {
                AnimatedContent(
                    targetState = ui,
                    transitionSpec = {
                        (fadeIn(Motion.enter(Motion.Quick)) + scaleIn(Motion.pop(), initialScale = 0.8f)) togetherWith
                            fadeOut(Motion.exit(Motion.Fast))
                    },
                    label = "downloadState",
                ) { current ->
                    when (current) {
                        DownloadUi.AVAILABLE -> FableIconButton(
                            icon = FableIcons.Download,
                            contentDescription = "Download $title",
                            size = 32.dp,
                            onClick = onDownload,
                        )
                        DownloadUi.QUEUED -> StateText("Queued")
                        DownloadUi.DOWNLOADING -> StateText("$percent%")
                        DownloadUi.VERIFYING -> StateText("Verifying")
                        DownloadUi.DONE -> StateText("Downloaded")
                    }
                }
            },
        )
        AnimatedVisibility(
            visible = active,
            enter = fadeIn(Motion.enter(Motion.Quick)),
            exit = fadeOut(Motion.exit(Motion.Standard)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = RowPaddingHorizontal),
        ) {
            ThinProgressBar(progress = progress)
        }
    }
}
