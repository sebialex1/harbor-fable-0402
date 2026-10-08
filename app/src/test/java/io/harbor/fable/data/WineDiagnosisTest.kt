package io.harbor.fable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineDiagnosisTest {
    @Test fun loaderFailureOfTheProgramIsAFailure() {
        val output = """
            0130:err:module:import_dll Library UnityPlayer.dll (which is needed by L"C:\\fable\\abc\\ULTRAKILL.exe") not found
            0130:err:module:loader_init Importing dlls for L"C:\\fable\\abc\\ULTRAKILL.exe" failed, status c0000135
        """.trimIndent()
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(listOf("ULTRAKILL.exe (c0000135)"), diagnosis.failedImports)
        assertTrue(diagnosis.programFailed)
        assertTrue(diagnosis.summary()!!.contains("UnityPlayer.dll"))
    }

    @Test fun crashBeforeDirect3dIsAFailure() {
        val output = "wine: Unhandled page fault on read access to 0000000000000000 at address 00006FFFFF4A1234 (thread 0130), starting debugger...\r\n" +
            "0130:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=00006FFFFF4A1234\r\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(2, diagnosis.crashes.size)
        assertTrue(diagnosis.programFailed)
        assertFalse(diagnosis.isEmpty)
        assertTrue(diagnosis.summary()!!.startsWith("crashed: wine: Unhandled page fault"))
    }

    @Test fun ordinaryOutputIsNotAFailure() {
        val output = "0138:trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\user32.dll\" at 000000027FC60000: builtin\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertFalse(diagnosis.programFailed)
        assertTrue(diagnosis.isEmpty)
    }
}
