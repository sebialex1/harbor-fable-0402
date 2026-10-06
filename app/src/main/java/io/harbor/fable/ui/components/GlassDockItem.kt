package io.harbor.fable.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.theme.*

/**
 * Glass dock item — frosted pill button with active glow.
 * Scales down on press, glows when active.
 */
@Composable
fun GlassDockItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dockScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(300),
        label = "dockGlow"
    )

    Box(
        modifier = modifier
            .height(64.dp)
            .width(72.dp)
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (active) FableAccent.copy(alpha = 0.15f * glowAlpha)
                else FableGlass
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // Glow ring behind icon when active
        if (glowAlpha > 0.01f) {
            Box(
                Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        shadowElevation = 12f * glowAlpha
                        alpha = glowAlpha
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(FableAccent.copy(alpha = 0.2f * glowAlpha))
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) FableAccent else DockItemIdle,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Medium
                             else androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (active) FableAccent else DockItemIdle,
                maxLines = 1,
            )
        }
    }
}
