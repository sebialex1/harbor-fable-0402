package io.harbor.fable.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.*

/**
 * The bottom glass dock — a floating frosted bar that holds tab navigation.
 * Manages tabs: Home, Containers, Drivers, Assets, Settings.
 */
@Composable
fun GlassDock(
    items: List<DockTab>,
    activeIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Dock background — frosted glass bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(DockRadius))
                .background(DockBg)
                .blur(32.dp)
        )
        // Specular gradient on dock surface
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(DockRadius))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.02f),
                        )
                    )
                )
        )
        // Border
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(DockRadius))
                .shadow(0.dp, RoundedCornerShape(DockRadius))
                .background(Color.Transparent)
        )
        // Items row
        Row(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(DockRadius))
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, tab ->
                GlassDockItem(
                    icon = tab.icon,
                    label = tab.label,
                    active = index == activeIndex,
                    onClick = { onTabSelected(index) },
                )
            }
        }
    }
}

data class DockTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)
