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
 * The same directory also carries the other native libraries Box64 wraps for Wine's Unix side,
 * all Termux aarch64 builds:
 * - FreeType 2.14.3 (`libfreetype.so.6` + `libfreetype.so`) and its DT_NEEDED closure: libpng
 *   1.6.59 (`libpng16.so` + `libpng.so`), zlib 1.3.2 (`libz.so.1` + `libz.so`), bzip2 1.0.8
 *   (`libbz2.so.1.0` + `libbz2.so`), brotli 1.2.0 (`libbrotlidec.so`, `libbrotlicommon.so`).
 *   Android's own FreeType (`/system/lib64/libft2.so`) is too stripped down for Wine ("upgrade
 *   FreeType to at least version 2.1.4", then `kernel32.dll` c0000135), so it is never used.
 * - X extensions for winex11: libXrender, libXcursor, libXfixes, libXi, libXrandr, libXinerama,
 *   libXcomposite.
 * - Fontconfig 2.18.3 (`libfontconfig.so` + `libfontconfig.so.1`) and expat 2.9.0 (`libexpat.so.1`
 *   + `libexpat.so`). Without Fontconfig Box64 can't initialize the wrapped library and
 *   `kernel32.dll` fails to load (c0000135).
 * - GnuTLS 3.8.13 (`libgnutls.so` + `libgnutls.so.30`, for secur32 / bcrypt) and its DT_NEEDED
 *   closure: nettle 3.10.2 (`libnettle.so.8`, `libhogweed.so.6` + unversioned), GMP, libtasn1,
 *   libidn2, libunistring, libiconv, p11-kit, libffi and zstd (`libzstd.so.1` + `libzstd.so`).
 * - SDL 2.32.10 (`libSDL2-2.0.so.0` + `libSDL2.so`, for winebus joysticks; termux-x11 repo) and
 *   the DT_NEEDED entries the X libraries above don't cover: libXss, libwayland-client,
 *   libwayland-cursor, libwayland-egl, libxkbcommon, libdecor-0.
 *
 * Box64 asks for the versioned name first (`libfreetype.so.6`) and then the unversioned one, while
 * the Termux libraries reference each other by their DT_NEEDED names (`libz.so.1`, `libbz2.so.1.0`,
 * `libpng16.so`), so both spellings are shipped where they differ. Every asset named `lib*.so` or
 * `lib*.so.<n>…` is installed ([isLibraryName]).
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
    private const val CONF_DIR = "x11"
    private const val FONTS_CONF = "fonts.conf"
    /** Bump whenever the bundled assets change so existing installs copy them again. */
    private const val VERSION = "termux-libx11-1.8.13_libxcb-1.17.0_freetype-2.14.3_xext-libs-2_fontconfig-2.18.3_gnutls-3.8.13_sdl2-2.32.10_fontsconf-1"
    private const val MARKER = ".fable-x11"
    private const val TERMUX_SOCKET_PREFIX = "/data/data/com.termux/files/usr/tmp/.X11-unix/X"

    /** `libfoo.so`, `libfoo.so.6`, `libbz2.so.1.0`, …; not `.tmp` leftovers or other files. */
    private val LIBRARY_NAME = Regex("""lib[^/]*\.so(\.\d+)*""")

    /**
     * FreeType names an older NativeLibResolver (`native-libs-1`) filled with a copy of Android's
     * `/system/lib64/libft2.so`. That copy is what makes Wine ask for FreeType >= 2.1.4, so it is
     * removed on every (re)install before the bundled FreeType is written in its place.
     */
    internal val STALE_SYSTEM_FREETYPE = listOf("libfreetype.so", "libfreetype.so.6")

    /** Whether an asset / file name is a shared library this installer ships. */
    internal fun isLibraryName(name: String): Boolean = LIBRARY_NAME.matches(name)

    /** Library file names bundled in this build's assets (what [install] copies). */
    fun bundledLibraries(context: Context): Set<String> =
        runCatching { context.assets.list(ASSET_DIR)?.filter(::isLibraryName)?.toSortedSet() }.getOrNull().orEmpty()

    /** A short identity for the bundled set, so dependants (NativeLibResolver) re-run when it changes. */
    fun bundleStamp(): String = VERSION

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
        val names = bundledLibraries(context).toList()
        if (names.isEmpty()) throw IOException("X11 client libraries are missing from this build")
        dir.mkdirs()
        marker.delete()
        removeStaleSystemFreeType(dir)
        installFontsConf(context, dir)
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
        Log.i(TAG, "Installed ${names.size} bundled native libraries (${names.joinToString()}), socket prefix $prefix")
        if (STALE_SYSTEM_FREETYPE.none { it in names }) {
            Log.e(TAG, "This build bundles no FreeType; Wine will report it missing (Android's libft2.so is not used)")
        }
        return dir
    }

    /** Path to the installed `fonts.conf`, for `FONTCONFIG_FILE`. Null if not installed. */
    fun fontsConfPath(context: Context): String? {
        val dir = libDir(context)
        val file = File(dir, FONTS_CONF)
        return if (file.isFile) file.absolutePath else null
    }

    /**
     * Copies `assets/x11/fonts.conf` into [dir]. Fontconfig's compiled-in config path
     * (`/data/data/com.termux/files/usr/etc/fonts`) doesn't exist on this device, so without this
     * file it logs "Cannot load default config file" and Wine can't enumerate fonts.
     */
    private fun installFontsConf(context: Context, dir: File) {
        val dest = File(dir, FONTS_CONF)
        runCatching {
            context.assets.open("$CONF_DIR/$FONTS_CONF").use { input ->
                val tmp = File(dir, "$FONTS_CONF.tmp")
                tmp.outputStream().use { input.copyTo(it) }
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("Couldn't install $FONTS_CONF")
                }
                dest.setReadable(true, false)
            }
        }.onFailure { Log.w(TAG, "Couldn't install $FONTS_CONF", it) }
    }

    /**
     * Deletes the FreeType copies (and temp files) a previous install may have left in [dir]:
     * the old resolver copied `/system/lib64/libft2.so` there as `libfreetype.so` / `.so.6`.
     * The bundled FreeType is written right after, so nothing here is needed afterwards.
     */
    private fun removeStaleSystemFreeType(dir: File) {
        for (name in STALE_SYSTEM_FREETYPE) {
            val file = File(dir, name)
            if (file.exists() && file.delete()) Log.i(TAG, "Removed previous $name (possibly a copy of the system libft2.so) before installing the bundled FreeType")
            File(dir, "$name.tmp").delete()
        }
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
