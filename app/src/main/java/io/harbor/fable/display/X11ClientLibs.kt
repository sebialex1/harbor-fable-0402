package io.harbor.fable.display

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The ARM64 (bionic) X11 client libraries Wine's `winex11.so` talks to the display server through.
 *
 * Box64 runs the x86_64 `winex11.so` and wraps `libX11.so` / `libXext.so` onto the host's native
 * libraries, so these have to be aarch64 Android builds. Winlator bionic ships them inside its
 * imagefs; Fable ships the same family of builds (Termux packages: libx11 1.8.13, libxcb 1.17.0,
 * libxau, libxdmcp, libxext, libandroid-support) under `assets/x11/arm64-v8a/` and copies them
 * to `filesDir/x11/lib`, which goes first on the Wine process's `LD_LIBRARY_PATH`.
 *
 * Termux's libxcb has its socket directory compiled in
 * (`/data/data/com.termux/files/usr/tmp/.X11-unix/X`), where Winlator bionic's own libxcb reads
 * `$TMPDIR`. That path is not reachable from this app, so the copy is patched in place to
 * [socketPrefix]: same string slot, NUL padded, which only works while Fable's path is no longer
 * than Termux's (47 bytes; `/data/user/0/io.harbor.fable/files/.X11-unix/X` is 46).
 */
internal object X11ClientLibs {
    private const val TAG = "X11ClientLibs"
    private const val ASSET_DIR = "x11/arm64-v8a"
    private const val VERSION = "termux-libx11-1.8.13_libxcb-1.17.0-1"
    private const val MARKER = ".fable-x11"
    private const val TERMUX_SOCKET_PREFIX = "/data/data/com.termux/files/usr/tmp/.X11-unix/X"

    fun libDir(context: Context): File = File(context.filesDir, "x11/lib")

    /** Directory the display server binds `X0` in. */
    fun socketDir(context: Context): File = File(context.filesDir, ".X11-unix")

    /** What the patched libxcb prepends to the display number: `<socketDir>/X`. */
    fun socketPrefix(context: Context): String = socketDir(context).absolutePath + "/X"

    /**
     * Copies (once per [VERSION] and socket path) and patches the libraries. Returns the library
     * directory. Throws [IOException] with a short message when the assets are missing or the
     * socket path doesn't fit the patch slot.
     */
    @Synchronized
    fun install(context: Context): File {
        val dir = libDir(context)
        val prefix = socketPrefix(context)
        val stamp = "$VERSION\n$prefix"
        val marker = File(dir, MARKER)
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return dir
        if (prefix.toByteArray().size > TERMUX_SOCKET_PREFIX.length) {
            throw IOException("Display socket path is too long for libxcb ($prefix)")
        }
        val names = context.assets.list(ASSET_DIR)?.filter { it.endsWith(".so") }.orEmpty()
        if (names.isEmpty()) throw IOException("X11 client libraries are missing from this build")
        dir.mkdirs()
        marker.delete()
        for (name in names) {
            val bytes = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
            val out = if (name == "libxcb.so") patchSocketPrefix(bytes, prefix) else bytes
            val dest = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(dest)) throw IOException("Couldn't install $name")
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
        }
        marker.writeText(stamp)
        Log.i(TAG, "Installed ${names.size} X11 client libraries, socket prefix $prefix")
        return dir
    }

    /** Replaces Termux's socket prefix in libxcb's .rodata with [prefix] (NUL padded). */
    internal fun patchSocketPrefix(library: ByteArray, prefix: String): ByteArray {
        val needle = (TERMUX_SOCKET_PREFIX + "\u0000").toByteArray()
        val at = indexOf(library, needle)
        if (at < 0) throw IOException("libxcb has no Termux socket path to patch")
        val replacement = prefix.toByteArray()
        require(replacement.size <= TERMUX_SOCKET_PREFIX.length)
        val out = library.copyOf()
        for (i in needle.indices) out[at + i] = if (i < replacement.size) replacement[i] else 0
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
