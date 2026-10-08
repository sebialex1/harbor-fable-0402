package io.harbor.fable.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * The Unreal Engine launcher check, on synthetic packages shaped like the RTX benchmark
 * (`SimpleRayTracingBenchmark_v0.7/` holding `RTX_bench.exe`, `Engine/Binaries/Win64/UE4Game.exe`
 * and `RTX_bench/RTX_bench.uproject`), with nested folders and spaces in names.
 */
class Ue4PackageTest {

    /** A PE32+ image whose only section is `.rsrc` with RCDATA [resources] (id to bytes). */
    private fun peWithRcData(resources: Map<Int, ByteArray>): ByteArray {
        val sectionRaw = 0x200
        val sectionRva = 0x1000
        val sectionSize = 0x800
        val buf = ByteBuffer.allocate(sectionRaw + sectionSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, 0x5A4D)
        buf.putInt(0x3C, 0x40)
        buf.putInt(0x40, 0x00004550)
        buf.putShort(0x44, PeImports.MACHINE_AMD64.toShort())
        buf.putShort(0x46, 1)
        buf.putShort(0x54, 240)
        val opt = 0x58
        buf.putShort(opt, 0x20B)
        buf.putInt(opt + 108, 16)
        buf.putInt(opt + 112 + 2 * 8, sectionRva) // resource directory
        buf.putInt(opt + 112 + 2 * 8 + 4, sectionSize)
        val section = opt + 240
        ".rsrc".toByteArray().forEachIndexed { i, b -> buf.put(section + i, b) }
        buf.putInt(section + 8, sectionSize)
        buf.putInt(section + 12, sectionRva)
        buf.putInt(section + 16, sectionSize)
        buf.putInt(section + 20, sectionRaw)

        val ids = resources.keys.sorted()
        fun dirHeader(at: Int, count: Int) { buf.putShort(sectionRaw + at + 14, count.toShort()) }
        fun entry(at: Int, id: Int, target: Int) {
            buf.putInt(sectionRaw + at, id)
            buf.putInt(sectionRaw + at + 4, target)
        }
        // Root: one type entry, RT_RCDATA (10).
        dirHeader(0, 1)
        val typeDir = 0x18
        entry(16, 10, 0x80000000.toInt() or typeDir)
        dirHeader(typeDir, ids.size)
        var langDir = typeDir + 16 + ids.size * 8
        val dataEntries = langDir + ids.size * 24
        var data = 0x300
        ids.forEachIndexed { i, id ->
            entry(typeDir + 16 + i * 8, id, 0x80000000.toInt() or langDir)
            dirHeader(langDir, 1)
            val dataEntry = dataEntries + i * 16
            entry(langDir + 16, 0x409, dataEntry)
            val bytes = resources.getValue(id)
            buf.putInt(sectionRaw + dataEntry, sectionRva + data)
            buf.putInt(sectionRaw + dataEntry + 4, bytes.size)
            bytes.forEachIndexed { j, b -> buf.put(sectionRaw + data + j, b) }
            data += (bytes.size + 15) / 16 * 16
            langDir += 24
        }
        return buf.array()
    }

    private fun utf16z(text: String) = (text + "\u0000").toByteArray(Charsets.UTF_16LE)

    private val ue4GameTarget = "Engine\\Binaries\\Win64\\UE4Game.exe"
    private val rtxArgs = "..\\..\\..\\RTX_bench\\RTX_bench.uproject"

    private fun launcher(target: String = ue4GameTarget, args: String = rtxArgs) =
        peWithRcData(mapOf(Ue4Package.EXEC_FILE_RESOURCE to utf16z(target), Ue4Package.EXEC_ARGS_RESOURCE to utf16z(args)))

    /** A fake prefix with dosdevices/c: -> drive_c and z: -> /. */
    private fun prefix(): File {
        val dir = Files.createTempDirectory("prefix").toFile()
        File(dir, "drive_c/fable").mkdirs()
        File(dir, "dosdevices").mkdirs()
        Files.createSymbolicLink(File(dir, "dosdevices/c:").toPath(), File("../drive_c").toPath())
        Files.createSymbolicLink(File(dir, "dosdevices/z:").toPath(), File("/").toPath())
        return dir
    }

