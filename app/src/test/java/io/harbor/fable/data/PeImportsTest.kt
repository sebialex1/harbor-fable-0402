package io.harbor.fable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeImportsTest {
    /** A minimal PE32+ image with one section holding an import directory for [dlls]. */
    private fun pe(dlls: List<String>): ByteArray {
        val buf = ByteBuffer.allocate(0x400).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, 0x5A4D)
        buf.putInt(0x3C, 0x40)
        buf.putInt(0x40, 0x00004550)
        buf.putShort(0x44, PeImports.MACHINE_AMD64.toShort())
        buf.putShort(0x46, 1) // sections
        buf.putShort(0x54, 240) // SizeOfOptionalHeader
        val opt = 0x58
        buf.putShort(opt, 0x20B)
        buf.putInt(opt + 108, 16) // NumberOfRvaAndSizes
        buf.putInt(opt + 112 + 8, 0x1000) // import directory RVA
        buf.putInt(opt + 112 + 12, 20 * (dlls.size + 1))
        val section = opt + 240
        ".idata".toByteArray().forEachIndexed { i, b -> buf.put(section + i, b) }
        buf.putInt(section + 8, 0x200) // VirtualSize
        buf.putInt(section + 12, 0x1000) // VirtualAddress
        buf.putInt(section + 16, 0x200) // SizeOfRawData
        buf.putInt(section + 20, 0x200) // PointerToRawData
        dlls.forEachIndexed { i, name ->
            val nameRva = 0x1100 + i * 0x20
            buf.putInt(0x200 + i * 20 + 12, nameRva)
            name.toByteArray().forEachIndexed { j, b -> buf.put(0x200 + (nameRva - 0x1000) + j, b) }
        }
        return buf.array()
    }

    @Test fun readsImportedDllNames() {
        val file = File.createTempFile("game", ".exe").apply { deleteOnExit() }
        file.writeBytes(pe(listOf("UnityPlayer.dll", "KERNEL32.dll")))
        val info = PeImports.read(file)!!
        assertTrue(info.is64Bit)
        assertEquals(listOf("UnityPlayer.dll", "KERNEL32.dll"), info.imports)
        assertTrue(info.delayImports.isEmpty())
    }

    @Test fun nonPeFileGivesNull() {
        val file = File.createTempFile("notpe", ".exe").apply { deleteOnExit() }
        file.writeBytes(ByteArray(200) { 1 })
        assertNull(PeImports.read(file))
    }

    @Test fun diagnosisRecognizesMissingImport() {
        val output = "0128:err:module:import_dll Library UnityPlayer.dll (which is needed by " +
            "L\"C:\\\\fable\\\\abc\\\\ULTRAKILL.exe\") not found\n"
        val diagnosis = WineDiagnosis.analyze(output)
        assertEquals(listOf("UnityPlayer.dll (needed by ULTRAKILL.exe)"), diagnosis.missingImports)
        assertTrue(diagnosis.programFailed)
    }
}
