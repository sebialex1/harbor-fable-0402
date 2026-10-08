package io.harbor.fable.data

import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.data.models.ToolVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolVisibilityTest {
    private fun tool(id: String) = ExeEntry(id = "exe-$id", containerId = "c", name = id, path = "C:\\fable\\tools\\$id.exe", toolId = id)
    private val tools = listOf(tool("gpu-info"), tool("d3d9-test"), tool("d3d11-test"), tool("d3d12-test"))

    @Test fun nothingHiddenByDefault() {
        val split = ToolVisibility.of(tools, emptySet())
        assertEquals(tools, split.visible)
        assertTrue(split.hidden.isEmpty())
    }

    @Test fun hiddenToolsMoveToTheGroupKeepingOrder() {
        val split = ToolVisibility.of(tools, setOf("d3d12-test", "d3d9-test"))
        assertEquals(listOf("gpu-info", "d3d11-test"), split.visible.map { it.toolId })
        assertEquals(listOf("d3d9-test", "d3d12-test"), split.hidden.map { it.toolId })
    }

    @Test fun unknownIdsAndUserAppsAreIgnored() {
        val app = ExeEntry(containerId = "c", name = "Game", path = "C:\\Game.exe")
        val split = ToolVisibility.of(tools + app, setOf("removed-tool"))
        assertEquals(5, split.visible.size)
        assertTrue(split.hidden.isEmpty())
    }

    @Test fun toggleHidesAndRestores() {
        val hidden = ToolVisibility.toggle(emptySet(), "d3d11-test", hide = true)
        assertEquals(setOf("d3d11-test"), hidden)
        assertEquals(hidden, ToolVisibility.toggle(hidden, "d3d11-test", hide = true))
        assertEquals(emptySet<String>(), ToolVisibility.toggle(hidden, "d3d11-test", hide = false))
    }
}
