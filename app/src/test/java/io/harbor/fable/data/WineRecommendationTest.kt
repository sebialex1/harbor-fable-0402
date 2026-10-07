package io.harbor.fable.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineRecommendationTest {
    @Test fun wine920IsPreferredRegardlessOfCatalogOrder() {
        val names = listOf("Proton.9.0-x86_64.wcp", "wine-10.0.wcp", "wine-9.20.wcp")
        val supported = names.filter(WineRuntime::isBionicWinePackageName).sortedBy(CatalogPolicy::wineRank)
        assertTrue(supported.first() == "wine-9.20.wcp")
        assertFalse(supported.any { it.startsWith("Proton") })
    }

    @Test fun unsupportedPackagesDoNotCountAsInstalledWine() {
        assertTrue(WineRuntime.isBionicWinePackageName("wine-9.20.wcp"))
        assertTrue(WineRuntime.isBionicWinePackageName("Wine-9.20.wcp.xz"))
        assertFalse(WineRuntime.isBionicWinePackageName("Proton.9.0-x86_64.wcp"))
        assertFalse(WineRuntime.isBionicWinePackageName("proton-10-arm64ec.wcp.xz"))
        assertFalse(WineRuntime.isBionicWinePackageName("wine-10-arm64ec.wcp"))
        assertFalse(WineRuntime.isBionicWinePackageName("wine-9.20-amd64.tar.xz"))
        assertFalse(WineRuntime.isBionicWinePackageName("Box64-0.4.2.wcp"))
    }

    @Test fun defaultWineSourceExcludesProton() {
        val source = AssetRepository.defaultCatalog.first { it.type == io.harbor.fable.data.models.AssetType.WINE }
        assertTrue(source.assetGlobs.any { it.contains("wine-") })
        assertFalse(source.assetGlobs.any { it.contains("proton", ignoreCase = true) })
        assertTrue(AssetRepository.DEFAULTS_VERSION >= 6)
    }
}
