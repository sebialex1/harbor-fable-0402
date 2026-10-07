package io.harbor.fable.data

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Small Windows programs every container gets, so a user can check that graphics work before
 * blaming a game: Winlator puts a set of Direct3D test programs and a Vulkan GPU info tool into
 * every container in the same spirit.
 *
 * They live in `C:\fable\tools` ([PREFIX_DIR]) and show up as app shortcuts ([ExeEntry] with
 * [io.harbor.fable.data.models.ExeEntry.toolId] set) in the container's Tools list:
 *
 * - **GPU Info** (`gpu-info.bat`, written by Fable, always available): runs the bundled
 *   `VulkanGpuInfo.exe` when there is one; otherwise collects what Wine sees through DXGI
 *   (`wmic … Win32_VideoController`, which goes through DXVK's dxgi.dll), the bundled
 *   `vulkaninfo.exe --summary` when present, and Wine's `dxdiag /t` (Direct3D 9 adapter, through
 *   DXVK's d3d9.dll), and opens the report in Notepad.
 * - **Direct3D 9 / 11 / 12 Test** (`d3d9-test.exe`, `d3d11-test.exe`, `d3d12-test.exe`): copied
 *   from the APK's `assets/container_tools/` when the build bundles them. `d3d11-test.exe` is
 *   Fable's own (`container_tools/d3d11-test`), cross-compiled and bundled by the release
 *   workflow, so it is available in those builds. The shortcuts exist either way; launching a
 *   missing one says it isn't bundled. See `app/src/main/assets/container_tools/README.md`
 *   for what goes there.
 *
 * [install] runs on every launch, after the prefix exists, and only rewrites files when the
 * APK changed ([MARKER]) or a file is missing.
 */
internal object ContainerTools {
    private const val TAG = "ContainerTools"

    /** Folder in the prefix, i.e. `C:\fable\tools`. */
    const val PREFIX_DIR = "drive_c/fable/tools"
    const val WINDOWS_DIR = "C:\\fable\\tools"

    /** APK asset folder the real binaries are bundled from. */
    const val ASSET_DIR = "container_tools"

    /** `<version> <stamp>` of the last complete install. */
    private const val MARKER = ".fable-tools"
    private const val VERSION = 1

    data class Tool(
        /** Stable id stored on the shortcut ([io.harbor.fable.data.models.ExeEntry.toolId]). */
        val id: String,
        val name: String,
        val fileName: String,
        val description: String,
        /** Contents Fable writes itself (a batch script); null for binaries bundled as assets. */
        val script: String? = null,
    ) {
        val windowsPath: String get() = "$WINDOWS_DIR\\$fileName"

        /** Scripts are always there; binaries only when the APK bundles them. */
        fun isAvailable(bundled: Set<String>): Boolean = script != null || fileName.lowercase() in bundled
    }

    val GPU_INFO = Tool(
        id = "gpu-info",
        name = "GPU Info",
        fileName = "gpu-info.bat",
        description = "What Vulkan and Direct3D report in this container",
        script = gpuInfoScript(),
    )
    val D3D9_TEST = Tool("d3d9-test", "Direct3D 9 Test", "d3d9-test.exe", "Renders through DXVK's d3d9.dll")
    val D3D11_TEST = Tool("d3d11-test", "Direct3D 11 Test", "d3d11-test.exe", "Renders through DXVK's d3d11.dll")
    val D3D12_TEST = Tool("d3d12-test", "Direct3D 12 Test", "d3d12-test.exe", "Renders through VKD3D-Proton's d3d12.dll")

    /** Shortcut order in the Tools list. */
    val all: List<Tool> = listOf(GPU_INFO, D3D9_TEST, D3D11_TEST, D3D12_TEST)

    /** Bundled helpers GPU Info uses when present; they get no shortcut of their own. */
    val HELPERS: List<String> = listOf("VulkanGpuInfo.exe", "vulkaninfo.exe")

    fun byId(id: String?): Tool? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }

    fun file(prefix: File, tool: Tool): File = File(prefix, "$PREFIX_DIR/${tool.fileName}")

    /** Lower-case names of the files in the APK's [ASSET_DIR] (empty when the build bundles none). */
    fun bundledFiles(assets: AssetManager): Set<String> =
        runCatching { assets.list(ASSET_DIR)?.map { it.lowercase() }?.toSet() }.getOrNull().orEmpty()

    data class Report(
        val written: List<String> = emptyList(),
        /** Tools whose binary this build doesn't bundle. */
        val notBundled: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val upToDate: Boolean = false,
    ) {
        fun describe(): List<String> = buildList {
            add(if (upToDate) "tools in $WINDOWS_DIR: up to date" else "tools in $WINDOWS_DIR: wrote ${written.size} file(s)")
            if (notBundled.isNotEmpty()) add("not bundled in this build: ${notBundled.joinToString()}")
            failed.forEach { add("failed: $it") }
        }
    }

    /**
     * Writes the tools into [prefix]'s `C:\fable\tools`: scripts from [Tool.script], binaries and
     * [HELPERS] from the APK's [ASSET_DIR]. [stamp] identifies the installed APK (its update
     * time), so files are refreshed after an app update. Never throws for I/O problems.
     */
    fun install(prefix: File, assets: AssetManager, stamp: String): Report {
        val dir = File(prefix, PREFIX_DIR)
        val bundled = bundledFiles(assets)
        val markerText = "$VERSION $stamp"
        val notBundled = all.filterNot { it.isAvailable(bundled) }.map { it.name }
        val wantedFiles = all.filter { it.isAvailable(bundled) }.map { it.fileName } +
            HELPERS.filter { it.lowercase() in bundled }
        val marker = File(dir, MARKER)
        if (readTextOrNull(marker)?.trim() == markerText && wantedFiles.all { File(dir, it).isFile }) {
            return Report(notBundled = notBundled, upToDate = true)
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Report(notBundled = notBundled, failed = listOf("couldn't create ${dir.absolutePath}"))
        }
        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (tool in all) {
            val target = File(dir, tool.fileName)
            try {
                when {
                    tool.script != null -> writeAtomic(target, tool.script)
                    tool.fileName.lowercase() in bundled -> copyAsset(assets, tool.fileName, bundled, target)
                    else -> continue
                }
                written += tool.fileName
            } catch (error: Exception) {
                failed += "${tool.fileName}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        for (helper in HELPERS) {
            if (helper.lowercase() !in bundled) continue
            try {
                copyAsset(assets, helper, bundled, File(dir, helper))
                written += helper
            } catch (error: Exception) {
                failed += "$helper: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        runCatching { writeAtomic(File(dir, "README.txt"), userReadme(notBundled)) }
        if (failed.isEmpty()) {
            runCatching { writeAtomic(marker, markerText) }.onFailure { Log.w(TAG, "Couldn't write $MARKER", it) }
        }
        return Report(written = written, notBundled = notBundled, failed = failed)
    }

    /** Copies the asset whose name matches [fileName] case-insensitively (asset names keep their case). */
    private fun copyAsset(assets: AssetManager, fileName: String, bundled: Set<String>, target: File) {
        if (fileName.lowercase() !in bundled) throw IOException("not bundled")
        val assetName = assets.list(ASSET_DIR)?.firstOrNull { it.equals(fileName, ignoreCase = true) }
            ?: throw IOException("not bundled")
        val temp = File(target.parentFile, target.name + ".fable-tmp")
        try {
            assets.open("$ASSET_DIR/$assetName").use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (target.exists() && !target.delete()) throw IOException("couldn't replace ${target.name}")
            if (!temp.renameTo(target)) throw IOException("rename failed")
            target.setReadable(true, false)
        } finally {
            temp.delete()
        }
    }

    /** CRLF batch file: Wine's cmd handles LF too, but Notepad users expect Windows line ends. */
    private fun gpuInfoScript(): String = listOf(
        "@echo off",
        "rem GPU Info, written by Fable. Shows what Vulkan and Direct3D report in this container.",
        "set \"REPORT=%~dp0gpu-info.txt\"",
        "if exist \"%~dp0VulkanGpuInfo.exe\" (",
        "  \"%~dp0VulkanGpuInfo.exe\"",
        "  exit /b",
        ")",
        "echo Fable GPU report> \"%REPORT%\"",
        "ver>> \"%REPORT%\"",
        "echo.>> \"%REPORT%\"",
        "echo == Video adapters (DXGI, through DXVK when it is installed) ==>> \"%REPORT%\"",
        "wmic path Win32_VideoController get Name,AdapterRAM,DriverVersion>> \"%REPORT%\" 2>&1",
        "if exist \"%~dp0vulkaninfo.exe\" (",
        "  echo == vulkaninfo --summary ==>> \"%REPORT%\"",
        "  \"%~dp0vulkaninfo.exe\" --summary>> \"%REPORT%\" 2>&1",
        ")",
        "echo == dxdiag (Direct3D 9 adapter) ==>> \"%REPORT%\"",
        "start /wait dxdiag /t \"%~dp0dxdiag.txt\"",
        "if exist \"%~dp0dxdiag.txt\" type \"%~dp0dxdiag.txt\">> \"%REPORT%\"",
        "notepad \"%REPORT%\"",
    ).joinToString("\r\n", postfix = "\r\n")

    private fun userReadme(notBundled: List<String>): String = buildList {
        add("Fable container tools")
        add("")
        add("GPU Info (gpu-info.bat) writes gpu-info.txt next to itself and opens it.")
        add("The Direct3D tests open a window that renders through DXVK (D3D9, D3D11) or")
        add("VKD3D-Proton (D3D12). A black window or an error means that layer doesn't work.")
        if (notBundled.isNotEmpty()) {
            add("")
            add("Not included in this build of Fable: ${notBundled.joinToString()}.")
        }
    }.joinToString("\r\n", postfix = "\r\n")
}
