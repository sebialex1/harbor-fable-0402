package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.display.DisplayServer
import io.harbor.fable.display.NativeLibResolver
import io.harbor.fable.display.X11ClientLibs
import io.harbor.fable.nativebridge.NativeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory container store with a Room-shaped persistence seam.
 *
 * Live state is held in memory and exposed as [StateFlow]. Every mutation is
 * written through [ContainerDao]. The shipping backend is [FileContainerStore]
 * (JSON under `filesDir/containers`) because Room is not a project dependency.
 * To switch to Room, annotate a `@Dao` with the same methods, back it with the
 * schema in [ROOM_SCHEMA_V1], and pass that implementation to the constructor.
 *
 * Reads are synchronous against memory. Writes are suspend functions so disk
 * I/O stays off the main thread.
 */
class ContainerRepository internal constructor(
    private val dao: ContainerDao,
    private val containersRoot: File,
    private val runtime: WineRuntime? = null,
    /** Where [LaunchLog] files go (`filesDir/logs`); null disables persisted launch logs. */
    private val logsRoot: File? = null,
    /** Starts the X display server Wine draws to; null launches without a display (tests). */
    private val display: DisplayProvider? = null,
) {
    private val mutex = Mutex()
    private val containersById = LinkedHashMap<String, Container>()
    private val exesById = LinkedHashMap<String, ExeEntry>()

    /** Outlives individual screens: watches launched Wine processes until they exit. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live Wine process ids per container id. */
    private val runningPids = ConcurrentHashMap<String, MutableSet<Int>>()

    /** Live Wine processes per container id, so [stopContainer] can end them. */
    private val processes = ConcurrentHashMap<String, MutableSet<WineProcess>>()

    /** Serializes Wine extraction per container. */
    private val setupLocks = ConcurrentHashMap<String, Mutex>()

    /** Containers with a launch in flight. */
    private val starting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    private val _exes = MutableStateFlow<List<ExeEntry>>(emptyList())

    /** Containers, newest first. */
    val containers: StateFlow<List<Container>> = _containers.asStateFlow()

    /** Every executable registered against any container. */
    val exes: StateFlow<List<ExeEntry>> = _exes.asStateFlow()

    private val _wineFailures = MutableStateFlow<Map<String, WineFailure>>(emptyMap())

    /**
     * Why a container's launched Wine process died after the launch had already been reported
     * as started (so the display is up), keyed by container id. [DisplayActivity][io.harbor.fable.display.DisplayActivity]
     * shows it instead of leaving a black screen. Cleared when the container is launched again
     * or stopped.
     */
    val wineFailures: StateFlow<Map<String, WineFailure>> = _wineFailures.asStateFlow()

    init {
        val loaded = runCatching { dao.read() }.getOrElse { error ->
            Log.e(TAG, "Container index unreadable, starting empty", error)
            ContainerSnapshot(emptyList(), emptyList())
        }
        loaded.containers.forEach { containersById[it.id] = it }
        loaded.exes.forEach { exesById[it.id] = it }
        // No Wine process survives the app, so a persisted RUNNING / CONFIGURING state is stale.
        var reset = false
        containersById.values.toList().forEach { container ->
            val settled = when (container.status) {
                ContainerStatus.RUNNING -> ContainerStatus.READY
                ContainerStatus.CONFIGURING -> ContainerStatus.CREATED
                else -> null
            }
            if (settled != null) {
                containersById[container.id] = container.copy(status = settled)
                reset = true
            }
        }
        if (reset) persistLocked() else publish()
    }

    fun list(): List<Container> = _containers.value

    fun get(id: String): Container? = containersById[id]

    fun listExes(containerId: String): List<ExeEntry> =
        exesById.values.filter { it.containerId == containerId }.sortedBy { it.name.lowercase() }

    /**
     * Downloaded Wine packages a new container can use, newest first: bionic Winlator `.wcp`
     * builds only (see [WineRuntime.wineArchives]). Reads the download folders, so call it off
     * the main thread.
     */
    suspend fun availableWineBuilds(): List<WineBuild> = withContext(Dispatchers.IO) {
        runCatching { runtime?.wineBuilds().orEmpty() }.getOrElse { error ->
            Log.w(TAG, "Could not list Wine builds", error)
            emptyList()
        }
    }

    /** Prefix directory for [id]. Created on [create]. Safe to call before the directory exists. */
    fun directory(id: String): File = File(containersRoot, safeId(id))

    suspend fun create(
        name: String,
        wineVersion: String = ContainerDefaults.WINE_VERSION,
        screenResolution: String = ContainerDefaults.SCREEN_RESOLUTION,
        graphicsDriver: String = ContainerDefaults.GRAPHICS_DRIVER,
        isFullscreen: Boolean = false,
        envVars: Map<String, String> = emptyMap(),
        dxvkVersion: String? = null,
        driverId: String? = null,
        translator: String = ContainerDefaults.TRANSLATOR,
    ): Container {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Container name is required" }
        return insert(
            Container(
                name = trimmed,
                wineVersion = wineVersion,
                screenResolution = screenResolution,
                graphicsDriver = graphicsDriver,
                isFullscreen = isFullscreen,
                envVars = envVars,
                dxvkVersion = dxvkVersion,
                driverId = driverId,
                translator = translator,
                status = ContainerStatus.CREATED,
            )
        )
    }

    suspend fun insert(container: Container): Container = mutex.withLock {
        withContext(Dispatchers.IO) {
            containersById[container.id] = container
            directory(container.id).mkdirs()
            persistLocked()
            container
        }
    }

    /**
     * Replaces the container with [id]. [Container.createdAt] is preserved.
     * Returns null when the id is unknown.
     */
    suspend fun update(container: Container): Container? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = containersById[container.id] ?: return@withContext null
            val next = container.copy(id = existing.id, createdAt = existing.createdAt)
            containersById[next.id] = next
            persistLocked()
            next
        }
    }

    suspend fun updateStatus(id: String, status: ContainerStatus): Container? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = containersById[id] ?: return@withContext null
            val next = existing.copy(status = status)
            containersById[id] = next
            persistLocked()
            next
        }
    }

    /** Deletes the container, its executables, and its managed prefix directory. */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (containersById.remove(id) == null) return@withContext false
            exesById.entries.removeAll { it.value.containerId == id }
            val dir = directory(id)
            if (isUnder(containersRoot, dir)) dir.deleteRecursively()
            persistLocked()
            true
        }
    }

    /**
     * Registers an executable. When the container has no primary exe yet, the
     * new entry becomes [Container.exePath] / [Container.exeName].
     */
    suspend fun addExe(
        containerId: String,
        name: String,
        path: String,
        icon: String? = null,
    ): ExeEntry {
        val trimmedName = name.trim()
        val trimmedPath = path.trim()
        require(trimmedName.isNotEmpty()) { "Executable name is required" }
        require(trimmedPath.isNotEmpty()) { "Executable path is required" }
        return addExe(
            ExeEntry(
                containerId = containerId,
                name = trimmedName,
                path = trimmedPath,
                icon = icon,
            )
        )
    }

    suspend fun addExe(entry: ExeEntry): ExeEntry = mutex.withLock {
        withContext(Dispatchers.IO) {
            val container = containersById[entry.containerId]
                ?: throw NoSuchElementException("Unknown container ${entry.containerId}")
            exesById[entry.id] = entry
            if (container.exePath.isNullOrBlank()) {
                containersById[container.id] = container.copy(
                    exePath = entry.path,
                    exeName = entry.name,
                )
            }
            persistLocked()
            entry
        }
    }

    suspend fun removeExe(id: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val removed = exesById.remove(id) ?: return@withContext false
            val container = containersById[removed.containerId]
            if (container != null && container.exePath == removed.path) {
                val replacement = exesById.values.firstOrNull { it.containerId == container.id }
                containersById[container.id] = container.copy(
                    exePath = replacement?.path,
                    exeName = replacement?.name,
                )
            }
            persistLocked()
            true
        }
    }

    /** Makes [exeId] the container's primary executable (the one "Launch" starts). */
    suspend fun setPrimaryExe(exeId: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val exe = exesById[exeId] ?: return@withContext false
            val container = containersById[exe.containerId] ?: return@withContext false
            containersById[container.id] = container.copy(exePath = exe.path, exeName = exe.name)
            persistLocked()
            true
        }
    }

    /**
     * Launches [exeId] — or the container's primary executable when null — inside the
     * container's Wine prefix, through the container's translator (Box64, or FEX when
     * [Container.translator] is "fex").
     *
     * Needs a downloaded package for that translator and a Wine build (Assets tab). The first launch of a
     * container unpacks Wine into it, which takes a while; the container shows as configuring
     * meanwhile. Every failure carries a message that says what to do about it.
     */
    suspend fun launch(containerId: String, exeId: String? = null): LaunchResult {
        val (container, exe) = mutex.withLock {
            val container = containersById[containerId]
                ?: return LaunchResult.Failed("Container not found")
            val candidates = exesById.values.filter { it.containerId == containerId }
            val exe = when {
                exeId != null -> candidates.firstOrNull { it.id == exeId }
                else -> candidates.firstOrNull { it.path == container.exePath } ?: candidates.firstOrNull()
            } ?: return LaunchResult.Failed("Add an app first")
            container to exe
        }
        return start(container, exe)
    }

    /**
     * Starts the full Wine desktop (`wine explorer /desktop=Fable,<resolution>`) in the
     * container, without any executable.
     */
    suspend fun launchDesktop(containerId: String): LaunchResult {
        val container = mutex.withLock { containersById[containerId] }
            ?: return LaunchResult.Failed("Container not found")
        return start(container, exe = null)
    }

    /**
     * Unpacks the newest downloaded Wine build into the container's prefix directory unless that
     * build is already there. Returns the build name (e.g. `wine-11.19-amd64`), or a failure
     * message when no Wine package has been downloaded or extraction fails.
     */
    suspend fun installWine(containerId: String): Result<String> {
        val runtime = runtime
            ?: return Result.failure(IllegalStateException("Wine runtime isn't available in this build"))
        if (mutex.withLock { containersById[containerId] } == null) {
            return Result.failure(IllegalStateException("Container not found"))
        }
        // One extraction at a time per container; later callers find the finished tree.
        return setupLocks.getOrPut(containerId) { Mutex() }.withLock {
            val container = mutex.withLock { containersById[containerId] }
                ?: return@withLock Result.failure(IllegalStateException("Container not found"))
            val dir = directory(container.id).also { it.mkdirs() }
            val installed = runtime.installedWine(dir)
            if (installed != null && (installed.build == container.wineVersion || runtime.wineArchives().isEmpty())) {
                return@withLock Result.success(installed.build)
            }
            val archive = runtime.pickWineArchive(container.wineVersion)
                ?: return@withLock Result.failure(IllegalStateException("Install Wine in Assets"))
            if (installed != null && installed.build == WineRuntime.buildName(archive)) {
                return@withLock Result.success(installed.build)
            }
            setStatus(container.id, ContainerStatus.CONFIGURING)
            try {
                val build = runtime.installWine(archive, dir).build
                mutex.withLock {
                    containersById[container.id]?.let { current ->
                        containersById[current.id] = current.copy(wineVersion = build, status = ContainerStatus.READY)
                        persistLocked()
                    }
                }
                Result.success(build)
            } catch (error: CancellationException) {
                setStatus(container.id, ContainerStatus.CREATED)
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Wine extraction failed for ${container.name}", error)
                setStatus(container.id, ContainerStatus.ERROR)
                Result.failure(error)
            }
        }
    }

    /**
     * Crash containment for the whole launch path. Anything thrown while preparing or starting
     * Wine (I/O, SecurityException from a revoked document, OOM while unpacking, a JNI error…)
     * ends up as a short [LaunchResult.Failed] plus a persisted [LaunchLog] with the stack trace,
     * never as an uncaught exception on the UI scope. Cancellation is still propagated.
     */
    private suspend fun start(container: Container, exe: ExeEntry?): LaunchResult {
        // A second tap while the first launch is still preparing would start Wine twice.
        if (!starting.add(container.id)) return LaunchResult.Failed("${container.name} is already starting")
        _wineFailures.update { it - container.id }
        val log = LaunchLog.begin(logsRoot, container.name, container.id)
        log.line("target: ${exe?.let { "${it.name} (${it.path})" } ?: "Wine desktop"}")
        try {
            val result = startLocked(container, exe, log)
            log.section("Result")
            log.line(result.toString())
            return result.withLog(log.path)
        } catch (error: CancellationException) {
            log.line("cancelled")
            throw error
        } catch (error: Throwable) {
            log.error("launch threw ${error.javaClass.name}", error)
            runCatching { setStatus(container.id, ContainerStatus.READY) }
            val detail = error.message?.lineSequence()?.firstOrNull()?.take(90)
            return LaunchResult.Failed(
                "Launch failed: ${error.javaClass.simpleName}${detail?.let { " ($it)" }.orEmpty()}. Log saved",
                log.path,
            )
        } finally {
            starting.remove(container.id)
        }
    }

    private suspend fun startLocked(container: Container, exe: ExeEntry?, log: LaunchLog): LaunchResult {
        val runtime = runtime
            ?: return LaunchResult.Unavailable("Wine runtime isn't available in this build")
        val dir = directory(container.id).also { it.mkdirs() }
        log.section("Pre-flight")
        // Wine is started with ProcessBuilder now; libfable_native is only needed for the opt-in
        // native launcher ($LAUNCHER_ENV=$LAUNCHER_NATIVE) and the driver tools.
        log.line("native runtime loaded: ${NativeLoader.isLoaded}")
        log.line("container dir: ${dir.absolutePath} (exists=${dir.isDirectory}, writable=${dir.canWrite()})")
        log.line("wine version requested: ${container.wineVersion.ifBlank { "(newest bionic)" }}")
        log.line("downloaded bionic Wine packages: ${runtime.wineArchives().joinToString { it.name }.ifEmpty { "none" }}")

        // 1. The container's translator (Box64 or FEX) and a Wine build have to be downloaded.
        val hasWine = runtime.installedWine(dir) != null || runtime.wineArchives().isNotEmpty()
        // Read the translator from the latest record: it may have been switched since the tap.
        val configured = mutex.withLock { containersById[container.id] } ?: container
        val translator = resolveTranslator(runtime, configured, hasWine)
            .getOrElse { error ->
                log.error("translator: ${error.message}")
                return LaunchResult.Failed(error.message ?: "Couldn't set up the x86_64 translator")
            }
        describeBinary(log, "translator ${translator.name}", translator.executable)
        if (!translator.executable.canExecute() && !translator.executable.setExecutable(true, false)) {
            log.error("chmod +x failed on ${translator.executable}")
            return LaunchResult.Failed("Couldn't mark ${translator.executable.name} executable")
        }
        ElfInfo.interpreter(translator.executable)?.takeIf { it.contains("ld-linux") }?.let { interp ->
            log.error("translator is a glibc build ($interp); it cannot run on Android without a glibc rootfs")
            return LaunchResult.Failed("${translator.executable.name} is a glibc build. Download a bionic (Android) build")
        }
        if (!hasWine) {
            log.error("no Wine installed and no bionic Wine package downloaded")
            return LaunchResult.Failed("Install Wine in Assets")
        }

        // 2. Unpack Wine into the container's prefix (first launch only).
        val wineBuild = installWine(container.id).getOrElse { error ->
            log.error("Wine setup failed", error)
            return LaunchResult.Failed(error.message?.let { "Couldn't set up Wine: $it" } ?: "Couldn't set up Wine")
        }
        log.line("wine build: $wineBuild")
        val wineBinary = runtime.wineBinary(dir)
        if (wineBinary == null) {
            log.error("bin/wine missing after setup in ${dir.absolutePath}")
            return LaunchResult.Failed("Wine isn't installed in this container")
        }
        describeBinary(log, "wine", wineBinary)

        // 3. The display server has to be listening before Wine starts (Winlator's XEnvironment
        //    starts XServerComponent before GuestProgramLauncherComponent the same way).
        val current = mutex.withLock { containersById[container.id] } ?: container
        log.section("Display")
        val screen: DisplayEnv? = display?.let { provider ->
            runCatching { provider.prepare(current.screenResolution) }.getOrElse { error ->
                log.error("display server failed to start", error)
                return LaunchResult.Failed("Display couldn't start: ${error.message?.take(80) ?: error.javaClass.simpleName}")
            }
        }
        if (screen != null) {
            log.line("X server: DISPLAY=${screen.display}, socket ${screen.socketPath}, screen ${screen.resolution}")
            log.line("X11 client libraries: ${screen.x11LibDir}")
            screen.nativeLibs?.let { report ->
                // Bundled FreeType & co. plus what NativeLibResolver copied from the system.
                log.section("Native libraries (${report.dir.absolutePath})")
                log.line(report.summary())
                report.describe().forEach { log.line(it) }
                if (report.missingRequired.isNotEmpty()) {
                    log.error(
                        "required native libraries not found on this device: ${report.missingRequired.joinToString()} " +
                            "(searched ${NativeLibResolver.SYSTEM_LIB_DIRS.joinToString()}); Wine will likely fail to start",
                    )
                }
            }
        } else {
            log.line("no display server in this build")
        }
        val desktopSize = screen?.resolution ?: current.screenResolution

        // 4. Resolve what to run: `wine explorer /desktop=shell,WxH [start /d <dir> <exe>]`,
        //    Winlator's guest command, so every app runs inside a virtual desktop the size of the
        //    X screen. Desktop mode opens a file browser at C:\ so the user sees something
        //    instead of a black screen.
        val program = "explorer"
        val arguments: List<String>
        if (exe == null) {
            arguments = listOf("/desktop=shell,$desktopSize", "/root,C:\\")
        } else {
            val path = runtime.materializeExecutable(dir, exe.id, exe.name, exe.path)
                ?: run {
                    log.error("executable not readable: ${exe.path}")
                    return LaunchResult.Failed("Can't open ${exe.name}. Add it again")
                }
            val programFile = File(path)
            log.line("program: $path (exists=${programFile.isFile}, size=${programFile.length()})")
            val windowsPath = toWindowsPath(path)
            val windowsDir = windowsPath.substringBeforeLast('\\', missingDelimiterValue = "C:\\")
            arguments = listOf("/desktop=shell,$desktopSize", "start", "/d", windowsDir, windowsPath)
        }
        val label = exe?.name ?: "${current.name} desktop"

        // Android has no lscpu; Box64 builds that don't read BOX64_SYSINFO_* run it through
        // popen(), so put a stub first on the Wine process's PATH (<container>/bin).
        val cpu = HostCpu.get()
        val lscpu = cpu.installLscpu(File(dir, "bin"))
        log.line("host CPU: ${cpu.name}, ${cpu.count} cores, max ${cpu.maxFrequencyHz?.let { "${it / 1_000_000} MHz" } ?: "unknown"}")
        log.line("lscpu stub: ${lscpu?.absolutePath ?: "couldn't be written"}")

        // 5. Start the process.
        val environment = buildList {
            // Diagnostics: WINEDEBUG=-all hid why Wine stopped (it only printed "could not load
            // kernel32.dll, status c0000135"). +loaddll names every DLL Wine maps and from where,
            // +module traces the loader's search (system32, WINEDLLDIR*, load order), and every
            // other channel keeps Wine's default err/fixme output. A container can still set its
            // own WINEDEBUG (e.g. -all) through its environment variables, which come last.
            add("WINEDEBUG=$DIAGNOSTIC_WINEDEBUG")
            // Box64 reports native dlopen()/dlsym() failures instead of failing silently.
            add("BOX64_DLSYM_ERROR=1")
            addAll(translator.environment)
            // What Winlator's bionic Wine reads instead of /etc/resolv.conf and netlink.
            addAll(runtime.bionicWineEnvironment())
            if (screen != null) {
                add("DISPLAY=${screen.display}")
                // Native (aarch64 bionic) libX11/libxcb for Box64's wrapped libX11, then the system
                // libraries, as Winlator's LD_LIBRARY_PATH={imagefs}/usr/lib:/system/lib64.
                // Libraries NativeLibResolver copied from /vendor or /system_ext keep their own
                // dependencies there, so those directories go last.
                val extra = screen.nativeLibs?.extraSearchDirs.orEmpty()
                add("LD_LIBRARY_PATH=" + (listOf(screen.x11LibDir, NativeLibResolver.DEFAULT_SYSTEM_DIR) + extra).joinToString(":"))
                // Fontconfig's compiled-in config path (/data/data/com.termux/files/usr/etc/fonts)
                // doesn't exist on this device. Point it at the fonts.conf X11ClientLibs installed
                // next to the native libraries, so Wine can enumerate fonts and load kernel32.dll.
                val fontsConf = File(screen.x11LibDir, "fonts.conf")
                if (fontsConf.isFile) add("FONTCONFIG_FILE=${fontsConf.absolutePath}")
            }
            // The container's own variables come last so they can override anything above.
            current.envVars.forEach { (key, value) -> if (key != LAUNCHER_ENV) add("$key=$value") }
        }
        val driver = runtime.activeDriverLibrary()
        val useNative = current.envVars[LAUNCHER_ENV]?.trim().equals(LAUNCHER_NATIVE, ignoreCase = true)
        log.section("Command")
        log.line("${translator.executable.absolutePath} ${wineBinary.absolutePath} $program ${arguments.joinToString(" ")}".trim())
        log.line("driver: ${driver ?: "none (system Vulkan)"}")
        log.line("launcher: ${if (useNative) "native JNI fork/execve ($LAUNCHER_ENV=$LAUNCHER_NATIVE)" else "ProcessBuilder"}")
        log.section("Environment (Fable additions; the launcher also sets WINEPREFIX, HOME, TMPDIR, PATH, BOX64_*)")
        environment.forEach { log.line(it) }
        log.section("Launch")
        val processLog = File(dir, WineRuntime.LAUNCH_LOG)
        val started: WineProcess = if (useNative) {
            launchNative(dir, program, arguments, environment, driver, translator, log, processLog)
                ?: return nativeFailure(label)
        } else {
            val outcome = withContext(Dispatchers.IO) {
                WineProcessLauncher.launch(
                    WineProcessLauncher.Request(
                        containerDir = dir,
                        wine = wineBinary,
                        translatorName = translator.name,
                        translator = translator.executable,
                        program = program,
                        args = arguments,
                        env = environment,
                        driverPath = driver,
                    ),
                    log,
                )
            }
            when (outcome) {
                is WineProcessLauncher.Outcome.Started -> outcome.process
                is WineProcessLauncher.Outcome.Failed -> {
                    log.attachTail(processLog, "Process output (${processLog.name})")
                    return LaunchResult.Failed("Couldn't start $label: ${outcome.reason}")
                }
            }
        }
        val pid = started.pid
        val spawnedAt = System.nanoTime()
        log.line("spawned pid $pid")

        // 6. A process that dies straight away has a reason worth showing (missing libraries, …).
        delay(EARLY_EXIT_WINDOW_MS)
        val earlyExit = started.exitCodeOrNull()
        val diedEarly = if (started.process != null) {
            earlyExit != null && earlyExit != 0
        } else {
            !started.isAlive() && !WineRuntime.exitedCleanly(dir)
        }
        if (diedEarly) {
            log.error("process $pid exited within ${EARLY_EXIT_WINDOW_MS}ms${earlyExit?.let { " (exit status $it)" }.orEmpty()}")
            // Give the reaper a moment to append the exit line before the tail is copied.
            delay(REAPER_GRACE_MS)
            log.attachTail(processLog, "Process output (${processLog.name}, includes exit code)")
            setStatus(container.id, ContainerStatus.READY)
            // Name what broke (missing libfreetype.so, kernel32.dll c0000135, …) rather than only
            // echoing the last line of output.
            val diagnosis = WineDiagnosis.analyze(processLog)
            logDiagnosis(log, diagnosis, screen)
            val reason = diagnosis.summary() ?: WineRuntime.lastLogLine(dir)
            val hint = translator.earlyExitHint
                ?.takeIf { current.envVars.keys.none { key -> key == FEX_ROOTFS_ENV } }
                ?.let { " ($it)" }
                .orEmpty()
            return LaunchResult.Failed(
                (if (reason != null) "$label stopped right away: $reason" else "$label stopped right away") + hint
            )
        }
        markStarted(container.id, exe?.id, started, log, processLog, label, spawnedAt, screen)
        return LaunchResult.Started(pid)
    }

    /**
     * The old JNI fork/execve launcher, kept only as an opt-in diagnostic path
     * ([LAUNCHER_ENV]=[LAUNCHER_NATIVE] in the container's environment). It crashed the app with
     * a native signal on Android 16, so it is never used automatically. Null on failure.
     */
    private suspend fun launchNative(
        dir: File,
        program: String,
        arguments: List<String>,
        environment: List<String>,
        driver: String?,
        translator: ResolvedTranslator,
        log: LaunchLog,
        processLog: File,
    ): WineProcess? {
        if (!NativeLoader.isLoaded) {
            log.error("native launcher requested but libfable_native didn't load")
            return null
        }
        val pid = try {
            withContext(Dispatchers.IO) {
                NativeLoader.launchWineContainer(
                    containerPath = dir.absolutePath,
                    exePath = program,
                    args = arguments.toTypedArray(),
                    envVars = environment.toTypedArray(),
                    driverPath = driver,
                    translator = translator.name,
                    translatorPath = translator.executable.absolutePath,
                )
            }
        } catch (error: UnsatisfiedLinkError) {
            log.error("native launcher missing", error)
            return null
        }
        if (pid <= 0) {
            val reason = runCatching { NativeLoader.lastLaunchError() }.getOrNull()
            log.error("native launcher returned $pid: ${reason ?: "no reason"}")
            log.attachTail(processLog, "Process output (${processLog.name})")
            return null
        }
        return WineProcess(pid, null)
    }

    private fun nativeFailure(label: String): LaunchResult {
        if (!NativeLoader.isLoaded) return LaunchResult.Unavailable("The native runtime couldn't load on this device")
        val reason = runCatching { NativeLoader.lastLaunchError() }.getOrNull()
        return LaunchResult.Failed(reason?.let { "Couldn't start $label: $it" } ?: "Couldn't start $label")
    }

    /**
     * The x86_64 translator [container] launches through, per [Container.translator]: FEX
     * (`FEXInterpreter`) for "fex", Box64 otherwise. Fails with a user-facing message when the
     * chosen translator hasn't been downloaded or its package has no usable executable; there is
     * deliberately no silent fallback to the other translator.
     */
    private suspend fun resolveTranslator(
        runtime: WineRuntime,
        container: Container,
        hasWine: Boolean,
    ): Result<ResolvedTranslator> {
        val wineToo = if (hasWine) "" else " and Wine"
        return if (usesFex(container)) {
            when (val status = runtime.ensureFex()) {
                is FexStatus.Ready -> Result.success(
                    ResolvedTranslator(
                        name = NativeLoader.TRANSLATOR_FEX,
                        executable = status.executable,
                        environment = buildList {
                            // FEX resolves the guest's x86_64 libraries (glibc, …) from its RootFS.
                            status.rootFs?.let { add("$FEX_ROOTFS_ENV=${it.absolutePath}") }
                        },
                        earlyExitHint = if (status.rootFs == null) {
                            "the FEX package has no x86_64 RootFS; set $FEX_ROOTFS_ENV in the container's environment"
                        } else {
                            null
                        },
                    )
                )
                FexStatus.NotDownloaded -> Result.failure(
                    IllegalStateException(
                        "Install FEX$wineToo in Assets, or switch to Box64"
                    )
                )
                is FexStatus.NoExecutable -> Result.failure(
                    IllegalStateException(
                        "${status.packageName} has no FEXInterpreter. Try another build, or switch to Box64"
                    )
                )
            }
        } else {
            when (val status = runtime.ensureBox64()) {
                is Box64Status.Ready -> Result.success(
                    ResolvedTranslator(
                        name = NativeLoader.TRANSLATOR_BOX64,
                        executable = status.executable,
                        // CPU facts from the environment so Box64 doesn't popen("lscpu") (HostCpu).
                        environment = BOX64_ENVIRONMENT + HostCpu.get().box64Environment(),
                    )
                )
                Box64Status.NotDownloaded -> Result.failure(
                    IllegalStateException("Install Box64$wineToo in Assets")
                )
                is Box64Status.NoExecutable -> Result.failure(
                    IllegalStateException("${status.packageName} has no box64. Try another build")
                )
            }
        }
    }

    /** `/data/x/game.exe` -> `Z:\\data\\x\\game.exe` (the prefix maps Z: to /); Windows paths pass through. */
    private fun toWindowsPath(path: String): String =
        if (path.startsWith("/")) "Z:" + path.replace('/', '\\') else path

    /** Logs what [file] is: size, exec bit, ELF machine and loader (bionic vs glibc). */
    private fun describeBinary(log: LaunchLog, label: String, file: File) {
        val machine = when (ElfInfo.machine(file)) {
            ElfInfo.MACHINE_X86_64 -> "x86_64"
            ElfInfo.MACHINE_AARCH64 -> "aarch64"
            null -> "not ELF64"
            else -> "other"
        }
        log.line(
            "$label: ${file.absolutePath} (exists=${file.isFile}, size=${file.length()}, exec=${file.canExecute()}, " +
                "elf=$machine, interp=${ElfInfo.interpreter(file) ?: "none/static"})",
        )
    }

    private suspend fun setStatus(id: String, status: ContainerStatus) {
        mutex.withLock {
            val existing = containersById[id] ?: return
            if (existing.status != status) {
                containersById[id] = existing.copy(status = status)
                persistLocked()
            }
        }
    }

    /**
     * Stops everything [containerId] is running: the tracked Wine processes, any helper processes
     * Wine spawned for the same prefix (wineserver, explorer, services, …, which outlive their
     * parent), and the X display server. The container goes back to READY.
     */
    suspend fun stopContainer(containerId: String) = withContext(Dispatchers.IO) {
        _wineFailures.update { it - containerId }
        runningPids.remove(containerId)
        val tracked = processes.remove(containerId).orEmpty()
        tracked.forEach { process -> runCatching { process.destroy() } }
        runCatching { killPrefixProcesses(directory(containerId)) }
            .onFailure { Log.w(TAG, "Couldn't sweep Wine processes for $containerId", it) }
        runCatching { DisplayServer.stop() }
            .onFailure { Log.w(TAG, "Couldn't stop the display server", it) }
        mutex.withLock {
            containersById[containerId]?.let { existing ->
                if (existing.status == ContainerStatus.RUNNING) {
                    containersById[containerId] = existing.copy(status = ContainerStatus.READY)
                    persistLocked()
                }
            }
        }
        Log.i(TAG, "Stopped container $containerId (${tracked.size} tracked process(es))")
    }

    /** [stopContainer] on the repository's own scope, for callers that are going away (an activity's onDestroy). */
    fun stopContainerInBackground(containerId: String) {
        scope.launch {
            runCatching { stopContainer(containerId) }
                .onFailure { Log.e(TAG, "Stopping $containerId failed", it) }
        }
    }

    /**
     * SIGKILLs this app's other processes whose WINEPREFIX is [prefix]. Wine daemonizes
     * wineserver and re-parents its helpers, so they aren't reachable from the launched process.
     */
    private fun killPrefixProcesses(prefix: File) {
        val self = android.os.Process.myPid()
        val marker = "WINEPREFIX=${prefix.absolutePath}"
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            // environ is only readable for our own uid's processes; others fail and are skipped.
            val environ = runCatching { File(entry, "environ").readBytes() }.getOrNull() ?: return@forEach
            val matches = String(environ, Charsets.UTF_8).split('\u0000').any { it == marker }
            if (matches) runCatching { android.os.Process.killProcess(pid) }
        }
    }

    /**
     * Records a started process: container RUNNING, exe play stats, and a watcher that flips back
     * on exit and, when the process dies on its own with a failure status, publishes a
     * [WineFailure] for the display screen.
     */
    private suspend fun markStarted(
        containerId: String,
        exeId: String?,
        started: WineProcess,
        log: LaunchLog,
        processLog: File,
        label: String,
        spawnedAt: Long,
        screen: DisplayEnv?,
    ) {
        val pid = started.pid
        mutex.withLock {
            containersById[containerId]?.let { containersById[containerId] = it.copy(status = ContainerStatus.RUNNING) }
            exeId?.let { id ->
                exesById[id]?.let { exesById[id] = it.copy(lastPlayed = System.currentTimeMillis(), playCount = it.playCount + 1) }
            }
            persistLocked()
        }
        runningPids.getOrPut(containerId) { ConcurrentHashMap.newKeySet() }.add(pid)
        processes.getOrPut(containerId) { ConcurrentHashMap.newKeySet() }.add(started)
        scope.launch {
            // Poll faster while Wine is starting so a failure replaces the black screen quickly.
            while (started.isAlive()) {
                delay(if (elapsedMs(spawnedAt) < STARTUP_FAILURE_WINDOW_MS) STARTUP_POLL_MS else PROCESS_POLL_MS)
            }
            val uptimeMs = elapsedMs(spawnedAt)
            // The reaper thread appends "[fable] exit code N" / "killed by signal N"; give it a beat.
            delay(PROCESS_POLL_MS)
            log.section("Process $pid ended")
            log.attachTail(processLog, "Process output (${processLog.name}, includes exit code)")
            // stopContainer() drops the container's process set before ending them: an exit it
            // caused (the user left the display) is not a failure.
            val stoppedByUser = processes[containerId]?.contains(started) != true
            processes[containerId]?.remove(started)
            if (!stoppedByUser) {
                runCatching { reportUnexpectedExit(containerId, label, started, uptimeMs, log, processLog, screen) }
                    .onFailure { Log.w(TAG, "Couldn't analyze the exit of pid $pid", it) }
            }
            val remaining = runningPids[containerId]?.also { it.remove(pid) }
            if (remaining.isNullOrEmpty()) {
                mutex.withLock {
                    val existing = containersById[containerId]
                    if (existing != null && existing.status == ContainerStatus.RUNNING) {
                        containersById[containerId] = existing.copy(status = ContainerStatus.READY)
                        persistLocked()
                    }
                }
            }
        }
    }

    /**
     * Publishes a [WineFailure] when [started] ended with a failure status (anything but a clean
     * exit 0), with what [WineDiagnosis] found in its output.
     */
    private fun reportUnexpectedExit(
        containerId: String,
        label: String,
        started: WineProcess,
        uptimeMs: Long,
        log: LaunchLog,
        processLog: File,
        screen: DisplayEnv?,
    ) {
        val code = started.exitCodeOrNull()
        val dir = processLog.parentFile ?: return
        val clean = if (started.process != null) code == 0 else WineRuntime.exitedCleanly(dir)
        if (clean) {
            log.line("process ${started.pid} exited cleanly after ${uptimeMs}ms")
            return
        }
        val duringStartup = uptimeMs < STARTUP_FAILURE_WINDOW_MS
        val diagnosis = WineDiagnosis.analyze(processLog)
        logDiagnosis(log, diagnosis, screen)
        val status = code?.let { if (it > 128) "killed by signal ${it - 128}" else "exit code $it" }
        val detail = diagnosis.summary() ?: WineRuntime.lastLogLine(dir)
        val reason = listOfNotNull(detail, status?.let { "($it)" }).joinToString(" ").ifBlank { "no output" }
        log.error(
            "$label ${if (duringStartup) "failed during startup" else "stopped"} after ${uptimeMs}ms: $reason",
        )
        val failure = WineFailure(
            containerId = containerId,
            label = label,
            pid = started.pid,
            exitCode = code,
            uptimeMs = uptimeMs,
            duringStartup = duringStartup,
            reason = reason,
            missingLibraries = (diagnosis.missingLibraries + screen?.nativeLibs?.missingRequired.orEmpty()).distinct(),
            logPath = log.path,
        )
        _wineFailures.update { it + (containerId to failure) }
        Log.w(TAG, "Wine for $containerId ended: $reason")
    }

    /** Writes [diagnosis] (and the native libraries that weren't found) into [log]. */
    private fun logDiagnosis(log: LaunchLog, diagnosis: WineDiagnosis.Diagnosis, screen: DisplayEnv?) {
        log.section("Diagnosis")
        diagnosis.describe().forEach { log.line(it) }
        screen?.nativeLibs?.let { report ->
            val missing = report.missing.map { it.target }
            log.line("native libraries not found on this device: ${missing.joinToString().ifEmpty { "none" }}")
            if (diagnosis.freeTypeMissing || diagnosis.freeTypeTooOld || diagnosis.missingLibraries.any { it.contains("freetype") }) {
                log.line(
                    "libfreetype.so in ${report.dir.absolutePath}: " +
                        (
                            report.entries.firstOrNull { it.target == "libfreetype.so" }
                                ?.let { "${it.status} ${it.source.orEmpty()}${it.note?.let { note -> " ($note)" }.orEmpty()}".trim() }
                                ?: "unknown"
                            ),
                )
            }
        }
    }

    private fun elapsedMs(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000L

    private fun persistLocked() {
        publish()
        runCatching { dao.write(ContainerSnapshot(containersById.values.toList(), exesById.values.toList())) }
            .onFailure { logPersistFailure("containers", it) }
    }

    private fun publish() {
        _containers.value = containersById.values.sortedByDescending { it.createdAt }
        _exes.value = exesById.values.sortedWith(compareBy({ it.containerId }, { it.name.lowercase() }))
    }

    private fun safeId(id: String): String {
        require(id.isNotEmpty() && !id.contains('/') && !id.contains('\\') && id != "." && id != "..") {
            "Unsafe container id"
        }
        return id
    }

    companion object {
        private const val TAG = "ContainerRepository"

        /** True when [container] is set to run through FEX rather than Box64. */
        internal fun usesFex(container: Container): Boolean =
            container.translator.trim().equals(NativeLoader.TRANSLATOR_FEX, ignoreCase = true)

        /** Environment variable that points FEX at its x86_64 guest root file system. */
        private const val FEX_ROOTFS_ENV = "FEX_ROOTFS"

        /** How long a freshly started process is watched for an immediate crash. */
        private const val EARLY_EXIT_WINDOW_MS = 1_200L
        private const val PROCESS_POLL_MS = 1_500L

        /**
         * A Wine process that fails within this long after it was spawned failed to start (shown
         * as "Wine couldn't start"); later failures read "Wine stopped". Box64 + Wine routinely
         * take several seconds to get from exec to the first window, well past
         * [EARLY_EXIT_WINDOW_MS].
         */
        private const val STARTUP_FAILURE_WINDOW_MS = 30_000L
        private const val STARTUP_POLL_MS = 400L
        private const val REAPER_GRACE_MS = 200L

        /**
         * Container environment switch for the launcher: `FABLE_LAUNCHER=native` selects the old
         * JNI fork/execve path (diagnostics only). Never passed on to Wine.
         */
        private const val LAUNCHER_ENV = "FABLE_LAUNCHER"
        private const val LAUNCHER_NATIVE = "native"

        /**
         * Room schema the file store stands in for. Not executed.
         * containers.envVars is a JSON object string. status is the enum name.
         */
        const val ROOM_SCHEMA_V1 = """
            CREATE TABLE containers (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                exePath TEXT,
                exeName TEXT,
                wineVersion TEXT NOT NULL,
                dxvkVersion TEXT,
                driverId TEXT,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                graphicsDriver TEXT NOT NULL,
                envVars TEXT NOT NULL,
                screenResolution TEXT NOT NULL,
                isFullscreen INTEGER NOT NULL,
                translator TEXT NOT NULL DEFAULT 'box64'
            );
            CREATE TABLE exes (
                id TEXT NOT NULL PRIMARY KEY,
                containerId TEXT NOT NULL,
                name TEXT NOT NULL,
                path TEXT NOT NULL,
                icon TEXT,
                lastPlayed INTEGER,
                playCount INTEGER NOT NULL,
                FOREIGN KEY(containerId) REFERENCES containers(id) ON DELETE CASCADE
            );
            CREATE INDEX index_exes_containerId ON exes(containerId);
        """

        @Volatile
        private var instance: ContainerRepository? = null

        @Synchronized
        fun get(context: Context): ContainerRepository {
            instance?.let { return it }
            val app = context.applicationContext
            val root = File(app.filesDir, "containers")
            val runtime = WineRuntime(app, AssetRepository.get(app), DriverRepository.get(app), File(app.filesDir, "runtime"))
            val display = DisplayProvider { resolution ->
                val libDir = X11ClientLibs.install(app)
                // Native deps Box64 wraps that the APK doesn't bundle (the bundled FreeType, X
                // extensions, … always win), copied from the system next to the X11 libraries.
                // Never throws.
                val nativeLibs = NativeLibResolver.resolveWithReport(app)
                val ready = DisplayServer.ensureStarted(app, resolution).getOrThrow()
                DisplayEnv(ready.display, ready.socketPath, ready.resolution, libDir.absolutePath, nativeLibs)
            }
            val created = ContainerRepository(
                FileContainerStore(File(root, "index.json")),
                root,
                runtime,
                LaunchLog.logsDir(app),
                display,
            )
            instance = created
            return created
        }
    }
}

