package io.harbor.fable.data.models

import java.util.UUID

enum class AssetType {
    WINE, BOX64, DXVK, VULKAN_DRIVER, PROTON, RUNTIME, OTHER
}

enum class AssetSource {
    GITHUB_RELEASE, DIRECT_URL, LOCAL_IMPORT
}

data class DriverPackage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String,
    val downloadUrl: String,
    val source: AssetSource,
    val sourceRepo: String? = null,
    val fileSizeBytes: Long = 0,
    val sha256: String? = null,
    val minApi: Int = 28,
    val vulkanVersion: String? = null,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
)

data class AssetEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String,
    val type: AssetType,
    val downloadUrl: String,
    val source: AssetSource,
    val sourceRepo: String? = null,
    val fileSizeBytes: Long = 0,
    val sha256: String? = null,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
)
