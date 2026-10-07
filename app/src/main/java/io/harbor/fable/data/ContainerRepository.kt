package io.harbor.fable.data

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.harbor.fable.data.models.Box64Preset
import io.harbor.fable.data.models.Box64Settings
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.data.models.HudPosition
import io.harbor.fable.data.models.HudSettings
import io.harbor.fable.display.DisplayServer
import io.harbor.fable.display.NativeLibResolver
import io.harbor.fable.display.X11ClientLibs
import io.harbor.fable.nativebridge.NativeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.io.IOException
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
        // Containers from before the built-in tools existed (or from an older tool list) get
        // their GPU Info / Direct3D test shortcuts here.
        var reset = false
        containersById.keys.toList().forEach { id -> if (ensureToolEntriesLocked(id)) reset = true }
        // No Wine process survives the app, so a persisted RUNNING / CONFIGURING state is stale.
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
     * Whether [exe] can run: always for user apps and scripted tools, and for a binary tool
     * (Direct3D tests) only when this build bundles it ([ContainerTools]).
     */
    fun toolAvailable(exe: ExeEntry): Boolean {
        val tool = ContainerTools.byId(exe.toolId) ?: return true
        return tool.isAvailable(runtime?.bundledTools.orEmpty())
    }

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

    /** Downloaded DXVK packages a container can pick, newest first. Reads the disk: call off the main thread. */
    suspend fun availableDxvkBuilds(): List<ComponentBuild> = withContext(Dispatchers.IO) {
        runCatching { runtime?.dxvkBuilds().orEmpty() }.getOrElse { error ->
            Log.w(TAG, "Could not list DXVK builds", error)
            emptyList()
        }
    }

    /** Downloaded VKD3D-Proton packages a container can pick, newest first. Call off the main thread. */
    suspend fun availableVkd3dBuilds(): List<ComponentBuild> = withContext(Dispatchers.IO) {
        runCatching { runtime?.vkd3dBuilds().orEmpty() }.getOrElse { error ->
            Log.w(TAG, "Could not list VKD3D-Proton builds", error)
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
        vkd3dVersion: String? = null,
        driverId: String? = null,
        translator: String = ContainerDefaults.TRANSLATOR,
        box64: Box64Settings = Box64Settings(),
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
                vkd3dVersion = vkd3dVersion,
                driverId = driverId,
                translator = translator,
                box64 = box64,
                status = ContainerStatus.CREATED,
            )
        )
    }

    suspend fun insert(container: Container): Container = mutex.withLock {
        withContext(Dispatchers.IO) {
            containersById[container.id] = container
            directory(container.id).mkdirs()
            ensureToolEntriesLocked(container.id)
            persistLocked()
            container
        }
    }

    /**
     * Adds the shortcuts for [ContainerTools.all] that [containerId] doesn't have yet and keeps
     * existing ones pointing at the current file. The files themselves are written on launch,
     * once the prefix exists. Returns true when anything changed. Call with [mutex] held (or
     * from `init`).
     */
    private fun ensureToolEntriesLocked(containerId: String): Boolean {
        var changed = false
        val existing = exesById.values.filter { it.containerId == containerId && it.toolId != null }
            .associateBy { it.toolId }
        for (tool in ContainerTools.all) {
            val entry = existing[tool.id]
            if (entry == null) {
                val created = ExeEntry(containerId = containerId, name = tool.name, path = tool.windowsPath, toolId = tool.id)
                exesById[created.id] = created
                changed = true
            } else if (entry.name != tool.name || entry.path != tool.windowsPath) {
                exesById[entry.id] = entry.copy(name = tool.name, path = tool.windowsPath)
                changed = true
            }
        }
        return changed
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

    /**
     * Registers [entry]. Throws [IllegalArgumentException] when the container already has a
     * user app at the same path, so the same game can't be added twice by picking it again.
     */
    suspend fun addExe(entry: ExeEntry): ExeEntry = mutex.withLock {
        withContext(Dispatchers.IO) {
            val container = containersById[entry.containerId]
                ?: throw NoSuchElementException("Unknown container ${entry.containerId}")
            if (!entry.isTool && exesById.values.any { it.isDuplicateOf(entry) }) {
                throw IllegalArgumentException("Already added")
            }
            exesById[entry.id] = entry
            if (container.exePath.isNullOrBlank() && !entry.isTool) {
                containersById[container.id] = container.copy(
                    exePath = entry.path,
                    exeName = entry.name,
                )
            }
            persistLocked()
            entry
        }
    }

    /**
     * Whether this entry and [other] are two registrations of the same user app in the same
     * container. `content://` URIs are compared case-insensitively (providers differ in how they
     * case the same document id); filesystem and Windows paths are compared as-is.
     */
    private fun ExeEntry.isDuplicateOf(other: ExeEntry): Boolean {
        if (id == other.id || isTool || other.isTool || containerId != other.containerId) return false
        val a = path.trim()
        val b = other.path.trim()
        val contentUri = a.startsWith("content://", ignoreCase = true) || b.startsWith("content://", ignoreCase = true)
        return a.equals(b, ignoreCase = contentUri)
    }

    /**
     * Removes the shortcut [id]; the program's files are left alone. When it was the
     * container's primary app, the next user app (if any) takes over. The icon file Fable
     * extracted for it is deleted unless another entry still shows it.
     */
    suspend fun removeExe(id: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val removed = exesById.remove(id) ?: return@withContext false
            val container = containersById[removed.containerId]
            if (container != null && container.exePath == removed.path) {
                val replacement = exesById.values.firstOrNull { it.containerId == container.id && !it.isTool }
                containersById[container.id] = container.copy(
                    exePath = replacement?.path,
                    exeName = replacement?.name,
                )
            }
            persistLocked()
            removed.icon?.let { icon ->
                if (exesById.values.none { it.icon == icon }) {
                    val file = File(icon)
                    // Only Fable's own extracted icons (ExeIcons.save) are ours to delete.
                    if (file.parentFile?.name == "exe-icons" && !file.delete() && file.exists()) {
                        Log.w(TAG, "Couldn't delete icon $icon")
                    }
                }
            }
            true
        }
    }

    /** Makes [exeId] the container's primary executable (the one "Launch" starts). */
    suspend fun setPrimaryExe(exeId: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val exe = exesById[exeId] ?: return@withContext false
            if (exe.isTool) return@withContext false
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
            // "Launch" without a choice starts the primary app, never a built-in tool.
            val apps = candidates.filter { !it.isTool }
            val exe = when {
                exeId != null -> candidates.firstOrNull { it.id == exeId }
                else -> apps.firstOrNull { it.path == container.exePath } ?: apps.firstOrNull()
            } ?: return LaunchResult.Failed("Add an app first")
            container to exe
        }
        return start(container, exe)
    }

    /**
     * Starts the full Wine desktop (`wine explorer /desktop=shell,<resolution>`) in the
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
        if (!usesFex(configured)) {
            log.line("box64 preset: ${configured.box64.summary}${if (configured.box64.useRcFile) ", rc file ${translator.rcFile ?: "not in the package"}" else ""}")
        }
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

        // 2b. Complete the prefix the way Winlator does: prefixPack.txz ships system32/syswow64
        //     without Wine's DLLs, and outside prefix bootstrap Wine only loads builtins whose
        //     file is in system32, so kernel32.dll failed with c0000135. Containers set up by
        //     older builds get the copy here, on their next launch; drive links are checked too.
        log.section("Prefix")
        val copying = WinePrefix.needsSystemDlls(dir, wineBuild)
        if (copying) {
            log.line("copying Wine's DLLs into drive_c/windows/system32 and syswow64 (once per Wine build)")
            setStatus(container.id, ContainerStatus.CONFIGURING)
        }
        val prefix = try {
            withContext(Dispatchers.IO) { WinePrefix.ensure(prefix = dir, wineRoot = dir, build = wineBuild) }
        } finally {
            if (copying) withContext(NonCancellable) { setStatus(container.id, ContainerStatus.READY) }
        }
        prefix.describe().forEach { log.line(it) }
        if (!prefix.hasKernel32) {
            log.error("${WinePrefix.KERNEL32} is missing; Wine would stop with c0000135")
            return LaunchResult.Failed(
                "Couldn't finish the Wine prefix (no kernel32.dll in system32). Free some storage and try again"
            )
        }

        // 2c. Direct3D the way Winlator sets it up: DXVK (d3d8/9/10/11, dxgi) and VKD3D-Proton
        //     (d3d12) DLLs in system32/syswow64, loaded as native through WINEDLLOVERRIDES.
        //     Without them a D3D game goes through WineD3D, which needs GLX OpenGL that Android
        //     doesn't have: the desktop works, but games (ULTRAKILL) stay black and exit.
        log.section("Direct3D (DXVK / VKD3D-Proton)")
        val direct3d = installDxWrappers(runtime, configured, dir, log)
        direct3d.describe().forEach { log.line(it) }
        checkDxvk(direct3d, configured, exe, dir, log)?.let { return it }
        val dxvkInstalled = direct3d.installed[DxWrappers.Kind.DXVK] != null
        val dxvkConf = if (dxvkInstalled) DxWrappers.ensureDxvkConf(dir) else null
        dxvkConf?.let { log.line("dxvk.conf: ${it.absolutePath}") }
        if (dxvkInstalled && dxvkConf == null) {
            log.error("dxvk.conf couldn't be written in ${dir.absolutePath}; DXVK_CONFIG_FILE won't be set (DXVK uses its defaults)")
        }

        // 2d. GPU Info and the Direct3D tests in C:\fable\tools (Winlator puts its test programs
        //     into every container the same way); refreshed after an app update.
        log.section("Container tools")
        val tools = withContext(Dispatchers.IO) { runtime.installContainerTools(dir) }
        tools.describe().forEach { log.line(it) }
        val tool = ContainerTools.byId(exe?.toolId)
        if (tool != null && !ContainerTools.file(dir, tool).isFile) {
            log.error("${tool.fileName} isn't in ${ContainerTools.WINDOWS_DIR}: this build doesn't bundle it")
            return LaunchResult.Failed(
                "${tool.name} isn't included in this build. Its .exe goes in assets/${ContainerTools.ASSET_DIR}"
            )
        }

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

        // 4. Resolve what to run: `wine explorer /desktop=<name>,WxH [start /d <dir> <exe>]`,
        //    Winlator's guest command, so every app runs inside a virtual desktop the size of the
        //    X screen. Winlator names the desktop "nogui" when it launches a shortcut (its
        //    explorer.exe skips the taskbar/start menu for that name, so the game owns the whole
        //    screen) and "shell" for desktop mode, which opens a file browser so the user sees
        //    something instead of a black screen. That browser starts in the phone's Downloads
        //    folder (mapped as D:), where installers and games people copy over actually are;
        //    C:\ (the prefix's Windows tree) only when Downloads can't be reached.
        val program = "explorer"
        val arguments: List<String>
        if (exe == null) {
            val root = downloadsDrive(dir, log) ?: "C:\\"
            arguments = listOf("/desktop=$DESKTOP_SHELL,$desktopSize", "/root,$root")
        } else {
            val path = runtime.materializeExecutable(dir, exe.id, exe.name, exe.path)
                ?: run {
                    log.error("executable not readable: ${exe.path}")
                    return LaunchResult.Failed("Can't open ${exe.name}. Add it again")
                }
            val programFile = File(path)
            log.line("program: $path (exists=${programFile.isFile}, size=${programFile.length()})")
            val windowsPath = toWindowsPath(path, dir)
            val windowsDir = windowsPath.substringBeforeLast('\\', missingDelimiterValue = "C:\\")
            arguments = listOf("/desktop=$DESKTOP_NOGUI,$desktopSize", "start", "/d", windowsDir, windowsPath)
        }
        val label = exe?.name ?: "${current.name} desktop"

        // Android has no lscpu; Box64 builds that don't read BOX64_SYSINFO_* run it through
        // popen(), so put a stub first on the Wine process's PATH (<container>/bin).
        val cpu = HostCpu.get()
        val lscpu = cpu.installLscpu(File(dir, "bin"))
        log.line("host CPU: ${cpu.name}, ${cpu.count} cores, max ${cpu.maxFrequencyHz?.let { "${it / 1_000_000} MHz" } ?: "unknown"}")
        log.line("lscpu stub: ${lscpu?.absolutePath ?: "couldn't be written"}")

        // Where Wine's builtin PE DLLs are (WINEDLLPATH, extra to the dll_dir ntdll.so derives
        // from its own location at run time) and whether kernel32.dll / ntdll.dll are there. With
        // them missing the Wine package itself is incomplete. (The c0000135 Fable used to hit
        // had a different cause, an empty system32; WinePrefix fixes that above.)
        val wineLocations = WineRuntime.wineLocationEnvironment(dir)
        log.section("Wine DLL search path")
        val wineDllPath = wineLocations.firstOrNull { it.startsWith("WINEDLLPATH=") }?.substringAfter('=')
        log.line("WINEDLLPATH=${wineDllPath ?: "(not set: none of ${WineRuntime.WINE_DLL_DIRS.joinToString()} exist in ${dir.absolutePath})"}")
        val dllDirs = WineRuntime.wineDllDirs(dir)
        val kernel32 = dllDirs.map { File(it, "kernel32.dll") }.filter { it.isFile }
        log.line(
            "kernel32.dll in a WINEDLLPATH dir: " +
                if (kernel32.isEmpty()) "NO (the Wine package is incomplete)" else "yes (${kernel32.joinToString { it.absolutePath }})",
        )
        val ntdll = File(dir, "lib/wine/x86_64-windows/ntdll.dll").takeIf { it.isFile }
            ?: File(dir, "lib64/wine/x86_64-windows/ntdll.dll").takeIf { it.isFile }
        log.line("ntdll.dll in x86_64-windows: ${ntdll?.let { "yes (${it.absolutePath}, ${it.length()} bytes)" } ?: "NO"}")
        val system32Kernel32 = File(dir, "drive_c/windows/system32/kernel32.dll")
        log.line(
            "kernel32.dll in prefix system32: " +
                if (system32Kernel32.isFile) "yes (${system32Kernel32.length()} bytes)" else "NO",
        )
        wineLocations.filterNot { it.startsWith("WINEDLLPATH=") }.forEach { log.line(it) }

        // Fable's native overrides for DXVK / VKD3D-Proton first, the container's own after them
        // (Wine lets a later entry for the same DLL win), as one variable.
        val dllOverrides = DxWrappers.mergeOverrides(direct3d.dllOverrides, current.envVars[DLL_OVERRIDES_ENV])

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
            // Winlator sets these in setupXEnvironment before every launch. They are safe
            // defaults that improve compatibility; the container's own env vars (below) can
            // override any of them.
            // - WINEESYNC=1: enables eventfd-based synchronization (faster than server-side).
            // - WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1: stops Wine's dxgi.dll from creating
            //   its own DXGI device manager, which would conflict with DXVK's dxgi.dll and can
            //   cause D3D11CreateDeviceAndSwapChain to return DXGI_ERROR_UNSUPPORTED.
            // - MESA_NO_ERROR=1, MESA_DEBUG=silent: suppress Mesa error spam in the log.
            // - vblank_mode=0: disable vsync wait in Mesa (Fable's X server syncs).
            if (current.envVars["WINEESYNC"].isNullOrEmpty()) add("WINEESYNC=1")
            add("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1")
            add("MESA_NO_ERROR=1")
            add("MESA_DEBUG=silent")
            add("vblank_mode=0")
            addAll(translator.environment)
            // What Winlator's bionic Wine reads instead of /etc/resolv.conf and netlink.
            addAll(runtime.bionicWineEnvironment())
            // WINEDLLPATH / WINELOADER / WINESERVER: explicit locations of the container's Wine
            // files. Belt and braces: Wine derives the same paths from ntdll.so's location, and
            // WINEDLLPATH can't stand in for missing system32 files (see WinePrefix).
            addAll(wineLocations)
            dllOverrides?.let { add("$DLL_OVERRIDES_ENV=$it") }
            if (dxvkConf != null) {
                // A Windows path: DXVK opens it through Wine's file APIs.
                add("DXVK_CONFIG_FILE=${toWindowsPath(dxvkConf.absolutePath, dir)}")
            }
            if (direct3d.installed[DxWrappers.Kind.VKD3D] != null) {
                // Winlator's default VKD3D feature level.
                add("VKD3D_FEATURE_LEVEL=12_1")
            }
            if (screen != null) {
                add("DISPLAY=${screen.display}")
                add("FABLE_VULKAN_SOCKET=${File(screen.socketPath).parent}/V0")
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
            // The container's own variables come last so they can override anything above
            // (its WINEDLLOVERRIDES is already part of the merged value).
            current.envVars.forEach { (key, value) ->
                if (key != LAUNCHER_ENV && key != DLL_OVERRIDES_ENV) add("$key=$value")
            }
        }
        val driver = runtime.activeDriverLibrary()
        val useNative = current.envVars[LAUNCHER_ENV]?.trim().equals(LAUNCHER_NATIVE, ignoreCase = true)
        log.section("Command")
        log.line("${translator.executable.absolutePath} ${wineBinary.absolutePath} $program ${arguments.joinToString(" ")}".trim())
        log.line("driver: ${driver ?: "none (system Vulkan)"}")
        log.line("launcher: ${if (useNative) "native JNI fork/execve ($LAUNCHER_ENV=$LAUNCHER_NATIVE)" else "ProcessBuilder"}")
        log.section("Environment (Fable additions; the launcher also sets WINEPREFIX, HOME, TMPDIR, PATH, BOX64_*)")
        environment.forEach { log.line(it) }
        checkDirect3dEnvironment(direct3d, environment, log)
        // Optional `wineboot -u` for a freshly extracted prefix, only when the container asks for
        // it ($WINEBOOT_ENV=1). Winlator-Ludashi never runs it: prefixPack.txz plus the DLL copy
        // (WinePrefix) is a complete prefix, and Wine runs `wineboot --init` by itself on every
        // start. A forced update under Box64 re-registers every DLL (minutes) and can stop on
        // the Wine Mono / Gecko install prompts, so it isn't done by default.
        if (runtime.winebootPending(dir) && current.envVars[WINEBOOT_ENV]?.trim() == "1") {
            setStatus(container.id, ContainerStatus.CONFIGURING)
            runWineboot(runtime, dir, wineBinary, translator, environment, driver, log)
            setStatus(container.id, ContainerStatus.READY)
        }
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
     * Runs `<translator> <container>/bin/wine wineboot -u` for a prefix [WineRuntime.installWine]
     * just unpacked, and waits for it (up to [WINEBOOT_TIMEOUT_MS]).
     *
     * Opt-in ([WINEBOOT_ENV]=1 in the container's environment): it brings the prefix's registry
     * up to date with the installed Wine build, which prefixPack.txz normally already matches.
     * It uses the launch's own [environment] (BOX64_*, LD_LIBRARY_PATH, FONTCONFIG_FILE,
     * DISPLAY, …) and needs the DLLs [WinePrefix] copied, like any Wine process. Only the
     * diagnostic WINEDEBUG is toned down (+loaddll,+module on wineboot's dozens of helper
     * processes would bury the error lines), and mscoree/mshtml are disabled unless the
     * container's WINEDLLOVERRIDES says something about them, so the update doesn't wait on the
     * Wine Mono / Gecko install prompts. A failure is logged but never blocks the launch.
     */
    private suspend fun runWineboot(
        runtime: WineRuntime,
        dir: File,
        wineBinary: File,
        translator: ResolvedTranslator,
        environment: List<String>,
        driver: String?,
        log: LaunchLog,
    ) {
        log.section("Prefix initialisation (wineboot -u)")
        val winebootEnv = environment.map { if (it == "WINEDEBUG=$DIAGNOSTIC_WINEDEBUG") "WINEDEBUG=$WINEBOOT_WINEDEBUG" else it }
            .let { env ->
                val overrides = env.firstOrNull { it.startsWith("$DLL_OVERRIDES_ENV=") }
                when {
                    overrides == null -> env + "$DLL_OVERRIDES_ENV=$NO_MONO_GECKO"
                    overrides.contains("mscoree") || overrides.contains("mshtml") -> env
                    else -> env.map { if (it == overrides) "$it;$NO_MONO_GECKO" else it }
                }
            }
        val winebootLog = File(dir, WineRuntime.WINEBOOT_LOG)
        val outcome = withContext(Dispatchers.IO) {
            WineProcessLauncher.launch(
                WineProcessLauncher.Request(
                    containerDir = dir,
                    wine = wineBinary,
                    translatorName = translator.name,
                    translator = translator.executable,
                    program = "wineboot",
                    args = listOf("-u"),
                    env = winebootEnv,
                    driverPath = driver,
                    processLogName = WineRuntime.WINEBOOT_LOG,
                ),
                log,
            )
        }
        val started = when (outcome) {
            is WineProcessLauncher.Outcome.Started -> outcome.process
            is WineProcessLauncher.Outcome.Failed -> {
                log.error("wineboot -u couldn't start: ${outcome.reason}; launching anyway")
                log.attachTail(winebootLog, "wineboot output (${winebootLog.name})")
                return
            }
        }
        val process = started.process
        val startedAt = System.nanoTime()
        val finished = withContext(Dispatchers.IO) {
            process?.waitFor(WINEBOOT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ?: false
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        if (finished) {
            val code = process?.exitValue()
            if (code == 0) {
                log.line("wineboot -u finished in ${elapsedMs}ms")
                runtime.markWinebootDone(dir)
            } else {
                // Left pending so it's retried on the next launch once the cause is fixed.
                log.error("wineboot -u exited with status $code after ${elapsedMs}ms; launching anyway")
            }
        } else {
            // Don't stall every launch on a wineboot that hangs: give up on it for this prefix.
            log.error("wineboot -u still running after ${WINEBOOT_TIMEOUT_MS}ms; stopped it and launching anyway")
            started.destroy()
            killPrefixProcesses(dir)
            runtime.markWinebootDone(dir)
        }
        // Give the reaper a moment to append the exit line before the tail is copied.
        delay(REAPER_GRACE_MS)
        log.attachTail(winebootLog, "wineboot output (${winebootLog.name})")
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
     * Puts the container's DXVK and VKD3D-Proton into its prefix ([DxWrappers]) on every launch
     * (a no-op when they're already in place). [Container.dxvkVersion] / [Container.vkd3dVersion]
     * pick the builds: null for the newest download, [ContainerDefaults.DXVK_OFF] /
     * [ContainerDefaults.VKD3D_OFF] for none (WineD3D / Wine's builtin d3d12). A layer whose
     * package can't be unpacked is left as it is in the prefix rather than removed.
     */
    private suspend fun installDxWrappers(
        runtime: WineRuntime,
        container: Container,
        dir: File,
        log: LaunchLog,
    ): DxWrappers.Report {
        val wanted = LinkedHashMap<DxWrappers.Kind, DxWrappers.Package?>()
        val dxvkChoice = container.dxvkVersion?.trim()?.ifEmpty { null }
        if (dxvkChoice.equals(ContainerDefaults.DXVK_OFF, ignoreCase = true)) {
            log.line("DXVK: off for this container; Direct3D 8-11 go through WineD3D (needs OpenGL)")
            wanted[DxWrappers.Kind.DXVK] = null
        } else {
            resolveDxWrapper(runtime, DxWrappers.Kind.DXVK, runtime.dxvkArchives(), dxvkChoice, log)
                .onSuccess { wanted[DxWrappers.Kind.DXVK] = it }
        }
        val vkd3dChoice = container.vkd3dVersion?.trim()?.ifEmpty { null }
        if (vkd3dChoice.equals(ContainerDefaults.VKD3D_OFF, ignoreCase = true)) {
            log.line("VKD3D-Proton: off for this container; Direct3D 12 uses Wine's builtin d3d12")
            wanted[DxWrappers.Kind.VKD3D] = null
        } else {
            resolveDxWrapper(runtime, DxWrappers.Kind.VKD3D, runtime.vkd3dArchives(), vkd3dChoice, log)
                .onSuccess { wanted[DxWrappers.Kind.VKD3D] = it }
        }
        return withContext(Dispatchers.IO) { DxWrappers.apply(prefix = dir, wineRoot = dir, wanted = wanted) }
    }

    /**
     * After [installDxWrappers]: when a game or one of the Direct3D 9/11 tests is about to start
     * without DXVK, fails the launch with a message that says what to do (Direct3D would go
     * through WineD3D, which needs OpenGL Android doesn't have: the game stays black and the
     * D3D11 test's swap chain creation fails with DXGI_ERROR_INVALID_CALL, 0x887A0001). The Wine
     * desktop and GPU Info run without DXVK. When DXVK is installed, checks that its `d3d11.dll`
     * and `dxgi.dll` really are in the prefix's system32 and load as native; that part only logs.
     * Returns the failure to report, or null to go on.
     */
    private fun checkDxvk(direct3d: DxWrappers.Report, container: Container, exe: ExeEntry?, dir: File, log: LaunchLog): LaunchResult? {
        val dxvk = direct3d.installed[DxWrappers.Kind.DXVK]
        // The Wine desktop and GPU Info are useful without DXVK; D3D tests and games aren't.
        val isD3dTest = exe?.toolId == ContainerTools.D3D9_TEST.id || exe?.toolId == ContainerTools.D3D11_TEST.id
        val needsDxvk = exe != null && !exe.isTool || isD3dTest
        if (dxvk == null) {
            if (!needsDxvk) return null
            val off = container.dxvkVersion?.trim().equals(ContainerDefaults.DXVK_OFF, ignoreCase = true)
            val subject = if (isD3dTest) "${exe?.name} needs it" else "Direct3D games will not render"
            val warning = if (off) {
                "DXVK is off for this container — $subject. Pick a DXVK build in the container's Settings."
            } else {
                "DXVK is not installed — $subject. Go to the Assets tab to download DXVK."
            }
            log.line("WARNING $warning")
            Log.w(TAG, "${container.name}: $warning")
            return LaunchResult.Failed(warning)
        }
        val system32 = File(dir, SYSTEM32_DIR)
        val native = direct3d.nativeDlls[DxWrappers.Kind.DXVK].orEmpty()
        var allThere = true
        for (name in DXVK_REQUIRED_DLLS) {
            val file = File(system32, name)
            if (!file.isFile) {
                allThere = false
                log.error("DXVK check: $name is NOT in ${system32.absolutePath}; Wine will use its builtin (WineD3D) instead")
                continue
            }
            // DXVK's DLL replaced the copy of Wine's builtin WinePrefix put there; an identical
            // size means the replacement didn't happen.
            val builtin = listOf("lib/wine/x86_64-windows", "lib64/wine/x86_64-windows")
                .map { File(dir, "$it/$name") }
                .firstOrNull { it.isFile }
            val looksBuiltin = builtin != null && builtin.length() == file.length()
            log.line(
                "DXVK check: $name in system32: yes (${file.length()} bytes" +
                    (if (looksBuiltin) ", same size as Wine's builtin: probably NOT DXVK's" else "") +
                    (if (name in native) ", loaded as native" else ", NOT in WINEDLLOVERRIDES") + ")",
            )
            if (looksBuiltin || name !in native) allThere = false
        }
        if (allThere) {
            log.line("DXVK check: $dxvk ok, ${DXVK_REQUIRED_DLLS.joinToString(" and ")} are DXVK's and load as native")
        } else {
            Log.w(TAG, "${container.name}: DXVK $dxvk is recorded as installed but its DLLs aren't all in place")
        }
        return null
    }

    /**
     * Confirms from the exact list handed to the launcher that Wine will load DXVK and
     * VKD3D-Proton as native: `WINEDLLOVERRIDES` has to be there and must not end up with a
     * builtin-first mode for d3d11/dxgi/d3d12 (the container's own value is merged after
     * Fable's and wins), and `DXVK_CONFIG_FILE` has to point at dxvk.conf.
     */
    private fun checkDirect3dEnvironment(direct3d: DxWrappers.Report, environment: List<String>, log: LaunchLog) {
        val wanted = buildList {
            if (direct3d.installed[DxWrappers.Kind.DXVK] != null) addAll(listOf("d3d11", "dxgi"))
            if (direct3d.installed[DxWrappers.Kind.VKD3D] != null) add("d3d12")
        }
        if (wanted.isEmpty()) return
        val overrides = environment.lastOrNull { it.startsWith("$DLL_OVERRIDES_ENV=") }?.substringAfter('=')
        if (overrides == null) {
            log.error("$DLL_OVERRIDES_ENV isn't in the launch environment; Wine will load its builtin Direct3D DLLs")
        } else {
            val modes = parseDllOverrides(overrides)
            val notNative = wanted.filter { modes[it]?.startsWith("n") != true }
            if (notNative.isEmpty()) {
                log.line("$DLL_OVERRIDES_ENV check: ${wanted.joinToString()} load as native")
            } else {
                log.error(
                    "$DLL_OVERRIDES_ENV check: ${notNative.joinToString { "$it=${modes[it] ?: "(not listed)"}" }}: " +
                        "these won't load DXVK / VKD3D-Proton (the container's own $DLL_OVERRIDES_ENV may override them)",
                )
            }
        }
        if (direct3d.installed[DxWrappers.Kind.DXVK] != null) {
            val config = environment.lastOrNull { it.startsWith("DXVK_CONFIG_FILE=") }
            log.line("DXVK_CONFIG_FILE check: ${config?.substringAfter('=') ?: "NOT set"}")
        }
    }

    /**
     * The unpacked package for one layer: success(null) when nothing is downloaded (Wine's
     * builtin is used, which the log flags as an error), failure when the chosen package
     * couldn't be unpacked.
     */
    private suspend fun resolveDxWrapper(
        runtime: WineRuntime,
        kind: DxWrappers.Kind,
        archives: List<File>,
        preferred: String?,
        log: LaunchLog,
    ): Result<DxWrappers.Package?> {
        if (archives.isEmpty()) {
            // Not a launch failure (the Wine desktop and non-D3D programs still work), but the
            // reason a game stays black, so it has to stand out in the log and in logcat.
            val message = when (kind) {
                DxWrappers.Kind.DXVK ->
                    "DXVK: no package downloaded! Games will show a black screen (WineD3D needs OpenGL). " +
                        "Download DXVK from the Assets tab."
                DxWrappers.Kind.VKD3D ->
                    "VKD3D-Proton: no package downloaded! Direct3D 12 games will likely show a black screen " +
                        "(they fall back to Wine's builtin d3d12). Download VKD3D-Proton from the Assets tab."
            }
            log.error(message)
            Log.e(TAG, message)
            return Result.success(null)
        }
        val archive = WineRuntime.pickComponent(archives, preferred) ?: archives.first().also {
            log.line("${kind.label}: $preferred isn't downloaded; using the newest, ${it.name}")
        }
        return try {
            Result.success(DxWrappers.Package(kind, archive.name, runtime.unpackDxWrapper(archive)))
        } catch (error: IOException) {
            log.error("${kind.label}: ${error.message}; leaving the prefix's ${kind.label} DLLs as they are")
            Result.failure(error)
        }
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
                        // The container's preset (Winlator's Box64PresetManager values) plus its own
                        // changes, then CPU facts so Box64 doesn't popen("lscpu") (HostCpu).
                        environment = container.box64.environment(status.rcFile?.absolutePath) +
                            HostCpu.get().box64Environment(),
                        rcFile = status.rcFile,
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

    /**
     * Points the prefix's `dosdevices/d:` at the device's public Downloads folder and returns
     * `D:\` when that folder exists and is readable, null otherwise (no shared storage, or the
     * storage permission wasn't granted, in which case explorer would open an empty drive).
     *
     * The link is (re)written on every desktop launch rather than once: Wine reads `dosdevices`
     * at start-up, the Downloads path can differ per user profile, and a stale link costs nothing
     * to replace. `Os.symlink` rather than `java.nio.file.Files`: it reports `EEXIST` and friends
     * through plain errno values, and Android's `Files` implementation has been flaky with links
     * pointing outside the app's sandbox. Never throws.
     */
    private fun downloadsDrive(prefix: File, log: LaunchLog): String? {
        val downloads = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.takeIf { it.isDirectory && it.canRead() }
            ?: File("/sdcard/Download").takeIf { it.isDirectory && it.canRead() }
        if (downloads == null) {
            log.line("Downloads folder not readable (no storage permission?); desktop opens at C:\\")
            return null
        }
        val dosDevices = File(prefix, "dosdevices")
        if (!dosDevices.isDirectory && !dosDevices.mkdirs()) {
            log.line("couldn't create ${dosDevices.absolutePath}; desktop opens at C:\\")
            return null
        }
        val link = File(dosDevices, "d:")
        val target = downloads.absolutePath
        try {
            val current = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
            if (current != target) {
                // A regular file or directory named d: would be user data; only links are replaced.
                if (current != null || !link.exists()) {
                    runCatching { Os.remove(link.absolutePath) }
                    Os.symlink(target, link.absolutePath)
                } else {
                    log.line("${link.absolutePath} exists and isn't a link; desktop opens at C:\\")
                    return null
                }
            }
        } catch (error: Exception) {
            // Fall back to a shell, same as the existing extraction fallback: the link is just a
            // convenience and must never stop the launch.
            val fallback = runCatching {
                Runtime.getRuntime().exec(arrayOf("ln", "-sfn", target, link.absolutePath)).waitFor() == 0
            }.getOrDefault(false)
            if (!fallback) {
                log.line("couldn't link D: to $target (${error.message ?: error.javaClass.simpleName}); desktop opens at C:\\")
                return null
            }
        }
        log.line("D: -> $target (desktop opens there)")
        return "D:\\"
    }

    /**
     * Converts a host path to a Windows path Wine can open. Paths inside the container's
     * `drive_c` directory map to `C:\\...` (where the Wine prefix's C: drive is); everything
     * else maps to `Z:\\...` (Wine's default mapping of the host filesystem).
     *
     * This mirrors Winlator's `WineUtils.unixToDOSPath`, which walks the container's drives
     * (drive_c → C:, D:, E:, …) to find the shortest DOS path for a Unix path. Fable's
     * containers only have a C: drive and an implicit Z: for the host, so this is simpler.
     */
    private fun toWindowsPath(path: String, containerDir: File): String {
        if (!path.startsWith("/")) return path
        val driveC = File(containerDir, "drive_c")
        val driveCAbs = driveC.absolutePath
        if (path.startsWith(driveCAbs + "/") || path == driveCAbs) {
            val relative = path.removePrefix(driveCAbs).removePrefix("/")
            return "C:\\" + relative.replace('/', '\\')
        }
        return "Z:" + path.replace('/', '\\')
    }

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
     * Freezes ([paused] true, SIGSTOP) or thaws (SIGCONT) every Wine process of [containerId],
     * for the display screen's Pause button. The whole prefix is signalled, not just the launcher
     * pid: wineserver and the game's own children are re-parented away from it (see
     * [killPrefixProcesses]) and a game whose server keeps running while it is stopped would
     * time out on its own windows. Runs on the repository's scope; never throws.
     */
    fun setPausedInBackground(containerId: String, paused: Boolean) {
        val signal = if (paused) OsConstants.SIGSTOP else OsConstants.SIGCONT
        scope.launch {
            runCatching {
                val count = signalPrefixProcesses(directory(containerId), signal)
                Log.i(TAG, "${if (paused) "Paused" else "Resumed"} $count Wine process(es) of $containerId")
            }.onFailure { Log.w(TAG, "Couldn't ${if (paused) "pause" else "resume"} $containerId", it) }
        }
    }

    /**
     * SIGKILLs this app's other processes whose WINEPREFIX is [prefix]. Wine daemonizes
     * wineserver and re-parents its helpers, so they aren't reachable from the launched process.
     */
    private fun killPrefixProcesses(prefix: File) {
        signalPrefixProcesses(prefix, OsConstants.SIGKILL)
    }

    /** Sends [signal] to every other process of this uid whose WINEPREFIX is [prefix]; returns how many. */
    private fun signalPrefixProcesses(prefix: File, signal: Int): Int {
        val self = android.os.Process.myPid()
        val marker = "WINEPREFIX=${prefix.absolutePath}"
        var count = 0
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            // environ is only readable for our own uid's processes; others fail and are skipped.
            val environ = runCatching { File(entry, "environ").readBytes() }.getOrNull() ?: return@forEach
            val matches = String(environ, Charsets.UTF_8).split('\u0000').any { it == marker }
            if (matches && runCatching { android.os.Process.sendSignal(pid, signal) }.isSuccess) count++
        }
        return count
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
         * How long the first-launch `wineboot -u` may take. Under Box64 it re-registers the
         * built-in DLLs and starts services.exe/winedevice, which can take a minute or more.
         */
        private const val WINEBOOT_TIMEOUT_MS = 180_000L

        /** Wine's default err output for wineboot, without the fixme noise. */
        private const val WINEBOOT_WINEDEBUG = "fixme-all"

        /**
         * Container environment switch for the launcher: `FABLE_LAUNCHER=native` selects the old
         * JNI fork/execve path (diagnostics only). Never passed on to Wine.
         */
        private const val LAUNCHER_ENV = "FABLE_LAUNCHER"

        /** Wine's DLL load-order variable; Fable's and the container's values are merged. */
        private const val DLL_OVERRIDES_ENV = "WINEDLLOVERRIDES"

        /**
         * Virtual desktop names passed to `wine explorer /desktop=<name>,WxH`, as Winlator's
         * GuestProgramLauncherComponent uses them: [DESKTOP_NOGUI] when a single app is launched
         * (Winlator's explorer.exe shows no taskbar or start menu for that name), [DESKTOP_SHELL]
         * for desktop mode.
         */
        private const val DESKTOP_NOGUI = "nogui"
        private const val DESKTOP_SHELL = "shell"

        /** The prefix's 64-bit system directory, where DXVK's DLLs go. */
        private const val SYSTEM32_DIR = "drive_c/windows/system32"

        /** What a Direct3D 11 game (ULTRAKILL) loads first; both must be DXVK's for it to render. */
        private val DXVK_REQUIRED_DLLS = listOf("d3d11.dll", "dxgi.dll")

        /**
         * `d3d11,dxgi=n,b;d3d12=n` -> {d3d11: "n,b", dxgi: "n,b", d3d12: "n"}, names lower case
         * without `.dll`. Later entries replace earlier ones, as in Wine.
         */
        internal fun parseDllOverrides(value: String): Map<String, String> {
            val modes = LinkedHashMap<String, String>()
            value.split(';').forEach { entry ->
                val eq = entry.indexOf('=')
                if (eq <= 0) return@forEach
                val mode = entry.substring(eq + 1).trim().lowercase()
                entry.substring(0, eq).split(',').forEach { dll ->
                    val name = dll.trim().lowercase().removeSuffix(".dll")
                    if (name.isNotEmpty()) modes[name] = mode
                }
            }
            return modes
        }

        /** Disables Wine Mono and Gecko so `wineboot -u` doesn't stop on their install prompts. */
        private const val NO_MONO_GECKO = "mscoree,mshtml="

        /** Container environment switch: `FABLE_WINEBOOT=1` runs `wineboot -u` once for a new prefix. */
        private const val WINEBOOT_ENV = "FABLE_WINEBOOT"
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
                vkd3dVersion TEXT,
                driverId TEXT,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                graphicsDriver TEXT NOT NULL,
                envVars TEXT NOT NULL,
                screenResolution TEXT NOT NULL,
                isFullscreen INTEGER NOT NULL,
                translator TEXT NOT NULL DEFAULT 'box64',
                box64Preset TEXT NOT NULL DEFAULT 'compatibility',
                box64Overrides TEXT NOT NULL DEFAULT '{}',
                box64RcFile INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE exes (
                id TEXT NOT NULL PRIMARY KEY,
                containerId TEXT NOT NULL,
                name TEXT NOT NULL,
                path TEXT NOT NULL,
                icon TEXT,
                lastPlayed INTEGER,
                playCount INTEGER NOT NULL,
                toolId TEXT,
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
    /** The `box64rc` that came with the Box64 package, when it has one. */
    val rcFile: File? = null,
)

/**
 * Wine's debug channels for every launch while the start-up failures are being diagnosed:
 * `+loaddll` logs each DLL Wine loads (builtin or native, and its path), `+module` the loader's
 * search for it. Channels not named here keep Wine's default `err`/`fixme` messages, which
 * `-all` used to silence.
 */
internal const val DIAGNOSTIC_WINEDEBUG = "+loaddll,+module"

// The Box64 variables a container launches with come from its Box64Settings: Winlator's base
// variables (GuestProgramLauncherComponent.addBox64EnvVars: BOX64_NOBANNER, BOX64_X11GLX,
// BOX64_NORCFILES) plus the container's preset (Box64PresetManager) and its own changes. The
// per-device BOX64_SYSINFO_* variables (no `lscpu` on Android) come from HostCpu.box64Environment.

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
        putNullable("vkd3dVersion", vkd3dVersion)
        putNullable("driverId", driverId)
        put("status", status.name)
        put("createdAt", createdAt)
        put("graphicsDriver", graphicsDriver)
        put("envVars", JSONObject(envVars))
        put("screenResolution", screenResolution)
        put("isFullscreen", isFullscreen)
        put("translator", translator)
        put("box64Preset", box64.preset.id)
        put("box64Overrides", JSONObject(box64.overrides))
        put("box64RcFile", box64.useRcFile)
        put(
            "hud",
            JSONObject().apply {
                put("enabled", hud.enabled)
                put("fps", hud.showFps)
                put("resolution", hud.showResolution)
                put("cpu", hud.showCpu)
                put("position", hud.position.name)
            },
        )
    }

    private fun JSONObject.toContainer(): Container = Container(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        name = getString("name"),
        exePath = stringOrNull("exePath"),
        exeName = stringOrNull("exeName"),
        wineVersion = optString("wineVersion", ContainerDefaults.WINE_VERSION),
        dxvkVersion = stringOrNull("dxvkVersion"),
        vkd3dVersion = stringOrNull("vkd3dVersion"),
        driverId = stringOrNull("driverId"),
        status = runCatching { ContainerStatus.valueOf(optString("status")) }
            .getOrDefault(ContainerStatus.CREATED),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        graphicsDriver = optString("graphicsDriver", ContainerDefaults.GRAPHICS_DRIVER).removeSuffix(" (default)"),
        envVars = optJSONObject("envVars")?.toStringMap() ?: emptyMap(),
        screenResolution = optString("screenResolution", ContainerDefaults.SCREEN_RESOLUTION),
        isFullscreen = optBoolean("isFullscreen", false),
        translator = optString("translator", ContainerDefaults.TRANSLATOR).ifBlank { ContainerDefaults.TRANSLATOR },
        // Records from before presets existed launched with Winlator's Compatibility values.
        box64 = Box64Settings(
            preset = Box64Preset.fromId(stringOrNull("box64Preset")),
            overrides = optJSONObject("box64Overrides")?.toStringMap() ?: emptyMap(),
            useRcFile = optBoolean("box64RcFile", false),
        ),
        // Records from before the overlay was configurable showed everything, top left.
        hud = optJSONObject("hud")?.let { hud ->
            HudSettings(
                enabled = hud.optBoolean("enabled", true),
                showFps = hud.optBoolean("fps", true),
                showResolution = hud.optBoolean("resolution", true),
                showCpu = hud.optBoolean("cpu", true),
                position = HudPosition.fromName(hud.optString("position", "")),
            )
        } ?: HudSettings(),
    )

    private fun ExeEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("containerId", containerId)
        put("name", name)
        put("path", path)
        putNullable("icon", icon)
        if (lastPlayed == null) put("lastPlayed", JSONObject.NULL) else put("lastPlayed", lastPlayed)
        put("playCount", playCount)
        putNullable("toolId", toolId)
    }

    private fun JSONObject.toExe(): ExeEntry = ExeEntry(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        containerId = getString("containerId"),
        name = getString("name"),
        path = getString("path"),
        icon = stringOrNull("icon"),
        lastPlayed = longOrNull("lastPlayed"),
        playCount = optInt("playCount", 0),
        toolId = stringOrNull("toolId"),
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
