package io.harbor.fable.display

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import com.winlator.xserver.XServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Receives frames from the guest's Android ImageReader surface. The display's EGL surface
 * belongs to the app process, so sharing its ANativeWindow address with exec'd Wine is invalid.
 * Presented pixels instead update the actual X window, keeping window placement, cursor and
 * input in the existing Winlator renderer. This is a compatibility path, not zero-copy WSI.
 */
internal class VulkanDisplayBridge(private val server: XServer, private val path: File) {
    private val bound = LocalSocket()
    private val clients = mutableSetOf<LocalSocket>()
    @Volatile private var running = false
    private var listener: LocalServerSocket? = null

    fun start() {
        path.delete()
        bound.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        listener = LocalServerSocket(bound.fileDescriptor)
        running = true
        val socket = listener!!
        thread(name = "Vulkan-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "Display bridge accept failed", e)
                    break
                }
                synchronized(clients) {
                    if (!running) {
                        client.close()
                    } else {
                        clients.add(client)
                        thread(name = "Vulkan-frame", isDaemon = true) { receive(client) }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { listener?.close() }
        listener = null
        runCatching { bound.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        path.delete()
    }

    private fun writeSize(output: DataOutputStream, windowId: Int) {
        server.lock(XServer.Lockable.WINDOW_MANAGER).use {
            val window = server.windowManager.getWindow(windowId)
            output.writeInt(window?.width?.toInt() ?: 0)
            output.writeInt(window?.height?.toInt() ?: 0)
        }
        output.flush()
    }

    private fun receive(client: LocalSocket) {
        try {
            client.use {
                // A filesystem path is not authentication; require the app's Linux UID.
                if (client.peerCredentials.uid != Process.myUid()) return
                val input = DataInputStream(client.inputStream)
                val output = DataOutputStream(client.outputStream)
                if (input.readInt() != MAGIC) return
                val windowId = input.readInt()
                writeSize(output, windowId)
                var pixels = ByteArray(0)
                while (running) {
                    when (input.readInt()) {
                        QUERY_SIZE -> writeSize(output, windowId)
                        FRAME -> {
                            val width = input.readInt()
                            val height = input.readInt()
                            if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return
                            val size = width * height * 4
                            if (pixels.size != size) pixels = ByteArray(size)
                            input.readFully(pixels)
                            server.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER).use frame@{
                                val drawable = server.windowManager.getWindow(windowId)?.content ?: return@frame
                                synchronized(drawable.renderLock) {
                                    val w = minOf(width, drawable.width.toInt())
                                    val h = minOf(height, drawable.height.toInt())
                                    val data = drawable.data
                                    for (y in 0 until h) {
                                        data.position(y * drawable.width * 4)
                                        data.put(pixels, y * width * 4, w * 4)
                                    }
                                    data.rewind()
                                    drawable.texture.setNeedsUpdate(true)
                                }
                                drawable.onDrawListener?.run()
                            }
                        }
                        else -> return
                    }
                }
            }
        } catch (e: IOException) {
            if (running) Log.d(TAG, "Vulkan surface disconnected: ${e.message}")
        } finally {
            synchronized(clients) { clients.remove(client) }
        }
    }

    companion object {
        private const val TAG = "VulkanDisplayBridge"
        private const val MAGIC = 0x46414231
        private const val QUERY_SIZE = 1
        private const val FRAME = 2
        private const val MAX_EDGE = 4096
    }
}