    /** The benchmark package under [root]/[packageName], optionally without UE4Game.exe. */
    private fun ue4Tree(root: File, packageName: String = "SimpleRayTracingBenchmark v0.7", withEngineExe: Boolean = true): File {
        val pkg = File(root, packageName)
        File(pkg, "Engine/Binaries/Win64").mkdirs()
        File(pkg, "Engine/Binaries/ThirdParty/Windows/DirectX/x64").mkdirs()
        File(pkg, "RTX_bench/Content/Paks").mkdirs()
        File(pkg, "RTX_bench.exe").writeBytes(launcher())
        if (withEngineExe) File(pkg, "Engine/Binaries/Win64/UE4Game.exe").writeBytes(ByteArray(4096) { 7 })
        File(pkg, "Engine/Binaries/ThirdParty/Windows/DirectX/x64/WinPixEventRuntime.dll").writeBytes(ByteArray(100))
        File(pkg, "RTX_bench/RTX_bench.uproject").writeText("{}")
        File(pkg, "RTX_bench/Content/Paks/RTX_bench-WindowsNoEditor.pak").writeBytes(ByteArray(10_000) { 1 })
        File(pkg, "Manifest_NonUFSFiles_Win64.txt").writeText("x")
        return pkg
    }

    /** [GameFolders.Source] over a plain folder (what the SAF tree is on device). */
    private class DirSource(private val root: File, private val skip: (String) -> Boolean = { false }) : GameFolders.Source {
        override fun list(): List<GameFolders.Entry> = root.walkTopDown().filter { it != root }.map { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            GameFolders.Entry(rel, rel, file.isDirectory, if (file.isFile) file.length() else -1)
        }.toList()

        override fun open(entry: GameFolders.Entry) =
            if (skip(entry.relativePath)) throw java.io.IOException("simulated provider error") else File(root, entry.relativePath).inputStream()
    }

    @Test fun readsLauncherTargetAndArgumentsFromResources() {
        val file = File.createTempFile("RTX_bench", ".exe").apply { deleteOnExit() }
        file.writeBytes(launcher())
        val bootstrap = Ue4Package.readBootstrap(file)
        assertNotNull(bootstrap)
        assertEquals(ue4GameTarget, bootstrap!!.targetExe)
        assertEquals(rtxArgs, bootstrap.arguments)
        assertEquals("Engine/Binaries/Win64/UE4Game.exe", bootstrap.targetRelative)
        assertEquals("RTX_bench/RTX_bench.uproject", bootstrap.uprojectRelative)
    }

    @Test fun ordinaryExeIsNotALauncher() {
        val file = File.createTempFile("game", ".exe").apply { deleteOnExit() }
        file.writeBytes(peWithRcData(mapOf(1 to "hello".toByteArray())))
        assertNull(Ue4Package.readBootstrap(file))
        file.writeBytes(ByteArray(300) { 3 })
        assertNull(Ue4Package.readBootstrap(file))
    }

    @Test fun pathsNormalizeAndNeverLeaveThePackage() {
        assertEquals("Engine/Binaries/Win64/UE4Game.exe", Ue4Package.normalize("Engine\\Binaries\\Win64\\UE4Game.exe"))
        assertEquals("Engine/Binaries/Win64/UE4Game.exe", Ue4Package.normalize("Engine/Binaries/./Win64//UE4Game.exe"))
        assertEquals("RTX_bench/RTX_bench.uproject", Ue4Package.normalize("Engine/Binaries/Win64/../../../RTX_bench/RTX_bench.uproject"))
        assertNull(Ue4Package.normalize("..\\outside.exe"))
        assertNull(Ue4Package.normalize("C:\\abs.exe"))
        assertEquals(listOf("My Game/My Game.uproject", "-log"), Ue4Package.tokens("\"My Game/My Game.uproject\" -log"))
    }

