package io.harbor.fable.data

import android.util.Log
import io.harbor.fable.nativebridge.NativeLoader
import java.io.File
import java.io.IOException

/**
 * Starts Wine with [ProcessBuilder], the way Winlator does (`ProcessHelper.exec`), instead of the
 * JNI fork()/execve() in `wine_launcher.cpp`. The JNI path killed the whole app with a native
 * signal on Android 16 before any Java `catch` could run; ProcessBuilder goes through ART's own,
 * well-tested spawn code, and every failure here surfaces as a [Outcome.Failed] instead.
 *
 * The command and environment mirror the native launcher exactly:
 * `<translator> <wine> <program> [args…]` with WINEPREFIX, HOME, USER, TMPDIR, XDG_CACHE_HOME,
 * PATH, the Vulkan driver variables (plus the `fable_icd.json` manifest), the caller's variables
 * (DISPLAY, LD_LIBRARY_PATH, the container's Box64 preset as BOX64_* (see
 * [io.harbor.fable.data.models.Box64Settings]), WINEDEBUG, …) and BOX64_PATH / BOX64_LD_LIBRARY_PATH.
 * stdout/stderr go to [WineRuntime.LAUNCH_LOG] in the container directory, and a reaper thread
 * appends `[fable] exit code N` when the process ends (what [WineRuntime.exitedCleanly] reads).
 */
internal object WineProcessLauncher {
    private const val TAG = "WineProcessLauncher"
    private const val ICD_NAME = "fable_icd.json"
    private const val LINKER64 = "/system/bin/linker64"
    private const val SHIM_LOG_ENV = "FABLE_VULKAN_SHIM_LOG"

    /** Where the Vulkan shim mirrors its diagnostics, in the container directory. */
    const val SHIM_LOG_NAME = "vulkan_shim.log"

    data class Request(
        val containerDir: File,
        val wine: File,
        /** [NativeLoader.TRANSLATOR_BOX64] or [NativeLoader.TRANSLATOR_FEX]. */
        val translatorName: String,
        /** `box64` / `FEXInterpreter`; null runs Wine directly (not usable for x86_64 Wine on ARM64). */
        val translator: File?,
        /** What Wine runs: a built-in (`explorer`) or a path. */
        val program: String,
        val args: List<String>,
        /** `KEY=VALUE` pairs from the caller. PATH / LD_LIBRARY_PATH are prepended, others override. */
        val env: List<String>,
        val driverPath: String?,
        /**
         * File in [containerDir] stdout/stderr go to. Prefix initialisation (`wineboot -u`) uses its
         * own file so its output isn't truncated away by the launch that follows it.
         */
        val processLogName: String = WineRuntime.LAUNCH_LOG,
        /**
         * Unix working directory of the Wine process; the container directory when null. For an
         * app it is the folder of its .exe, so the cwd Wine derives for the first process
         * (explorer.exe) already is the game's folder. `start /d <dir>` sets the same directory
         * for the game itself; this keeps the two in agreement (Unity games resolve
         * `<Name>_Data` and their plugins against it).
         */
        val workingDir: File? = null,
    )

    sealed interface Outcome {
        data class Started(val process: WineProcess) : Outcome
        data class Failed(val reason: String, val error: Throwable? = null) : Outcome
    }

    /** Never throws (short of an Error the caller's crash containment handles). */
    fun launch(request: Request, log: LaunchLog): Outcome = try {
        launchUnchecked(request, log)
    } catch (error: Exception) {
        log.error("ProcessBuilder launch threw ${error.javaClass.name}", error)
        Log.e(TAG, "launch failed", error)
        Outcome.Failed(error.message?.lineSequence()?.firstOrNull()?.take(120) ?: error.javaClass.simpleName, error)
    }

