package io.harbor.fable.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableSurfaceHigh
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.ScreenPadding
import io.harbor.fable.ui.theme.TabBarMetrics

/**
 * The floating pill bottom navigation: a fully rounded, opaque dark-grey capsule inset from the
 * screen edges and lifted off the system navigation bar. The active tab sits on a lighter grey
 * capsule that slides between tabs. Monochrome only: black canvas, grey pill, white active item.
 *
 * Screens reserve [TabBarMetrics.Clearance] plus the navigation-bar inset so their last item
 * stays visible above the pill.
 */
@Composable
fun FableTabBar(
    items: List<TabBarTab>,
    activeIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = ScreenPadding + Spacing4, vertical = TabBarMetrics.FloatGap),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .height(TabBarMetrics.Height)
                .solidSurface(pill, SurfaceLevel.Bar),
        ) {
            val count = items.size.coerceAtLeast(1)
            val inner = TabBarMetrics.IndicatorInset
            val slot = (maxWidth - inner * 2) / count
            val indicatorX by animateDpAsState(
                targetValue = inner + slot * activeIndex.coerceIn(0, count - 1),
                animationSpec = Motion.settle(),
                label = "tabIndicator",
            )
            if (activeIndex in items.indices) {
                Box(
                    Modifier
                        .offset(x = indicatorX)
                        .padding(vertical = inner)
                        .width(slot)
                        .fillMaxHeight()
                        .clip(pill)
                        .background(FableSurfaceHigh),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, tab ->
                    FableTabBarItem(
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
}

private val Spacing4 = 4.dp

data class TabBarTab(
    val label: String,
    val icon: ImageVector,
)
