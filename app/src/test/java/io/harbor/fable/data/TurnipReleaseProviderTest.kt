package io.harbor.fable.data

import io.harbor.fable.data.models.DriverFamily
import io.harbor.fable.data.models.InstalledDriver
import io.harbor.fable.data.models.RadvAsset
import io.harbor.fable.data.models.RadvRelease
import io.harbor.fable.data.models.ReleaseChannel
import io.harbor.fable.data.models.VulkanDevice
import io.harbor.fable.nativebridge.GpuDetector
import io.harbor.fable.nativebridge.GpuKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnipReleaseProviderTest {

    // Asset names as published by K11MCH1/AdrenoToolsDrivers and whitebelyash/freedreno_turnip-CI.
    private val k11 = listOf("turnip_a8xx.zip", "Turnip_v26.0.0_R8.zip", "Turnip_v26.0.0_R8_Gmem.zip", "Turnip_v26.0.0_R8_Sysmem.zip")
    private val white = listOf("mainline-turnip-sync-V32.zip", "mainline-turnip-V32.zip")

    @Test fun plainBuildIsPickedOnAdreno7xx() {
        assertEquals("Turnip_v26.0.0_R8.zip", TurnipReleaseProvider.pickAsset(k11, a8xx = false))
        assertEquals("mainline-turnip-V32.zip", TurnipReleaseProvider.pickAsset(white, a8xx = false))
    }

    @Test fun a8xxBuildIsPickedOnAdreno8xx() {
        assertEquals("turnip_a8xx.zip", TurnipReleaseProvider.pickAsset(k11, a8xx = true))
        assertEquals("a8xx-turnip-gen8-V29.zip", TurnipReleaseProvider.pickAsset(listOf("a8xx-turnip-gen8-sync-V29.zip", "a8xx-turnip-gen8-V29.zip"), a8xx = true))
    }

    @Test fun a8xxOnlyReleaseIsSkippedOnOlderAdreno() {
        assertNull(TurnipReleaseProvider.pickAsset(listOf("a8xx-turnip-gen8-V29.zip"), a8xx = false))
    }

    @Test fun proprietaryQualcommPackagesAreNotTurnip() {
        assertFalse(TurnipReleaseProvider.isTurnipAsset("Qualcomm_840_adpkg.zip"))
        assertTrue(TurnipReleaseProvider.isTurnipAsset("Turnip_v26.0.0_R8.zip"))
        assertTrue(TurnipReleaseProvider.isTurnipAsset("mainline-turnip-V32.zip"))
    }

    @Test fun mesaVersionIsReadFromTitles() {
        assertEquals("26.0.0", TurnipReleaseProvider.parseMesaVersion("v26.0.0 - Revision 8 + super beta 8xx support"))
        assertEquals("25.3.0-devel", TurnipReleaseProvider.parseMesaVersion("Turnip - 25.3.0-devel - Oct 11, 2025"))
        assertNull(TurnipReleaseProvider.parseMesaVersion("Mainline Turnip v32"))
    }

    @Test fun latestComesFromThePrimaryRepository() {
        val primary = TurnipReleaseProvider.PRIMARY
        val secondary = TurnipReleaseProvider.SECONDARY
        val lists = listOf(
            primary to listOf(turnip("v26.0.0-rc07", primary.slug, 100), turnip("v26.0.0-rc08", primary.slug, 200)),
            secondary to listOf(turnip("tu_v32", secondary.slug, 900)),
        )
        val sorted = TurnipReleaseProvider.categorize(lists)
        assertEquals("v26.0.0-rc08", sorted.first().tag)
        assertEquals(ReleaseChannel.LATEST, sorted.first().channel)
        assertEquals(1, sorted.count { it.channel == ReleaseChannel.LATEST })
        assertTrue(sorted.first().id.startsWith("turnip/K11MCH1/AdrenoToolsDrivers/"))
    }

    @Test fun radvIdsAreUnchanged() {
        val radv = RadvRelease(
            tag = "v1.5.0", title = "", mesaVersion = null, commit = null, publishedAt = 0,
            channel = ReleaseChannel.LATEST, body = "", htmlUrl = null,
            asset = RadvAsset("radv.zip", "https://example.invalid/radv.zip", 1, null),
        )
        assertEquals("radv-xclipse/v1.5.0", radv.id)
        assertEquals(DriverFamily.RADV_XCLIPSE, radv.family)
    }

    @Test fun installedRecordMatchesItsRelease() {
        // Records from before Turnip support have no family / release id and are RADV.
        val legacy = InstalledDriver("v1.5.0", "a.zip", "/x/vulkan.radeon.so", "/x", null, null, null, null, 1)
        assertEquals(DriverFamily.RADV_XCLIPSE, legacy.family)
        val radv = RadvRelease(
            tag = "v1.5.0", title = "", mesaVersion = null, commit = null, publishedAt = 0,
            channel = ReleaseChannel.LATEST, body = "", htmlUrl = null,
            asset = RadvAsset("a.zip", "https://example.invalid/a.zip", 1, null),
        )
        assertTrue(legacy.isFrom(radv))
        // A Turnip release with the same tag is a different driver.
        assertFalse(legacy.isFrom(radv.copy(family = DriverFamily.TURNIP, sourceRepo = "a/b")))
    }

    @Test fun adrenoIsRecognisedFromVulkanAndSysfsStrings() {
        assertEquals(7, GpuDetector.adrenoGeneration("Adreno740v2"))
        assertEquals(8, GpuDetector.adrenoGeneration("Adreno (TM) 830"))
        assertEquals(6, GpuDetector.adrenoGeneration("Turnip Adreno (TM) 650"))
        val adreno = GpuDetector.fromVulkan(device("Adreno (TM) 740", 0x5143, "Qualcomm Technologies Inc. Adreno Vulkan Driver"))
        assertEquals(GpuKind.ADRENO, adreno.kind)
        assertEquals(DriverFamily.TURNIP, adreno.recommendedFamily)
        assertFalse(adreno.matches(DriverFamily.RADV_XCLIPSE))
        val xclipse = GpuDetector.fromVulkan(device("Samsung Xclipse 940", 0x144D, "Samsung Xclipse Driver"))
        assertEquals(GpuKind.XCLIPSE, xclipse.kind)
        assertEquals(DriverFamily.RADV_XCLIPSE, xclipse.recommendedFamily)
    }

    private fun turnip(tag: String, repo: String, published: Long) = RadvRelease(
        tag = tag, title = tag, mesaVersion = null, commit = null, publishedAt = published,
        channel = ReleaseChannel.VERSIONED, body = "", htmlUrl = null,
        asset = RadvAsset("$tag.zip", "https://example.invalid/$tag.zip", 1, null),
        family = DriverFamily.TURNIP, sourceRepo = repo,
    )

    private fun device(name: String, vendor: Int, driverName: String) = VulkanDevice(
        name = name, apiVersion = "1.3.0", apiVersionRaw = 0, driverVersion = "0", driverVersionRaw = 0,
        vendorId = vendor, deviceId = 0, deviceType = "integrated", driverName = driverName, driverInfo = null,
        conformanceVersion = null, extensions = emptyList(),
    )
}