    private fun launchUnchecked(request: Request, log: LaunchLog): Outcome {
        val dir = request.containerDir
        if (!dir.isDirectory) return Outcome.Failed("Container path is not a directory")
        val containerPath = dir.absolutePath
        val processLog = File(dir, request.processLogName)
        // Truncate the process log first so even an early failure leaves a fresh, readable trail.
        runCatching { processLog.writeText("[fable] launcher: ProcessBuilder\n") }
            .onFailure { log.error("couldn't create ${processLog.absolutePath}", it) }

        fun failed(message: String, error: Throwable? = null): Outcome.Failed {
            log.error(message, error)
            note(processLog, "[fable] launch failed: $message")
            return Outcome.Failed(message, error)
        }

        if (!request.wine.isFile) return failed("Wine isn't installed in this container")
        if (!request.wine.canExecute()) request.wine.setExecutable(true, false)
        val translator = request.translator
        if (translator != null && !translator.canExecute() && !translator.setExecutable(true, false)) {
            return failed("${translator.name} is not executable: ${translator.absolutePath}")
        }
        val useBox64 = translator != null && !request.translatorName.equals(NativeLoader.TRANSLATOR_FEX, ignoreCase = true)

        File(dir, "tmp").mkdirs()
        File(dir, "cache").mkdirs()

        val builder = ProcessBuilder()
        // Starts as a copy of the app's environment, like the native launcher's `environ` copy.
        val env = builder.environment()
        env["WINEPREFIX"] = containerPath
        env["HOME"] = containerPath
        // Winlator runs Wine as "xuser"; the bionic prefixPack ships drive_c/users/xuser.
        env["USER"] = "xuser"
        env["TMPDIR"] = "$containerPath/tmp"
        env["XDG_CACHE_HOME"] = "$containerPath/cache"
        // PATH = <container>/bin:<translator dir>:$PATH (wineserver and FEXServer are found on it).
        translator?.parentFile?.absolutePath?.let { prependPath(env, "PATH", it) }
        prependPath(env, "PATH", "$containerPath/bin")
        log.line("env: base variables set")

        // The libvulkan.so.1 shim (cpp/vulkan/vulkan_shim.c) logs which Vulkan implementation it
        // picked, and why vkCreateInstance failed, to stderr (this process log) and to this file.
        val shimLog = File(dir, SHIM_LOG_NAME)
        runCatching { shimLog.writeText("") }
        env[SHIM_LOG_ENV] = shimLog.absolutePath

        val driverPath = request.driverPath
        if (driverPath == null) {
            log.line("vulkan: no custom driver active; the shim uses the system Vulkan loader")
            note(processLog, "[fable] vulkan: no custom driver (FABLE_VULKAN_DRIVER unset)")
        } else {
            val driver = File(driverPath)
            if (!driver.isFile) return failed("Driver library not found: $driverPath")
            log.line("vulkan: FABLE_VULKAN_DRIVER=$driverPath (${driver.length()} bytes)")
            note(processLog, "[fable] vulkan: FABLE_VULKAN_DRIVER=$driverPath (${driver.length()} bytes)")
            val icd = try {
                writeIcd(driver)
            } catch (error: IOException) {
                return failed("Failed to write ICD manifest: ${error.message}", error)
            }
            log.line("driver ICD manifest: ${icd.absolutePath}")
            env["ADRENOTOOLS_DRIVER_PATH"] = driverPath
            env["ADRENOTOOLS_DRIVER_NAME"] = driver.name
            env["FABLE_VULKAN_DRIVER"] = driverPath
            env["VK_ICD_FILENAMES"] = icd.absolutePath
            env["VK_DRIVER_FILES"] = icd.absolutePath
            // Found through the ICD manifest only, never LD_PRELOADed into the translator. The
            // driver directory stays on LD_LIBRARY_PATH for the driver's own dependencies.
            driver.parentFile?.absolutePath?.let { prependPath(env, "LD_LIBRARY_PATH", it) }
        }

        for (item in request.env) {
            val eq = item.indexOf('=')
            if (eq <= 0) return failed("Malformed environment variable: $item")
            val key = item.substring(0, eq)
            if (!key.all { it.isLetterOrDigit() || it == '_' }) return failed("Illegal environment variable name: $key")
            val value = item.substring(eq + 1)
            if (key == "PATH" || key == "LD_LIBRARY_PATH") {
                // The caller's search paths (X11 client libraries, /system/lib64) go in front of the
                // driver directory instead of replacing it.
                prependPath(env, key, value)
            } else {
                env[key] = value
            }
        }

        if (useBox64) {
            // Where Box64 looks for the emulated program's libraries and helpers; caller wins.
            env.putIfAbsent("BOX64_PATH", request.wine.parentFile?.absolutePath ?: "$containerPath/bin")
            env.putIfAbsent(
                "BOX64_LD_LIBRARY_PATH",
                "$containerPath/lib/wine/x86_64-unix:$containerPath/lib:$containerPath/lib64",
            )
        }

        val command = buildList {
            translator?.let { add(it.absolutePath) }
            add(request.wine.absolutePath)
            add(request.program)
            addAll(request.args)
        }
        val printable = command.joinToString(" ")
        log.line("argv built: $printable")

        // Same header the native launcher wrote, so a failed start can be reproduced from the log.
        // WINEDLLPATH / WINELOADER / WINESERVER are named explicitly (the WINE prefix also matches
        // them) so where Wine was told its files are stays visible in the log even if the WINE*
        // filter is ever narrowed.
        val header = buildString {
            append("[fable] ").append(printable).append('\n')
            env.toSortedMap().forEach { (key, value) ->
                if (key == "DISPLAY" || key == "WINEDLLPATH" || key == "WINELOADER" || key == "WINESERVER" ||
                    key.startsWith("WINE") || key.startsWith("BOX64") || key.startsWith("LD_") ||
                    key == "PATH" || key == "HOME" || key == "TMPDIR" || key.startsWith("VK_") || key.startsWith("FEX") ||
                    key == "USER" || key == "XDG_CACHE_HOME" || key.startsWith("ADRENOTOOLS") || key.startsWith("FABLE_VULKAN") ||
                    key == "FONTCONFIG_FILE" || key.startsWith("ANDROID_") || key.startsWith("DXVK") ||
                    key.startsWith("VKD3D")
                ) {
                    append("[fable] env ").append(key).append('=').append(value).append('\n')
                }
            }
        }
        note(processLog, header.trimEnd())

        val cwd = request.workingDir?.takeIf { it.isDirectory } ?: dir
        note(processLog, "[fable] cwd ${cwd.absolutePath}")
        builder.directory(cwd)
        builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(processLog))

