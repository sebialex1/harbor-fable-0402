package io.harbor.fable.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.DockItemActive
import io.harbor.fable.ui.theme.DockItemIdle
import io.harbor.fable.ui.theme.Motion

/**
 * Tab bar item: icon and label, white when active and grey otherwise, on a faint neutral
 * highlight; pressing settles the whole item a touch. Width comes from the caller (the dock gives every
 * item an equal weight).
 */
@Composable
fun GlassDockItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.press(),
        label = "dockPress",
    )
    val highlight by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = Motion.inPlace(),
        label = "dockHighlight",
    )
    val tint by animateColorAsState(
        targetValue = if (active) DockItemActive else DockItemIdle,
        animationSpec = Motion.inPlace(),
        label = "dockTint",
    )

    Column(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f * highlight))
            .semantics { selected = active }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
