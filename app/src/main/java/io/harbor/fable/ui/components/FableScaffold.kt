package io.harbor.fable.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.harbor.fable.ui.icons.FableIcons
import io.harbor.fable.ui.theme.BarGlassBottom
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableBlue
import io.harbor.fable.ui.theme.FableDivider
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.NotifyWash
import io.harbor.fable.ui.theme.ScreenPadding
import io.harbor.fable.ui.theme.Spacing
import io.harbor.fable.ui.theme.notificationEnter
import io.harbor.fable.ui.theme.notificationExit
import kotlinx.coroutines.flow.filter
import kotlin.math.abs

/**
 * iOS-style navigation bar used by every screen.
 *
 * Tab screens show their title large at the top of the list (see [FableScreen]); the bar then
 * carries only the actions, and a compact centred title takes over once the large one scrolls
 * away ([showInlineTitle]). Pushed screens pass [onBack] and always show the inline title.
 *
 * The bar is glass: at rest it is the black canvas itself, and as content scrolls beneath it a
 * translucent [glassSurface] fades in (with a hairline under it), so rows stay faintly visible
 * through the chrome. The back button and the action buttons are frosted glass discs
 * ([LocalGlassControls]). Messages from [FableUi.showMessage] appear just under the bar
 * ([TopBarNotice]) unless [showNotice] is false.
 *
 * [drawGlass] is false when the caller ([FableScreen]) draws one glass layer behind the bar and
 * a pinned header together.
 */
@Composable
fun FableTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showDivider: Boolean = false,
    showInlineTitle: Boolean = true,
    collapseFraction: (() -> Float)? = null,
    drawGlass: Boolean = true,
    showNotice: Boolean = true,
    titleMorph: TitleMorph? = null,
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

    Column(modifier.fillMaxWidth()) {
        GlassChrome(
            glassAlpha = if (drawGlass) dividerAlpha else ({ 0f }),
            dividerAlpha = if (drawGlass) dividerAlpha else ({ 0f }),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = TopBarHeight)
                    .padding(horizontal = ScreenPadding, vertical = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    onTextLayout = { layout -> titleMorph?.inlineTextWidth = layout.lineWidth() },
                    modifier = Modifier
                        .padding(horizontal = 96.dp)
                        // Measured before the layer below, so the morph sees the resting spot.
                        .onPlaced { titleMorph?.inline = it }
                        .graphicsLayer {
                            val f = collapseFraction?.invoke()
                            val travel = if (f != null) titleMorph?.travel(f) else null
                            if (f != null && travel != null) {
                                // One title travels: from the large title's spot in the list
                                // (scaled up to its size) up and across into the bar, tracking
                                // the scroll, instead of two titles cross-fading.
                                alpha = if (f > 0f) 1f else 0f
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = travel.scale
                                scaleY = travel.scale
                                translationX = travel.dx
                                translationY = travel.dy
                            } else {
                                val a = titleAlpha()
                                alpha = a
                                // Fallback before the large title is measured: slides up from
                                // below the bar as it fades in.
                                translationY = (1f - a) * titleShiftPx
                            }
                        },
                )
                CompositionLocalProvider(LocalGlassControls provides true) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            FableIconButton(
                                icon = FableIcons.Back,
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
            }
        }
        if (showNotice) TopBarNotice()
    }
}

/**
 * The bar's material: a [glassSurface] layer whose opacity follows [glassAlpha] (0 at rest, so
 * the bar is the canvas; 1 once content is beneath it) and a hairline along the bottom edge.
 */
@Composable
private fun GlassChrome(
    glassAlpha: () -> Float,
    dividerAlpha: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Rows scrolled beneath the bar must not be tappable through it.
    Box(modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = glassAlpha() }
                .glassSurface(shape = RectangleShape, fill = BarGlassBottom, border = null, blurRadius = 32),
        )
        content()
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.5.dp)
                .graphicsLayer { alpha = dividerAlpha() }
                .background(FableDivider),
        )
    }
}

/**
 * The top bar's message line: [FableUi.notice] fades and slides down out of the bar in
 * [FableBlue], with no card behind it — only a soft wash continuing the bar's glass so the text
 * stays legible over rows, and a thin blue glint that grows out from the bar's centre.
 *
 * Drag it up (or tap it) to dismiss; a short drag springs back. The space it takes morphs
 * open and closed with the text, so nothing below jumps.
 */
@Composable
fun TopBarNotice(modifier: Modifier = Modifier) {
    val ui = LocalFableUi.current
    AnimatedContent(
        targetState = ui.notice,
        transitionSpec = {
            notificationEnter<FableNotice?>()(this) togetherWith notificationExit<FableNotice?>()(this) using
                SizeTransform(clip = false) { _, _ -> Motion.morph() }
        },
        contentKey = { it?.id },
        label = "topBarNotice",
        modifier = modifier.fillMaxWidth(),
    ) { notice ->
        if (notice == null) {
            Spacer(Modifier.fillMaxWidth().height(0.dp))
        } else {
            NoticeLine(notice = notice, onDismiss = { ui.dismissNotice(notice.id) })
        }
    }
}