        log.line("starting process (ProcessBuilder)")
        val process = try {
            builder.command(command).start()
        } catch (error: IOException) {
            // Android 10+ refuses exec() from app storage for targetSdk >= 29. targetSdk is 28, but
            // keep the native launcher's fallback: the system linker can map an ARM64 bionic ELF.
            val program = File(command.first())
            val denied = error.message?.contains("error=13") == true ||
                error.message?.contains("Permission denied", ignoreCase = true) == true
            if (denied && ElfInfo.machine(program) == ElfInfo.MACHINE_AARCH64 && File(LINKER64).exists()) {
                log.line("exec denied (${error.message}); retrying through $LINKER64")
                note(processLog, "[fable] execve: EACCES, retrying through $LINKER64")
                try {
                    builder.command(listOf(LINKER64) + command).start()
                } catch (retry: IOException) {
                    return failed("Couldn't run ${program.name}: ${retry.message}", retry)
                }
            } else {
                return failed("Couldn't run ${program.name}: ${error.message}", error)
            }
        }

        val pid = pidOf(process)
        log.line("process started, pid=${pid ?: "unknown"}")
        note(processLog, "[fable] spawned pid ${pid ?: "unknown"}")
        startReaper(process, pid, processLog)
        return Outcome.Started(WineProcess(pid ?: -1, process))
    }

    /** `<driver dir>/fable_icd.json` pointing the Vulkan loader at [driver]. */
    private fun writeIcd(driver: File): File {
        val icd = File(driver.parentFile, ICD_NAME)
        val body = buildString {
            append("{\n")
            append("  \"file_format_version\": \"1.0.0\",\n")
            append("  \"ICD\": {\n")
            append("    \"library_path\": \"").append(jsonEscape(driver.absolutePath)).append("\",\n")
            append("    \"api_version\": \"1.3.0\"\n")
            append("  }\n")
            append("}\n")
        }
        icd.writeText(body)
        return icd
    }

    private fun jsonEscape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c < ' ' -> Unit
                else -> append(c)
            }
        }
    }

    private fun prependPath(env: MutableMap<String, String>, key: String, prefix: String) {
        if (prefix.isEmpty()) return
        val current = env[key]
        if (current.isNullOrEmpty()) {
            env[key] = prefix
            return
        }
        if (current == prefix || current.startsWith("$prefix:")) return
        env[key] = "$prefix:$current"
    }

    /**
     * The child's pid. `Process.pid()` where the runtime has it, else the private `pid` field of
     * libcore's `UNIXProcess` (what Winlator reads), else the `Process[pid=N, …]` toString.
     */
    private fun pidOf(process: Process): Int? {
        runCatching {
            val value = process.javaClass.getMethod("pid").invoke(process)
            (value as? Number)?.toInt()?.takeIf { it > 0 }
        }.getOrNull()?.let { return it }
        runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process).takeIf { it > 0 }
        }.getOrNull()?.let { return it }
        return Regex("""pid=(\d+)""").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Waits for [process] and appends how it ended, like the native reaper thread did. */
    private fun startReaper(process: Process, pid: Int?, processLog: File) {
        val reaper = Runnable {
            val code = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                null
            }
            if (code != null) reportExit(code, pid, processLog)
        }
        val thread = Thread(reaper, "wine-reaper-${pid ?: "?"}")
        thread.isDaemon = true
        thread.start()
    }

    private fun reportExit(code: Int, pid: Int?, processLog: File) {
        run {
            val line = buildString {
                append("[fable] exit code ").append(code)
                // ART reports a signal death as 0x80 + signal number.
                if (code > 128) append("\n[fable] (status > 128: probably killed by signal ${code - 128})")
            }
            Log.i(TAG, "wine process ${pid ?: "?"}: exit $code")
            note(processLog, line)
        }
    }

    private fun note(file: File, line: String) {
        runCatching { file.appendText(line + "\n") }
    }
}

/**
 * A started Wine process. [process] is set for ProcessBuilder launches; the native fallback only
 * has a [pid], so liveness then comes from `/proc`.
 */
internal class WineProcess(val pid: Int, val process: Process?) {
    fun isAlive(): Boolean = process?.isAlive ?: (pid > 0 && WineRuntime.isAlive(pid))

    /** The exit status once the process has ended, or null while it runs or when unknown. */
    fun exitCodeOrNull(): Int? = process?.let { if (it.isAlive) null else runCatching { it.exitValue() }.getOrNull() }

    /**
     * Ends the process: SIGTERM through [Process.destroy] for ProcessBuilder launches (SIGKILL if
     * it is still alive after [graceMs]), or SIGKILL by [pid] for native launches.
     */
    fun destroy(graceMs: Long = DESTROY_GRACE_MS) {
        val proc = process
        if (proc != null) {
            runCatching { proc.destroy() }
            val exited = runCatching { proc.waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited) runCatching { proc.destroyForcibly() }
        } else if (pid > 0) {
            runCatching { android.os.Process.killProcess(pid) }
        }
    }

    private companion object {
        const val DESTROY_GRACE_MS = 1_000L
    }
}
