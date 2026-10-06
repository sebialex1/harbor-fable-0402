package io.harbor.fable.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.LaunchResult
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
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
    val containers by repository.containers.collectAsStateWithLifecycle()
    val exes by repository.exes.collectAsStateWithLifecycle()

    HomeContent(
        exes = exes,
        containers = containers,
        onAddApp = onAddApp,
        onSeeAllContainers = onNavigateToContainers,
        onContainerClick = onContainerClick,
        // Launching can take a while on first use, so it runs on the app-level scope.
        onLaunch = { exe ->
            fableUi.scope.launch {
                fableUi.showMessage(repository.launch(exe.containerId, exe.id).message(), long = true)
            }
        },
    )
}

@Composable
internal fun HomeContent(
    exes: List<ExeEntry>,
    containers: List<Container>,
    onAddApp: () -> Unit,
    onSeeAllContainers: () -> Unit,
    onContainerClick: (String) -> Unit,
    onLaunch: (ExeEntry) -> Unit,
) {
    val containerNames = remember(containers) { containers.associate { it.id to it.name } }

    FableScreen(
        title = "Fable",
        actions = {
            GlassIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add app",
                onClick = onAddApp,
            )
        },
    ) {
        if (exes.isNotEmpty()) {
            item(key = "apps-label") { SectionLabel("Apps", Modifier.animateItem()) }
            item(key = "apps") {
                GlassCard(Modifier.animateItem()) {
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
                                    size = 34.dp,
                                    onClick = { onLaunch(exe) },
                                )
                            },
                            onClick = { onLaunch(exe) },
                        )
                    }
                }
            }
        } else {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.SportsEsports,
                    title = "No apps yet",
                    message = "Add an app to get started",
                    actionLabel = "Add App",
                    actionIcon = Icons.Outlined.Add,
                    onAction = onAddApp,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (containers.isNotEmpty()) {
            item(key = "containers-label") {
                SectionLabel(
                    text = "Containers",
                    modifier = Modifier.animateItem(),
                    trailing = { SectionAction(text = "See all", onClick = onSeeAllContainers) },
                )
            }
            item(key = "containers") {
                GlassCard(Modifier.animateItem()) {
                    containers.take(5).forEachIndexed { index, container ->
                        if (index > 0) CardDivider()
                        ListRow(
                            title = container.name,
                            subtitle = container.wineVersion,
                            icon = Icons.Outlined.Apps,
                            iconTint = containerStatusColor(container.status),
                            onClick = { onContainerClick(container.id) },
                        )
                    }
                }
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
