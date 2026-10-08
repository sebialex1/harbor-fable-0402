package io.harbor.fable.data

/**
 * A Windows process running in a container, for the display screen's task manager.
 *
 * [windowsPid] is set when the list came from Wine's `tasklist` (and is what `taskkill /PID`
 * takes); [linuxPid] when it came from `/proc` (the fallback when `tasklist` isn't available),
 * in which case ending it is a SIGKILL.
 */
data class WindowsProcess(
    val name: String,
    val windowsPid: Int? = null,
    val linuxPid: Int? = null,
    /** As `tasklist` prints it ("12,345 K"), or null. */
    val memory: String? = null,
)

/** Parsing for `wine tasklist /FO CSV /NH` output and `/proc` command lines. */
object WindowsTasks {
    /**
     * Reads `tasklist /FO CSV` rows (`"Image Name","PID","Session Name","Session#","Mem Usage"`),
     * skipping the header if present and anything that isn't a row (Wine's own messages mixed
     * into the output).
     */
    fun parseTasklistCsv(output: String): List<WindowsProcess> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("\"") }
            .mapNotNull { line ->
                val cells = splitCsv(line)
                val name = cells.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pid = cells.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                WindowsProcess(name = name, windowsPid = pid, memory = cells.getOrNull(4)?.takeIf { it.isNotBlank() })
            }
            .toList()

    /**
     * The Windows image name of a Wine process from its `/proc/<pid>/cmdline` arguments, or null
     * for the Wine host processes that aren't Windows programs (wineserver, the preloader, Box64).
     * Wine rewrites a process's argv to its Windows path (`C:\windows\system32\explorer.exe`).
     */
    fun imageNameFromCmdline(args: List<String>): String? {
        val exe = args.firstOrNull { it.contains('\\') && it.endsWith(".exe", ignoreCase = true) }
            ?: args.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
            ?: return null
        return exe.substringAfterLast('\\').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    /** The processes ending which would take the whole container down; marked in the list. */
    val SYSTEM_PROCESSES = setOf(
        "explorer.exe", "services.exe", "winedevice.exe", "plugplay.exe", "svchost.exe", "rpcss.exe",
        "conhost.exe", "start.exe", "tabtip.exe", "wineboot.exe",
    )

    private fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }
}
