package io.harbor.fable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.DockBg
import io.harbor.fable.ui.theme.DockMetrics
import io.harbor.fable.ui.theme.DockRadius
import io.harbor.fable.ui.theme.FableGlassBorder

/**
 * The floating glass dock that holds the primary tabs.
 *
 * It sits above the system navigation bar (gesture handle or 3-button bar) and items share
 * the width equally, so it never clips on narrow screens. Screens reserve
 * [DockMetrics.Clearance] plus the navigation-bar inset so their last item stays visible.
 */
@Composable
fun GlassDock(
    items: List<DockTab>,
    activeIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(DockRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = DockMetrics.Margin),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(DockMetrics.Height)
                .clip(shape)
                .background(DockBg)
                .background(GlassSpecular)
                .border(1.dp, FableGlassBorder, shape)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, tab ->
                GlassDockItem(
                    icon = tab.icon,
                    label = tab.label,
                    active = index == activeIndex,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

data class DockTab(
    val label: String,
    val icon: ImageVector,
)