/**
 * The translator a launch goes through: [name] is passed to the native launcher
 * ([NativeLoader.TRANSLATOR_BOX64] / [NativeLoader.TRANSLATOR_FEX]), [executable] is `box64` or
 * `FEXInterpreter`, and [environment] holds translator-specific `KEY=VALUE` pairs.
 * [earlyExitHint] is appended to the error when the process dies straight away.
 */
private data class ResolvedTranslator(
    val name: String,
    val executable: File,
    val environment: List<String>,
    val earlyExitHint: String? = null,
)

/**
 * Wine's debug channels for every launch while the start-up failures are being diagnosed:
 * `+loaddll` logs each DLL Wine loads (builtin or native, and its path), `+module` the loader's
 * search for it. Channels not named here keep Wine's default `err`/`fixme` messages, which
 * `-all` used to silence.
 */
internal const val DIAGNOSTIC_WINEDEBUG = "+loaddll,+module"

/**
 * Box64 settings Winlator bionic launches Wine with (GuestProgramLauncherComponent's base
 * variables plus its default "COMPATIBILITY" Box64 preset). BOX64_X11GLX makes Box64's wrapped
 * libX11 advertise GLX; BOX64_NORCFILES stops it reading /etc/box64.box64rc-style files that
 * don't exist on Android. The per-device BOX64_SYSINFO_* variables (no `lscpu` on Android) are
 * added by [HostCpu.box64Environment].
 */
