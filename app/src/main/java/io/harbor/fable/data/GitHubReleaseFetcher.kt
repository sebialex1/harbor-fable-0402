package io.harbor.fable.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * One file attached to a GitHub release.
 *
 * [sha256] comes from the asset `digest` field, the release body, or a sibling
 * checksum file ([checksumUrl]). It is null when the publisher did not provide
 * a SHA-256 (Proton-GE publishes SHA-512 only, for example).
 */
data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String? = null,
    val sha256: String? = null,
    val checksumUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("downloadUrl", downloadUrl)
        put("sizeBytes", sizeBytes)
        putNullable("contentType", contentType)
        putNullable("sha256", sha256)
        putNullable("checksumUrl", checksumUrl)
    }

    companion object {
        fun fromJson(obj: JSONObject): GitHubAsset = GitHubAsset(
            name = obj.getString("name"),
            downloadUrl = obj.getString("downloadUrl"),
            sizeBytes = obj.optLong("sizeBytes", 0L),
            contentType = obj.stringOrNull("contentType"),
            sha256 = normalizeSha256(obj.stringOrNull("sha256")),
            checksumUrl = obj.stringOrNull("checksumUrl"),
        )
    }
}

/** Parsed `releases/latest` payload. Source archives (zipball/tarball) are not included. */
data class GitHubRelease(
    val owner: String,
    val repo: String,
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String?,
    val htmlUrl: String?,
    val assets: List<GitHubAsset>,
) {
    val sourceRepo: String get() = "$owner/$repo"

    fun toJson(): JSONObject = JSONObject().apply {
        put("owner", owner)
        put("repo", repo)
        put("tagName", tagName)
        put("name", name)
        put("body", body.take(MAX_CACHED_BODY))
        putNullable("publishedAt", publishedAt)
        putNullable("htmlUrl", htmlUrl)
        put("assets", JSONArray().apply { assets.forEach { put(it.toJson()) } })
    }

    companion object {
        const val MAX_CACHED_BODY = 100_000

        fun fromJson(obj: JSONObject): GitHubRelease {
            val assetsJson = obj.optJSONArray("assets") ?: JSONArray()
            val assets = ArrayList<GitHubAsset>(assetsJson.length())
            for (i in 0 until assetsJson.length()) {
                val item = assetsJson.optJSONObject(i) ?: continue
                runCatching { assets += GitHubAsset.fromJson(item) }
            }
            return GitHubRelease(
                owner = obj.getString("owner"),
                repo = obj.getString("repo"),
                tagName = obj.getString("tagName"),
                name = obj.optString("name", obj.getString("tagName")),
                body = obj.optString("body", ""),
                publishedAt = obj.stringOrNull("publishedAt"),
                htmlUrl = obj.stringOrNull("htmlUrl"),
                assets = assets,
            )
        }
    }
}

data class CachedRelease(
    val release: GitHubRelease,
    val fetchedAt: Long,
    val fromCache: Boolean,
    val stale: Boolean,
)

class GitHubFetchException(
    val httpCode: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
) : IOException(message)

/**
 * Fetches `https://api.github.com/repos/{owner}/{repo}/releases/latest` with
 * an in-memory and on-disk cache.
 *
 * Results are reused until [ttlMs] elapses. A failed refresh falls back to a
 * stale cache entry when one exists ([fetchLatest] with the default
 * `allowStale` behavior via [fetchLatestOrCached]). Asset names can be filtered
 * with globs (`*.zip`) or `regex:` patterns. See [filterAssets].
 *
 * Networking uses [HttpURLConnection] only. No token is required for public
 * repositories; set [authToken] to a personal access token if the anonymous
 * rate limit (60/hour) is too low. The token is not persisted.
 */
