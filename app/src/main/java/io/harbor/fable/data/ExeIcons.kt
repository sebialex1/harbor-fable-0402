package io.harbor.fable.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * Icons for added Windows programs.
 *
 * [extract] reads the main icon out of a PE file (.exe/.dll): it walks the `.rsrc` resource
 * tree for the first RT_GROUP_ICON, picks its largest image and decodes that RT_ICON — PNG
 * images directly, classic DIB images wrapped in a one-image .ico, which Android's decoder
 * reads. Only the headers and the chosen resources are read, never the whole (often huge) file.
 * Anything unexpected yields null and the UI falls back to the initials tile.
 */
object ExeIcons {
    private const val TAG = "ExeIcons"

    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14

    /** Upper bound for one icon image; real ones are a few hundred KB at most. */
    private const val MAX_ICON_BYTES = 4 * 1024 * 1024

    /** Icons are stored at most this large; the UI never shows them bigger. */
    private const val STORED_SIZE = 256

    /** Decodes the main icon of the PE file at [uri], or null when it has none or is not PE. */
    suspend fun extract(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream -> extract(stream.channel) }
            }
        }.onFailure { Log.w(TAG, "No icon from $uri", it) }.getOrNull()
    }

    /** Decodes the main icon of the PE file at [file], or null. */
    suspend fun extract(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { FileInputStream(file).use { stream -> extract(stream.channel) } }
            .onFailure { Log.w(TAG, "No icon from $file", it) }
            .getOrNull()
    }

    /** Saves [bitmap] as a PNG under filesDir/exe-icons and returns its absolute path. */
    suspend fun save(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "exe-icons").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file.absolutePath
        }.onFailure { Log.w(TAG, "Couldn't save icon", it) }.getOrNull()
    }

    /** Loads a stored icon, or null when the file is gone. */
    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    // --- PE parsing ---------------------------------------------------------------------------

    private class Section(val virtualAddress: Long, val virtualSize: Long, val rawSize: Long, val rawOffset: Long)

    private class Pe(val channel: FileChannel, val sections: List<Section>) {
        fun offsetOf(rva: Long): Long? {
            val section = sections.firstOrNull { s ->
                rva >= s.virtualAddress && rva < s.virtualAddress + maxOf(s.virtualSize, s.rawSize)
            } ?: return null
            return rva - section.virtualAddress + section.rawOffset
        }
    }

    private fun FileChannel.readAt(position: Long, length: Int): ByteBuffer {
        require(position >= 0 && length >= 0 && position + length <= size()) { "Read outside the file" }
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var read = 0
        while (read < length) {
            val n = read(buffer, position + read)
            if (n <= 0) break
            read += n
        }
        require(read == length) { "Short read" }
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.u16(at: Int): Int = getShort(at).toInt() and 0xFFFF
    private fun ByteBuffer.u32(at: Int): Long = getInt(at).toLong() and 0xFFFFFFFFL

    internal fun extract(channel: FileChannel): Bitmap? {
        val size = channel.size()
        if (size < 64) return null
        val dos = channel.readAt(0, 64)
        if (dos.u16(0) != 0x5A4D) return null // "MZ"
        val peOffset = dos.u32(0x3C)
        if (peOffset <= 0 || peOffset + 24 > size) return null
        val coff = channel.readAt(peOffset, 24)
        if (coff.u32(0) != 0x00004550L) return null // "PE\0\0"
        val sectionCount = coff.u16(6)
        val optionalSize = coff.u16(20)
        if (optionalSize < 2 || sectionCount == 0 || sectionCount > 96) return null
        val optional = channel.readAt(peOffset + 24, optionalSize)
        val (rvaCountAt, directoriesAt) = when (optional.u16(0)) {
            0x10B -> 92 to 96 // PE32
            0x20B -> 108 to 112 // PE32+
            else -> return null
        }
        if (directoriesAt + 3 * 8 > optionalSize) return null
        if (optional.u32(rvaCountAt) < 3) return null
        val resourceRva = optional.u32(directoriesAt + 2 * 8)
        if (resourceRva == 0L) return null

        val sectionTable = channel.readAt(peOffset + 24 + optionalSize, sectionCount * 40)
        val sections = (0 until sectionCount).map { i ->
            val at = i * 40
            Section(
                virtualAddress = sectionTable.u32(at + 12),
                virtualSize = sectionTable.u32(at + 8),
                rawSize = sectionTable.u32(at + 16),
                rawOffset = sectionTable.u32(at + 20),
            )
        }
        val pe = Pe(channel, sections)
        val resourceBase = pe.offsetOf(resourceRva) ?: return null

        val groups = resourceEntries(pe, resourceBase, RT_GROUP_ICON)
        val icons = resourceEntries(pe, resourceBase, RT_ICON).toMap()
        if (icons.isEmpty()) return null

        // The first icon group is the one Explorer shows for the program.
        val group = groups.firstOrNull()?.second
        val iconData: ByteArray? = if (group != null) {
            bestFromGroup(pe, group, icons)
        } else {
            // No group: take the biggest image there is.
            icons.values.maxByOrNull { it.size }?.let { readData(pe, it) }
        }
        return iconData?.let(::decodeIconImage)?.let(::scaleDown)
    }

    /** A resource data entry: where its bytes are and how many. */
    private class DataEntry(val rva: Long, val size: Int)

    /** (id, first-language data entry) for every resource of [type]. */
    private fun resourceEntries(pe: Pe, base: Long, type: Int): List<Pair<Int, DataEntry>> {
        val typeDir = directoryEntries(pe, base, 0)
        val typeEntry = typeDir.firstOrNull { it.id == type && it.isDirectory } ?: return emptyList()
        return directoryEntries(pe, base, typeEntry.offset).mapNotNull { named ->
            if (!named.isDirectory) return@mapNotNull null
            val language = directoryEntries(pe, base, named.offset).firstOrNull { !it.isDirectory }
                ?: return@mapNotNull null
            val entry = pe.channel.readAt(base + language.offset, 16)
            named.id to DataEntry(rva = entry.u32(0), size = entry.u32(4).toInt())
        }
    }

    private class DirEntry(val id: Int, val isDirectory: Boolean, val offset: Long)

    private fun directoryEntries(pe: Pe, base: Long, offset: Long): List<DirEntry> {
        val header = pe.channel.readAt(base + offset, 16)
        val count = header.u16(12) + header.u16(14)
        if (count == 0 || count > 4096) return emptyList()
        val entries = pe.channel.readAt(base + offset + 16, count * 8)
        return (0 until count).map { i ->
            val name = entries.u32(i * 8)
            val target = entries.u32(i * 8 + 4)
            DirEntry(
                // Named entries (high bit set) get -1: icons are looked up by numeric id only.
                id = if (name and 0x80000000L != 0L) -1 else name.toInt(),
                isDirectory = target and 0x80000000L != 0L,
                offset = target and 0x7FFFFFFFL,
            )
        }
    }

    private fun readData(pe: Pe, entry: DataEntry): ByteArray? {
        if (entry.size <= 0 || entry.size > MAX_ICON_BYTES) return null
        val offset = pe.offsetOf(entry.rva) ?: return null
        val buffer = pe.channel.readAt(offset, entry.size)
        return ByteArray(entry.size).also { buffer.get(it) }
    }

    /** Reads the GRPICONDIR and returns the largest, deepest image it lists. */
    private fun bestFromGroup(pe: Pe, group: DataEntry, icons: Map<Int, DataEntry>): ByteArray? {
        val dir = readData(pe, group) ?: return null
        val buffer = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        if (dir.size < 6) return null
        val count = buffer.u16(4)
        data class Candidate(val id: Int, val size: Int, val bits: Int)
        val candidates = (0 until count).mapNotNull { i ->
            val at = 6 + i * 14
            if (at + 14 > dir.size) return@mapNotNull null
            val width = (dir[at].toInt() and 0xFF).let { if (it == 0) 256 else it }
            Candidate(id = buffer.u16(at + 12), size = width, bits = buffer.u16(at + 6))
        }
        val ordered = candidates.sortedWith(compareByDescending<Candidate> { it.size }.thenByDescending { it.bits })
        for (candidate in ordered) {
            val entry = icons[candidate.id] ?: continue
            readData(pe, entry)?.let { return it }
        }
        return null
    }

    private val PngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    /** Decodes one RT_ICON image: PNG as-is, DIB wrapped in a single-image .ico. */
    private fun decodeIconImage(data: ByteArray): Bitmap? {
        if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(PngSignature)) {
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        if (data.size < 40) return null
        val dib = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val width = dib.getInt(4)
        val height = dib.getInt(8) / 2 // XOR image + AND mask
        val bits = dib.u16(14)
        val ico = ByteBuffer.allocate(6 + 16 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0) // reserved
            putShort(1) // type: icon
            putShort(1) // one image
            put((if (width >= 256) 0 else width).toByte())
            put((if (height >= 256) 0 else height).toByte())
            put(0) // palette size
            put(0) // reserved
            putShort(1) // planes
            putShort(bits.toShort())
            putInt(data.size)
            putInt(6 + 16)
            put(data)
        }.array()
        return BitmapFactory.decodeByteArray(ico, 0, ico.size)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= STORED_SIZE) return bitmap
        val scale = STORED_SIZE.toFloat() / largest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }
}
