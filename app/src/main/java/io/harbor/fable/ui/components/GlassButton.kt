package io.harbor.fable.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.ControlRadius
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.FableControl
import io.harbor.fable.ui.theme.FableControlBorder
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableText

/**
 * Glass button: a quiet translucent pill that settles on press.
 *
 * [primary] fills it with the accent colour (a light-to-deep gradient with a hairline of light on
 * the edge), [destructive] tints it red, and [compact] shrinks it for inline use. [icon] is drawn
 * before the label in the content colour.
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
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "glassButtonScale",
    )
    val shape = RoundedCornerShape(if (compact) 12.dp else ControlRadius)
    val container: Brush = when {
        primary -> Brush.verticalGradient(listOf(FableAccentLight, FableAccent))
        destructive -> Brush.verticalGradient(listOf(FableError.copy(alpha = 0.16f), FableError.copy(alpha = 0.16f)))
        else -> Brush.verticalGradient(listOf(FableControl, FableControl))
    }
    val contentColor = when {
        primary -> Color.White
        destructive -> FableError
        else -> FableText
    }
    val borderColor = when {
        primary -> Color.White.copy(alpha = 0.16f)
        destructive -> FableError.copy(alpha = 0.32f)
        else -> FableControlBorder
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .height(if (compact) 34.dp else 44.dp)
            .clip(shape)
            .background(container)
            .border(Dp.Hairline, borderColor, shape)
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
