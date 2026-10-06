package io.harbor.fable.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.*

/**
 * Refraction glass surface — the core design element.
 * Layered translucent gradient + border blur + specular highlight.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius,
    blurRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        // Blur backdrop layer
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(FableGlass)
                .blur(blurRadius)
        )
        // Specular gradient overlay (top-left light refraction)
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.03f),
                        )
                    )
                )
        )
        // Border (frosted edge)
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .border(1.dp, FableGlassBorder, RoundedCornerShape(cornerRadius))
        )
        // Content
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(cornerRadius))
                .padding(0.dp),
            content = content,
        )
    }
}