class GitHubReleaseFetcher(
    private val cacheFile: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val userAgent: String = FABLE_USER_AGENT,
) {
    /** Optional `Authorization: Bearer` token. Not stored. */
    @Volatile
    var authToken: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<GitHubRelease>>()
    private val checksumMemo = ConcurrentHashMap<String, Map<String, String>>()
    private val diskLock = Any()

    init {
        loadDiskCache()
    }

    /**
     * Latest release. Throws [GitHubFetchException] or [IOException] when the
     * network fails and no fresh cache entry exists.
     */
    suspend fun fetchLatest(
        owner: String,
        repo: String,
        forceRefresh: Boolean = false,
    ): CachedRelease = withContext(Dispatchers.IO) {
        val key = cacheKey(owner, repo)
        if (!forceRefresh) {
            freshEntry(key)?.let { entry ->
                return@withContext CachedRelease(entry.release, entry.fetchedAt, fromCache = true, stale = false)
            }
        }
        val deferred = inFlight.getOrPut(key) {
            scope.async { fetchAndStore(owner, repo) }
        }
        try {
            val release = deferred.await()
            val fetchedAt = memory[key]?.fetchedAt ?: System.currentTimeMillis()
            CachedRelease(release, fetchedAt, fromCache = false, stale = false)
        } finally {
            if (deferred.isCompleted) inFlight.remove(key, deferred)
        }
    }

    /**
     * Like [fetchLatest], but returns a stale cached release instead of throwing
     * when the network is down. [CachedRelease.stale] is true in that case.
     */
    suspend fun fetchLatestOrCached(
        owner: String,
        repo: String,
        forceRefresh: Boolean = false,
    ): CachedRelease {
        return try {
            fetchLatest(owner, repo, forceRefresh)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val cached = memory[cacheKey(owner, repo)]
            if (cached != null) {
                CachedRelease(cached.release, cached.fetchedAt, fromCache = true, stale = true)
            } else {
                throw error
            }
        }
    }

    fun invalidate(owner: String, repo: String) {
        memory.remove(cacheKey(owner, repo))
        persistDisk()
    }

    fun clearCache() {
        memory.clear()
        checksumMemo.clear()
        synchronized(diskLock) {
            if (cacheFile.exists() && !cacheFile.delete()) {
                Log.w(TAG, "Could not delete ${cacheFile.path}")
            }
        }
    }

    /**
     * Keeps assets whose name matches any [patterns]. Empty [patterns] keeps
     * every non-checksum asset. Checksum sidecars (`*.sha256`, `sha256sums.txt`)
     * are omitted so they are not offered as downloads.
     */
    fun filterAssets(release: GitHubRelease, patterns: List<String> = emptyList()): List<GitHubAsset> {
        return release.assets.filter { asset ->
            !isChecksumSidecar(asset.name) && matchesAnyPattern(asset.name, patterns)
        }
    }

    fun filterAssets(release: GitHubRelease, pattern: Regex): List<GitHubAsset> {
        return release.assets.filter { asset ->
            !isChecksumSidecar(asset.name) && pattern.containsMatchIn(asset.name)
        }
    }

    /** Downloads sibling checksum files and fills [GitHubAsset.sha256] when possible. */
    suspend fun attachChecksums(release: GitHubRelease): GitHubRelease = withContext(Dispatchers.IO) {
        val urls = release.assets.mapNotNull { it.checksumUrl }.distinct()
        if (urls.isEmpty() && release.assets.none { isChecksumSidecar(it.name) && it.sha256 == null }) {
            return@withContext release
        }
        val sidecarUrls = (urls + release.assets.filter { isChecksumSidecar(it.name) }.map { it.downloadUrl }).distinct()
        val merged = LinkedHashMap<String, String>()
        for (url in sidecarUrls) {
            val parsed = checksumMemo[url] ?: runCatching { parseChecksumText(httpGet(url, githubApi = false)) }
                .getOrElse { error ->
                    Log.w(TAG, "Checksum file skipped: $url", error)
                    emptyMap()
                }
            checksumMemo[url] = parsed
            merged.putAll(parsed)
        }
        if (merged.isEmpty()) return@withContext release
        release.copy(
            assets = release.assets.map { asset ->
                if (asset.sha256 != null) asset
                else asset.copy(sha256 = merged[asset.name] ?: merged[asset.name.lowercase()])
            }
        )
    }

    fun parseReleaseJson(json: String, owner: String, repo: String): GitHubRelease = parseRelease(json, owner, repo)

    fun parseChecksumText(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val hash = HASH_REGEX.find(line)?.groupValues?.get(1)?.lowercase() ?: continue
            val file = FILE_REGEX.find(line)?.groupValues?.get(1)
                ?: line.substringAfter('*', missingDelimiterValue = "")
                    .substringAfter(' ')
                    .trim()
                    .takeIf { it.isNotEmpty() && !it.contains(' ') && !SHA256_ONLY.matches(it) }
            if (file != null) out[file.substringAfterLast('/')] = hash
        }
        return out
    }

    private fun fetchAndStore(owner: String, repo: String): GitHubRelease {
        val slugOwner = requireSlug(owner, "owner")
        val slugRepo = requireSlug(repo, "repo")
        val body = httpGet(latestUrl(slugOwner, slugRepo), githubApi = true)
        val parsed = parseRelease(body, slugOwner, slugRepo)
        val withChecksums = runCatching { kotlinx.coroutines.runBlocking { attachChecksums(parsed) } }
            .getOrDefault(parsed)
        val entry = CacheEntry(withChecksums, System.currentTimeMillis())
        memory[cacheKey(slugOwner, slugRepo)] = entry
        trimMemory()
        persistDisk()
        return withChecksums
    }

    private fun freshEntry(key: String): CacheEntry? {
        val entry = memory[key] ?: return null
        return entry.takeIf { it.isFresh() }
    }

    private fun CacheEntry.isFresh(now: Long = System.currentTimeMillis()): Boolean =
        now - fetchedAt in 0..ttlMs

    private fun trimMemory() {
        if (memory.size <= MAX_CACHE_ENTRIES) return
        val overflow = memory.entries.sortedBy { it.value.fetchedAt }.dropLast(MAX_CACHE_ENTRIES)
        overflow.forEach { memory.remove(it.key) }
    }

    private fun loadDiskCache() {
        val text = readTextOrNull(cacheFile) ?: return
        runCatching {
            val root = JSONObject(text)
            val entries = root.optJSONObject("entries") ?: return
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = entries.optJSONObject(key) ?: continue
                val release = item.optJSONObject("release")?.let { GitHubRelease.fromJson(it) } ?: continue
                val fetchedAt = item.optLong("fetchedAt", 0L)
                if (fetchedAt > 0L) memory[key] = CacheEntry(release, fetchedAt)
            }
        }.onFailure { error ->
            Log.w(TAG, "Ignoring unreadable GitHub cache", error)
            runCatching { cacheFile.delete() }
        }
    }

    private fun persistDisk() {
        synchronized(diskLock) {
            runCatching {
                val entries = JSONObject()
                memory.forEach { (key, entry) ->
                    entries.put(
                        key,
                        JSONObject()
                            .put("fetchedAt", entry.fetchedAt)
                            .put("release", entry.release.toJson()),
                    )
                }
                val root = JSONObject().put("version", 1).put("entries", entries)
                writeAtomic(cacheFile, root.toString())
            }.onFailure { logPersistFailure("github cache", it) }
        }
    }

    private fun parseRelease(json: String, owner: String, repo: String): GitHubRelease {
        val obj = JSONObject(json)
        if (!obj.has("tag_name")) {
            val message = obj.optString("message").ifBlank { "GitHub response had no tag_name" }
            throw GitHubFetchException(0, message)
        }
        val tag = obj.getString("tag_name")
        val name = obj.optString("name").ifBlank { tag }
        val body = obj.optString("body", "")
        val assetsJson = obj.optJSONArray("assets") ?: JSONArray()
        val raw = ArrayList<GitHubAsset>(assetsJson.length())
        for (i in 0 until assetsJson.length()) {
            val item = assetsJson.optJSONObject(i) ?: continue
            val assetName = item.optString("name")
            val url = item.optString("browser_download_url")
            if (assetName.isBlank() || url.isBlank()) continue
            raw += GitHubAsset(
                name = assetName,
                downloadUrl = url,
                sizeBytes = item.optLong("size", 0L),
                contentType = item.stringOrNull("content_type"),
                sha256 = normalizeSha256(item.stringOrNull("digest")),
            )
        }
        val bodyChecksums = checksumsFromBody(body, raw.map { it.name }.toSet())
        val sidecars = raw.filter { isChecksumSidecar(it.name) }
        val genericSidecar = sidecars.firstOrNull { isGenericChecksumFile(it.name) }
        val linked = raw.map { asset ->
            val paired = sidecars.firstOrNull { sidecar ->
                val lower = sidecar.name.lowercase()
                lower == "${asset.name.lowercase()}.sha256" || lower == "${asset.name.lowercase()}.sha256sum"
            }
            asset.copy(
                sha256 = asset.sha256 ?: bodyChecksums[asset.name],
                checksumUrl = paired?.downloadUrl ?: genericSidecar?.downloadUrl,
            )
        }
        return GitHubRelease(
            owner = owner,
            repo = repo,
            tagName = tag,
            name = name,
            body = body,
            publishedAt = obj.stringOrNull("published_at"),
            htmlUrl = obj.stringOrNull("html_url"),
            assets = linked,
        )
    }

    private fun checksumsFromBody(body: String, assetNames: Set<String>): Map<String, String> {
        if (body.isBlank() || assetNames.isEmpty()) return emptyMap()
        val found = LinkedHashMap<String, String>()
        val lines = body.lines()
        lines.forEachIndexed { index, line ->
            val hash = HASH_REGEX.find(line)?.groupValues?.get(1)?.lowercase() ?: return@forEachIndexed
            val onLine = FILE_REGEX.find(line)?.groupValues?.get(1)
            val named = onLine?.let { token -> assetNames.find { it.equals(token, ignoreCase = true) } }
            val neighbor = if (named == null) {
                listOfNotNull(lines.getOrNull(index - 1), lines.getOrNull(index + 1))
                    .firstNotNullOfOrNull { nearby ->
                        FILE_REGEX.find(nearby)?.groupValues?.get(1)?.let { token ->
                            assetNames.find { it.equals(token, ignoreCase = true) }
                        }
                    }
            } else {
                null
            }
            val key = named ?: neighbor ?: return@forEachIndexed
            found.putIfAbsent(key, hash)
        }
        return found
    }

    private fun httpGet(url: String, githubApi: Boolean): String {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = open(current, githubApi)
            try {
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) throw GitHubFetchException(code, "HTTP $code without Location")
                    current = URL(URL(current), loc).toExternalForm()
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let { readLimited(it) }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                        ?.ifBlank { null }
                        ?: "HTTP $code from $url"
                    val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()
                    throw GitHubFetchException(code, message, retryAfter)
                }
                return text
            } finally {
                conn.disconnect()
            }
        }
        throw GitHubFetchException(0, "Too many redirects for $url")
    }

    private fun open(url: String, githubApi: Boolean): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", if (githubApi) "application/vnd.github+json" else "*/*")
            if (githubApi) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val token = authToken?.trim().orEmpty()
        if (token.isNotEmpty() && githubApi) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    private fun readLimited(stream: InputStream, max: Int = 8 * 1024 * 1024): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Response exceeded $max bytes")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private data class CacheEntry(val release: GitHubRelease, val fetchedAt: Long)

    companion object {
        const val DEFAULT_TTL_MS = 30L * 60L * 1000L
        private const val MAX_CACHE_ENTRIES = 32
        private const val MAX_REDIRECTS = 5
        private const val TAG = "GitHubReleaseFetcher"
        private val SLUG = Regex("[A-Za-z0-9._-]+")
        private val HASH_REGEX = Regex("""(?i)(?<![a-f0-9])([a-f0-9]{64})(?![a-f0-9])""")
        private val SHA256_ONLY = Regex("""(?i)[a-f0-9]{64}""")
        private val FILE_REGEX = Regex(
            """(?i)([A-Za-z0-9][A-Za-z0-9._+-]*\.(?:tar\.gz|tar\.xz|tar\.zst|tar\.bz2|zip|7z|apk|wcp|wcp\.xz|exe|sha256|sha256sum|txt))""",
        )
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        @Volatile
        private var instance: GitHubReleaseFetcher? = null

        @Synchronized
        fun get(context: Context, ttlMs: Long = DEFAULT_TTL_MS): GitHubReleaseFetcher {
            instance?.let { return it }
            val created = GitHubReleaseFetcher(File(context.applicationContext.cacheDir, "github_releases.json"), ttlMs)
            instance = created
            return created
        }

        fun latestUrl(owner: String, repo: String): String =
            "https://api.github.com/repos/${requireSlug(owner, "owner")}/${requireSlug(repo, "repo")}/releases/latest"

        fun requireSlug(value: String, label: String): String {
            require(SLUG.matches(value)) { "Invalid GitHub $label: $value" }
            return value
        }

        fun isChecksumSidecar(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".sha256") ||
                lower.endsWith(".sha256sum") ||
                lower.endsWith(".sha256sums") ||
                lower == "sha256sums" ||
                lower == "sha256sums.txt" ||
                lower == "sha256sum.txt" ||
                lower.endsWith(".sha256.txt")
        }

        private fun isGenericChecksumFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower == "sha256sums.txt" || lower == "sha256sums" || lower == "sha256sum.txt"
        }

        private fun cacheKey(owner: String, repo: String): String = "${owner.lowercase()}/${repo.lowercase()}"
    }
}
