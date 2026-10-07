package io.harbor.fable.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * One package listed by a Winlator content index (`contents.json`, the file Winlator's
 * `ContentsManager` reads from `REMOTE_PROFILES`): a `.wcp` of [type] (`Wine`, `Proton`,
 * `Box64`, `WOWBox64`, `FEXCore`, `DXVK`, `VKD3D`) at [remoteUrl].
 *
 * ```json
 * { "type": "DXVK", "verName": "2.7.1-gplasync", "verCode": "1",
 *   "remoteUrl": "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/DXVK/dxvk-2.7.1-gplasync.wcp" }
 * ```
 */
data class WinlatorContentItem(
    val type: String,
    val versionName: String,
    val versionCode: Int,
    val remoteUrl: String,
) {
    /** The file the URL points at (`dxvk-2.7.1-gplasync.wcp`). */
    val fileName: String get() = sanitizeFileName(remoteUrl)

    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("verName", versionName)
        .put("verCode", versionCode)
        .put("remoteUrl", remoteUrl)

    companion object {
        /** Null when the entry has no usable type or URL (the index is hand-edited and has gaps). */
        fun fromJson(obj: JSONObject): WinlatorContentItem? {
            val type = obj.optString("type").trim()
            val url = obj.optString("remoteUrl").trim()
            if (type.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) return null
            val code = obj.opt("verCode")?.toString()?.trim()?.toIntOrNull() ?: 0
            return WinlatorContentItem(
                type = type,
                versionName = obj.optString("verName").trim().ifEmpty { sanitizeFileName(url) },
                versionCode = code,
                remoteUrl = url,
            )
        }
    }
}

/**
 * Fetches and caches Winlator `contents.json` indexes. Unlike GitHub releases, the `.wcp`
 * packages in `StevenMXZ/Winlator-Contents` (DXVK, VKD3D-Proton, Box64, FEXCore) are files in
 * the repository tree listed by that index, not release assets, so [AssetRepository] reads this
 * for catalog sources that set [CatalogSource.contentsIndex].
 *
 * One index per URL is kept in memory and on disk ([cacheFile]); a fetch younger than [ttlMs]
 * is served from memory, and when the network is down a stale copy is returned rather than an
 * error. Plain [HttpURLConnection], no token.
 */
class WinlatorContentsFetcher(
    private val cacheFile: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val userAgent: String = FABLE_USER_AGENT,
) {
    private class CacheEntry(val items: List<WinlatorContentItem>, val fetchedAt: Long)

    private val memory = ConcurrentHashMap<String, CacheEntry>()
    private val diskLock = Any()

    init {
        loadDiskCache()
    }

    /**
     * The items of the index at [url], in the order the index lists them (oldest first in the
     * known indexes). Throws [IOException] when it can't be fetched and nothing is cached.
     */
    suspend fun fetchOrCached(url: String, forceRefresh: Boolean = false): List<WinlatorContentItem> =
        withContext(Dispatchers.IO) {
            val cached = memory[url]
            if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt < ttlMs) {
                return@withContext cached.items
            }
            try {
                val items = parse(httpGet(url))
                memory[url] = CacheEntry(items, System.currentTimeMillis())
                persistDisk()
                items
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (cached != null) {
                    Log.w(TAG, "Using the cached index for $url", error)
                    cached.items
                } else {
                    throw error
                }
            }
        }

    fun clearCache() {
        memory.clear()
        synchronized(diskLock) {
            if (cacheFile.exists() && !cacheFile.delete()) Log.w(TAG, "Could not delete ${cacheFile.path}")
        }
    }

    /** Tolerates the hand-edited indexes in the wild: stray whitespace, blank entries, string verCodes. */
    private fun parse(text: String): List<WinlatorContentItem> {
        val array = runCatching { JSONArray(text.trim()) }.getOrElse { error ->
            throw IOException("Not a Winlator contents index: ${error.message}")
        }
        val out = ArrayList<WinlatorContentItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            WinlatorContentItem.fromJson(obj)?.let { out += it }
        }
        return out
    }

    private fun httpGet(url: String): String {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, */*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw IOException("HTTP $code without Location from $current")
                    current = URL(URL(current), location).toExternalForm()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code from $current")
                return conn.inputStream.use { readLimited(it) }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects for $url")
    }

    private fun readLimited(stream: InputStream, max: Int = 4 * 1024 * 1024): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw IOException("Index larger than $max bytes")
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun loadDiskCache() {
        val text = readTextOrNull(cacheFile) ?: return
        runCatching {
            val root = JSONObject(text)
            val keys = root.keys()
            while (keys.hasNext()) {
                val url = keys.next()
                val entry = root.optJSONObject(url) ?: continue
                val array = entry.optJSONArray("items") ?: continue
                val items = ArrayList<WinlatorContentItem>(array.length())
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { WinlatorContentItem.fromJson(it) }?.let { items += it }
                }
                memory[url] = CacheEntry(items, entry.optLong("fetchedAt", 0L))
            }
        }.onFailure { Log.w(TAG, "Index cache unreadable; ignoring", it) }
    }

    private fun persistDisk() {
        synchronized(diskLock) {
            runCatching {
                val root = JSONObject()
                memory.forEach { (url, entry) ->
                    root.put(
                        url,
                        JSONObject()
                            .put("fetchedAt", entry.fetchedAt)
                            .put("items", JSONArray().apply { entry.items.forEach { put(it.toJson()) } }),
                    )
                }
                writeAtomic(cacheFile, root.toString())
            }.onFailure { Log.w(TAG, "Could not write ${cacheFile.path}", it) }
        }
    }

    companion object {
        private const val TAG = "WinlatorContents"
        const val DEFAULT_TTL_MS = 30L * 60L * 1000L
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** The index Winlator-Ludashi's `ContentsManager.REMOTE_PROFILES` points at. */
        const val STEVENMXZ_INDEX = "https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/contents.json"

        @Volatile
        private var instance: WinlatorContentsFetcher? = null

        @Synchronized
        fun get(context: Context, ttlMs: Long = DEFAULT_TTL_MS): WinlatorContentsFetcher {
            instance?.let { return it }
            val created = WinlatorContentsFetcher(File(context.applicationContext.cacheDir, "winlator_contents.json"), ttlMs)
            instance = created
            return created
        }
    }
}