    @Test fun copiedWholeFolderFindsTheTarget() = runBlocking {
        val source = ue4Tree(Files.createTempDirectory("src").toFile())
        val prefix = prefix()
        val dest = File(prefix, "drive_c/fable/75557a4c-app")
        val report = GameFolders.copyTree(DirSource(source), dest, "RTX_bench.exe")
        assertNotNull(report.exe)
        assertTrue(report.failed.isEmpty())
        // Engine and the project folder are siblings of the launcher in the copy, names kept.
        assertTrue(File(dest, "Engine/Binaries/Win64/UE4Game.exe").isFile)
        assertTrue(File(dest, "RTX_bench/Content/Paks/RTX_bench-WindowsNoEditor.pak").isFile)
        assertTrue("engine/binaries/win64/ue4game.exe" in report.sourceFiles!!)
        assertTrue(report.topLevel.containsAll(listOf("Engine/", "RTX_bench/", "RTX_bench.exe")))

        val check = Ue4Package.check(
            Ue4Package.readBootstrap(report.exe!!)!!, prefix, dest, "C:\\fable\\75557a4c-app",
            copiedAlone = false, inPlace = false, sourceFiles = report.sourceFiles, launcherInSource = "RTX_bench.exe",
        )
        assertEquals(Ue4Package.TargetState.PRESENT, check.state)
        assertEquals("C:\\fable\\75557a4c-app\\Engine\\Binaries\\Win64\\UE4Game.exe", check.windowsPath)
        assertEquals(true, check.uprojectPresent)
        assertNull(Ue4Package.userMessage("RTX", "RTX_bench.exe", check, allFilesAccess = false))
    }

    @Test fun launcherOneLevelDownKeepsItsSiblings() = runBlocking {
        // The user picked the outer folder: the launcher is in "SimpleRayTracingBenchmark v0.7/".
        val outer = Files.createTempDirectory("outer").toFile()
        ue4Tree(outer)
        val prefix = prefix()
        val dest = File(prefix, "drive_c/fable/app2")
        val exe = "SimpleRayTracingBenchmark v0.7/RTX_bench.exe"
        val report = GameFolders.copyTree(DirSource(outer), dest, exe)
        val launcherDir = report.exe!!.parentFile!!
        val check = Ue4Package.check(
            Ue4Package.readBootstrap(report.exe!!)!!, prefix, launcherDir, "C:\\fable\\app2\\SimpleRayTracingBenchmark v0.7",
            copiedAlone = false, inPlace = false, sourceFiles = report.sourceFiles, launcherInSource = exe,
        )
        assertEquals(Ue4Package.TargetState.PRESENT, check.state)
    }

    @Test fun launcherCopiedAloneIsOmittedDuringImport() {
        val prefix = prefix()
        val dest = File(prefix, "drive_c/fable/lone").apply { mkdirs() }
        File(dest, "RTX_bench.exe").writeBytes(launcher())
        val check = Ue4Package.check(
            Ue4Package.readBootstrap(File(dest, "RTX_bench.exe"))!!, prefix, dest, "C:\\fable\\lone",
            copiedAlone = true, inPlace = false, sourceFiles = null, launcherInSource = null,
        )
        assertEquals(Ue4Package.TargetState.OMITTED_DURING_IMPORT, check.state)
        val message = Ue4Package.userMessage("RTX", "RTX_bench.exe", check, allFilesAccess = false)!!
        assertTrue(message, message.contains("Choose game folder") && message.contains("All files access"))
        assertEquals(false, check.uprojectPresent)
    }

    @Test fun targetMissingFromThePickedFolderIsAbsentFromSource() = runBlocking {
        val source = ue4Tree(Files.createTempDirectory("src").toFile(), withEngineExe = false)
        val prefix = prefix()
        val dest = File(prefix, "drive_c/fable/app3")
        val report = GameFolders.copyTree(DirSource(source), dest, "RTX_bench.exe")
        val check = Ue4Package.check(
            Ue4Package.readBootstrap(report.exe!!)!!, prefix, dest, "C:\\fable\\app3",
            copiedAlone = false, inPlace = false, sourceFiles = report.sourceFiles, launcherInSource = "RTX_bench.exe",
        )
        assertEquals(Ue4Package.TargetState.ABSENT_FROM_SOURCE, check.state)
        assertTrue(check.detail, check.detail.contains("has an Engine folder"))
        assertNotNull(Ue4Package.userMessage("RTX", "RTX_bench.exe", check, allFilesAccess = true))
    }

