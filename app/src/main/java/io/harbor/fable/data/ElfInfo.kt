package io.harbor.fable.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ELF64 reader: just enough to tell a bionic build (`/system/bin/linker64`) from a
 * glibc one (`/lib64/ld-linux-x86-64.so.2`) and to read the target machine, without running it.
 */
internal object ElfInfo {
    const val MACHINE_X86_64 = 62
    const val MACHINE_AARCH64 = 183
    private const val PT_INTERP = 3

    /** e_machine of [file], or null when it is not a little-endian ELF64 file. */
    fun machine(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(20)
            raf.readFully(header)
            if (!isElf64Le(header)) return null
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() and 0xFFFF
        }
    }.getOrNull()

    /** PT_INTERP of [file] (the dynamic loader path), or null for static/non-ELF files. */
    fun interpreter(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(64)
            raf.readFully(header)
            if (!isElf64Le(header)) return null
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val phoff = buf.getLong(32)
            val phentsize = buf.getShort(54).toInt() and 0xFFFF
            val phnum = buf.getShort(56).toInt() and 0xFFFF
            if (phentsize < 56 || phnum > 128) return null
            val ph = ByteArray(phentsize)
            for (i in 0 until phnum) {
                raf.seek(phoff + i.toLong() * phentsize)
                raf.readFully(ph)
                val entry = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                if (entry.getInt(0) != PT_INTERP) continue
                val offset = entry.getLong(8)
                val size = entry.getLong(32).toInt().coerceIn(0, 4096)
                val path = ByteArray(size)
                raf.seek(offset)
                raf.readFully(path)
                return String(path, Charsets.US_ASCII).trimEnd('\u0000')
            }
            null
        }
    }.getOrNull()

    private fun isElf64Le(header: ByteArray): Boolean =
        header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte() && header[4] == 2.toByte() && header[5] == 1.toByte()
}
