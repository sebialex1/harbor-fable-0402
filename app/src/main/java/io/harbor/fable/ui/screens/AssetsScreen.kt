package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.DownloadStatus
import io.harbor.fable.data.DownloadTask
import io.harbor.fable.data.formatBytes
import io.harbor.fable.data.models.AssetEntry
import io.harbor.fable.data.models.AssetType
import io.harbor.fable.ui.components.GlassButton
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import kotlinx.coroutines.launch

@Composable
fun AssetsScreen() {
    val context = LocalContext.current
    val repository = remember(context) { FableApp.from(context).assetRepository }
    val downloadManager = remember(context) { FableApp.from(context).downloadManager }
    val assets by repository.assets.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val downloadSnapshot by downloadManager.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(repository) {
        repository.refresh()
    }

    val assetsByType = assets.groupBy { it.type }
    val assetTypes = listOf(
        AssetType.WINE,
        AssetType.DXVK,
        AssetType.VULKAN_DRIVER,
        AssetType.PROTON,
        AssetType.RUNTIME,
        AssetType.OTHER,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        Text("Assets", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FableText)
        Text("Download Wine, DXVK, Proton and more", fontSize = 13.sp, color = FableTextDim)

        Spacer(Modifier.height(24.dp))

        if (isRefreshing) {
            LoadingCard(message = "Refreshing asset catalog…")
            Spacer(Modifier.height(16.dp))
        }

        if (assets.isEmpty() && !isRefreshing) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No assets found in the catalog",
                    modifier = Modifier.padding(20.dp),
                    fontSize = 14.sp,
                    color = FableTextDim,
                )
            }
        } else {
            assetTypes.forEach { type ->
                val typeAssets = assetsByType[type].orEmpty()
                if (typeAssets.isNotEmpty()) {
                    Text(
                        text = type.displayName(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = FableTextDim,
                    )
                    Spacer(Modifier.height(10.dp))

                    typeAssets.forEachIndexed { index, asset ->
                        val task = downloadSnapshot.tasks
                            .filter { it.assetId == asset.id }
                            .maxByOrNull { it.updatedAt }
                        AssetCard(
                            asset = asset,
                            task = task,
                            onDownload = {
                                scope.launch { repository.download(asset.id) }
                            },
                        )
                        if (index != typeAssets.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun AssetCard(
    asset: AssetEntry,
    task: DownloadTask?,
    onDownload: () -> Unit,
) {
    val status = assetStatus(asset, task)
    val isDownloaded = asset.isDownloaded || task?.status == DownloadStatus.COMPLETED
    val isActive = task?.status == DownloadStatus.QUEUED ||
        task?.status == DownloadStatus.DOWNLOADING ||
        task?.status == DownloadStatus.VERIFYING
    val actionText = when {
        isDownloaded -> "Downloaded"
        isActive -> "In progress"
        else -> "Download"
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(asset.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FableText)
            Spacer(Modifier.height(5.dp))
            Text("Version ${asset.version}", fontSize = 12.sp, color = FableTextDim)
            Text(
                "${formatBytes(asset.fileSizeBytes)}  ·  $status",
                fontSize = 12.sp,
                color = FableTextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
            GlassButton(
                text = actionText,
                primary = !isDownloaded,
                icon = if (!isDownloaded) {
                    {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = FableText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                onClick = {
                    if (!isDownloaded && !isActive) {
                        onDownload()
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = FableAccent,
                strokeWidth = 2.dp,
            )
            Text(message, fontSize = 13.sp, color = FableTextDim)
        }
    }
}

private fun assetStatus(asset: AssetEntry, task: DownloadTask?): String {
    if (asset.isDownloaded) return "Downloaded"
    return when (task?.status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.DOWNLOADING -> {
            val percent = (task.progressFraction * 100).toInt()
            if (task.totalBytes > 0) "Downloading · $percent%" else "Downloading"
        }
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.VERIFYING -> "Verifying"
        DownloadStatus.COMPLETED -> "Downloaded"
        DownloadStatus.FAILED -> "Failed"
        DownloadStatus.CANCELLED -> "Cancelled"
        null -> "Available"
    }
}

private fun AssetType.displayName(): String = when (this) {
    AssetType.WINE -> "Wine Builds"
    AssetType.DXVK -> "DXVK"
    AssetType.VULKAN_DRIVER -> "Vulkan Drivers"
    AssetType.PROTON -> "Proton"
    AssetType.RUNTIME -> "Runtimes"
    AssetType.OTHER -> "Other"
}
