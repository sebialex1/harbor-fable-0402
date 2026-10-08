package io.harbor.fable.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads the DLLs a Windows program imports (its PE import directory), so a launch can tell
 * before Wine starts that a game can't run: a Unity game's `ULTRAKILL.exe` is a small stub that
 * imports `UnityPlayer.dll` from its own folder. When only the .exe was copied into the
 * container, Wine's loader stops with `err:module:import_dll Library UnityPlayer.dll ... not
 * found` and explorer.exe exits 0 after a black desktop, which looks like a graphics problem.
 */
internal object PeImports {
    const val MACHINE_I386 = 0x14C
    const val MACHINE_AMD64 = 0x8664
    const val MACHINE_ARM64 = 0xAA64

    data class Info(
        val machine: Int,
        /** DLL names from the import directory, as written (`UnityPlayer.dll`, `KERNEL32.dll`). */
        val imports: List<String>,
        /** DLL names from the delay-load import directory; loaded on first use, not at start. */
        val delayImports: List<String>,
    ) {
        val is64Bit: Boolean get() = machine == MACHINE_AMD64 || machine == MACHINE_ARM64
    }

    /** Import information of [file], or null when it isn't a readable PE image. Never throws. */
    fun read(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf -> read(raf.channel) }
    }.getOrNull()

    private class Section(val virtualAddress: Long, val virtualSize: Long, val rawSize: Long, val rawOffset: Long)

    private class Pe(val channel: FileChannel, val sections: List<Section>, val machine: Int) {
        fun offsetOf(rva: Long): Long? {
            val section = sections.firstOrNull { s ->
                rva >= s.virtualAddress && rva < s.virtualAddress + maxOf(s.virtualSize, s.rawSize)
            } ?: return null
            return rva - section.virtualAddress + section.rawOffset
        }

        /** NUL-terminated ASCII string at [rva] (at most 255 bytes). */
        fun stringAt(rva: Long): String? {
            val offset = offsetOf(rva) ?: return null
            val length = minOf(256L, channel.size() - offset).toInt()
            if (length <= 0) return null
            val buffer = channel.readAt(offset, length)
            val bytes = ByteArray(length)
            buffer.get(bytes)
            val end = bytes.indexOf(0).let { if (it < 0) length else it }
            return String(bytes, 0, end, Charsets.US_ASCII).takeIf { it.isNotBlank() }
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

    /**
     * The `RT_RCDATA` resources of [file] by numeric id (first language of each), at most
     * [maxBytes] each; empty when it isn't a PE image or has none. Never throws. An Unreal Engine
     * packaged game's launcher (BootstrapPackagedGame) keeps the program it starts and that
     * program's arguments in RCDATA 201 / 202 (see [Ue4Package]).
     */
    fun rawDataResources(file: File, maxBytes: Int = 64 * 1024): Map<Int, ByteArray> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val pe = open(raf.channel) ?: return@use emptyMap()
            rcData(pe.first, pe.second(2), maxBytes)
        }
    }.getOrElse { emptyMap() }

    private fun read(channel: FileChannel): Info? {
        val (pe, directoryRva) = open(channel) ?: return null
        // Import descriptors are 20 bytes (name RVA at +12), delay-load descriptors 32 bytes
        // (name RVA at +4); both lists end with an all-zero entry.
        val imports = names(pe, directoryRva(1), entrySize = 20, nameAt = 12)
        val delayImports = names(pe, directoryRva(13), entrySize = 32, nameAt = 4)
        return Info(machine = pe.machine, imports = imports, delayImports = delayImports)
    }

    /** RT_RCDATA (type 10) leaves under the resource directory at [rootRva]. */
    private fun rcData(pe: Pe, rootRva: Long, maxBytes: Int): Map<Int, ByteArray> {
        if (rootRva == 0L) return emptyMap()
        val root = pe.offsetOf(rootRva) ?: return emptyMap()
        val size = pe.channel.size()
        // (id or -1 for a named entry, offset relative to the resource root, isDirectory)
        fun entries(dirOffset: Long): List<Triple<Int, Long, Boolean>> {
            val at = root + dirOffset
            if (dirOffset < 0 || at + 16 > size) return emptyList()
            val header = pe.channel.readAt(at, 16)
            val count = (header.u16(12) + header.u16(14)).coerceAtMost(MAX_RESOURCE_ENTRIES)
            if (at + 16 + count * 8L > size) return emptyList()
            val table = pe.channel.readAt(at + 16, count * 8)
            return (0 until count).map { i ->
                val name = table.u32(i * 8)
                val target = table.u32(i * 8 + 4)
                Triple(
                    if (name and 0x80000000L != 0L) -1 else name.toInt(),
                    target and 0x7FFFFFFFL,
                    target and 0x80000000L != 0L,
                )
            }
        }
        val rcdata = entries(0).firstOrNull { it.first == RT_RCDATA && it.third } ?: return emptyMap()
        val result = LinkedHashMap<Int, ByteArray>()
        for ((id, offset, isDir) in entries(rcdata.second)) {
            if (id < 0 || !isDir) continue
            val leaf = entries(offset).firstOrNull { !it.third } ?: continue
            val dataEntryAt = root + leaf.second
            if (dataEntryAt + 16 > size) continue
            val dataEntry = pe.channel.readAt(dataEntryAt, 16)
            val dataRva = dataEntry.u32(0)
            val dataSize = dataEntry.u32(4).coerceAtMost(maxBytes.toLong()).toInt()
            val dataAt = pe.offsetOf(dataRva) ?: continue
            if (dataSize <= 0 || dataAt + dataSize > size) continue
            val bytes = ByteArray(dataSize)
            pe.channel.readAt(dataAt, dataSize).get(bytes)
            result[id] = bytes
        }
        return result
    }

    /** The section table and a data-directory lookup of the PE image in [channel], or null. */
    private fun open(channel: FileChannel): Pair<Pe, (Int) -> Long>? {
        val size = channel.size()
        if (size < 64) return null
        val dos = channel.readAt(0, 64)
        if (dos.u16(0) != 0x5A4D) return null // "MZ"
        val peOffset = dos.u32(0x3C)
        if (peOffset <= 0 || peOffset + 24 > size) return null
        val coff = channel.readAt(peOffset, 24)
        if (coff.u32(0) != 0x00004550L) return null // "PE\0\0"
        val machine = coff.u16(4)
        val sectionCount = coff.u16(6)
        val optionalSize = coff.u16(20)
        if (optionalSize < 2 || sectionCount == 0 || sectionCount > 96) return null
        if (peOffset + 24 + optionalSize + sectionCount * 40L > size) return null
        val optional = channel.readAt(peOffset + 24, optionalSize)
        val (rvaCountAt, directoriesAt) = when (optional.u16(0)) {
            0x10B -> 92 to 96 // PE32
            0x20B -> 108 to 112 // PE32+
            else -> return null
        }
        val directoryCount = if (rvaCountAt + 4 <= optionalSize) optional.u32(rvaCountAt).toInt() else 0
        fun directoryRva(index: Int): Long =
            if (index < directoryCount && directoriesAt + index * 8 + 8 <= optionalSize) optional.u32(directoriesAt + index * 8) else 0L

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
        return Pe(channel, sections, machine) to ::directoryRva
    }

    private fun names(pe: Pe, tableRva: Long, entrySize: Int, nameAt: Int): List<String> {
        if (tableRva == 0L) return emptyList()
        val base = pe.offsetOf(tableRva) ?: return emptyList()
        val names = mutableListOf<String>()
        val size = pe.channel.size()
        for (i in 0 until MAX_IMPORTS) {
            val at = base + i.toLong() * entrySize
            if (at + entrySize > size) break
            val entry = pe.channel.readAt(at, entrySize)
            var empty = true
            for (b in 0 until entrySize step 4) if (entry.getInt(b) != 0) empty = false
            if (empty) break
            val nameRva = entry.u32(nameAt)
            pe.stringAt(nameRva)?.let { names += it }
        }
        return names.distinctBy { it.lowercase() }
    }

    private const val MAX_IMPORTS = 512
    private const val RT_RCDATA = 10
    private const val MAX_RESOURCE_ENTRIES = 4096
}
