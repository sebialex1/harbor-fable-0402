package io.harbor.fable.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persisted, human-readable record of every container launch attempt, so a failure on a real
 * device is captured and reportable instead of vanishing with a crash.
 *
 * One file per attempt under `filesDir/logs/launch-<timestamp>-<container>.log`, holding the
 * timestamp, container, pre-flight checks, the exact command and environment, the native
 * launcher's error, the child's pid, and — once the process ends — its exit code and the tail of
 * its stdout/stderr (`fable-launch.log` in the container). The newest [KEEP] files are kept.
 * [crash] writes `crash-<timestamp>.log` from the process-wide uncaught-exception handler.
 *
 * Every method swallows its own I/O errors: logging must never be the thing that crashes.
 */
class LaunchLog private constructor(private val file: File?) {
    private val lock = Any()

    val path: String? get() = file?.absolutePath

    fun line(text: String) = append("${stamp()}  $text")

    fun section(title: String) = append("\n== $title ==")

    fun error(text: String, error: Throwable? = null) {
        line("ERROR $text")
        if (error != null) append(stackTrace(error))
    }

    /** Appends the last [maxBytes] of [source] (the child's stdout/stderr), if it exists. */
    fun attachTail(source: File, title: String, maxBytes: Int = TAIL_BYTES) {
        section(title)
        val text = tail(source, maxBytes)
        append(text ?: "(no output file at ${source.absolutePath})")
    }

    private fun append(text: String) {
        val target = file ?: return
        synchronized(lock) {
            runCatching { target.appendText(text + "\n") }
                .onFailure { Log.w(TAG, "Could not write ${target.name}", it) }
        }
    }

    companion object {
        private const val TAG = "LaunchLog"
        private const val KEEP = 20

        /**
         * How much of the Wine process output a launch log copies. With WINEDEBUG=+loaddll,+module
         * the loader trace leading up to a failure is tens of kilobytes.
         */
        const val TAIL_BYTES = 96 * 1024

        fun logsDir(context: Context): File = File(context.applicationContext.filesDir, "logs")

        /** Starts a new log for a launch of [containerName] in [dir]; never throws. */
        fun begin(dir: File?, containerName: String, containerId: String): LaunchLog {
            val file = runCatching {
                dir ?: return@runCatching null
                dir.mkdirs()
                prune(dir)
                val safe = containerName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
                File(dir, "launch-${fileStamp()}-$safe.log")
            }.getOrNull()
            return LaunchLog(file).also {
                it.line("Fable launch log")
                it.line("container: $containerName ($containerId)")
                it.line("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            }
        }

        /** Records an uncaught exception that is about to end the process. */
        fun crash(context: Context, thread: Thread, error: Throwable) {
            runCatching {
                val dir = logsDir(context).also { it.mkdirs() }
                File(dir, "crash-${fileStamp()}.log").writeText(
                    "Fable crash on thread ${thread.name} at ${stamp()}\n" + stackTrace(error),
                )
            }
        }

        /** Newest launch or crash log, or null when there are none. */
        fun latest(context: Context): File? = runCatching {
            logsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
        }.getOrNull()

        fun tail(source: File, maxBytes: Int): String? = runCatching {
            if (!source.isFile) return null
            source.inputStream().use { input ->
                val skip = (source.length() - maxBytes).coerceAtLeast(0)
                input.skip(skip)
                String(input.readBytes(), Charsets.UTF_8)
            }
        }.getOrNull()

        private fun prune(dir: File) {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("launch-") } ?: return
            files.sortedByDescending { it.lastModified() }.drop(KEEP - 1).forEach { it.delete() }
        }

        private fun stackTrace(error: Throwable): String =
            StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

        private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }
}
