package io.harbor.fable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFoldersTest {
    @Test fun unityGameExeWinsOverItsCrashHandler() {
        // ULTRAKILL.exe is a ~650 KB stub; UnityCrashHandler64.exe is bigger.
        val exes = listOf("UnityCrashHandler64.exe" to 1_500_000L, "ULTRAKILL.exe" to 650_000L)
        val dirs = setOf("ULTRAKILL_Data", "MonoBleedingEdge")
        assertEquals("ULTRAKILL.exe", GameFolders.pickMainExe(exes, "ULTRAKILL", dirs))
        // Folder renamed by the user: the <Name>_Data folder still identifies the game.
        assertEquals("ULTRAKILL.exe", GameFolders.pickMainExe(exes, "my games copy", dirs))
    }

    @Test fun topLevelExeAndInstallersSkipped() {
        val exes = listOf("redist/vc_redist.x64.exe" to 25_000_000L, "unins000.exe" to 3_000_000L, "Game.exe" to 9_000_000L, "bin/Tool.exe" to 50_000_000L)
        assertEquals("Game.exe", GameFolders.pickMainExe(exes, null, emptySet()))
    }

    @Test fun onlyHelpersStillGivesSomething() {
        assertEquals("setup.exe", GameFolders.pickMainExe(listOf("setup.exe" to 1L), null, emptySet()))
        assertEquals(null, GameFolders.pickMainExe(emptyList(), null, emptySet()))
    }

    @Test fun namesAreKeptButUnsafeOnesRejected() {
        assertTrue(GameFolders.isSafeName("ULTRAKILL_Data"))
        assertTrue(GameFolders.isSafeName("My Game (x64).exe"))
        assertFalse(GameFolders.isSafeName(".."))
        assertFalse(GameFolders.isSafeName("a/b"))
        assertFalse(GameFolders.isSafeName(" "))
    }
}
