package io.harbor.fable.data

import java.io.File

/**
 * Unreal Engine 4/5 packaged builds started through their launcher.
 *
 * A packaged UE4 game has a small launcher at the top of the package (`RTX_bench.exe`, built
 * from UE4's BootstrapPackagedGame) next to the `Engine/` folder and the project folder
 * (`RTX_bench/`). The launcher keeps the program it really starts in its resources — RCDATA
 * 201 holds the path relative to its own folder (`Engine\Binaries\Win64\UE4Game.exe` for a
 * content-only project, `<Project>\Binaries\Win64\<Project>.exe` for a C++ one), RCDATA 202
 * the arguments (`..\..\..\RTX_bench\RTX_bench.uproject`, relative to that program's folder).
 * It runs `"<its folder>\<201>" <202>` with CreateProcess and, when that fails, shows the
 * dialog `Couldn't start:\n<command line>\nCreateProcess() returned <hex error>` itself. Error 2
 * is ERROR_FILE_NOT_FOUND: the program isn't at that path as Wine sees it.
 *
 * [check] tells which of these it is, before Wine starts:
 * - [TargetState.ABSENT_FROM_SOURCE]: the folder the user picked doesn't have it;
 * - [TargetState.OMITTED_DURING_IMPORT]: the source has it (or can't have been looked at
 *   because only the launcher was copied) but Fable's copy in the container doesn't;
 * - [TargetState.PRESENT_NOT_MAPPED]: the file is on disk but the Wine drive the launcher runs
 *   from doesn't lead to it (a `dosdevices` link pointing somewhere else) or it can't be read.
 *
 * No Android dependencies: unit-tested with synthetic package trees.
 */
internal object Ue4Package {
    const val EXEC_FILE_RESOURCE = 201
    const val EXEC_ARGS_RESOURCE = 202

    /** What a launcher starts: [targetExe] as stored (backslashes), [arguments] as stored. */
    data class Bootstrap(val targetExe: String, val arguments: String) {
        /** [targetExe] as a `/`-separated path relative to the launcher's folder, or null. */
        val targetRelative: String? get() = normalize(targetExe)

        /**
         * The `.uproject` the arguments name, relative to the launcher's folder (the argument is
         * relative to the target's folder), or null when there is none or it leaves the package.
         */
        val uprojectRelative: String?
            get() {
                val target = targetRelative ?: return null
                val token = tokens(arguments).firstOrNull { it.endsWith(".uproject", ignoreCase = true) } ?: return null
                val targetDir = target.substringBeforeLast('/', "")
                return normalize(if (targetDir.isEmpty()) token else "$targetDir/$token")
            }
    }

    enum class TargetState(val label: String) {
        PRESENT("present"),
        ABSENT_FROM_SOURCE("absent from the source folder"),
        OMITTED_DURING_IMPORT("in the source but not copied into the container"),
        PRESENT_NOT_MAPPED("on disk but not reachable through the Wine drive"),
        UNKNOWN("missing in the container; whether the source has it is unknown"),
    }

