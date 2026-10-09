package io.harbor.fable.data

import java.util.Locale

/**
 * Turns how a launched Wine process ended, plus what [WineDiagnosis] found in its output, into
 * the message the user sees — and decides how sure that message may sound.
 *
 * Evidence levels, strongest first:
 * 1. [Evidence.CRASH]: Wine's unhandled-exception banner / `err:seh` / a backtrace, or the
 *    process was killed by a crash signal (SIGSEGV, SIGABRT, …). Only here is the word "crashed"
 *    used, and only here may a missing export be named as a possible cause.
 * 2. First-chance exceptions and missing exports ([WineDiagnosis.Diagnosis.context]): the `+seh`
 *    trace records every exception, handled or not, and GetProcAddress probes are routine, so
 *    these are reported as observations next to the verdict, never as the verdict.
 * 3. [Evidence.CLEAN_EXIT]: the app ended inside the startup window with a clean status and no
 *    crash evidence. The 30 s heuristic still treats that as a failed start (a game that ends that
 *    fast usually did fail), but the message says what is known: it exited on its own.
 *
 * Why this exists: DRAPLINE (a CEF game) ran 18.9 s and ended with status 0; the log held one
 * first-chance `EXCEPTION_BREAKPOINT` record and three GetProcAddress warnings — two of them
 * Wine's own probes — and was reported as "crashed … this Wine build lacks … which the program
 * asked for". None of that was established by the log.
 */
internal object LaunchExitReport {
    /** A Wine process that ends within this long after it was spawned failed to start. */
    const val STARTUP_FAILURE_WINDOW_MS = 30_000L

    enum class Evidence { CRASH, CLEAN_EXIT, OTHER }

    data class Report(
        /** The self-contained explanation (also [WineFailure.reason]). */
        val reason: String,
        /** The line for the launch log's error entry. */
        val logLine: String,
        val evidence: Evidence,
        val duringStartup: Boolean,
        /** Section-less lines to write to the launch log before [logLine]. */
        val notes: List<String> = emptyList(),
    )

    /** Signals that mean the process itself crashed (not SIGTERM/SIGKILL: those are being ended). */
    private val CRASH_SIGNALS = mapOf(
        4 to "SIGILL", 5 to "SIGTRAP", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 11 to "SIGSEGV",
    )

    /**
     * @param code the exit status (> 128: killed by signal `code - 128`), or null when unknown
     * @param clean the process exited with status 0 (explorer.exe's, for a Wine desktop launch)
     * @param lastLogLine the last meaningful line of the process output, for an unexplained exit
     * @return null when nothing is worth reporting (a clean exit outside the startup window with
     *   no program failure in the output)
     */
    fun compose(
        label: String,
        isApp: Boolean,
        diagnosis: WineDiagnosis.Diagnosis,
        uptimeMs: Long,
        code: Int?,
        clean: Boolean,
        d3d12Hint: String?,
        lastLogLine: () -> String?,
    ): Report? {
        val duringStartup = uptimeMs < STARTUP_FAILURE_WINDOW_MS
        // An app (not the Wine desktop) whose explorer.exe exits 0 within the startup window
        // never got going either, recognized pattern or not: a game doesn't open and close by
        // itself in a few seconds. Without this the display stayed black with no explanation.
        val silentAppExit = clean && isApp && duringStartup
        if (clean && !diagnosis.programFailed && !silentAppExit) return null

        val notes = mutableListOf<String>()
        if (silentAppExit) {
            notes += "status 0 is explorer.exe's, not $label's; an app ending within ${STARTUP_FAILURE_WINDOW_MS}ms " +
                "of the launch is reported as a failed start"
        }
        val signal = code?.takeIf { !clean && it > 128 }?.let { it - 128 }
        val crashSignal = signal?.let { CRASH_SIGNALS[it] }
        val confirmedCrash = diagnosis.crashes.isNotEmpty() || crashSignal != null
        val summary = diagnosis.summary()
        val seconds = String.format(Locale.US, "%.1f", uptimeMs / 1000f)
        val evidence = when {
            confirmedCrash -> Evidence.CRASH
            clean && summary == null -> Evidence.CLEAN_EXIT
            else -> Evidence.OTHER
        }

        val status = if (clean) {
            null
        } else {
            code?.let { if (signal != null) "killed by signal $signal" else "exit code $it" }
        }
        val detail = when {
            summary != null && crashSignal != null && diagnosis.crashes.isEmpty() ->
                "$summary; the Wine process then crashed ($crashSignal)"
            summary != null -> summary
            crashSignal != null ->
                "the Wine process crashed ($crashSignal), a native crash in Wine or the translator rather than a " +
                    "Windows error" + diagnosis.context().withLeadIn()
            clean -> "$label exited on its own after $seconds s — no crash in Wine's log (exit status 0 is " +
                "explorer.exe's, not necessarily $label's)" + diagnosis.context().withLeadIn() +
                ". The full launch log in Settings has Wine's messages"
            signal != null -> {
                // SIGTERM/SIGKILL while the program was alive (the UE4 RTX launcher's "Couldn't
                // start" dialog sat open until the launch was ended): not a crash — it was still
                // running, so the last log line would be loader-trace noise, not a reason.
                "$label was still running when it was terminated — it hadn't crashed or exited; " +
                    "a window or dialog was likely waiting for input (or it hung)"
            }
            else -> lastLogLine()
        }
        val reason = listOfNotNull(detail, d3d12Hint, status?.let { "($it)" }).joinToString(" ").ifBlank { "no output" }
        val logLine = when {
            evidence == Evidence.CLEAN_EXIT -> "$label ended during startup after ${uptimeMs}ms without crash evidence: $reason"
            else -> "$label ${if (duringStartup) "failed during startup" else "stopped"} after ${uptimeMs}ms: $reason"
        }
        return Report(reason, logLine, evidence, duringStartup, notes)
    }

    /** `"; <a>; <b>"`, or nothing when empty. */
    private fun List<String>.withLeadIn(): String = if (isEmpty()) "" else joinToString(prefix = "; ", separator = "; ")
}
