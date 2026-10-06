package io.harbor.fable.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.theme.ControlHeight
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.PillRadius

/**
 * Pill button. [primary] is solid white with black text (the one emphasis in a monochrome UI),
 * secondary is a neutral grey lift, [destructive] is grey with red text. [compact] shrinks it for
 * inline use. Pressing dims it slightly, like an iOS button, instead of a ripple or a glow.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.7f else 1f,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "glassButtonPress",
    )
    val shape = RoundedCornerShape(PillRadius)
    val fill = if (primary) FableAccent else GlassLevel.Control.fill
    val contentColor = when {
        primary -> Color.Black
        destructive -> FableError
        else -> FableText
    }

    Row(
        modifier = modifier
            .graphicsLayer { alpha = (if (enabled) 1f else 0.4f) * pressedAlpha }
            .height(if (compact) ControlHeight.Compact else ControlHeight.Regular)
            .glassSurface(shape = shape, level = GlassLevel.Control, fill = fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(if (compact) 15.dp else 18.dp),
            )
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = if (compact) MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp) else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
