package io.harbor.fable.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.LaunchResult
import io.harbor.fable.data.models.Container
import io.harbor.fable.data.models.ExeEntry
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.ControlHeight
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
                fableUi.showMessage(repository.launch(exe.containerId, exe.id).also { it.openDisplay(context) }.message(), long = true)
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
    val appear = rememberEntrance()

    FableScreen(
        title = "Fable",
        actions = {
            FableIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add app",
                onClick = onAddApp,
            )
        },
    ) {
        if (exes.isNotEmpty()) {
            item(key = "apps-label") { SectionLabel("Apps", Modifier.animateItem().entrance(appear, 0)) }
            item(key = "apps") {
                FableCard(Modifier.animateItem().entrance(appear, 1)) {
                    exes.forEachIndexed { index, exe ->
                        if (index > 0) CardDivider(afterIcon = true)
                        ListRow(
                            modifier = Modifier.entrance(appear, index + 2),
                            title = exe.name,
                            subtitle = containerNames[exe.containerId] ?: "Unassigned",
                            leading = { ExeIcon(name = exe.name, iconPath = exe.icon) },
                            showChevron = false,
                            trailing = {
                                FableIconButton(
                                    icon = Icons.Outlined.PlayArrow,
                                    contentDescription = "Launch ${exe.name}",
                                    tint = Color.Black,
                                    containerColor = FableAccent,
                                    bordered = false,
                                    size = ControlHeight.Compact,
                                    onClick = { onLaunch(exe) },
                                )
                            },
                            onClick = { onLaunch(exe) },
                        )
                    }
                }
            }
        }
        // No apps: nothing but the + in the top bar; the list does not repeat it.

        if (containers.isNotEmpty()) {
            item(key = "containers-label") {
                SectionLabel(
                    text = "Containers",
                    modifier = Modifier.animateItem().entrance(appear, 2),
                    trailing = { SectionAction(text = "See all", onClick = onSeeAllContainers) },
                )
            }
            item(key = "containers") {
                FableCard(Modifier.animateItem().entrance(appear, 3)) {
                    containers.take(5).forEachIndexed { index, container ->
                        if (index > 0) CardDivider(afterIcon = true)
                        ListRow(
                            modifier = Modifier.entrance(appear, index + 4),
                            title = container.name,
                            subtitle = container.wineVersion,
                            icon = Icons.Outlined.Inventory2,
                            onClick = { onContainerClick(container.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Brings up Wine's screen once a launch has started. */
internal fun LaunchResult.openDisplay(context: android.content.Context) {
    if (this is LaunchResult.Started) runCatching { io.harbor.fable.display.DisplayActivity.open(context) }
}

/** Human-readable text for a launch outcome, shown in the snackbar. */
internal fun LaunchResult.message(): String = when (this) {
    is LaunchResult.Started -> "Launched (pid $pid)"
    is LaunchResult.Unavailable -> reason
    is LaunchResult.Failed -> if (logPath != null && !reason.endsWith("Log saved")) "$reason. Log in Settings" else reason
}
