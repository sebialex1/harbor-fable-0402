package io.harbor.fable.data

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

internal const val FABLE_USER_AGENT = "Fable/0.1.0 (io.harbor.fable; Android)"

private const val TAG = "FableData"
private val SHA256_HEX = Regex("[a-f0-9]{64}")

/** Human-readable byte size, e.g. "18.9 MB". Negative values render as an em dash. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
}

/**
 * Accepts a raw digest ("sha256:ABCD…", "ABCD…") and returns lowercase hex, or null
 * when the value is not a SHA-256 digest.
 */
internal fun normalizeSha256(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val hex = raw.trim().substringAfter(':').trim().lowercase()
    return hex.takeIf { SHA256_HEX.matches(it) }
}

internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buf = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    val bytes = digest.digest()
    val out = CharArray(bytes.size * 2)
    val alphabet = "0123456789abcdef"
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xff
        out[i * 2] = alphabet[v ushr 4]
        out[i * 2 + 1] = alphabet[v and 0x0f]
    }
    return String(out)
}

internal fun sanitizeFileName(name: String): String {
    val base = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBefore('?')
        .substringBefore('#')
    val cleaned = base.replace(Regex("""[^A-Za-z0-9._-]"""), "_").trim('_', '.')
    return cleaned.take(120).ifBlank { "download.bin" }
}

internal fun isUnder(parent: File, child: File): Boolean = runCatching {
    val root = parent.canonicalFile.path
    val path = child.canonicalFile.path
    path == root || path.startsWith(root + File.separator)
}.getOrDefault(false)

/**
 * Glob (`*.zip`, `*amd64.tar.xz`, `.zip`) or `regex:` prefixed pattern.
 * Matching is case-insensitive. An empty pattern matches everything.
 */
internal fun globMatch(text: String, pattern: String): Boolean {
    if (pattern.isEmpty()) return true
    if (pattern.startsWith("regex:")) {
        val body = pattern.removePrefix("regex:")
        return runCatching {
            Regex(body, RegexOption.IGNORE_CASE).containsMatchIn(text)
        }.getOrDefault(false)
    }
    if (!pattern.contains('*') && !pattern.contains('?')) {
        return if (pattern.startsWith(".")) text.endsWith(pattern, ignoreCase = true)
        else text.equals(pattern, ignoreCase = true)
    }
    val regex = buildString {
        append('^')
        for (ch in pattern) {
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> {
                    if (ch in ".\\+()[]{}^\$|") append('\\')
                    append(ch)
                }
            }
        }
        append('$')
    }
    return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(text) }.getOrDefault(false)
}

internal fun matchesAnyPattern(name: String, patterns: List<String>): Boolean {
    if (patterns.isEmpty()) return true
    return patterns.any { globMatch(name, it) }
}

internal fun writeAtomic(file: File, text: String) {
    val parent = file.parentFile ?: throw IllegalStateException("No parent for ${file.path}")
    if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
        throw IllegalStateException("Cannot create ${parent.path}")
    }
    val tmp = File(parent, "${file.name}.tmp")
    tmp.writeText(text)
    if (file.exists() && !file.delete()) {
        file.writeText(text)
        tmp.delete()
        return
    }
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}

internal fun readTextOrNull(file: File): String? {
    if (!file.isFile) return null
    return runCatching { file.readText() }.getOrNull()
}

internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifBlank { null }
}

internal fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

internal fun JSONObject.putNullable(key: String, value: String?) {
    if (value == null) put(key, NULL) else put(key, value)
}

internal fun JSONObject.toStringMap(): Map<String, String> {
    val out = linkedMapOf<String, String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (!isNull(key)) out[key] = optString(key)
    }
    return out
}

internal fun logPersistFailure(what: String, error: Throwable) {
    Log.e(TAG, "Failed to persist $what", error)
}
