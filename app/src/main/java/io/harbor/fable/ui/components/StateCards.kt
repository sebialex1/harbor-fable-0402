package io.harbor.fable.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import io.harbor.fable.ui.theme.FableBlue
import io.harbor.fable.ui.theme.TileTone
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
 * Centered empty state: the glyph on a toned gradient tile floating in a soft halo of the same
 * hue, a title and at most one short line, straight on the canvas. No card and no button; the
 * screen's own top-bar action is how you fill it. The tile drifts gently so the screen isn't
 * dead while empty.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String = "",
    tone: TileTone = TileTone.Graphite,
) {
    val drift = rememberInfiniteTransition(label = "emptyDrift")
    val float by drift.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = Motion.EaseInOut), RepeatMode.Reverse),
        label = "emptyFloat",
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(tone.start.copy(alpha = 0.28f), Color.Transparent),
                            radius = size.minDimension / 2f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ToneIconTile(
                icon = icon,
                tone = tone,
                size = 56.dp,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.graphicsLayer { translationY = float.dp.toPx() },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
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
                color = FableBlue,
                trackColor = FableTrack,
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
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
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
 * (queued / verifying / unknown size). Fills whatever width it is given, edge to edge.
 */
@Composable
fun ThinProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = FableBlue,
) {
    LineProgressBar(progress = progress, modifier = modifier, color = color, thickness = 2.dp, rounded = true)
}

/**
 * A continuous progress line drawn by hand. Material3's LinearProgressIndicator splits its
 * indeterminate variant into gapped segments and insets the ends, which reads as a broken line
 * on a 2dp bar; this one is a single unbroken track with one solid fill.
 *
 * Determinate: the fill grows from the start edge. Indeterminate (null [progress]): a single
 * segment sweeps across the track and wraps around.
 */
@Composable
fun LineProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = FableAccent,
    trackColor: Color = FableTrack,
    thickness: Dp = 2.dp,
    rounded: Boolean = false,
) {
    val cap = if (rounded) StrokeCap.Round else StrokeCap.Butt
    if (progress == null) {
        val transition = rememberInfiniteTransition(label = "lineProgressIndeterminate")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1300, easing = LinearEasing)),
            label = "lineProgressPhase",
        )
        Canvas(modifier.fillMaxWidth().height(thickness).clipToBounds()) {
            val w = size.width
            val y = size.height / 2
            val stroke = size.height
            drawLine(trackColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke, cap = cap)
            // Segment is 35% of the width; it enters from the left edge and leaves on the right.
            val segment = w * 0.35f
            val head = (w + segment) * phase
            val startX = (head - segment).coerceIn(0f, w)
            val endX = head.coerceIn(0f, w)
            if (endX > startX) {
                drawLine(color, Offset(startX, y), Offset(endX, y), strokeWidth = stroke, cap = cap)
            }
        }
    } else {
        val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.settle(), label = "lineProgress")
        Canvas(modifier.fillMaxWidth().height(thickness).clipToBounds()) {
            val w = size.width
            val y = size.height / 2
            val stroke = size.height
            drawLine(trackColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke, cap = cap)
            val endX = w * animated
            if (endX > 0f) {
                drawLine(color, Offset(0f, y), Offset(endX, y), strokeWidth = stroke, cap = cap)
            }
        }
    }
}