    data class Check(
        val bootstrap: Bootstrap,
        val state: TargetState,
        /** The target as Wine would open it (`C:\fable\<id>\Engine\Binaries\Win64\UE4Game.exe`). */
        val windowsPath: String,
        /** The target's Unix file when it exists (case-insensitive match), else null. */
        val unixFile: File?,
        /** True / false when the `.uproject` argument resolves / doesn't, null when there is none. */
        val uprojectPresent: Boolean?,
        val detail: String,
    ) {
        fun describe(): List<String> = buildList {
            add("Unreal Engine launcher: starts \"${bootstrap.targetExe}\" ${bootstrap.arguments}".trimEnd())
            add("launcher target: $windowsPath — ${state.label}${unixFile?.let { " (${it.absolutePath})" }.orEmpty()}")
            if (detail.isNotEmpty()) add("launcher target detail: $detail")
            bootstrap.uprojectRelative?.let { project ->
                add(
                    "project file argument: $project ${
                        when (uprojectPresent) {
                            true -> "found"
                            false -> "NOT found (UE4 then starts without the project's content; this alone does not make CreateProcess fail)"
                            null -> "not checked"
                        }
                    }",
                )
            }
        }
    }

    /** The launcher information of [exe], or null when it isn't a UE4 launcher. Never throws. */
    fun readBootstrap(exe: File): Bootstrap? = parseBootstrap(PeImports.rawDataResources(exe))

    /**
     * [Bootstrap] from a PE's RCDATA resources: 201 must be a UTF-16LE `.exe` path (that's what
     * UE's packaging writes), 202 the arguments (may be missing or empty).
     */
    fun parseBootstrap(resources: Map<Int, ByteArray>): Bootstrap? {
        val exec = resources[EXEC_FILE_RESOURCE]?.let(::utf16)?.trim() ?: return null
        if (!exec.endsWith(".exe", ignoreCase = true) || exec.any { it < ' ' }) return null
        val args = resources[EXEC_ARGS_RESOURCE]?.let(::utf16)?.trim().orEmpty()
        return Bootstrap(exec, args)
    }

    /** UTF-16LE up to the first NUL. */
    fun utf16(bytes: ByteArray): String {
        val text = String(bytes, 0, bytes.size - bytes.size % 2, Charsets.UTF_16LE)
        return text.substringBefore('\u0000')
    }

    /**
     * A Windows or Unix relative path as `/`-separated components with `.` and `..` resolved.
     * Null for an absolute path, a drive path or one that climbs out of its starting folder.
     */
    fun normalize(path: String): String? {
        val trimmed = path.trim().trim('"')
        if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.startsWith("\\") || (trimmed.length > 1 && trimmed[1] == ':')) return null
        val parts = ArrayList<String>()
        for (part in trimmed.split('/', '\\')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    /** Command-line tokens, honouring double quotes. */
    fun tokens(arguments: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in arguments) {
            when {
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty()) result += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /**
     * [relative] (`/`-separated) under [base], matching each component case-insensitively the way
     * Wine looks names up on a case-sensitive file system. An exact match wins. Null when absent.
     */
    fun resolveCaseInsensitive(base: File, relative: String): File? {
        var current = base
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            val exact = File(current, part)
            current = if (exact.exists()) {
                exact
            } else {
                current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
        }
        return current
    }

    /**
     * Whether [windowsPath] (`C:\…`, `Z:\…`) leads to [expected] through the prefix's
     * `dosdevices/<drive>:` link, the way Wine maps it. Null when the drive letter isn't linked.
     */
    fun mapsTo(prefix: File, windowsPath: String, expected: File): Boolean? {
        if (windowsPath.length < 3 || windowsPath[1] != ':') return false
        val drive = File(prefix, "dosdevices/${windowsPath[0].lowercaseChar()}:")
        val root = runCatching { drive.canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
        val relative = windowsPath.substring(2).replace('\\', '/').trim('/')
        val resolved = if (relative.isEmpty()) root else resolveCaseInsensitive(root, relative) ?: return false
        return runCatching { resolved.canonicalFile == expected.canonicalFile }.getOrDefault(false)
    }

    /**
     * Classifies the launcher [bootstrap]'s target.
     *
     * @param prefix the Wine prefix (for `dosdevices`).
     * @param launcherDir the Unix folder the launcher runs from (its copy, or the source in place).
     * @param launcherWindowsDir that folder as Wine sees it (`C:\fable\<id>`).
     * @param copiedAlone only the launcher .exe was copied (a single picked file): nothing next to it came along.
     * @param inPlace [launcherDir] is the source folder itself.
     * @param sourceFiles lower-case `/` paths of every file in the picked source folder, or null when unknown.
     * @param launcherInSource the launcher's path inside the picked folder (`RTX_bench.exe`, `WindowsNoEditor/RTX_bench.exe`).
     * @param copyFailures lower-case source paths whose copy failed, with the reason.
     */
    fun check(
        bootstrap: Bootstrap,
        prefix: File,
        launcherDir: File,
        launcherWindowsDir: String,
        copiedAlone: Boolean,
        inPlace: Boolean,
        sourceFiles: Set<String>?,
        launcherInSource: String?,
        copyFailures: Map<String, String> = emptyMap(),
    ): Check {
        val target = bootstrap.targetRelative
        if (target == null) {
            return Check(
                bootstrap, TargetState.UNKNOWN, bootstrap.targetExe, null, null,
                "the launcher's target isn't a path inside its folder; not checked",
            )
        }
        val windowsPath = launcherWindowsDir.trimEnd('\\') + "\\" + target.replace('/', '\\')
        val unix = resolveCaseInsensitive(launcherDir, target)?.takeIf { it.isFile }
        val uproject = bootstrap.uprojectRelative?.let { resolveCaseInsensitive(launcherDir, it)?.isFile == true }
        if (unix != null) {
            val mapped = mapsTo(prefix, windowsPath, unix)
            return when {
                !unix.canRead() -> Check(bootstrap, TargetState.PRESENT_NOT_MAPPED, windowsPath, unix, uproject, "the file isn't readable by Fable (and so not by Wine)")
                mapped == null -> Check(
                    bootstrap, TargetState.PRESENT_NOT_MAPPED, windowsPath, unix, uproject,
                    "drive ${windowsPath.take(2)} has no dosdevices link in ${prefix.absolutePath}",
                )
                !mapped -> Check(
                    bootstrap, TargetState.PRESENT_NOT_MAPPED, windowsPath, unix, uproject,
                    "dosdevices/${windowsPath[0].lowercaseChar()}: doesn't lead to ${unix.absolutePath}",
                )
                else -> Check(bootstrap, TargetState.PRESENT, windowsPath, unix, uproject, "")
            }
        }
        if (copiedAlone) {
            return Check(
                bootstrap, TargetState.OMITTED_DURING_IMPORT, windowsPath, null, uproject,
                "only the launcher .exe was copied into the container (the app was added as a single file); " +
                    "the rest of the package didn't come along",
            )
        }
        if (inPlace) {
            return Check(
                bootstrap, TargetState.ABSENT_FROM_SOURCE, windowsPath, null, uproject,
                "the game runs in place from ${launcherDir.absolutePath} and $target isn't there",
            )
        }
        if (sourceFiles == null) {
            return Check(bootstrap, TargetState.UNKNOWN, windowsPath, null, uproject, "the source folder's file list isn't known")
        }
        val launcherParent = launcherInSource?.let { normalize(it) }?.substringBeforeLast('/', "").orEmpty()
        val inSource = (if (launcherParent.isEmpty()) target else "$launcherParent/$target").lowercase()
        return if (inSource in sourceFiles) {
            val reason = copyFailures[inSource]
            Check(
                bootstrap, TargetState.OMITTED_DURING_IMPORT, windowsPath, null, uproject,
                "the picked folder has $inSource but the container's copy doesn't" + (reason?.let { ": the copy failed ($it)" } ?: ""),
            )
        } else {
            val hasEngine = sourceFiles.any { it.startsWith(if (launcherParent.isEmpty()) "engine/" else "${launcherParent.lowercase()}/engine/") }
            Check(
                bootstrap, TargetState.ABSENT_FROM_SOURCE, windowsPath, null, uproject,
                "the picked folder has no $inSource" +
                    if (hasEngine) " (it has an Engine folder, but not that file)" else " (it has no Engine folder next to the launcher)",
            )
        }
    }

    /** What the user should do about a [Check] that isn't [TargetState.PRESENT], or null. */
    fun userMessage(appName: String, launcherName: String, check: Check, allFilesAccess: Boolean): String? {
        val target = check.bootstrap.targetExe
        return when (check.state) {
            TargetState.PRESENT, TargetState.UNKNOWN, TargetState.PRESENT_NOT_MAPPED -> null
            TargetState.OMITTED_DURING_IMPORT ->
                if (check.detail.startsWith("only the launcher")) {
                    "$launcherName is an Unreal Engine launcher: it starts $target from its own folder, but Fable could only " +
                        "copy $launcherName. Remove $appName and add it again with \"Choose game folder\" (the folder that holds " +
                        "$launcherName and Engine)" + if (!allFilesAccess) ", or allow Fable \"All files access\"" else ""
                } else {
                    "$appName's launcher starts $target, which is in the game folder but wasn't copied into the container " +
                        "(${check.detail.substringAfter(": ", "")}). Free some storage and launch again".replace(" ()", "")
                }
            TargetState.ABSENT_FROM_SOURCE ->
                "$launcherName is an Unreal Engine launcher that starts $target, and the game folder doesn't have it. " +
                    "Add the complete packaged build (the folder with $launcherName, Engine and the project folder)"
        }
    }
}
