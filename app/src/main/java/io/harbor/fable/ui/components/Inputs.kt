package io.harbor.fable.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.ControlRadius
import io.harbor.fable.ui.theme.DialogRadius
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.HairlineStroke
import io.harbor.fable.ui.theme.FableControl
import io.harbor.fable.ui.theme.FableOutline
import io.harbor.fable.ui.theme.FableSurface
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.RowPaddingVertical
import io.harbor.fable.ui.theme.SheetRadius
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.launch
import io.harbor.fable.ui.icons.FableIcons

/** Outlined text field with the Fable palette. */
@Composable
fun FableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text, color = FableTextDim.copy(alpha = 0.6f)) } },
        supportingText = supportingText?.let { text -> { Text(text) } },
        isError = isError,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(ControlRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = FableText,
            unfocusedTextColor = FableText,
            focusedContainerColor = FableSurface,
            unfocusedContainerColor = FableSurface,
            focusedBorderColor = FableTextDim,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = FableTextDim,
            unfocusedLabelColor = FableTextDim,
            cursorColor = FableAccent,
            errorBorderColor = FableError,
            errorLabelColor = FableError,
            errorSupportingTextColor = FableError,
            focusedSupportingTextColor = FableTextDim,
            unfocusedSupportingTextColor = FableTextDim,
        ),
    )
}

/** One choice for [OptionSelector]. */
@Immutable
data class SelectOption<T>(
    val value: T,
    val label: String,
    val supporting: String? = null,
)

/**
 * Settings-style row showing the current choice; tapping it expands an inline list of
 * [options]. Works inside cards and bottom sheets alike (no popup).
 */
@Composable
fun <T> OptionSelector(
    label: String,
    options: List<SelectOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hint: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, Motion.inPlace(), label = "selectorChevron")
    val selectedLabel = options.firstOrNull { it.value == selected }?.label ?: selected?.toString() ?: "None"

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = FableTextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.md))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = FableTextDim,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FableIcons.ExpandMore,
                contentDescription = if (expanded) "Hide options" else "Show options",
                tint = FableTextDim,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                options.forEach { option ->
                    OptionRow(
                        option = option,
                        selected = option.value == selected,
                        onClick = {
                            onSelect(option.value)
                            expanded = false
                        },
                    )
                }
                if (!hint.isNullOrBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = RowPaddingHorizontal + Spacing.xxl, vertical = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> OptionRow(option: SelectOption<T>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = RowPaddingHorizontal + Spacing.xxl, end = RowPaddingHorizontal, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) FableText else FableTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!option.supporting.isNullOrBlank()) {
                Text(option.supporting, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        if (selected) {
            Icon(FableIcons.Check, contentDescription = "Selected", tint = FableText, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Modal sheet in the iOS manner: a grey [SurfaceLevel.Sheet] pane over the dimmed screen with a
 * hairline top edge, a title, optional subtitle and scrollable content. The sheet slides up with the system animation while its content
 * fades and rises into place a beat later, so the pane arrives first and its contents settle onto it.
 *
 * [content] receives `close`, which animates the sheet away and then runs its callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FableSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.(close: (after: () -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { after ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }
    val shape = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.enter(Motion.Standard, delay = 60)) }

    // The sheet's edge is drawn on the content that fills the pane, not through the
    // sheet modifier: Material offsets the sheet inside the node that modifier wraps, so anything
    // drawn there would land at the top of the screen. The drag handle moves inside for the same
    // reason, so the rim starts at the very top edge.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceLevel.Sheet.fill,
        contentColor = FableText,
        shape = shape,
        scrimColor = Color.Black.copy(alpha = 0.7f),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle(color = FableTextDim.copy(alpha = 0.35f))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = appear.value
                        translationY = (1f - appear.value) * 8.dp.toPx()
                    }
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                content(close)
            }
        }
    }
}

/** Two-button confirmation dialog. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val shape = RoundedCornerShape(DialogRadius)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(HairlineStroke, FableBorder, shape),
        containerColor = SurfaceLevel.Sheet.fill,
        titleContentColor = FableText,
        textContentColor = FableTextDim,
        shape = shape,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = FableText,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = FableTextDim)
            }
        },
    )
}
