package io.harbor.fable.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableDivider
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.ScreenPadding
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.flow.filter

/**
 * iOS-style navigation bar used by every screen.
 *
 * Tab screens show their title large at the top of the list (see [FableScreen]); the bar then
 * carries only the actions, and a compact centred title fades in once the large one scrolls
 * away ([showInlineTitle]). Pushed screens pass [onBack] and always show the inline title. The
 * bar is the black canvas itself; a hairline appears under it once content scrolls beneath.
 */
@Composable
fun FableTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showDivider: Boolean = false,
    showInlineTitle: Boolean = true,
    collapseFraction: (() -> Float)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val animatedDivider by animateFloatAsState(if (showDivider) 1f else 0f, Motion.inPlace(), label = "topBarDivider")
    val animatedTitle by animateFloatAsState(if (showInlineTitle) 1f else 0f, Motion.inPlace(Motion.Fast), label = "topBarTitle")
    // With a collapse fraction the inline title, divider and actions follow the scroll position
    // frame by frame (read in the draw phase only); without one they animate on their flags.
    val titleAlpha: () -> Float = collapseFraction?.let { f -> { inlineTitleAlpha(f()) } } ?: { animatedTitle }
    val dividerAlpha: () -> Float = collapseFraction?.let { f -> { f() } } ?: { animatedDivider }
    val actionsAlpha: () -> Float = collapseFraction?.let { f -> { barActionsAlpha(f()) } } ?: { 1f }
    val titleShiftPx = with(LocalDensity.current) { InlineTitleShift.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .background(FableBg)
            .statusBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = ScreenPadding, vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 96.dp)
                    .graphicsLayer {
                        val a = titleAlpha()
                        alpha = a
                        // Rises into place as it fades in, like the iOS hand-over.
                        translationY = (1f - a) * titleShiftPx
                    },
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    FableIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.graphicsLayer { alpha = actionsAlpha() },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .graphicsLayer { alpha = dividerAlpha() }
                .background(FableDivider),
        )
    }
}

/**
 * Standard screen layout: the black canvas, [FableTopBar] and a lazy list with 16dp margins.
 *
 * Tab screens (no [onBack]) open with a large title as the first list item, which scrolls away
 * under the bar while the inline title fades in, as in iOS. [subtitle], when given, sits under
 * the large title in grey.
 *
 * Bottom padding always includes the navigation-bar inset plus [LocalTabBarClearance], so
 * the last item is never hidden behind the floating tab bar. Items are spaced by [Spacing.sm];
 * use `SectionLabel` to start a new group. Give items a stable `key` and apply
 * `Modifier.animateItem()` so insertions, removals and reordering animate.
 */