@Composable
private fun NoticeLine(notice: FableNotice, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val dismissPx = with(density) { NoticeDismissDistance.toPx() }
    val stretchPx = with(density) { NoticeStretch.toPx() }
    var offset by remember(notice.id) { mutableFloatStateOf(0f) }
    // The glint under the bar grows from the centre as the text arrives.
    val glint = remember(notice.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(notice.id) { glint.animateTo(1f, Motion.morph(Motion.Slow)) }

    val dragState = rememberDraggableState { delta ->
        // Upwards follows the finger; downwards gives a little, like a rubber band.
        val next = offset + if (offset + delta > 0f) delta * 0.25f else delta
        offset = next.coerceIn(-dismissPx * 1.5f, stretchPx)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = offset
                alpha = (1f - abs(offset.coerceAtMost(0f)) / dismissPx).coerceIn(0f, 1f)
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (offset < -dismissPx * 0.35f || velocity < -NoticeFlingVelocity) {
                        animate(offset, -dismissPx * 1.5f, animationSpec = Motion.slideOutToTop()) { v, _ -> offset = v }
                        onDismiss()
                    } else {
                        animate(offset, 0f, animationSpec = Motion.dragSettle()) { v, _ -> offset = v }
                    }
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .drawBehind {
                // Continues the bar's glass downwards and dissolves into the content.
                drawRect(Brush.verticalGradient(listOf(Color(0xD9000000), NotifyWash, Color.Transparent)))
                val w = size.width * 0.55f * glint.value
                if (w > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, FableBlue.copy(alpha = 0.9f), Color.Transparent),
                            startX = (size.width - w) / 2f,
                            endX = (size.width + w) / 2f,
                        ),
                        topLeft = Offset((size.width - w) / 2f, 0f),
                        size = Size(w, 1.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = ScreenPadding * 2, vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = notice.message,
            style = MaterialTheme.typography.bodyMedium,
            color = FableBlue,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Standard screen layout: the black canvas, a glass [FableTopBar] and a lazy list with 16dp
 * margins that scrolls *beneath* the bar, so the chrome has something to frost.
 *
 * Tab screens (no [onBack]) open with a large title as the first list item, which scrolls away
 * under the bar while the inline title takes over, as in iOS. [subtitle], when given, sits under
 * the large title in grey.
 *
 * Bottom padding always includes the navigation-bar inset plus [LocalTabBarClearance], so
 * the last item is never hidden behind the floating tab bar. Items are spaced by [Spacing.sm];
 * use `SectionLabel` to start a new group. Give items a stable `key` and apply
 * `Modifier.animateItem()` so insertions, removals and reordering animate.
 *
 * [header] is pinned under the bar on the same glass (a tab row); it draws its own bottom edge,
 * so the bar's scroll hairline stays off.
 */
@Composable
fun FableScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
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
    // Glass for pushed screens fades in on the first scroll; tab screens follow the collapse.
    val animatedGlass by animateFloatAsState(if (scrolled) 1f else 0f, Motion.inPlace(), label = "chromeGlass")
    val glassAlpha: () -> Float = if (largeTitle) collapse else ({ animatedGlass })

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

    // Geometry shared by the large title and the bar title, for the slide hand-over.
    val titleMorph = remember { TitleMorph() }

    // Height of the bar (+ header) the list scrolls beneath; estimated until first measured.
    var chromePx by remember { mutableIntStateOf(0) }
    val chromeHeight = if (chromePx > 0) {
        with(density) { chromePx.toDp() }
    } else {
        statusTop + TopBarHeight + Spacing.sm * 2 + if (header != null) HeaderEstimate else 0.dp
    }

    Box(
        modifier
            .fillMaxSize()
            .background(FableBg),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .onPlaced { titleMorph.screen = it },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenPadding,
                    end = ScreenPadding,
                    // Breathing room so the first section never sits glued to the bar.
                    top = chromeHeight + if (largeTitle) 0.dp else Spacing.md,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (largeTitle || !subtitle.isNullOrBlank()) {
                    item(key = LargeTitleKey) {
                        LargeTitleBlock(
                            title = title,
                            subtitle = subtitle,
                            largeTitle = largeTitle,
                            collapse = collapse,
                            collapsePx = collapsePx,
                            showRowActions = !pastLargeTitle,
                            actions = actions,
                            titleMorph = titleMorph,
                        )
                    }
                }
                content()
            }

            // Bar + pinned header on one sheet of glass, then the message line under both.
            Column(Modifier.fillMaxWidth()) {
                // Tab screens carry their actions on the large-title row, vertically centred on
                // the title, so the + lines up with "Containers" instead of floating above it.
                // Once the large title scrolls away the actions move up into the compact bar.
                val actionsInBar = !largeTitle || pastLargeTitle
                GlassChrome(
                    glassAlpha = glassAlpha,
                    dividerAlpha = if (header == null) glassAlpha else ({ 0f }),
                    modifier = Modifier.onSizeChanged { chromePx = it.height },
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        FableTopBar(
                            title = title,
                            onBack = onBack,
                            showInlineTitle = actionsInBar,
                            collapseFraction = if (largeTitle) collapse else null,
                            drawGlass = false,
                            showNotice = false,
                            titleMorph = if (largeTitle) titleMorph else null,
                            actions = if (actionsInBar) actions else ({}),
                        )
                        header?.invoke()
                    }
                }
                TopBarNotice()
            }
        }
    }
}

