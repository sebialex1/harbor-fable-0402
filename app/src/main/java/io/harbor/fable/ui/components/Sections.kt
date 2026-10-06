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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableDivider
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing

private val LabelTopPadding = 18.dp

/** Size of the leading [IconTile] in list rows. */
internal val RowIconSize = 30.dp

/**
 * iOS-style grouped-list header: small uppercase grey text aligned with the row titles below.
 * The group's rows share one section card under it. The top padding, together with the list's
 * item spacing, separates groups.
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
            .padding(top = LabelTopPadding, start = RowPaddingHorizontal, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
            color = FableTextDim,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** Text action placed at the end of a [SectionLabel] ("See all"). */
@Composable
fun SectionAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = FableText,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

/**
 * Hairline separator between rows inside a section. Like iOS it starts at the row text and runs
 * to the trailing edge; [inset] off makes it full width, [afterIcon] starts it past a row's
 * leading [IconTile] so it lines up with the titles.
 */
@Composable
fun CardDivider(modifier: Modifier = Modifier, inset: Boolean = true, afterIcon: Boolean = false) {
    val start = when {
        afterIcon -> RowPaddingHorizontal + RowIconSize + Spacing.md
        inset -> RowPaddingHorizontal
        else -> 0.dp
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(0.5.dp)
            .background(FableDivider),
    )
}

/**
 * One section for a collapsible group: the header row (title, optional [badge] such as a
 * count, rotating chevron) is the top of the section and toggles it; the rows flow directly below
 * a hairline inside the same section. There is no card inside a card and no floating label, so a
 * collapsed group is one quiet row and an expanded one is a single continuous surface.
 */
@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, Motion.inPlace(), label = "sectionChevron")
    GlassCard(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = 44.dp)
                .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = FableText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            badge?.invoke()
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = FableTextDim,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
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