internal val BOX64_ENVIRONMENT = listOf(
    "BOX64_NOBANNER=1",
    "BOX64_DYNAREC=1",
    "BOX64_X11GLX=1",
    "BOX64_NORCFILES=1",
    "BOX64_MMAP32=0",
    "BOX64_AVX=0",
    "BOX64_UNITYPLAYER=1",
    "BOX64_DYNAREC_SAFEFLAGS=2",
    "BOX64_DYNAREC_FASTNAN=0",
    "BOX64_DYNAREC_FASTROUND=0",
    "BOX64_DYNAREC_X87DOUBLE=1",
    "BOX64_DYNAREC_BIGBLOCK=0",
    "BOX64_DYNAREC_STRONGMEM=1",
    "BOX64_DYNAREC_FORWARD=128",
    "BOX64_DYNAREC_CALLRET=0",
    "BOX64_DYNAREC_WAIT=1",
)

/** Starts (or reuses) the X display server for a launch at the container's resolution. */
internal fun interface DisplayProvider {
    fun prepare(resolution: String): DisplayEnv
}

/** What a Wine process needs to reach the display server. */
internal data class DisplayEnv(
    val display: String,
    val socketPath: String,
    val resolution: String,
    val x11LibDir: String,
    /** What [NativeLibResolver] put into [x11LibDir] (FreeType, …); null when it didn't run. */
    val nativeLibs: NativeLibResolver.Report? = null,
)

