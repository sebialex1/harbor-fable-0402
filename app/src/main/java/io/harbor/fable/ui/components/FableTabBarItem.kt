package io.harbor.fable.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.TabItemActive
import io.harbor.fable.ui.theme.TabItemIdle

private val TabIconSize = 22.dp
private val TabIconLabelGap = 3.dp

/**
 * The iOS tab-bar caption: 10sp Medium. The pill has a fixed height and five ~60dp slots, so the
 * caption ignores font scales above 1x (it would otherwise ellipsize "Containers" and push the
 * icon off centre); smaller scales still apply.
 */
@Composable
private fun tabLabelStyle(): TextStyle {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return MaterialTheme.typography.labelSmall.copy(
        fontSize = (10f / fontScale).sp,
        lineHeight = (12f / fontScale).sp,
        letterSpacing = 0.sp,
    )
}

/**
 * Tab bar item: icon over a small label, white when active and gray otherwise. The pill bar
 * draws the sliding grey capsule behind the active item; the item itself only changes tint.
 */
@Composable
fun FableTabBarItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (active) TabItemActive else TabItemIdle,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "tabTint",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics { selected = active }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TabIconSize),
        )
        Spacer(Modifier.height(TabIconLabelGap))
        Text(
            text = label,
            style = tabLabelStyle(),
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
