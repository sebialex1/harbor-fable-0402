package io.harbor.fable.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.LaunchResult
import io.harbor.fable.data.models.ContainerStatus
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableWarn
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onNavigateToContainers: () -> Unit,
    onAddApp: () -> Unit,
    onContainerClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val repository = app.containerRepository
    val fableUi = LocalFableUi.current
    val scope = rememberCoroutineScope()
    val containers by repository.containers.collectAsStateWithLifecycle()
    val exes by repository.exes.collectAsStateWithLifecycle()
    val containerNames = remember(containers) { containers.associate { it.id to it.name } }

    FableScreen(
        title = "Fable",
        subtitle = "Wine container manager",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add app",
                onClick = onAddApp,
            )
        },
    ) {
        if (exes.isNotEmpty()) {
            item { SectionLabel("Your Apps") }
            item {
                GlassCard {
                    exes.forEachIndexed { index, exe ->
                        if (index > 0) CardDivider()
                        ListRow(
                            title = exe.name,
                            subtitle = containerNames[exe.containerId] ?: "Unassigned",
                            icon = Icons.Outlined.SportsEsports,
                            showChevron = false,
                            trailing = {
                                GlassIconButton(
                                    icon = Icons.Outlined.PlayArrow,
                                    contentDescription = "Launch ${exe.name}",
                                    tint = Color.White,
                                    containerColor = FableAccent,
                                    bordered = false,
                                    onClick = {
                                        scope.launch {
                                            fableUi.showMessage(
                                                repository.launch(exe.containerId, exe.id).message(),
                                                long = true,
                                            )
                                        }
                                    },
                                )
                            },
                            onClick = {
                                scope.launch {
                                    fableUi.showMessage(
                                        repository.launch(exe.containerId, exe.id).message(),
                                        long = true,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        } else {
            item {
                EmptyState(
                    icon = Icons.Outlined.SportsEsports,
                    title = "No apps yet",
                    message = "Add a Windows app or game, then assign it to a container to run it.",
                    actionLabel = "Add App",
                    actionIcon = Icons.Outlined.Add,
                    onAction = onAddApp,
                )
            }
        }

        if (containers.isNotEmpty()) {
            item { SectionLabel("Recent Containers") }
            item {
                GlassCard {
                    containers.take(5).forEachIndexed { index, container ->
                        if (index > 0) CardDivider()
                        ListRow(
                            title = container.name,
                            subtitle = "${container.wineVersion} · ${container.screenResolution}",
                            icon = Icons.Outlined.Apps,
                            iconTint = containerStatusColor(container.status),
                            onClick = { onContainerClick(container.id) },
                        )
                    }
                }
            }
            item {
                GlassButton(
                    text = "All Containers",
                    onClick = onNavigateToContainers,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Human-readable text for a launch outcome, shown in the snackbar. */
internal fun LaunchResult.message(): String = when (this) {
    is LaunchResult.Started -> "Launched (pid $pid)"
    is LaunchResult.Unavailable -> reason
    is LaunchResult.Failed -> reason
}

internal fun containerStatusColor(status: ContainerStatus): Color = when (status) {
    ContainerStatus.READY -> FableSuccess
    ContainerStatus.RUNNING -> FableSuccess
    ContainerStatus.ERROR -> FableWarn
    else -> FableAccent
}
