package io.harbor.fable.data

import android.content.Context
import android.util.Log
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.data.models.ExeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
) {
    private val mutex = Mutex()
    private val containersById = LinkedHashMap<String, Container>()
    private val exesById = LinkedHashMap<String, ExeEntry>()

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
        publish()
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
     * container's Wine prefix.
     *
     * Stub: the Wine runtime is not bundled with the app yet, so this only validates the
     * request and reports [LaunchResult.Unavailable]. Once a runtime ships, this is where
     * `NativeLoader.launchWineContainer` gets called, the container moves to
     * [ContainerStatus.RUNNING], and the exe's play stats are updated.
     */
    suspend fun launch(containerId: String, exeId: String? = null): LaunchResult = mutex.withLock {
        val container = containersById[containerId]
            ?: return@withLock LaunchResult.Failed("Container not found")
        val candidates = exesById.values.filter { it.containerId == containerId }
        val exe = when {
            exeId != null -> candidates.firstOrNull { it.id == exeId }
            else -> candidates.firstOrNull { it.path == container.exePath } ?: candidates.firstOrNull()
        } ?: return@withLock LaunchResult.Failed("Add an executable to launch ${container.name}")
        LaunchResult.Unavailable("Wine runtime isn't bundled yet, so ${exe.name} can't start in this build")
    }

    /**
     * Starts the full Wine desktop (explorer) in the container, without any executable.
     *
     * Stub like [launch]: reports [LaunchResult.Unavailable] until a Wine runtime ships.
     */
    suspend fun launchDesktop(containerId: String): LaunchResult = mutex.withLock {
        val container = containersById[containerId]
            ?: return@withLock LaunchResult.Failed("Container not found")
        LaunchResult.Unavailable("Wine runtime isn't bundled yet, so the ${container.name} desktop can't start in this build")
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
            val created = ContainerRepository(FileContainerStore(File(root, "index.json")), root)
            instance = created
            return created
        }
    }
}

/** Outcome of [ContainerRepository.launch]. */
sealed interface LaunchResult {
    /** Wine started; [pid] is the launcher process id. */
    data class Started(val pid: Int) : LaunchResult

    /** Launching is not possible in this build (e.g. no Wine runtime bundled). */
    data class Unavailable(val reason: String) : LaunchResult

    /** The request was invalid (unknown container, no executable, …). */
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
