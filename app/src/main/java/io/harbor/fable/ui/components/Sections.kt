package io.harbor.fable.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableDivider
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing

/**
 * Small uppercase label that starts a group of items. The extra top padding, combined
 * with the list's item spacing, separates groups visually.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, start = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** Accent text action placed at the end of a [SectionLabel] ("Expand all", "See all"). */
@Composable
fun SectionAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = FableAccent,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

/** Hairline divider between rows inside a card. */
@Composable
fun CardDivider(modifier: Modifier = Modifier, inset: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (inset) RowPaddingHorizontal else 0.dp)
            .height(1.dp)
            .background(FableDivider),
    )
}

/**
 * Glass card with a tappable header that expands/collapses its [content].
 *
 * [header] is laid out in a row before the rotating chevron; [headerOverlay] is drawn on
 * top of the header area (e.g. a thin progress bar along its bottom edge).
 */
@Composable
fun CollapsibleCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    headerOverlay: @Composable BoxScope.() -> Unit = {},
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(start = RowPaddingHorizontal, end = Spacing.md, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                header()
                Spacer(Modifier.width(Spacing.sm))
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = FableTextDim,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation),
                )
            }
            headerOverlay()
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                CardDivider()
                content()
            }
        }
    }
}

/**
 * Remembers which collapsible groups the user expanded or collapsed. Groups the user never
 * touched fall back to the default passed at read time, so new groups get sensible
 * defaults. Survives tab switches and configuration changes.
 */
@Stable
class ExpansionState internal constructor(initial: Map<String, Boolean>) {
    private val overrides = mutableStateMapOf<String, Boolean>().apply { putAll(initial) }

    fun isExpanded(key: String, default: Boolean): Boolean = overrides[key] ?: default

    fun toggle(key: String, default: Boolean) {
        overrides[key] = !isExpanded(key, default)
    }

    fun setAll(keys: Collection<String>, expanded: Boolean) {
        keys.forEach { overrides[it] = expanded }
    }

    companion object {
        val Saver: Saver<ExpansionState, Any> = listSaver(
            save = { state -> state.overrides.map { (key, value) -> (if (value) "1" else "0") + key } },
            restore = { saved ->
                ExpansionState(saved.associate { entry -> entry.drop(1) to entry.startsWith("1") })
            },
        )
    }
}

@Composable
fun rememberExpansionState(): ExpansionState =
    rememberSaveable(saver = ExpansionState.Saver) { ExpansionState(emptyMap()) }
