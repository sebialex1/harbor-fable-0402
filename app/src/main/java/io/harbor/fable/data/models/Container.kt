package io.harbor.fable.data.models

import java.util.UUID

enum class ContainerStatus { CREATED, CONFIGURING, READY, RUNNING, ERROR }

data class Container(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val exePath: String? = null,
    val exeName: String? = null,
    val wineVersion: String = "wine-9.0",
    val dxvkVersion: String? = null,
    val driverId: String? = null,
    val status: ContainerStatus = ContainerStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val graphicsDriver: String = "Turnip (default)",
    val envVars: Map<String, String> = emptyMap(),
    val screenResolution: String = "1280x720",
    val isFullscreen: Boolean = false,
)

data class ExeEntry(
    val id: String = UUID.randomUUID().toString(),
    val containerId: String,
    val name: String,
    val path: String,
    val icon: String? = null,
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
)
