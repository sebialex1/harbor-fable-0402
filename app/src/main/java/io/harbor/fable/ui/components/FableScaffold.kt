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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dividerAlpha by animateFloatAsState(if (showDivider) 1f else 0f, Motion.inPlace(), label = "topBarDivider")
    val titleAlpha by animateFloatAsState(if (showInlineTitle) 1f else 0f, Motion.inPlace(Motion.Fast), label = "topBarTitle")
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
                    .graphicsLayer { alpha = titleAlpha },
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
                .graphicsLayer { alpha = dividerAlpha }
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
    val largeTitleGone = with(density) { LargeTitleCollapse.toPx() }
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val pastLargeTitle by remember(listState, largeTitleGone) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > largeTitleGone }
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
            FableTopBar(
                title = title,
                onBack = onBack,
                showDivider = scrolled,
                showInlineTitle = !largeTitle || pastLargeTitle,
                actions = actions,
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
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!subtitle.isNullOrBlank()) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
                content()
            }
        }
    }
}

/** How far the large title scrolls before the inline title takes over. */
private val LargeTitleCollapse = 30.dp

private const val LargeTitleKey = "fable-large-title"
