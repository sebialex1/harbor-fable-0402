package io.harbor.fable.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.harbor.fable.ui.theme.Motion
import kotlin.math.min

/**
 * Entrance helper: fades and rises [content] into place once, [index] * [staggerMs] after the
 * first composition. Use for hero screens where elements should arrive one after another.
 * For lazy lists use [rememberLiquidAppear] with [Modifier.liquidAppear] instead, which
 * survives scrolling and state restoration.
 */
@Composable
fun Staggered(
    index: Int,
    modifier: Modifier = Modifier,
    staggerMs: Int = 50,
    initialOffsetDp: Float = 8f,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, Motion.enter(duration = Motion.Entrance, delay = index * staggerMs))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * initialOffsetDp * density
        },
    ) {
        content()
    }
}

/**
 * One screen's entrance. [shown] flips to true right after the first frame, so every item
 * composed in that frame animates from hidden to visible; anything composed later (scrolled
 * into view, inserted by a data change) starts visible and leaves the motion to `animateItem`.
 * The flag is saved, so returning to a tab with restored state does not replay the entrance.
 */
@Stable
class LiquidAppearState internal constructor(shown: Boolean) {
    var shown by mutableStateOf(shown)
        internal set
}

/** Remembers the entrance state for a screen; call once at the top of the screen's content. */
@Composable
fun rememberLiquidAppear(): LiquidAppearState {
    var shown by rememberSaveable { mutableStateOf(false) }
    val state = remember { LiquidAppearState(shown) }
    LaunchedEffect(state) {
        shown = true
        state.shown = true
    }
    return state
}

/** How far apart consecutive items arrive, and the point past which they arrive together. */
private const val LiquidAppearStaggerMs = 30
private const val LiquidAppearMaxStagger = 6
private const val LiquidAppearRiseDp = 6f

/**
 * Fades and rises this element into place as part of [state]'s entrance, [index] steps after the
 * first. Composable so it can drive an `animateFloatAsState`; chain it after `animateItem()`.
 */
@Composable
fun Modifier.liquidAppear(state: LiquidAppearState, index: Int): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (state.shown) 1f else 0f,
        animationSpec = Motion.enter(
            duration = Motion.Entrance,
            delay = min(index, LiquidAppearMaxStagger) * LiquidAppearStaggerMs,
        ),
        label = "liquidAppear",
    )
    return if (progress >= 1f) {
        this
    } else {
        graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * LiquidAppearRiseDp * density
        }
    }
}
