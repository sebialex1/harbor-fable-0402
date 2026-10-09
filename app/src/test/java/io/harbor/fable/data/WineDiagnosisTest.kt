package io.harbor.fable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // The banner is the crash; the +seh record is the same event seen first-chance, context only.
        assertEquals(1, diagnosis.crashes.size)
        assertEquals(listOf("EXCEPTION_ACCESS_VIOLATION (c0000005) at 00006FFFFF4A1234"), diagnosis.exceptionsObserved)
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

    @Test fun firstChanceExceptionRecordsAreNotCrashes() {
        // +seh writes dispatch_exception for every exception, handled or not.
        val output = "0140:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=000000014000AAAA\n" +
            "0140:trace:seh:dispatch_exception code=80000003 (EXCEPTION_BREAKPOINT) flags=0 addr=0000000140E03C6C\n" +
            "0140:trace:seh:dispatch_exception code=40010006 (DBG_PRINTEXCEPTION_C) flags=0 addr=000000007B000000\n" +
            "0140:trace:seh:dispatch_exception code=e06d7363 (EXCEPTION_?) flags=1 addr=000000007B000000\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertTrue(diagnosis.crashes.isEmpty())
        assertFalse(diagnosis.programFailed)
        assertNull(diagnosis.summary())
        // OutputDebugString and C++ throws are routine and left out.
        assertEquals(2, diagnosis.exceptionsObserved.size)
        assertTrue(diagnosis.breakpointObserved)
        assertFalse(diagnosis.isEmpty)
    }

    @Test fun unhandledBreakpointIsStillACrash() {
        // A CEF game's failed CHECK: int3 nobody handles ends in Wine's banner.
        val output = "014c:warn:module:LdrGetProcedureAddress \"IsUserCetAvailableInEnvironment\" (ordinal 0) not found in " +
            "L\"C:\\\\windows\\\\system32\\\\kernel32.dll\"\n" +
            "014c:trace:seh:dispatch_exception code=80000003 (EXCEPTION_BREAKPOINT) flags=0 addr=0000000140E03C6C\n" +
            "wine: Unhandled exception 0x80000003 at address 0000000140E03C6C (thread 014c), starting debugger...\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(1, diagnosis.crashes.size)
        assertTrue(diagnosis.programFailed)
        assertTrue(diagnosis.breakpointObserved)
        assertEquals(listOf("IsUserCetAvailableInEnvironment (kernel32.dll)"), diagnosis.missingExports)
        // The export lookup came before the crash: named, but only as a possible cause.
        assertTrue(diagnosis.crashAfterMissingExport)
        val summary = diagnosis.summary()!!
        assertTrue(summary.startsWith("crashed: wine: Unhandled exception 0x80000003"))
        assertTrue(summary.contains("IsUserCetAvailableInEnvironment"))
        assertTrue(summary.contains("not confirmed"))
        assertFalse(summary.contains("which the program asked for"))
    }

    @Test fun errSehUnhandledRaiseExceptionIsACrash() {
        val output = "0140:err:seh:raise_exception Unhandled exception code 80000003 flags 0 addr 0000000140E03C6C\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(1, diagnosis.crashes.size)
        assertTrue(diagnosis.programFailed)
    }

    @Test fun backtraceIsACrash() {
        val diagnosis = WineDiagnosis.analyze("Backtrace:\n=>0 0x000000014000aaaa (0x0000000000000000)\n")
        assertEquals(listOf("Backtrace:"), diagnosis.crashes)
    }

    @Test fun missingExportWithoutACrashIsOnlyContext() {
        val output = "014c:warn:module:LdrGetProcedureAddress \"IsUserCetAvailableInEnvironment\" (ordinal 0) not found in " +
            "L\"C:\\\\windows\\\\system32\\\\kernel32.dll\"\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(1, diagnosis.missingExports.size)
        assertFalse(diagnosis.programFailed)
        assertNull(diagnosis.summary())
        assertFalse(diagnosis.crashAfterMissingExport)
    }

    @Test fun exportAfterTheCrashIsNotBlamed() {
        val output = "wine: Unhandled page fault on read access to 0000000000000000 at address 00006FFFFF4A1234 (thread 0130), starting debugger...\n" +
            "014c:warn:module:LdrGetProcedureAddress \"IsUserCetAvailableInEnvironment\" (ordinal 0) not found in " +
            "L\"C:\\\\windows\\\\system32\\\\kernel32.dll\"\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertFalse(diagnosis.crashAfterMissingExport)
        assertFalse(diagnosis.summary()!!.contains("IsUserCet"))
    }

    @Test fun wineInternalProbesAreNotTheProgramsExports() {
        val output = "0054:warn:module:LdrGetProcedureAddress \"DllCanUnloadNow\" (ordinal 0) not found in L\"C:\\\\windows\\\\system32\\\\ole32.dll\"\n" +
            "0054:warn:module:LdrGetProcedureAddress \"SvchostPushServiceGlobals\" (ordinal 0) not found in L\"C:\\\\windows\\\\system32\\\\wevtsvc.dll\"\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertTrue(diagnosis.missingExports.isEmpty())
        assertEquals(listOf("DllCanUnloadNow (ole32.dll)", "SvchostPushServiceGlobals (wevtsvc.dll)"), diagnosis.wineProbes)
        assertTrue(diagnosis.context().isEmpty())
        assertTrue(diagnosis.describe().none { it.contains("asked") && !it.contains("Wine itself") })
    }

    // DRAPLINE (a CEF game): ran 18.9 s, then explorer.exe exited 0. Fixture = verbatim lines of
    // that launch log (the breakpoint dispatch and the end of the run) plus the two Wine-internal
    // probe warnings its Diagnosis section listed, whose log lines were cut from the saved tail.
    private fun draplineDiagnosis(): WineDiagnosis.Diagnosis {
        val tail = javaClass.getResourceAsStream("/drapline-launch-tail.log")!!.bufferedReader().readText()
        val probes = "0054:warn:module:LdrGetProcedureAddress \"DllCanUnloadNow\" (ordinal 0) not found in L\"C:\\\\windows\\\\system32\\\\ole32.dll\"\n" +
            "0054:warn:module:LdrGetProcedureAddress \"SvchostPushServiceGlobals\" (ordinal 0) not found in L\"C:\\\\windows\\\\system32\\\\wevtsvc.dll\"\n"
        return WineDiagnosis.analyze(probes + tail)
    }

    @Test fun draplineLogHasNoConfirmedCrash() {
        val diagnosis = draplineDiagnosis()
        assertTrue(diagnosis.crashes.isEmpty())
        assertFalse(diagnosis.programFailed)
        assertNull(diagnosis.summary())
        assertEquals(listOf("EXCEPTION_BREAKPOINT (80000003) at 0000000140E03C6C"), diagnosis.exceptionsObserved)
        // Only the game's own probe is the program's; ole32 / wevtsvc ones are Wine's.
        assertEquals(listOf("IsUserCetAvailableInEnvironment (kernel32.dll)"), diagnosis.missingExports)
        assertEquals(2, diagnosis.wineProbes.size)
        assertFalse(diagnosis.crashAfterMissingExport)
        assertTrue(diagnosis.describe().none { it.startsWith("crash") })
    }

    @Test fun draplineCleanEarlyExitIsReportedHonestly() {
        val report = LaunchExitReport.compose(
            label = "DRAPLINE", isApp = true, diagnosis = draplineDiagnosis(), uptimeMs = 18_904,
            code = 0, clean = true, d3d12Hint = null, lastLogLine = { "should not be needed" },
        )!!
        assertEquals(LaunchExitReport.Evidence.CLEAN_EXIT, report.evidence)
        assertTrue(report.duringStartup)
        assertTrue(report.reason.startsWith("DRAPLINE exited on its own after 18.9 s"))
        assertTrue(report.reason.contains("no crash in Wine's log"))
        assertTrue(report.reason.contains("launch log in Settings"))
        // Context is there, worded as a lead.
        assertTrue(report.reason.contains("lead, not proof"))
        assertTrue(report.reason.contains("IsUserCetAvailableInEnvironment"))
        // No crash claim, no blame, no Wine-internal probes presented as the program's.
        assertFalse(report.reason.contains("crashed"))
        assertFalse(report.reason.contains("which the program asked for"))
        assertFalse(report.reason.contains("DllCanUnloadNow"))
        assertFalse(report.reason.contains("SvchostPushServiceGlobals"))
        assertFalse(report.logLine.contains("crashed"))
        assertTrue(report.notes.single().contains("30000ms"))
    }

    @Test fun cleanExitAfterTheWindowIsNotReported() {
        val report = LaunchExitReport.compose(
            label = "DRAPLINE", isApp = true, diagnosis = draplineDiagnosis(), uptimeMs = 45_000,
            code = 0, clean = true, d3d12Hint = null, lastLogLine = { null },
        )
        assertNull(report)
    }

    @Test fun unhandledBreakpointExitIsReportedAsACrash() {
        val diagnosis = WineDiagnosis.analyze(
            "014c:warn:module:LdrGetProcedureAddress \"IsUserCetAvailableInEnvironment\" (ordinal 0) not found in " +
                "L\"C:\\\\windows\\\\system32\\\\kernel32.dll\"\n" +
                "014c:trace:seh:dispatch_exception code=80000003 (EXCEPTION_BREAKPOINT) flags=0 addr=0000000140E03C6C\n" +
                "wine: Unhandled exception 0x80000003 at address 0000000140E03C6C (thread 014c), starting debugger...\n",
        )
        val report = LaunchExitReport.compose(
            label = "DRAPLINE", isApp = true, diagnosis = diagnosis, uptimeMs = 18_904,
            code = 0, clean = true, d3d12Hint = null, lastLogLine = { null },
        )!!
        assertEquals(LaunchExitReport.Evidence.CRASH, report.evidence)
        assertTrue(report.reason.startsWith("crashed: wine: Unhandled exception 0x80000003"))
        assertTrue(report.logLine.startsWith("DRAPLINE failed during startup after 18904ms: crashed:"))
        assertTrue(report.reason.contains("possible cause, not confirmed"))
    }

    @Test fun accessViolationExitIsReportedAsACrash() {
        val diagnosis = WineDiagnosis.analyze(
            "0130:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=00006FFFFF4A1234\n" +
                "wine: Unhandled page fault on read access to 0000000000000000 at address 00006FFFFF4A1234 (thread 0130), starting debugger...\n",
        )
        val report = LaunchExitReport.compose(
            label = "Game", isApp = true, diagnosis = diagnosis, uptimeMs = 5_000,
            code = 1, clean = false, d3d12Hint = null, lastLogLine = { null },
        )!!
        assertEquals(LaunchExitReport.Evidence.CRASH, report.evidence)
        assertTrue(report.reason.startsWith("crashed: wine: Unhandled page fault"))
        assertTrue(report.reason.endsWith("(exit code 1)"))
    }

    @Test fun accessViolationTraceWithoutBannerIsNotACrashOnItsOwn() {
        val diagnosis = WineDiagnosis.analyze(
            "0130:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=00006FFFFF4A1234\n",
        )
        val report = LaunchExitReport.compose(
            label = "Game", isApp = true, diagnosis = diagnosis, uptimeMs = 5_000,
            code = 0, clean = true, d3d12Hint = null, lastLogLine = { null },
        )!!
        assertEquals(LaunchExitReport.Evidence.CLEAN_EXIT, report.evidence)
        assertFalse(report.reason.contains("crashed"))
        assertTrue(report.reason.contains("EXCEPTION_ACCESS_VIOLATION"))
    }

    @Test fun processKilledBySegfaultIsACrashEvenWithoutWineOutput() {
        val report = LaunchExitReport.compose(
            label = "Game", isApp = true, diagnosis = WineDiagnosis.Diagnosis(), uptimeMs = 7_000,
            code = 128 + 11, clean = false, d3d12Hint = null, lastLogLine = { "noise" },
        )!!
        assertEquals(LaunchExitReport.Evidence.CRASH, report.evidence)
        assertTrue(report.reason.contains("SIGSEGV"))
        assertTrue(report.reason.endsWith("(killed by signal 11)"))
    }

    @Test fun processTerminatedBySigtermWasNotACrash() {
        val report = LaunchExitReport.compose(
            label = "Game", isApp = true, diagnosis = WineDiagnosis.Diagnosis(), uptimeMs = 40_000,
            code = 128 + 15, clean = false, d3d12Hint = null, lastLogLine = { "noise" },
        )!!
        assertEquals(LaunchExitReport.Evidence.OTHER, report.evidence)
        assertTrue(report.reason.contains("hadn't crashed"))
        assertFalse(report.reason.contains("noise"))
    }

    @Test fun nonZeroExitWithoutEvidenceFallsBackToTheLastLine() {
        val report = LaunchExitReport.compose(
            label = "Game", isApp = true, diagnosis = WineDiagnosis.Diagnosis(), uptimeMs = 3_000,
            code = 3, clean = false, d3d12Hint = null, lastLogLine = { "the last line" },
        )!!
        assertEquals("the last line (exit code 3)", report.reason)
        assertEquals("Game failed during startup after 3000ms: the last line (exit code 3)", report.logLine)
    }
}
