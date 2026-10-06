package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Memory
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
import io.harbor.fable.data.models.DriverPackage
import io.harbor.fable.ui.components.GlassButton
import io.harbor.fable.ui.components.GlassCard
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import kotlinx.coroutines.launch

@Composable
fun DriversScreen() {
    val context = LocalContext.current
    val repository = remember(context) { FableApp.from(context).assetRepository }
    val downloadManager = remember(context) { FableApp.from(context).downloadManager }
    val drivers by repository.drivers.collectAsStateWithLifecycle()
    val isRefreshing by repository.isRefreshing.collectAsStateWithLifecycle()
    val downloadSnapshot by downloadManager.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(repository) {
        repository.refresh()
    }

    val orderedDrivers = drivers.sortedBy { driver ->
        when {
            driver.sourceRepo.orEmpty().contains("AdrenoToolsDrivers", ignoreCase = true) -> 0
            driver.sourceRepo.orEmpty().contains("radv-xclipse", ignoreCase = true) -> 1
            else -> 2
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 120.dp),
    ) {
        Text("Drivers", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FableText)
        Text("Adrenotools Vulkan driver packages", fontSize = 13.sp, color = FableTextDim)

        Spacer(Modifier.height(24.dp))

        Text("Available Packages", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = FableTextDim)
        Spacer(Modifier.height(12.dp))

        if (isRefreshing) {
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
                    Text("Refreshing driver catalog…", fontSize = 13.sp, color = FableTextDim)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (orderedDrivers.isEmpty() && !isRefreshing) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No driver packages found in the catalog",
                    modifier = Modifier.padding(20.dp),
                    fontSize = 14.sp,
                    color = FableTextDim,
                )
            }
        } else {
            orderedDrivers.forEachIndexed { index, driver ->
                val task = downloadSnapshot.tasks
                    .filter { it.assetId == driver.id }
                    .maxByOrNull { it.updatedAt }
                DriverCard(
                    driver = driver,
                    task = task,
                    onDownload = {
                        scope.launch { repository.downloadDriver(driver.id) }
                    },
                )
                if (index != orderedDrivers.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DriverCard(
    driver: DriverPackage,
    task: DownloadTask?,
    onDownload: () -> Unit,
) {
    val title = driverTitle(driver)
    val isDownloaded = driver.isDownloaded || task?.status == DownloadStatus.COMPLETED
    val isActive = task?.status == DownloadStatus.QUEUED ||
        task?.status == DownloadStatus.DOWNLOADING ||
        task?.status == DownloadStatus.VERIFYING
    val status = when {
        isDownloaded -> "Downloaded"
        task?.status == DownloadStatus.QUEUED -> "Queued"
        task?.status == DownloadStatus.DOWNLOADING -> {
            val percent = (task.progressFraction * 100).toInt()
            if (task.totalBytes > 0) "Downloading · $percent%" else "Downloading"
        }
        task?.status == DownloadStatus.PAUSED -> "Paused"
        task?.status == DownloadStatus.VERIFYING -> "Verifying"
        task?.status == DownloadStatus.COMPLETED -> "Downloaded"
        task?.status == DownloadStatus.FAILED -> "Failed"
        task?.status == DownloadStatus.CANCELLED -> "Cancelled"
        else -> "Available"
    }
    val buttonText = when {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = FableAccent,
                    modifier = Modifier
                        .size(22.dp)
                        .padding(end = 10.dp),
                )
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FableText)
            }
            Text(
                text = driver.name,
                fontSize = 12.sp,
                color = FableTextDim,
                modifier = Modifier.padding(top = 5.dp),
            )
            Text(
                text = "Version ${driver.version}  ·  ${formatBytes(driver.fileSizeBytes)}  ·  $status",
                fontSize = 12.sp,
                color = FableTextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
            GlassButton(
                text = buttonText,
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
                    if (!isDownloaded && !isActive) onDownload()
                },
            )
        }
    }
}

private fun driverTitle(driver: DriverPackage): String = when {
    driver.sourceRepo.orEmpty().contains("AdrenoToolsDrivers", ignoreCase = true) -> "Turnip (Adreno)"
    driver.sourceRepo.orEmpty().contains("radv-xclipse", ignoreCase = true) -> "RADV Xclipse"
    else -> driver.sourceRepo ?: driver.name
}
