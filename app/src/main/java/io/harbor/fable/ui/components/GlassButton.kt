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
import io.harbor.fable.ui.theme.ControlHeight
import io.harbor.fable.ui.theme.ControlRadius
import io.harbor.fable.ui.theme.ControlRadiusCompact
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.Motion

/**
 * Glass button: a quiet translucent pill that settles on press and catches a little more light
 * while it is held, instead of a ripple.
 *
 * [primary] fills it with the accent (white light across the top makes the light-to-deep gradient),
 * [destructive] tints it red, and [compact] shrinks it for inline use. [icon] is drawn before the
 * label in the content colour.
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
    val pressedNow = pressed && enabled
    val scale by animateFloatAsState(
        targetValue = if (pressedNow) 0.965f else 1f,
        animationSpec = Motion.press(),
        label = "glassButtonScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (pressedNow) 1f else 0f,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "glassButtonLift",
    )
    val shape = RoundedCornerShape(if (compact) ControlRadiusCompact else ControlRadius)
    val fill = when {
        primary -> FableAccent
        destructive -> FableError.copy(alpha = 0.16f)
        else -> GlassLevel.Control.fill
    }
    val contentColor = when {
        primary -> Color.White
        destructive -> FableError
        else -> FableText
    }
    val sheen = when {
        primary -> 0.26f
        destructive -> 0.05f
        else -> GlassLevel.Control.sheen
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .height(if (compact) ControlHeight.Compact else ControlHeight.Regular)
            .glassSurface(
                shape = shape,
                level = GlassLevel.Control,
                fill = fill,
                sheenColor = if (destructive) FableError else Color.White,
                sheenAlpha = sheen + 0.08f * lift,
                rimColor = if (destructive) FableError else Color.White,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 12.dp else 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(if (compact) 15.dp else 17.dp),
            )
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
