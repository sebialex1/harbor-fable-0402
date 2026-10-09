package io.harbor.fable.display

import io.harbor.fable.data.WindowsProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerMenuTest {
    @Test fun startsOnTheRootAndBackFromItClosesTheDrawer() {
        val nav = MenuNavigator()
        assertEquals(MenuPage.ROOT, nav.current)
        assertFalse(nav.back())
        assertEquals(MenuPage.ROOT, nav.current)
    }

    @Test fun backGoesSubPageThenRootThenClose() {
        val nav = MenuNavigator()
        nav.open(MenuPage.TASKS)
        assertEquals(MenuPage.TASKS, nav.current)
        assertTrue(nav.back())
        assertEquals(MenuPage.ROOT, nav.current)
        assertFalse(nav.back())
    }

    @Test fun resetReturnsToTheRoot() {
        val nav = MenuNavigator()
        nav.open(MenuPage.PRESETS)
        nav.reset()
        assertEquals(MenuPage.ROOT, nav.current)
    }

    @Test fun pollsOnlyWhileTasksPageDrawerAndActivityAreAllLive() {
        assertTrue(TaskPollGate.shouldPoll(MenuPage.TASKS, drawerOpen = true, resumed = true))
        assertFalse(TaskPollGate.shouldPoll(MenuPage.ROOT, drawerOpen = true, resumed = true))
        assertFalse(TaskPollGate.shouldPoll(MenuPage.WINE, drawerOpen = true, resumed = true))
        assertFalse(TaskPollGate.shouldPoll(MenuPage.TASKS, drawerOpen = false, resumed = true))
        assertFalse(TaskPollGate.shouldPoll(MenuPage.TASKS, drawerOpen = true, resumed = false))
    }

    @Test fun overlappingQueriesAreSkipped() {
        val gate = TaskPollGate()
        assertTrue(gate.tryBegin())
        assertTrue(gate.busy)
        assertFalse(gate.tryBegin())
        gate.end()
        assertFalse(gate.busy)
        assertTrue(gate.tryBegin())
    }

    @Test fun unchangedProcessListUpdatesInPlace() {
        val a = listOf(WindowsProcess("game.exe", windowsPid = 20, memory = "10 K"), WindowsProcess("explorer.exe", windowsPid = 9))
        val b = listOf(WindowsProcess("GAME.exe", windowsPid = 20, memory = "11 K"), WindowsProcess("explorer.exe", windowsPid = 9))
        assertTrue(sameTaskRows(a, b))
    }

    @Test fun addedRemovedOrReorderedProcessesRebuildTheList() {
        val a = listOf(WindowsProcess("a.exe", windowsPid = 1), WindowsProcess("b.exe", windowsPid = 2))
        assertFalse(sameTaskRows(a, a.take(1)))
        assertFalse(sameTaskRows(a, a.reversed()))
        assertFalse(sameTaskRows(a, listOf(a[0], WindowsProcess("b.exe", windowsPid = 3))))
    }
}