/** Outcome of [ContainerRepository.launch]. */
sealed interface LaunchResult {
    /** Path of the persisted [LaunchLog] for this attempt, when one could be written. */
    val logPath: String?

    /** Wine started; [pid] is the launcher process id. */
    data class Started(val pid: Int, override val logPath: String? = null) : LaunchResult

    /** Launching is not possible on this device or build (e.g. the native runtime didn't load). */
    data class Unavailable(val reason: String, override val logPath: String? = null) : LaunchResult

    /** The launch couldn't happen: a missing Box64/FEX/Wine download, a bad request, or a crash on start. */
    data class Failed(val reason: String, override val logPath: String? = null) : LaunchResult
}

/**
 * A launched Wine process that ended by itself with a failure status while its display was up
 * (see [ContainerRepository.wineFailures]).
 */
data class WineFailure(
    val containerId: String,
    /** The app's name, or "<container> desktop". */
    val label: String,
    val pid: Int,
    /** Exit status (> 128: killed by signal status - 128); null when unknown (native launcher). */
    val exitCode: Int?,
    /** How long the process ran. */
    val uptimeMs: Long,
    /** True when it died within the startup window, i.e. Wine never came up. */
    val duringStartup: Boolean,
    /** Short explanation: what [WineDiagnosis] recognized, else the last line of output. */
    val reason: String,
    /** Native libraries that were reported missing (`libfreetype.so`, …). */
    val missingLibraries: List<String>,
    /** The persisted [LaunchLog] for this launch. */
    val logPath: String?,
)

