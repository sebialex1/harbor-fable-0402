package io.harbor.fable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableDivider
import io.harbor.fable.ui.theme.HairlineStroke
import io.harbor.fable.ui.theme.TabBarMetrics

/**
 * The bottom tab bar holding the primary tabs: full width, a near-opaque black bar with a hairline
 * along its top edge, iOS style. It sits above the system navigation bar and items share the width
 * equally. Screens reserve [TabBarMetrics.Clearance] plus the navigation-bar inset so their last
 * item stays visible.
 */
@Composable
fun FableTabBar(
    items: List<TabBarTab>,
    activeIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SurfaceLevel.Bar.fill),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HairlineStroke)
                .background(FableDivider),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .height(TabBarMetrics.Height),
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

data class TabBarTab(
    val label: String,
    val icon: ImageVector,
)
