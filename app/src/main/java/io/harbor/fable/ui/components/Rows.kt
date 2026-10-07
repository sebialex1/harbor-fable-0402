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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.ChipRadius
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.FableControl
import io.harbor.fable.ui.theme.FableControlBorder
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.PillRadius
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.Spacing
import io.harbor.fable.ui.theme.TileTone
import io.harbor.fable.ui.theme.FableBlue
import io.harbor.fable.ui.icons.FableIcons

/**
 * Leading visual of list rows: a small rounded square with a white glyph, on a graphite gradient
 * with a glossy top and a hairline rim (the neutral [TileTone.Graphite]), so even untoned rows
 * have depth. Rows that deserve an identity use [ToneIconTile]. [tint] colours the glyph only.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = FableText,
    size: Dp = RowIconSize,
) {
    Box(
        modifier
            .size(size)
            .gradientTile(
                shape = RoundedCornerShape(ChipRadius),
                start = TileTone.Graphite.start,
                end = TileTone.Graphite.end,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.6f))
    }
}

/** Trailing chevron that marks a row as navigable. */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = FableIcons.Chevron,
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
    iconTint: Color = FableText,
    titleColor: Color = FableText,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    titleBadge: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
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
        if (leading != null) {
            // Custom leading tile (an app icon); same footprint as the icon tile.
            leading()
            Spacer(Modifier.width(Spacing.md))
        } else if (icon != null) {
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
                        maxLines = titleMaxLines,
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
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = subtitleMaxLines,
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
            .heightIn(min = 44.dp)
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
            modifier = Modifier.scale(0.85f),
            colors = SwitchDefaults.colors(
                // Blue when on, like the display menu's switches and every progress fill.
                checkedThumbColor = Color.White,
                checkedTrackColor = FableBlue,
                checkedBorderColor = FableBlue,
                uncheckedThumbColor = FableTextDim,
                uncheckedTrackColor = FableControl,
                uncheckedBorderColor = FableControl,
            ),
        )
    }
}

/** Small grey tag: version badges, counts. Monochrome; the text carries the meaning. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FableTextDim,
    containerColor: Color = FableSurfaceRaised,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(PillRadius))
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

/**
 * Selectable chip (variant pickers, presets): grey when idle, white with black text when
 * selected, like an iOS segmented control segment.
 */
@Composable
fun FableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PillRadius)
    val fill by animateColorAsState(
        targetValue = if (selected) FableAccent else FableSurfaceRaised,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "chipFill",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Color.Black else FableText,
        animationSpec = Motion.inPlace(Motion.Fast),
        label = "chipText",
    )
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
        )
    }
}

/** Label and colour for a container's lifecycle state. */
internal fun containerStatusLabel(status: ContainerStatus): String = status.name.lowercase()

internal fun containerStatusColor(status: ContainerStatus): Color = when (status) {
    ContainerStatus.RUNNING -> FableText
    ContainerStatus.ERROR -> FableError
    else -> FableTextDim
}

/**
 * Container state, shown only when it needs attention (an error). A healthy container shows
 * nothing; a change of state crossfades instead of snapping.
 */
@Composable
fun StatusPill(status: ContainerStatus, modifier: Modifier = Modifier) {
    Crossfade(targetState = status, label = "containerStatus", modifier = modifier) { current ->
        if (current == ContainerStatus.ERROR) {
            Text(
                text = containerStatusLabel(current).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = containerStatusColor(current),
                maxLines = 1,
            )
        }
    }
}
