package io.harbor.fable.display

import io.harbor.fable.data.WindowsProcess

/** The pages of the in-container side menu: a compact [ROOT] whose entries open the others. */
enum class MenuPage(val title: String) {
    ROOT("Menu"),
    OVERLAYS("Overlays"),
    PRESETS("Control preset"),
    WINE("Wine"),
    TASKS("Tasks"),
}

/**
 * Which [MenuPage] the side menu shows. Every sub-page sits one level under [MenuPage.ROOT], so
 * Back is "sub-page -> root -> close the drawer". Pure state, no Android types.
 */
class MenuNavigator {
    var current: MenuPage = MenuPage.ROOT
        private set

    /** Opens [page]; opening [MenuPage.ROOT] is the same as [reset]. */
    fun open(page: MenuPage) {
        current = page
    }

    /** Back to the root (the drawer opens fresh each time). */
    fun reset() {
        current = MenuPage.ROOT
    }

    /**
     * One Back step. True when it navigated up (sub-page -> root); false when already on the
     * root, i.e. the caller should close the drawer.
     */
    fun back(): Boolean {
        if (current == MenuPage.ROOT) return false
        current = MenuPage.ROOT
        return true
    }
}

/**
 * Decides when the task manager page may poll, and keeps queries from overlapping. Polling needs
 * the Tasks page on screen, the drawer open and the activity resumed — losing any one stops it.
 */
class TaskPollGate {
    private var inFlight = false

    /** True while a query started by [tryBegin] hasn't [end]ed yet. */
    val busy: Boolean get() = inFlight

    /** Claims the single query slot; false (skip this round) when a query is still running. */
    fun tryBegin(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun end() {
        inFlight = false
    }

    companion object {
        const val INTERVAL_MS = 2_000L

        fun shouldPoll(page: MenuPage, drawerOpen: Boolean, resumed: Boolean): Boolean =
            page == MenuPage.TASKS && drawerOpen && resumed
    }
}

/** Identity of a task row across refreshes, so an unchanged list is updated in place. */
fun taskKey(process: WindowsProcess): String =
    "${process.name.lowercase()}|${process.windowsPid ?: ""}|${process.linuxPid ?: ""}"

/** True when [new] lists the same processes in the same order as [old] (only details may differ). */
fun sameTaskRows(old: List<WindowsProcess>, new: List<WindowsProcess>): Boolean =
    old.size == new.size && old.indices.all { taskKey(old[it]) == taskKey(new[it]) }