@Composable
fun FableScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = LocalTabBarClearance.current + navigationBottom + Spacing.lg
    val largeTitle = onBack == null
    val density = LocalDensity.current
    val collapsePx = with(density) { LargeTitleCollapse.toPx() }
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    // 0 with the large title fully shown, 1 once it has scrolled under the bar. Read only inside
    // graphicsLayer blocks, so scrolling redraws without recomposing the screen.
    val collapse: () -> Float = remember(listState, collapsePx) {
        {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / collapsePx).coerceIn(0f, 1f)
            }
        }
    }
    // The actions hop between the large-title row and the bar halfway through, where both
    // copies are fully transparent, so the move is invisible.
    val pastLargeTitle by remember(listState, collapsePx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= collapsePx * ActionsHandOver
        }
    }

    // Like iOS, a scroll that stops part-way through the collapse settles to whichever end is
    // closer, so the screen never rests with a half-faded title.
    if (largeTitle) {
        LaunchedEffect(listState, collapsePx) {
            // Where our own settle ended; a short list may not be able to scroll all the way, and
            // must not be nudged again every time that settle finishes.
            var settledAt = -1
            snapshotFlow { listState.isScrollInProgress }
                .filter { inProgress -> !inProgress }
                .collect {
                    val offset = listState.firstVisibleItemScrollOffset
                    if (listState.firstVisibleItemIndex == 0 && offset > 0 && offset < collapsePx && offset != settledAt) {
                        val target = if (offset < collapsePx / 2f) -offset.toFloat() else collapsePx - offset
                        listState.animateScrollBy(target, Motion.inPlace(Motion.Quick))
                        settledAt = listState.firstVisibleItemScrollOffset
                    }
                }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(FableBg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            // Tab screens carry their actions on the large-title row, vertically centred on the
            // title, so the + lines up with "Containers" instead of floating above it. Once the
            // large title scrolls away the actions move up into the compact bar.
            val actionsInBar = !largeTitle || pastLargeTitle
            FableTopBar(
                title = title,
                onBack = onBack,
                showDivider = scrolled,
                showInlineTitle = actionsInBar,
                collapseFraction = if (largeTitle) collapse else null,
                actions = if (actionsInBar) actions else ({}),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = ScreenPadding,
                    end = ScreenPadding,
                    // Breathing room so the first section never sits glued to the bar.
                    top = if (largeTitle) 0.dp else Spacing.md,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (largeTitle || !subtitle.isNullOrBlank()) {
                    item(key = LargeTitleKey) {
                        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
                            if (largeTitle) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = LargeTitleRowHeight),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.headlineLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .graphicsLayer {
                                                val f = collapse()
                                                // Shrinks from its leading edge, trails the scroll
                                                // a little (parallax) and fades out as the inline
                                                // title fades in.
                                                transformOrigin = LargeTitleOrigin
                                                val scale = 1f - LargeTitleShrink * f
                                                scaleX = scale
                                                scaleY = scale
                                                translationY = f * collapsePx * LargeTitleParallax
                                                alpha = largeTitleAlpha(f)
                                            },
                                    )
                                    if (!pastLargeTitle) {
                                        Row(
                                            modifier = Modifier.graphicsLayer { alpha = rowActionsAlpha(collapse()) },
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                            verticalAlignment = Alignment.CenterVertically,
                                            content = actions,
                                        )
                                    }
                                }
                            }
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    modifier = if (largeTitle) {
                                        Modifier.graphicsLayer { alpha = largeTitleAlpha(collapse()) }
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }
                }
                content()
            }
        }
    }
}

/** Minimum height of the large-title row, so title and icon buttons share one centre line. */
private val LargeTitleRowHeight = 44.dp

/** Scroll distance over which the large title hands over to the inline one (its row height). */
private val LargeTitleCollapse = LargeTitleRowHeight

/** Collapse fraction at which the actions move from the large-title row into the bar. */
private const val ActionsHandOver = 0.5f

/** The large title ends 12% smaller, scaled about its leading edge like iOS. */
private const val LargeTitleShrink = 0.12f
private val LargeTitleOrigin = TransformOrigin(0f, 0.5f)

/** Share of the scroll the large title gives back, so it lags the list slightly. */
private const val LargeTitleParallax = 0.35f

/** How far the inline title rises while it fades in. */
private val InlineTitleShift = 6.dp

/** Large title: gone by 70% of the collapse so it never overlaps the incoming inline title. */
private fun largeTitleAlpha(f: Float): Float = (1f - f / 0.7f).coerceIn(0f, 1f)

/** Inline title: starts at 40% and is fully in once the large title has gone under the bar. */
private fun inlineTitleAlpha(f: Float): Float = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f)

/** Actions on the large-title row fade out over the first half of the collapse… */
private fun rowActionsAlpha(f: Float): Float = (1f - f / ActionsHandOver).coerceIn(0f, 1f)

/** …and the bar copy fades in over the second half. */
private fun barActionsAlpha(f: Float): Float = ((f - ActionsHandOver) / (1f - ActionsHandOver)).coerceIn(0f, 1f)

private const val LargeTitleKey = "fable-large-title"
