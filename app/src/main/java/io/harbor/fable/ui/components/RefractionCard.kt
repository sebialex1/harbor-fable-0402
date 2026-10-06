package io.harbor.fable.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.theme.*

/**
 * Refraction card — a frosted glass card with a subtle moving specular highlight.
 * Used for list items, containers, driver entries.
 */
@Composable
fun RefractionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "cardScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(GlassRadius))
            .background(FableGlass)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onClick() }
                } else Modifier
            ),
    ) {
        // Specular highlight gradient
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(GlassRadius))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.07f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.02f),
                        )
                    )
                )
        )
        // Border
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(GlassRadius))
                .background(Color.Transparent)
        )
        // Content
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(GlassRadius))
                .padding(0.dp),
            content = content,
        )
    }
}