    @Test fun failedCopyIsOmittedDuringImportWithTheReason() = runBlocking {
        val source = ue4Tree(Files.createTempDirectory("src").toFile())
        val prefix = prefix()
        val dest = File(prefix, "drive_c/fable/app4")
        val report = GameFolders.copyTree(DirSource(source) { it.endsWith("UE4Game.exe") }, dest, "RTX_bench.exe")
        assertEquals(1, report.failed.size)
        val check = Ue4Package.check(
            Ue4Package.readBootstrap(report.exe!!)!!, prefix, dest, "C:\\fable\\app4",
            copiedAlone = false, inPlace = false, sourceFiles = report.sourceFiles, launcherInSource = "RTX_bench.exe",
            copyFailures = report.failedPaths,
        )
        assertEquals(Ue4Package.TargetState.OMITTED_DURING_IMPORT, check.state)
        assertTrue(check.detail, check.detail.contains("simulated provider error"))
    }

    @Test fun presentButDriveLinkElsewhereIsNotMapped() = runBlocking {
        val source = ue4Tree(Files.createTempDirectory("src").toFile())
        val prefix = prefix()
        val dest = File(prefix, "drive_c/fable/app5")
        val report = GameFolders.copyTree(DirSource(source), dest, "RTX_bench.exe")
        // c: points at some other folder.
        val c = File(prefix, "dosdevices/c:")
        c.delete()
        Files.createSymbolicLink(c.toPath(), Files.createTempDirectory("elsewhere"))
        val check = Ue4Package.check(
            Ue4Package.readBootstrap(report.exe!!)!!, prefix, dest, "C:\\fable\\app5",
            copiedAlone = false, inPlace = false, sourceFiles = report.sourceFiles, launcherInSource = "RTX_bench.exe",
        )
        assertEquals(Ue4Package.TargetState.PRESENT_NOT_MAPPED, check.state)
        // Logged, but the launch isn't blocked on a mapping check.
        assertNull(Ue4Package.userMessage("RTX", "RTX_bench.exe", check, allFilesAccess = true))
    }

    @Test fun inPlaceRunThroughZDriveResolvesCaseInsensitively() {
        val source = ue4Tree(Files.createTempDirectory("Download").toFile())
        // Wine looks names up case-insensitively; the launcher may store a different case.
        File(source, "RTX_bench.exe").writeBytes(launcher(target = "engine\\binaries\\win64\\ue4game.exe"))
        val prefix = prefix()
        val windowsDir = "Z:" + source.absolutePath.replace('/', '\\')
        val check = Ue4Package.check(
            Ue4Package.readBootstrap(File(source, "RTX_bench.exe"))!!, prefix, source, windowsDir,
            copiedAlone = false, inPlace = true, sourceFiles = null, launcherInSource = null,
        )
        assertEquals(Ue4Package.TargetState.PRESENT, check.state)
        File(source, "Engine/Binaries/Win64/UE4Game.exe").delete()
        val missing = Ue4Package.check(
            Ue4Package.readBootstrap(File(source, "RTX_bench.exe"))!!, prefix, source, windowsDir,
            copiedAlone = false, inPlace = true, sourceFiles = null, launcherInSource = null,
        )
        assertEquals(Ue4Package.TargetState.ABSENT_FROM_SOURCE, missing.state)
        assertFalse(missing.describe().isEmpty())
    }

    @Test fun recopyOnlyCopiesChangedFiles() = runBlocking {
        val source = ue4Tree(Files.createTempDirectory("src").toFile())
        val dest = File(prefix(), "drive_c/fable/app6")
        val first = GameFolders.copyTree(DirSource(source), dest, "RTX_bench.exe")
        val second = GameFolders.copyTree(DirSource(source), dest, "RTX_bench.exe")
        assertTrue(first.copied > 0)
        assertEquals(0, second.copied)
        assertEquals(first.copied, second.upToDate)
    }
}
