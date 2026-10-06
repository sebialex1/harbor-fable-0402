package io.harbor.fable.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.*

/**
 * Glass bottom sheet — frosted overlay sheet for modal content.
 * Slides up from bottom with blur backdrop.
 */
@Composable
fun GlassBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            )
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeOut(),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // Dimmed backdrop
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clip(RoundedCornerShape(0.dp))
            )

            // Sheet container
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(12.dp)
            ) {
                // Frosted glass background
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(FableSurface.copy(alpha = 0.85f))
                        .blur(40.dp)
                )
                // Specular gradient
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.06f),
                                    Color.Transparent,
                                )
                            )
                        )
                )
                // Border
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Transparent)
                )
                // Content
                Column(
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .padding(24.dp),
                    content = content,
                )
            }
        }
    }
}
