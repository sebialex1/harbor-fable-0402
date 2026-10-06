package io.harbor.fable.ui.components

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
import androidx.compose.ui.draw.shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableGlassDeep
import io.harbor.fable.ui.theme.FableGlassShadow
import io.harbor.fable.ui.theme.FableGlassShadowSpot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.DockMetrics
import io.harbor.fable.ui.theme.DockRadius

/**
 * The floating glass dock that holds the primary tabs.
 *
 * It sits above the system navigation bar (gesture handle or 3-button bar) and items share
 * the width equally, so it never clips on narrow screens. It is the one real piece of glass in
 * the app: the content scrolling underneath is blurred through it (see [DockMaterial]), a soft
 * shadow lifts it and a hairline marks its edge. Screens reserve [DockMetrics.Clearance] plus the navigation-bar inset
 * so their last item stays visible.
 */
@Composable
fun GlassDock(
    hazeState: HazeState,
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
            .padding(horizontal = 16.dp, vertical = DockMetrics.Margin),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(DockMetrics.Height)
                // A real material: the screen behind is blurred and darkened, not just tinted.
                .shadow(
                    elevation = GlassLevel.Floating.shadow,
                    shape = shape,
                    clip = false,
                    ambientColor = FableGlassShadow,
                    spotColor = FableGlassShadowSpot,
                )
                .clip(shape)
                .hazeChild(state = hazeState, style = DockMaterial)
                .glassRim(shape, GlassLevel.Floating)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
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

/**
 * The tab bar material, after the iOS dark "regular" material: content behind it blurred by
 * 24dp under a neutral dark tint. Where blur is unavailable (below API 31) Haze draws the opaque
 * fallback tint instead, so the bar never shows unblurred content through it.
 */
private val DockMaterial = HazeStyle(
    backgroundColor = FableBg,
    tints = listOf(HazeTint(FableGlassDeep)),
    blurRadius = 24.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(FableGlassDeep.copy(alpha = 0.96f)),
)

data class DockTab(
    val label: String,
    val icon: ImageVector,
)