private fun LaunchResult.withLog(path: String?): LaunchResult = when (this) {
    is LaunchResult.Started -> copy(logPath = path)
    is LaunchResult.Unavailable -> copy(logPath = path)
    is LaunchResult.Failed -> copy(logPath = path)
}

/** Snapshot written by [ContainerDao]. Mirrors the two Room tables. */
data class ContainerSnapshot(
    val containers: List<Container>,
    val exes: List<ExeEntry>,
)

/**
 * Persistence port. A future Room `@Dao` should implement this and be passed
 * to [ContainerRepository]. [RoomContainerDaoStub] is the unwired placeholder.
 */
interface ContainerDao {
    fun read(): ContainerSnapshot
    fun write(snapshot: ContainerSnapshot)
}

/**
 * JSON stand-in for Room. Atomic replace of `containers/index.json`.
 * A corrupt index is renamed aside and treated as empty so the app can still boot.
 */
class FileContainerStore(private val file: File) : ContainerDao {
    override fun read(): ContainerSnapshot {
        val text = readTextOrNull(file) ?: return ContainerSnapshot(emptyList(), emptyList())
        return try {
            parse(text)
        } catch (error: Exception) {
            Log.e(TAG, "Corrupt container index, moving aside", error)
            runCatching { file.renameTo(File(file.parentFile, "index.json.bak")) }
            ContainerSnapshot(emptyList(), emptyList())
        }
    }

