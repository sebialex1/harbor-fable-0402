package io.harbor.fable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.harbor.fable.ui.components.DockTab
import io.harbor.fable.ui.components.FableUi
import io.harbor.fable.ui.components.GlassDock
import io.harbor.fable.ui.components.LocalDockClearance
import io.harbor.fable.ui.components.LocalFableUi
import io.harbor.fable.ui.screens.*
import io.harbor.fable.ui.theme.DockMetrics
import io.harbor.fable.ui.theme.FableBg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private object Routes {
    const val HOME = "home"
    const val CONTAINERS = "containers"
    const val CONTAINER_DETAIL = "container/{containerId}"
    const val DRIVERS = "drivers"
    const val ASSETS = "assets"
    const val SETTINGS = "settings"

    val topLevel = setOf(HOME, CONTAINERS, DRIVERS, ASSETS, SETTINGS)
}

@Composable
fun FableRoot() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val fableUi = remember {
        FableUi(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            snackbarHostState = snackbarHostState,
        )
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isTopLevel = currentRoute in Routes.topLevel

    val tabs = remember {
        listOf(
            DockTab("Home", Icons.Outlined.Home),
            DockTab("Containers", Icons.Outlined.Apps),
            DockTab("Drivers", Icons.Outlined.Memory),
            DockTab("Assets", Icons.Outlined.Download),
            DockTab("Settings", Icons.Outlined.Settings),
        )
    }

    val activeTab = when (currentRoute) {
        Routes.HOME -> 0
        Routes.CONTAINERS, Routes.CONTAINER_DETAIL -> 1
        Routes.DRIVERS -> 2
        Routes.ASSETS -> 3
        Routes.SETTINGS -> 4
        else -> 0
    }

    CompositionLocalProvider(
        LocalFableUi provides fableUi,
        LocalDockClearance provides DockMetrics.Clearance,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FableBg)
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onNavigateToContainers = { navController.navigate(Routes.CONTAINERS) },
                        onNavigateToDrivers = { navController.navigate(Routes.DRIVERS) },
                        onNavigateToAssets = { navController.navigate(Routes.ASSETS) },
                        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.CONTAINERS) {
                    ContainersScreen(
                        onContainerClick = { id ->
                            navController.navigate("container/$id")
                        },
                    )
                }
                composable(
                    route = Routes.CONTAINER_DETAIL,
                    arguments = listOf(navArgument("containerId") { type = NavType.StringType }),
                ) { entry ->
                    val containerId = entry.arguments?.getString("containerId").orEmpty()
                    ContainerDetailScreen(
                        containerId = containerId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.DRIVERS) { DriversScreen() }
                composable(Routes.ASSETS) { AssetsScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = DockMetrics.Clearance),
            )

            // Floating glass dock — only on top-level tabs
            AnimatedVisibility(
                visible = isTopLevel,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                GlassDock(
                    items = tabs,
                    activeIndex = activeTab,
                    onTabSelected = { index ->
                        val route = when (index) {
                            0 -> Routes.HOME
                            1 -> Routes.CONTAINERS
                            2 -> Routes.DRIVERS
                            3 -> Routes.ASSETS
                            4 -> Routes.SETTINGS
                            else -> Routes.HOME
                        }
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}
