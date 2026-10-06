package io.harbor.fable.display

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XClientConnectionHandler
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
import java.io.File
import java.io.IOException

/**
 * The in-process X11 display server, vendored from Winlator (`com.winlator.xserver` +
 * `com.winlator.xconnector`, MIT, see THIRD_PARTY_NOTICES.md).
 *
 * Mirrors Winlator's `XServerComponent`: an [XServer] plus an epoll [XConnectorEpoll] listening
 * on a pathname AF_UNIX socket. Winlator bionic binds `{imagefs}/usr/tmp/.X11-unix/X0`; Fable
 * binds `filesDir/.X11-unix/X0`, which the patched libxcb from [X11ClientLibs] resolves for
 * `DISPLAY=:0`. The socket is bound and listening before [ensureStarted] returns, and Wine is
 * only spawned after that, the same ordering as Winlator's `XEnvironment` (X server component
 * before the guest launcher).
 *
 * One server for the app process: it outlives the display screen so Wine keeps its connection
 * while the user is elsewhere in the app.
 */
object DisplayServer {
    private const val TAG = "DisplayServer"
    const val DISPLAY = ":0"
    private const val SOCKET_NAME = "X0"
    private const val DEFAULT_RESOLUTION = "1280x720"

    @Volatile
    var xServer: XServer? = null
        private set

    private var connector: XConnectorEpoll? = null
    private var socketPath: String? = null

    /** Screen size of the running server, e.g. `1280x720`. */
    val resolution: String? get() = xServer?.screenInfo?.toString()

    data class Ready(val display: String, val socketPath: String, val resolution: String)

    /**
     * Starts the server at [resolution] (`WxH`) unless one is already running, and verifies the
     * socket accepts a connection. A running server keeps its size: X clients are attached to
     * it, and Wine's virtual desktop is sized from the same container setting.
     */
    @Synchronized
    fun ensureStarted(context: Context, resolution: String): Result<Ready> = runCatching {
        val running = xServer
        val path = socketPath
        if (running != null && path != null && File(path).exists()) {
            return@runCatching Ready(DISPLAY, path, running.screenInfo.toString())
        }
        stopLocked()
        val screen = parseScreen(resolution)
        val dir = X11ClientLibs.socketDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create ${dir.absolutePath}")
        val server = XServer(screen)
        // createSocket deletes a stale X0 left by a previous process.
        val config = UnixSocketConfig.createSocket(dir.absolutePath, "/$SOCKET_NAME")
        val epoll = XConnectorEpoll(config, XClientConnectionHandler(server), XClientRequestHandler())
        epoll.setInitialInputBufferCapacity(262144)
        epoll.setCanReceiveAncillaryMessages(true)
        epoll.start()
        xServer = server
        connector = epoll
        socketPath = config.path
        probe(config.path)
        Log.i(TAG, "X server listening on ${config.path} ($screen)")
        Ready(DISPLAY, config.path, screen.toString())
    }.onFailure { error ->
        Log.e(TAG, "X server failed to start", error)
        runCatching { stopLocked() }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        runCatching { connector?.stop() }
        connector = null
        xServer = null
        socketPath?.let { File(it).delete() }
        socketPath = null
    }

    /** One connect/close against the socket, so a launch never races a server that isn't up. */
    private fun probe(path: String) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        }
    }

    private fun parseScreen(value: String): ScreenInfo {
        val match = Regex("""^\s*(\d{3,4})\s*[xX]\s*(\d{3,4})\s*$""").find(value)
        val (w, h) = match?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
            ?: DEFAULT_RESOLUTION.split('x').let { it[0].toInt() to it[1].toInt() }
        return ScreenInfo(w.coerceIn(320, 3840), h.coerceIn(240, 2160))
    }
}
