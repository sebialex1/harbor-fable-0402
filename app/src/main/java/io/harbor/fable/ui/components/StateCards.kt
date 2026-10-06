package io.harbor.fable.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableTrack
import io.harbor.fable.ui.theme.FableTextFaint
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing

/**
 * Centered empty state: a grey glyph, a title and at most one short line, straight on the
 * canvas. No card and no button; the screen's own top-bar action is how you fill it.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String = "",
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = FableTextFaint, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(Spacing.md))
        Text(title, style = MaterialTheme.typography.titleMedium, color = FableTextDim, textAlign = TextAlign.Center)
        if (message.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(message, style = MaterialTheme.typography.bodySmall, color = FableTextFaint, textAlign = TextAlign.Center)
        }
    }
}

/** Inline "working on it" card. */
@Composable
fun LoadingCard(message: String, modifier: Modifier = Modifier) {
    FableCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = FableAccent,
                strokeWidth = 2.dp,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Compact notice row, e.g. for catalog sources that failed to refresh. Grey unless [tint] says otherwise. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    tint: Color = FableTextDim,
) {
    FableCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

/**
 * 2dp progress line for card edges. A null [progress] renders the indeterminate variant
 * (queued / verifying / unknown size).
 */
@Composable
fun ThinProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = FableAccent,
) {
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
            color = color,
            trackColor = FableTrack,
            gapSize = 0.dp,
        )
    } else {
        val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.settle(), label = "thinProgress")
        LinearProgressIndicator(
            progress = { animated },
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
            color = color,
            trackColor = FableTrack,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
