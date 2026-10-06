package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.nativebridge.NativeLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val mutex = Mutex()
    private val containersById = LinkedHashMap<String, Container>()
    private val exesById = LinkedHashMap<String, ExeEntry>()

    /** Outlives individual screens: watches launched Wine processes until they exit. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live Wine process ids per container id. */
    private val runningPids = ConcurrentHashMap<String, MutableSet<Int>>()

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
     * container's Wine prefix, through Box64.
     *
     * Needs a downloaded Box64 package and Wine build (Assets tab). The first launch of a
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
            } ?: return LaunchResult.Failed("Add an executable to launch ${container.name}")
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
                ?: return@withLock Result.failure(IllegalStateException("Install Wine from the Assets tab first"))
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

    private suspend fun start(container: Container, exe: ExeEntry?): LaunchResult {
        // A second tap while the first launch is still preparing would start Wine twice.
        if (!starting.add(container.id)) return LaunchResult.Failed("${container.name} is already starting")
        try {
            return startLocked(container, exe)
        } finally {
            starting.remove(container.id)
        }
    }

    private suspend fun startLocked(container: Container, exe: ExeEntry?): LaunchResult {
        val runtime = runtime
            ?: return LaunchResult.Unavailable("Wine runtime isn't available in this build")
        if (!NativeLoader.isLoaded) {
            return LaunchResult.Unavailable("The native runtime couldn't load on this device")
        }
        val dir = directory(container.id).also { it.mkdirs() }

        // 1. Box64 and a Wine build have to be downloaded.
        val hasWine = runtime.installedWine(dir) != null || runtime.wineArchives().isNotEmpty()
        val box64 = when (val status = runtime.ensureBox64()) {
            is Box64Status.Ready -> status.executable
            Box64Status.NotDownloaded -> {
                return LaunchResult.Failed(
                    if (hasWine) "Install Box64 from the Assets tab first"
                    else "Install Box64 and Wine from the Assets tab first"
                )
            }
            is Box64Status.NoExecutable -> {
                return LaunchResult.Failed("${status.packageName} has no box64 executable. Download a different Box64 build")
            }
        }
        if (!hasWine) return LaunchResult.Failed("Install Wine from the Assets tab first")

        // 2. Unpack Wine into the container's prefix (first launch only).
        installWine(container.id).getOrElse { error ->
            return LaunchResult.Failed(error.message?.let { "Couldn't set up Wine: $it" } ?: "Couldn't set up Wine")
        }

        // 3. Resolve what to run.
        val current = mutex.withLock { containersById[container.id] } ?: container
        val program: String
        val arguments: List<String>
        if (exe == null) {
            program = "explorer"
            arguments = listOf("/desktop=Fable,${current.screenResolution}")
        } else {
            program = runtime.materializeExecutable(dir, exe.id, exe.name, exe.path)
                ?: return LaunchResult.Failed("Couldn't open ${exe.name}. Add it again from the container")
            arguments = emptyList()
        }
        val label = exe?.name ?: "${current.name} desktop"

        // 4. Start the process.
        val environment = buildList {
            add("WINEDEBUG=-all")
            add("BOX64_NOBANNER=1")
            current.envVars.forEach { (key, value) -> add("$key=$value") }
        }
        val driver = runtime.installedDriverLibrary(current.driverId)
        val pid = try {
            withContext(Dispatchers.IO) {
                NativeLoader.launchWineContainer(
                    containerPath = dir.absolutePath,
                    exePath = program,
                    args = arguments.toTypedArray(),
                    envVars = environment.toTypedArray(),
                    driverPath = driver,
                    box64Path = box64.absolutePath,
                )
            }
        } catch (error: UnsatisfiedLinkError) {
            return LaunchResult.Unavailable("The native runtime couldn't load on this device")
        }
        if (pid <= 0) {
            val reason = NativeLoader.lastLaunchError()
            return LaunchResult.Failed(reason?.let { "Couldn't start $label: $it" } ?: "Couldn't start $label")
        }

        // 5. A process that dies straight away has a reason worth showing (missing libraries, …).
        delay(EARLY_EXIT_WINDOW_MS)
        if (!WineRuntime.isAlive(pid) && !WineRuntime.exitedCleanly(dir)) {
            setStatus(container.id, ContainerStatus.READY)
            val reason = WineRuntime.lastLogLine(dir)
            return LaunchResult.Failed(
                if (reason != null) "$label stopped right away: $reason" else "$label stopped right away"
            )
        }
        markStarted(container.id, exe?.id, pid)
        return LaunchResult.Started(pid)
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

    /** Records a started process: container RUNNING, exe play stats, and a watcher that flips back on exit. */
    private suspend fun markStarted(containerId: String, exeId: String?, pid: Int) {
        mutex.withLock {
            containersById[containerId]?.let { containersById[containerId] = it.copy(status = ContainerStatus.RUNNING) }
            exeId?.let { id ->
                exesById[id]?.let { exesById[id] = it.copy(lastPlayed = System.currentTimeMillis(), playCount = it.playCount + 1) }
            }
            persistLocked()
        }
        runningPids.getOrPut(containerId) { ConcurrentHashMap.newKeySet() }.add(pid)
        scope.launch {
            while (WineRuntime.isAlive(pid)) delay(PROCESS_POLL_MS)
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

        /** How long a freshly started process is watched for an immediate crash. */
        private const val EARLY_EXIT_WINDOW_MS = 1_200L
        private const val PROCESS_POLL_MS = 1_500L

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
                isFullscreen INTEGER NOT NULL
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
            val runtime = WineRuntime(app, AssetRepository.get(app), File(app.filesDir, "runtime"))
            val created = ContainerRepository(FileContainerStore(File(root, "index.json")), root, runtime)
            instance = created
            return created
        }
    }
}

/** Outcome of [ContainerRepository.launch]. */
sealed interface LaunchResult {
    /** Wine started; [pid] is the launcher process id. */
    data class Started(val pid: Int) : LaunchResult

    /** Launching is not possible on this device or build (e.g. the native runtime didn't load). */
    data class Unavailable(val reason: String) : LaunchResult

    /** The launch couldn't happen: a missing Box64/Wine download, a bad request, or a crash on start. */
    data class Failed(val reason: String) : LaunchResult
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
        graphicsDriver = optString("graphicsDriver", ContainerDefaults.GRAPHICS_DRIVER),
        envVars = optJSONObject("envVars")?.toStringMap() ?: emptyMap(),
        screenResolution = optString("screenResolution", ContainerDefaults.SCREEN_RESOLUTION),
        isFullscreen = optBoolean("isFullscreen", false),
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
