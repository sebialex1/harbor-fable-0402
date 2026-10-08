package io.harbor.fable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vkd3dSetupTest {
    @Test fun versionsFromPackageNames() {
        assertEquals(listOf(2, 14, 1), Vkd3dSetup.versionOf("vkd3d-proton-2.14.1.tar.zst"))
        assertEquals(listOf(2, 12), Vkd3dSetup.versionOf("Vkd3d-2.12-1.wcp"))
        assertEquals(listOf(2, 6), Vkd3dSetup.versionOf("dxvk-gplasync-v2.6-1.tar.gz"))
        assertEquals(listOf(1, 10, 3), Vkd3dSetup.versionOf("dxvk-1.10.3-async.wcp"))
        assertNull(Vkd3dSetup.versionOf("dxvk-latest.wcp"))
    }

    @Test fun currentVkd3dNeedsDxvkTwoOneDxgi() {
        assertTrue(Vkd3dSetup.pairingProblems("dxvk-2.4.1.tar.gz", "vkd3d-proton-2.14.1.tar.zst").isEmpty())
        assertTrue(Vkd3dSetup.pairingProblems("dxvk-2.1.tar.gz", "vkd3d-proton-3.0.1.tar.zst").isEmpty())
        val old = Vkd3dSetup.pairingProblems("dxvk-1.10.3-async.wcp", "vkd3d-proton-2.14.1.tar.zst")
        assertEquals(1, old.size)
        assertTrue(old.single().contains("DXVK 2.1+"))
        val none = Vkd3dSetup.pairingProblems(null, "vkd3d-proton-2.14.1.tar.zst")
        assertTrue(none.single().contains("DXVK isn't installed"))
    }

    @Test fun oldVkd3dStillHasItsOwnDxgiFallback() {
        assertTrue(Vkd3dSetup.pairingProblems(null, "vkd3d-proton-2.8.tar.zst").isEmpty())
        assertTrue(Vkd3dSetup.pairingProblems("dxvk-1.10.3.tar.gz", "vkd3d-proton-2.6.tar.zst").isEmpty())
    }

    @Test fun noVkd3dNoProblems() {
        assertTrue(Vkd3dSetup.pairingProblems("dxvk-1.10.3.tar.gz", null).isEmpty())
    }

    @Test fun featureLevelOverrideIsExplained() {
        assertNull(Vkd3dSetup.featureLevelWarning(null))
        assertNull(Vkd3dSetup.featureLevelWarning(" "))
        assertNull(Vkd3dSetup.featureLevelWarning("11_0"))
        val fl121 = Vkd3dSetup.featureLevelWarning("12_1")
        assertNotNull(fl121)
        assertTrue(fl121!!.contains("ROVs"))
        assertTrue(Vkd3dSetup.featureLevelWarning("12_2")!!.contains("ray tracing"))
        assertTrue(Vkd3dSetup.featureLevelWarning("13_0")!!.contains("isn't a level"))
    }

    @Test fun driverWithoutRayTracingRunsD3d12ButNotDxr() {
        val support = Vkd3dSetup.assess(
            "Turnip Adreno (TM) 740",
            "1.3.289",
            listOf("VK_KHR_push_descriptor", "VK_EXT_robustness2", "VK_KHR_swapchain", "VK_KHR_ray_query"),
        )
        assertTrue(support.canRunD3d12)
        assertEquals(Vkd3dSetup.RayTracing.UNSUPPORTED, support.rayTracing)
    }

    @Test fun rayTracingTiers() {
        val base = listOf("VK_KHR_push_descriptor", "VK_EXT_robustness2") + Vkd3dSetup.DXR_EXTENSIONS
        assertEquals(Vkd3dSetup.RayTracing.TIER_1_0, Vkd3dSetup.assess("RADV", "1.4.0", base).rayTracing)
        assertEquals(
            Vkd3dSetup.RayTracing.TIER_1_1,
            Vkd3dSetup.assess("RADV", "1.4.0", base + Vkd3dSetup.RAY_QUERY_EXTENSION).rayTracing,
        )
    }

    @Test fun missingRequirementsAreNamed() {
        val support = Vkd3dSetup.assess("Old GPU", "1.1.128", listOf("VK_KHR_swapchain"))
        assertFalse(support.apiOk)
        assertFalse(support.canRunD3d12)
        assertEquals(listOf("VK_KHR_push_descriptor", "VK_EXT_robustness2"), support.missingRequired)
        assertTrue(support.describe().any { it.startsWith("ERROR") && it.contains("Vulkan 1.3") })
    }
}
