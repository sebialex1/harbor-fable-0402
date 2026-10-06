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
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing

/** Centered empty state with an optional call to action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String = "",
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(icon = icon, tint = FableTextDim, size = 56.dp)
            Spacer(Modifier.height(Spacing.lg))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (message.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Spacing.xl))
                GlassButton(text = actionLabel, onClick = onAction, primary = true, icon = actionIcon)
            }
        }
    }
}

/** Inline "working on it" card. */
@Composable
fun LoadingCard(message: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = FableAccent,
                strokeWidth = 2.dp,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Compact warning/info card, e.g. for catalog sources that failed to refresh. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    tint: Color = FableWarn,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RowPaddingHorizontal, vertical = Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
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
            trackColor = color.copy(alpha = 0.12f),
            gapSize = 0.dp,
        )
    } else {
        val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "thinProgress")
        LinearProgressIndicator(
            progress = { animated },
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
            color = color,
            trackColor = color.copy(alpha = 0.12f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
