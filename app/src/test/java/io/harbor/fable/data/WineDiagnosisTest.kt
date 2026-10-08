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

    @Test fun vkd3dProtonErrorsAreRecognized() {
        val output = """
            0210:trace:loaddll:build_module Loaded L"C:\\windows\\system32\\d3d12.dll" at 000000027A8F0000: native
            0210:trace:loaddll:build_module Loaded L"C:\\windows\\system32\\dxgi.dll" at 0000000284600000: native
            0210:err:vkd3d_init_device_caps: Push descriptors are not supported by this implementation. This is required for correct operation.
            0210:err:d3d12:some_wine_function wine's own channel
        """.trimIndent()
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(1, diagnosis.vkd3dErrors.size)
        assertTrue(diagnosis.vkd3dErrors.single().startsWith("vkd3d_init_device_caps: Push descriptors"))
        assertTrue(diagnosis.usedD3d12)
        assertEquals("native", diagnosis.graphicsDlls["d3d12.dll"])
        assertEquals("native", diagnosis.graphicsDlls["dxgi.dll"])
        assertFalse(diagnosis.dxrEnabled)
        assertTrue(diagnosis.summary()!!.contains("VKD3D-Proton (Direct3D 12) error"))
    }

    @Test fun dxrAndFeatureLevelOverrideAreNoted() {
        val output = "0210:info:d3d12_device_determine_ray_tracing_tier: DXR support enabled.\n" +
            "0210:warn:d3d12_device_caps_override: Overriding feature level: 0xc100.\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertTrue(diagnosis.dxrEnabled)
        assertTrue(diagnosis.featureLevelOverridden)
        assertTrue(diagnosis.vkd3dErrors.isEmpty())
    }

    @Test fun moduleTraceOfTheUserLogIsNotAFailure() {
        // The tail of the RTX benchmark log: UE4's crash handler walking modules, nothing vkd3d printed.
        val output = "0210:trace:module:LdrGetDllHandleEx L\"C:\\\\windows\\\\system32\\\\d3d12.dll\" -> 000000027A8F0000\n" +
            "015c:warn:file:NtCreateFile L\"\\\\??\\\\Z:\\\\RTX_bench\\\\Saved\\\\Crashes\\\\UE4CC-Windows-41A9_0000\" not found (c0000035)\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertTrue(diagnosis.vkd3dErrors.isEmpty())
        assertFalse(diagnosis.usedD3d12)
    }

    @Test fun ntlmAuthWinediagWarningIsNotAGraphicsError() {
        // DRAPLINE's launch: a harmless networking warning was blamed as the graphics error.
        val output = "014c:err:winediag:ntlm_check_version ntlm_auth was not found. Make sure that ntlm_auth >= 3.0.25 " +
            "is in your path. Usually, you can find it in the winbind package of your distribution.\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertTrue(diagnosis.graphicsErrors.isEmpty())
        assertTrue(diagnosis.isEmpty)
    }

    @Test fun winediagGraphicsLinesAreStillGraphicsErrors() {
        val output = "0024:err:winediag:wined3d_dll_init Using the OpenGL renderer.\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(1, diagnosis.graphicsErrors.size)
    }

    @Test fun missingExportAndBreakpointCrashAreRecognized() {
        // DRAPLINE (CEF): kernel32 has no IsUserCetAvailableInEnvironment, Chromium CHECK-fails on int3.
        val output = "014c:warn:module:LdrGetProcedureAddress \"IsUserCetAvailableInEnvironment\" (ordinal 0) not found in " +
            "L\"C:\\\\windows\\\\system32\\\\kernel32.dll\"\n" +
            "014c:trace:seh:dispatch_exception code=80000003 (EXCEPTION_BREAKPOINT) flags=0 addr=0000000140E03C6C\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(listOf("IsUserCetAvailableInEnvironment (kernel32.dll)"), diagnosis.missingExports)
        assertEquals(1, diagnosis.crashes.size)
        assertTrue(diagnosis.programFailed)
        assertTrue(diagnosis.graphicsErrors.isEmpty())
        val summary = diagnosis.summary()!!
        assertTrue(summary.contains("IsUserCetAvailableInEnvironment"))
        assertTrue(summary.contains("crashed:"))
    }
}
