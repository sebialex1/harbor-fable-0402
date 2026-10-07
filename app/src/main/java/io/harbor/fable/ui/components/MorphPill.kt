package io.harbor.fable.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.icons.FableIcons
import io.harbor.fable.ui.theme.FableBlue
import io.harbor.fable.ui.theme.FableBlueDim
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.GlassBorder
import io.harbor.fable.ui.theme.GlassSurfaceRaised
import io.harbor.fable.ui.theme.Motion

/** What a [MorphPill] shows; each phase has its own width, and the pill morphs between them. */
@Immutable
enum class MorphPhase {
    /** A round button with a glyph (Download). */
    Idle,

    /** Waiting for its turn: a short pill with a soft travelling sheen. */
    Waiting,

    /** Bytes coming in: the pill stretches out and fills from the left with the progress. */
    Active,

    /** Checking the file: full, gently breathing. */
    Verifying,

    /** Finished: the fill drains away and a check with a quiet label remains. */
    Done,
}

/**
 * The download control that *becomes* its own progress bar. In [MorphPhase.Idle] it is a round
 * glass button; once a download starts the same surface stretches sideways into a pill (the
 * width animates on [Motion.morph]) and fills from the left in [FableBlue] as [progress] grows,
 * with the percentage inside. When it finishes the fill drains and the pill settles into a check
 * and [label]. There is no separate bar below the row: the progress extends out of the button.
 *
 * [progress] null while [MorphPhase.Active] runs an indeterminate sweep (size not known yet).
 */
@Composable
fun MorphPill(
    phase: MorphPhase,
    progress: Float?,
    label: String,
    modifier: Modifier = Modifier,
    idleIcon: ImageVector = FableIcons.Download,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    height: Dp = PillHeight,
    activeWidth: Dp = ActiveWidth,
) {
    val width by animateDpAsState(
        targetValue = when (phase) {
            MorphPhase.Idle -> height
            MorphPhase.Waiting -> WaitingWidth
            MorphPhase.Active, MorphPhase.Verifying -> activeWidth
            MorphPhase.Done -> DoneWidth
        },
        animationSpec = Motion.morph(),
        label = "morphPillWidth",
    )
    val fillTarget = when (phase) {
        MorphPhase.Active -> progress ?: 0f
        MorphPhase.Verifying -> 1f
        else -> 0f
    }
    val fill by animateFloatAsState(fillTarget.coerceIn(0f, 1f), Motion.settle(), label = "morphPillFill")
    val sweepOn = phase == MorphPhase.Waiting || (phase == MorphPhase.Active && progress == null)
    val infinite = rememberInfiniteTransition(label = "morphPillLoop")
    val sweep by infinite.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "morphPillSweep",
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = Motion.EaseInOut), RepeatMode.Reverse),
        label = "morphPillBreathe",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, Motion.press(), label = "morphPillPress")
    val clickable = if (onClick != null && phase == MorphPhase.Idle) {
        Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .width(width)
            .height(height)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .glassSurface(shape = shape, fill = GlassSurfaceRaised, border = GlassBorder, blurRadius = 12)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                // Progress fill: grows from the leading edge as one with the pill.
                val w = size.width * fill
                if (w > 0f) {
                    val alpha = if (phase == MorphPhase.Verifying) breathe else 1f
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(FableBlueDim, FableBlue)),
                        size = Size(w.coerceAtLeast(size.height), size.height),
                        cornerRadius = r,
                        alpha = alpha * 0.85f,
                    )
                }
                // Indeterminate / waiting: a soft highlight travelling across.
                if (sweepOn) {
                    val center = size.width * sweep
                    val half = size.width * 0.35f
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, FableBlue.copy(alpha = 0.45f), Color.Transparent),
                            startX = center - half,
                            endX = center + half,
                        ),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = r,
                    )
                }
            }
            .then(clickable)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(Motion.enter(Motion.Quick, delay = 80)) + scaleIn(Motion.pop(), initialScale = 0.85f)) togetherWith
                    fadeOut(Motion.exit(Motion.Fast))
            },
            label = "morphPillContent",
        ) { current ->
            when (current) {
                MorphPhase.Idle -> Icon(
                    imageVector = idleIcon,
                    contentDescription = null,
                    tint = FableText,
                    modifier = Modifier.size(height * 0.5f),
                )
                MorphPhase.Done -> Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Icon(FableIcons.Check, contentDescription = null, tint = FableBlue, modifier = Modifier.size(14.dp))
                    PillLabel(label, FableTextDim)
                }
                else -> PillLabel(label, FableText, Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}

@Composable
private fun PillLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

private val PillHeight = 32.dp
private val WaitingWidth = 76.dp
private val ActiveWidth = 92.dp
private val DoneWidth = 112.dp
