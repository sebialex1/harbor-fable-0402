package io.harbor.fable.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One message in the top bar. [id] changes with every message, so the same text shown twice still
 * animates in again.
 */
@Immutable
data class FableNotice(
    val id: Long,
    val message: String,
    /** Stays until dismissed (dragged away or tapped); used for messages that must be read. */
    val indefinite: Boolean,
)

/**
 * App-level UI services, one per process (held by `FableApp`, provided by `FableRoot`).
 *
 * [scope] outlives individual screens, so work started from a screen that is about to be
 * popped (deleting a container, refreshing after a settings change) is not cancelled, and
 * messages stay on screen across navigation.
 *
 * [notice] is drawn by a single `NoticeHost` that `FableRoot` places above the NavHost (and above
 * the setup screen), never by the screens themselves. A per-screen host was recreated by every
 * navigation, so the same message slid in again on each screen change; with one host the pill
 * animates in once per message ([FableNotice.id]) and simply stays while the screens change
 * beneath it. Because this object lives as long as the process, an Activity recreation does not
 * replay it either. Tap to dismiss.
 */
@Stable
class FableUi(
    val scope: CoroutineScope,
) {
    /** The message the notice host is showing, or null. */
    var notice: FableNotice? by mutableStateOf(null)
        private set

    /**
     * True while the floating tab bar is on screen, so the notice host can sit above it. Set by
     * the main shell; the host animates between the two resting heights instead of re-entering.
     */
    var tabBarVisible: Boolean by mutableStateOf(false)

    private var nextId = 0L
    private var timeout: Job? = null

    /**
     * Shows a message in the top bar. [long] keeps it up longer; [indefinite] keeps it until
     * dismissed, for messages that must be read (install failures, say) and should not time out
     * underneath the user.
     */
    fun showMessage(message: String, long: Boolean = false, indefinite: Boolean = false) {
        scope.launch {
            val current = notice
            if (current != null && current.message == message) {
                // The same text again (a screen re-running its LaunchedEffect after navigation,
                // say) keeps the pill that is already up instead of sliding a new one in; only
                // its timeout is renewed.
                timeout?.cancel()
                val kept = if (indefinite && !current.indefinite) current.copy(indefinite = true) else current
                notice = kept
                if (!kept.indefinite) scheduleTimeout(kept, long)
                return@launch
            }
            timeout?.cancel()
            val shown = FableNotice(id = ++nextId, message = message, indefinite = indefinite)
            notice = shown
            if (!indefinite) scheduleTimeout(shown, long)
        }
    }

    private fun scheduleTimeout(shown: FableNotice, long: Boolean) {
        timeout = scope.launch {
            delay(if (long) LONG_MS else SHORT_MS)
            if (notice?.id == shown.id) notice = null
        }
    }

    /** Dismisses [id] if it is still the one showing (a newer message is left alone). */
    fun dismissNotice(id: Long) {
        if (notice?.id == id) {
            timeout?.cancel()
            notice = null
        }
    }

    private companion object {
        const val SHORT_MS = 4_000L
        const val LONG_MS = 10_000L
    }
}

val LocalFableUi = staticCompositionLocalOf<FableUi> {
    error("FableUi is provided by FableRoot")
}

/**
 * Extra bottom space a screen must reserve for floating chrome (the tab bar) on top of the
 * navigation-bar inset. `FableRoot` provides it per screen; [FableScreen] applies it.
 */
val LocalTabBarClearance = staticCompositionLocalOf<Dp> { 0.dp }
