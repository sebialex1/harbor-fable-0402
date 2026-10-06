package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Spacing

@Composable
fun HomeScreen(
    onNavigateToContainers: () -> Unit,
    onNavigateToDrivers: () -> Unit,
    onNavigateToAssets: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val containers by app.containerRepository.containers.collectAsStateWithLifecycle()
    val drivers by app.assetRepository.drivers.collectAsStateWithLifecycle()
    val assets by app.assetRepository.assets.collectAsStateWithLifecycle()

    val readyContainers = containers.count {
        it.status == ContainerStatus.READY || it.status == ContainerStatus.RUNNING
    }
    val downloadedDrivers = drivers.count { it.isDownloaded }
    val downloadedAssets = assets.count { it.isDownloaded }

    FableScreen(
        title = "Fable",
        subtitle = "Wine container manager",
    ) {
        // Quick stats row
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatCard("Containers", readyContainers.toString(), FableAccent, Icons.Outlined.Apps, Modifier.weight(1f))
                StatCard("Drivers", downloadedDrivers.toString(), FableSuccess, Icons.Outlined.Memory, Modifier.weight(1f))
                StatCard("Assets", downloadedAssets.toString(), FableWarn, Icons.Outlined.Download, Modifier.weight(1f))
            }
        }

        // Quick actions
        item { SectionLabel("Quick Actions") }
        item {
            GlassCard {
                ListRow(
                    title = "Create Container",
                    subtitle = "Set up a new Wine environment",
                    icon = Icons.Outlined.Add,
                    onClick = onNavigateToContainers,
                )
                CardDivider()
                ListRow(
                    title = "Browse Drivers",
                    subtitle = "Adrenotools Vulkan driver packages",
                    icon = Icons.Outlined.Memory,
                    onClick = onNavigateToDrivers,
                )
                CardDivider()
                ListRow(
                    title = "Download Assets",
                    subtitle = "Wine, DXVK, Proton and more",
                    icon = Icons.Outlined.Download,
                    onClick = onNavigateToAssets,
                )
            }
        }

        // Recent containers
        if (containers.isNotEmpty()) {
            item { SectionLabel("Recent Containers") }
            items(containers.take(5)) { container ->
                GlassCard(onClick = onNavigateToContainers) {
                    ListRow(
                        title = container.name,
                        subtitle = "${container.wineVersion} · ${container.screenResolution}",
                        icon = Icons.Outlined.Apps,
                        iconTint = containerStatusColor(container.status),
                        showChevron = false,
                        trailing = {
                            Pill(
                                text = container.status.name.lowercase(),
                                color = containerStatusColor(container.status),
                            )
                        },
                        onClick = onNavigateToContainers,
                    )
                }
            }
        } else {
            item { SectionLabel("Getting Started") }
            item {
                EmptyState(
                    icon = Icons.Outlined.Smartphone,
                    title = "No containers yet",
                    message = "Create a Wine container to start running Windows applications on your device.",
                    actionLabel = "Create Container",
                    actionIcon = Icons.Outlined.Add,
                    onAction = onNavigateToContainers,
                )
            }
        }

        // Settings shortcut
        item {
            GlassCard {
                ListRow(
                    title = "Settings",
                    subtitle = "Configure defaults, graphics and catalog",
                    icon = Icons.Outlined.Settings,
                    onClick = onNavigateToSettings,
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    tint: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = icon, tint = tint)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = tint,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

internal fun containerStatusColor(status: ContainerStatus): Color = when (status) {
    ContainerStatus.READY -> FableSuccess
    ContainerStatus.RUNNING -> FableSuccess
    ContainerStatus.ERROR -> FableWarn
    else -> FableAccent
}