/** The large title (and subtitle) that opens a tab screen's list, with its own action row. */
@Composable
private fun LargeTitleBlock(
    title: String,
    subtitle: String?,
    largeTitle: Boolean,
    collapse: () -> Float,
    collapsePx: Float,
    showRowActions: Boolean,
    actions: @Composable RowScope.() -> Unit,
    titleMorph: TitleMorph,
) {
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
                    onTextLayout = { layout -> titleMorph.largeTextWidth = layout.lineWidth() },
                    modifier = Modifier
                        .weight(1f)
                        .onPlaced { titleMorph.large = it }
                        .graphicsLayer {
                            val f = collapse()
                            if (titleMorph.travel(f) != null) {
                                // The bar title takes over from the first pixel of scroll and
                                // carries the hand-over itself; this copy only shows at rest.
                                alpha = if (f > 0f) 0f else 1f
                            } else {
                                // Fallback: shrinks from its leading edge, trails the scroll a
                                // little (parallax) and fades out as the inline title fades in.
                                transformOrigin = LargeTitleOrigin
                                val scale = 1f - LargeTitleShrink * f
                                scaleX = scale
                                scaleY = scale
                                translationY = f * collapsePx * LargeTitleParallax
                                alpha = largeTitleAlpha(f)
                            }
                        },
                )
                if (showRowActions) {
                    CompositionLocalProvider(LocalGlassControls provides true) {
                        Row(
                            modifier = Modifier.graphicsLayer { alpha = rowActionsAlpha(collapse()) },
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
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

/**
 * Where the large title and the bar title sit, so the bar title can travel between the two.
 * Plain fields, written during layout (onPlaced / onTextLayout) and read in the draw phase of
 * the same frame, so the slide tracks the scroll with no lag and no recomposition.
 *
 * Positions are taken relative to the screen ([screen]) rather than the window, so navigation
 * transitions (which translate the whole screen) don't skew them.
 */
class TitleMorph internal constructor() {
    internal var screen: LayoutCoordinates? = null
    internal var large: LayoutCoordinates? = null
    internal var inline: LayoutCoordinates? = null
    internal var largeTextWidth = 0f
    internal var inlineTextWidth = 0f

    /** Offset and scale for the bar title at collapse [f]; null until both titles are measured. */
    internal fun travel(f: Float): Travel? {
        val p = f.coerceIn(0f, 1f)
        if (p >= 1f) return Travel(0f, 0f, 1f)
        val root = screen?.takeIf { it.isAttached } ?: return null
        val from = large?.takeIf { it.isAttached } ?: return null
        val to = inline?.takeIf { it.isAttached } ?: return null
        if (largeTextWidth <= 0f || inlineTextWidth <= 0f) return null
        // The large title's live position: it scrolls with the list.
        val start = root.localPositionOf(from, Offset.Zero)
        val end = root.localPositionOf(to, Offset.Zero)
        val ratio = (largeTextWidth / inlineTextWidth).coerceIn(1f, 3f)
        val q = 1f - p
        return Travel(
            dx = q * (start.x - end.x),
            dy = q * (start.y - end.y),
            scale = 1f + (ratio - 1f) * q,
        )
    }

    internal class Travel(val dx: Float, val dy: Float, val scale: Float)
}

/** Width of the first (only) line of a single-line title. */
private fun TextLayoutResult.lineWidth(): Float =
    if (lineCount > 0) getLineRight(0) - getLineLeft(0) else 0f

/** Height of the bar's content row (title and icon buttons), above the status bar. */
private val TopBarHeight = 52.dp

/** First-frame guess for a pinned header (a tab row) before it has been measured. */
private val HeaderEstimate = 48.dp

/** How far a notice must be dragged up before it is dismissed; past it, it fades fully. */
private val NoticeDismissDistance = 56.dp

/** How far a notice gives when pulled down. */
private val NoticeStretch = 16.dp

/** Upward fling speed (px/s) that dismisses a notice regardless of distance. */
private const val NoticeFlingVelocity = 900f

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
