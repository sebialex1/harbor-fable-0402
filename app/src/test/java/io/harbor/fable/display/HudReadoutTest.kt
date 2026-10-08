package io.harbor.fable.display

import io.harbor.fable.data.models.ContainerDefaults
import io.harbor.fable.data.models.HudLayout
import io.harbor.fable.data.models.HudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HudReadoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private val everything = HudSettings().allReadings()

    @Test fun readingsInOrderWithUnavailableSensors() {
        val sample = HudSample(
            fps = 58, frameTimeMs = 17.24f, detectedApi = "D3D11 · DXVK", configuredApi = "DXVK+VKD3D",
            driver = "turnip Mesa 25.1.0", gpuBusyPercent = null, gpuTempC = null, cpuPercent = 41,
            ramUsedBytes = 3L * 1024 * 1024 * 1024, ramTotalBytes = 8L * 1024 * 1024 * 1024, resolution = "1280x720",
        )
        assertEquals(
            listOf(
                "FPS" to "58", "FT" to "17.2 ms", "API" to "D3D11 · DXVK", "DRV" to "turnip Mesa 25.1.0",
                "GPU" to "unavailable", "CPU" to "41%", "RAM" to "3.0/8.0G", "RES" to "1280x720",
            ),
            HudReadout.lines(everything, sample),
        )
    }

    @Test fun configuredApiIsMarkedUntilDetected() {
        val lines = HudReadout.lines(HudSettings(), HudSample(configuredApi = "DXVK+VKD3D"))
        assertEquals("DXVK+VKD3D (set)", lines.toMap()["API"])
        assertEquals("--", lines.toMap()["FPS"])
    }

    @Test fun gpuUsageAndTemperatureShareALine() {
        val both = everything
        assertEquals("37% 52°C", HudReadout.lines(both, HudSample(gpuBusyPercent = 37, gpuTempC = 52.4f)).toMap()["GPU"])
        assertEquals("37% · temp unavailable", HudReadout.lines(both, HudSample(gpuBusyPercent = 37)).toMap()["GPU"])
        assertEquals("52°C · usage unavailable", HudReadout.lines(both, HudSample(gpuTempC = 52f)).toMap()["GPU"])
        val usageOnly = HudSettings(showGpuTemp = false)
        assertEquals("37%", HudReadout.lines(usageOnly, HudSample(gpuBusyPercent = 37)).toMap()["GPU"])
        val none = HudSettings(showGpuUsage = false, showGpuTemp = false)
        assertNull(HudReadout.lines(none, HudSample()).toMap()["GPU"])
    }

    @Test fun cpuWaitsForItsSecondSampleThenSaysUnavailable() {
        assertEquals("--", HudReadout.lines(HudSettings(), HudSample(cpuWarmedUp = false)).toMap()["CPU"])
        assertEquals("unavailable", HudReadout.lines(HudSettings(), HudSample(cpuWarmedUp = true)).toMap()["CPU"])
        assertEquals("12% app", HudReadout.lines(HudSettings(), HudSample(cpuPercent = 12, cpuAppOnly = true)).toMap()["CPU"])
    }

    @Test fun layoutsAndCycling() {
        val lines = listOf("FPS" to "60", "CPU" to "20%")
        assertEquals("FPS 60\nCPU 20%", HudReadout.plain(lines, HudLayout.STACKED))
        assertEquals("FPS 60  CPU 20%", HudReadout.plain(lines, HudLayout.ROW))
        assertEquals(HudLayout.ROW, HudLayout.STACKED.next())
        assertEquals(HudLayout.STACKED, HudLayout.ROW.next())
        assertEquals(HudLayout.STACKED, HudLayout.fromName("bogus"))
    }

    @Test fun emptinessCountsEveryReading() {
        val off = HudSettings(
            showFps = false, showFrameTime = false, showApi = false, showDriver = false, showGpuUsage = false,
            showGpuTemp = false, showCpu = false, showRam = false, showResolution = false,
        )
        assertTrue(off.isEmpty)
        assertFalse(off.copy(showGpuTemp = true).isEmpty)
        assertEquals(9, off.allReadings().readingCount)
    }

    @Test fun configuredApiFollowsTheContainer() {
        assertEquals("DXVK+VKD3D", HudReadout.configuredApi(null, null))
        assertEquals("DXVK", HudReadout.configuredApi("dxvk-2.4", ContainerDefaults.VKD3D_OFF))
        assertEquals("WineD3D", HudReadout.configuredApi(ContainerDefaults.DXVK_OFF, ContainerDefaults.VKD3D_OFF))
    }

    private fun loaded(dll: String, kind: String) =
        "0024:trace:loaddll:build_module Loaded L\"C:\\\\windows\\\\system32\\\\$dll\" at 00000002799A0000: $kind"

    @Test fun detectsTheNewestDirect3dAndItsImplementation() {
        val detector = GraphicsApiDetector()
        assertNull(detector.label)
        assertFalse(detector.feed("0024:err:module:something unrelated"))
        assertTrue(detector.feed(loaded("dxgi.dll", "native")).not()) // dxgi alone names no API
        assertTrue(detector.feed(loaded("d3d11.dll", "native")))
        assertEquals("D3D11 · DXVK", detector.label)
        assertFalse(detector.isFinal)
        assertTrue(detector.feed(loaded("d3d12.dll", "native")))
        detector.feed(loaded("d3d12core.dll", "native"))
        assertEquals("D3D12 · VKD3D-Proton", detector.label)
        assertTrue(detector.isFinal)
    }

    @Test fun builtinDllsReadAsWine() {
        val d3d9 = GraphicsApiDetector().apply { feed(loaded("d3d9.dll", "builtin")) }
        assertEquals("D3D9 · WineD3D", d3d9.label)
        val d3d12 = GraphicsApiDetector().apply { feed(loaded("d3d12.dll", "builtin")) }
        assertEquals("D3D12 · Wine vkd3d", d3d12.label)
        val gl = GraphicsApiDetector().apply { feed(loaded("opengl32.dll", "builtin")) }
        assertEquals("OpenGL", gl.label)
    }

    @Test fun logTailReadsOnlyNewCompleteLinesAndRestartsOnTruncation() {
        val file = tmp.newFile("fable-launch.log")
        val tail = LogTail(file)
        assertTrue(tail.readNewLines().isEmpty())
        file.appendText("one\ntwo\nthr")
        assertEquals(listOf("one", "two"), tail.readNewLines())
        file.appendText("ee\n")
        assertEquals(listOf("three"), tail.readNewLines())
        assertTrue(tail.readNewLines().isEmpty())
        file.writeText("new\n")
        assertEquals(listOf("new"), tail.readNewLines())
    }

    @Test fun logTailReadsBigLogsInChunks() {
        val file = tmp.newFile("big.log")
        file.writeText((1..100).joinToString("") { "line $it\n" })
        val tail = LogTail(file, maxBytesPerRead = 64)
        val all = ArrayList<String>()
        repeat(50) { all += tail.readNewLines() }
        assertEquals((1..100).map { "line $it" }, all)
    }

    private fun sysfs(path: String, text: String) {
        val f = File(tmp.root, path)
        f.parentFile!!.mkdirs()
        f.writeText(text)
    }

    @Test fun gpuSensorsReadAdrenoFiles() {
        sysfs("sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "37 %\n")
        sysfs("sys/class/kgsl/kgsl-3d0/temp", "51000\n")
        val sensors = GpuSensors(tmp.root)
        assertEquals(37, sensors.busyPercent())
        assertEquals(51f, sensors.temperatureC()!!, 0.01f)
    }

    @Test fun gpuSensorsFallBackToGpubusyAndThermalZones() {
        sysfs("sys/class/kgsl/kgsl-3d0/gpubusy", "  250   1000\n")
        sysfs("sys/class/thermal/thermal_zone0/type", "cpu-0-0\n")
        sysfs("sys/class/thermal/thermal_zone0/temp", "60000\n")
        sysfs("sys/class/thermal/thermal_zone7/type", "gpuss-0\n")
        sysfs("sys/class/thermal/thermal_zone7/temp", "48500\n")
        val sensors = GpuSensors(tmp.root)
        assertEquals(25, sensors.busyPercent())
        assertEquals(48.5f, sensors.temperatureC()!!, 0.01f)
    }

    @Test fun gpuSensorsReportNothingWhenNothingIsReadable() {
        val sensors = GpuSensors(tmp.root)
        assertNull(sensors.busyPercent())
        assertNull(sensors.temperatureC())
        assertNull(GpuSensors.celsius(0))
        assertNull(GpuSensors.celsius(500_000))
        assertEquals(45f, GpuSensors.celsius(45)!!, 0.01f)
    }
}
