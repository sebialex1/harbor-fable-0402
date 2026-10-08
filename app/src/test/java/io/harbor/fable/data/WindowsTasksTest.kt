package io.harbor.fable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing for the display screen's task manager. */
class WindowsTasksTest {
    @Test
    fun parsesTasklistCsvAndSkipsNoise() {
        val output = """
            0024:fixme:ntdll:NtQuerySystemInformation info_class SYSTEM_PERFORMANCE_INFORMATION
            "Image Name","PID","Session Name","Session#","Mem Usage"
            "explorer.exe","32","Console","1","10,240 K"
            "Game, The.exe","2a","Console","1","1 K"
            "ULTRAKILL.exe","120","Console","1","812,004 K"
            "quote ""x"".exe","7","Console","1",""
        """.trimIndent()
        val processes = WindowsTasks.parseTasklistCsv(output)
        assertEquals(listOf("explorer.exe", "ULTRAKILL.exe", "quote \"x\".exe"), processes.map { it.name })
        assertEquals(120, processes[1].windowsPid)
        assertEquals("812,004 K", processes[1].memory)
        assertNull(processes[2].memory)
    }

    @Test
    fun imageNameComesFromWinesRewrittenArgv() {
        assertEquals("explorer.exe", WindowsTasks.imageNameFromCmdline(listOf("C:\\windows\\system32\\explorer.exe", "/desktop")))
        assertEquals("Game.exe", WindowsTasks.imageNameFromCmdline(listOf("/data/box64", "wine", "Z:\\games\\Game.exe")))
        assertNull(WindowsTasks.imageNameFromCmdline(listOf("/data/user/0/io.harbor.fable/files/wine/bin/wineserver")))
    }
}
