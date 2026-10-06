package io.harbor.fable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.Crossfade
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.ChipRadius
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableControl
import io.harbor.fable.ui.theme.FableControlBorder
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing

/** Rounded square with a tinted icon — the leading visual of list rows. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = FableAccent,
    size: Dp = 34.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(ChipRadius + 2.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** Trailing chevron that marks a row as navigable. */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = FableTextDim,
        modifier = modifier.size(20.dp),
    )
}

/**
 * Generic list row: optional icon tile, title + subtitle, optional trailing content and a
 * chevron when the row navigates somewhere. [titleBadge] renders inline after the title (a
 * "Latest" pill, say).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = FableAccent,
    titleColor: Color = FableText,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    titleBadge: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(clickModifier)
            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon = icon, tint = iconTint)
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f)) {
            if (titleBadge != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    titleBadge()
                }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(Spacing.xs))
            Chevron()
        }
    }
}

/**
 * Label/value row for read-only details and settings. With [stacked] the value goes on
 * its own line (for long values such as paths); [valueContent] replaces the value text with
 * custom content such as a status pill.
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color = FableTextDim,
    stacked: Boolean = false,
    onClick: (() -> Unit)? = null,
    valueContent: (@Composable () -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(clickModifier)
            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = FableTextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.md))
        }
        if (stacked) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.width(Spacing.md))
            if (valueContent != null) {
                Spacer(Modifier.weight(1f))
                valueContent()
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(Spacing.xs))
            Chevron()
        }
    }
}

/** Row with a trailing switch; the whole row toggles. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = FableTextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.scale(0.88f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = FableAccent,
                checkedBorderColor = FableAccent,
                uncheckedThumbColor = FableTextDim,
                uncheckedTrackColor = FableControl,
                uncheckedBorderColor = FableControlBorder,
            ),
        )
    }
}

/** Small rounded label: version badges, statuses, counts. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FableTextDim,
    containerColor: Color = color.copy(alpha = 0.14f),
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(ChipRadius))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Selectable glass chip (resolution presets, type filters). */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(ChipRadius + 2.dp))
            .background(if (selected) FableAccent.copy(alpha = 0.22f) else FableControl)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) FableText else FableTextDim,
            maxLines = 1,
        )
    }
}

/** Label and colour for a container's lifecycle state. */
internal fun containerStatusLabel(status: ContainerStatus): String = status.name.lowercase()

internal fun containerStatusColor(status: ContainerStatus): Color = when (status) {
    ContainerStatus.READY, ContainerStatus.RUNNING -> FableSuccess
    ContainerStatus.ERROR -> FableWarn
    else -> FableAccent
}

/** Container state as a pill. A change of state crossfades instead of snapping. */
@Composable
fun StatusPill(status: ContainerStatus, modifier: Modifier = Modifier) {
    Crossfade(targetState = status, label = "containerStatus", modifier = modifier) { current ->
        Pill(text = containerStatusLabel(current), color = containerStatusColor(current))
    }
}