    override fun write(snapshot: ContainerSnapshot) {
        val root = JSONObject()
        root.put("version", 1)
        val containers = JSONArray()
        snapshot.containers.forEach { containers.put(it.toJson()) }
        val exes = JSONArray()
        snapshot.exes.forEach { exes.put(it.toJson()) }
        root.put("containers", containers)
        root.put("exes", exes)
        writeAtomic(file, root.toString(2))
    }

    private fun parse(text: String): ContainerSnapshot {
        val root = JSONObject(text)
        val containers = ArrayList<Container>()
        val containerArray = root.optJSONArray("containers") ?: JSONArray()
        for (i in 0 until containerArray.length()) {
            val item = containerArray.optJSONObject(i) ?: continue
            runCatching { containers += item.toContainer() }
                .onFailure { Log.w(TAG, "Skipping container record", it) }
        }
        val exes = ArrayList<ExeEntry>()
        val exeArray = root.optJSONArray("exes") ?: JSONArray()
        for (i in 0 until exeArray.length()) {
            val item = exeArray.optJSONObject(i) ?: continue
            runCatching { exes += item.toExe() }
                .onFailure { Log.w(TAG, "Skipping exe record", it) }
        }
        return ContainerSnapshot(containers, exes)
    }

    private fun Container.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        putNullable("exePath", exePath)
        putNullable("exeName", exeName)
        put("wineVersion", wineVersion)
        putNullable("dxvkVersion", dxvkVersion)
        putNullable("driverId", driverId)
        put("status", status.name)
        put("createdAt", createdAt)
        put("graphicsDriver", graphicsDriver)
        put("envVars", JSONObject(envVars))
        put("screenResolution", screenResolution)
        put("isFullscreen", isFullscreen)
        put("translator", translator)
    }

    private fun JSONObject.toContainer(): Container = Container(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        name = getString("name"),
        exePath = stringOrNull("exePath"),
        exeName = stringOrNull("exeName"),
        wineVersion = optString("wineVersion", ContainerDefaults.WINE_VERSION),
        dxvkVersion = stringOrNull("dxvkVersion"),
        driverId = stringOrNull("driverId"),
        status = runCatching { ContainerStatus.valueOf(optString("status")) }
            .getOrDefault(ContainerStatus.CREATED),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        graphicsDriver = optString("graphicsDriver", ContainerDefaults.GRAPHICS_DRIVER).removeSuffix(" (default)"),
        envVars = optJSONObject("envVars")?.toStringMap() ?: emptyMap(),
        screenResolution = optString("screenResolution", ContainerDefaults.SCREEN_RESOLUTION),
        isFullscreen = optBoolean("isFullscreen", false),
        translator = optString("translator", ContainerDefaults.TRANSLATOR).ifBlank { ContainerDefaults.TRANSLATOR },
    )

    private fun ExeEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("containerId", containerId)
        put("name", name)
        put("path", path)
        putNullable("icon", icon)
        if (lastPlayed == null) put("lastPlayed", JSONObject.NULL) else put("lastPlayed", lastPlayed)
        put("playCount", playCount)
    }

    private fun JSONObject.toExe(): ExeEntry = ExeEntry(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        containerId = getString("containerId"),
        name = getString("name"),
        path = getString("path"),
        icon = stringOrNull("icon"),
        lastPlayed = longOrNull("lastPlayed"),
        playCount = optInt("playCount", 0),
    )

    companion object {
        private const val TAG = "FileContainerStore"
    }
}

/**
 * Unwired Room placeholder. [ContainerRepository] does not use this class.
 * It exists so the Room seam is concrete until the dependency is added.
 */
class RoomContainerDaoStub : ContainerDao {
    override fun read(): ContainerSnapshot = unsupported()

    override fun write(snapshot: ContainerSnapshot) {
        unsupported()
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "Room is not a project dependency. ContainerRepository uses FileContainerStore. " +
            "Schema: ${ContainerRepository.ROOM_SCHEMA_V1.trim()}",
    )
}
