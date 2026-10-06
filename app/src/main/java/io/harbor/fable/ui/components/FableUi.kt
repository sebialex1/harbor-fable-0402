package io.harbor.fable.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-level UI services provided by `FableRoot`.
 *
 * [scope] outlives individual screens, so work started from a screen that is about to be
 * popped (deleting a container, refreshing after a settings change) is not cancelled, and
 * messages stay on screen across navigation.
 */
@Stable
class FableUi(
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState,
) {
    /**
     * Shows a snackbar the user can always dismiss by hand (close icon, or tapping it).
     * [long] keeps it up longer; [indefinite] keeps it until dismissed, for messages that must
     * be read (install failures, say) and should not time out underneath the user.
     */
    fun showMessage(message: String, long: Boolean = false, indefinite: Boolean = false) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = when {
                    indefinite -> SnackbarDuration.Indefinite
                    long -> SnackbarDuration.Long
                    else -> SnackbarDuration.Short
                },
            )
        }
    }
}

val LocalFableUi = staticCompositionLocalOf<FableUi> {
    error("FableUi is provided by FableRoot")
}

/**
 * Extra bottom space a screen must reserve for floating chrome (the dock) on top of the
 * navigation-bar inset. `FableRoot` provides it per screen; [FableScreen] applies it.
 */
val LocalDockClearance = staticCompositionLocalOf<Dp> { 0.dp }
