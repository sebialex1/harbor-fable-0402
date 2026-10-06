package io.harbor.fable.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.harbor.fable.app.FableApp
import io.harbor.fable.ui.components.DockTab
import io.harbor.fable.ui.components.FableUi
import io.harbor.fable.ui.components.GlassDock
import io.harbor.fable.ui.components.LocalDockClearance
import io.harbor.fable.ui.components.LocalFableUi
import io.harbor.fable.ui.screens.*
import io.harbor.fable.ui.theme.DockMetrics
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableControlBorder
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.FableText
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

private const val NAV_MS = 320

private fun tabIndex(route: String?): Int = when (route) {
    Routes.HOME -> 0
    Routes.CONTAINERS, Routes.CONTAINER_DETAIL -> 1
    Routes.DRIVERS -> 2
    Routes.ASSETS -> 3
    Routes.SETTINGS -> 4
    else -> 0
}

/** +1 when moving to a tab on the right of the current one, -1 for the left. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.direction(): Int =
    if (tabIndex(targetState.destination.route) >= tabIndex(initialState.destination.route)) 1 else -1

// Tabs: a soft fade with a short slide in the direction of travel.
private val TabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    val sign = direction()
    fadeIn(tween(300)) + slideInHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { sign * it / 5 }
}
private val TabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    val sign = direction()
    fadeOut(tween(200)) + slideOutHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { -sign * it / 8 }
}
private val TabPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(300)) + slideInHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { -it / 8 }
}
private val TabPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(200)) + slideOutHorizontally(tween(NAV_MS, easing = FastOutSlowInEasing)) { it / 5 }
}

// Container detail: pushed from the right over the list, which drifts away behind it.
private val DetailEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(240))
}
private val DetailExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(240))
}
private val DetailPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 5 } + fadeIn(tween(240))
}
private val DetailPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(240))
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

    // Refresh the catalog once per app launch when the user has it enabled.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val app = FableApp.from(context)
        if (app.settingsRepository.current.refreshCatalogOnLaunch) {
            runCatching { app.assetRepository.refresh() }
        }
    }

    var showAddApp by remember { mutableStateOf(false) }

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
                composable(
                    Routes.HOME,
                    enterTransition = TabEnter,
                    exitTransition = TabExit,
                    popEnterTransition = TabPopEnter,
                    popExitTransition = TabPopExit,
                ) {
                    HomeScreen(
                        onNavigateToContainers = { navController.navigate(Routes.CONTAINERS) },
                        onAddApp = { showAddApp = true },
                        onContainerClick = { id -> navController.navigate("container/$id") },
                    )
                }
                composable(
                    Routes.CONTAINERS,
                    enterTransition = TabEnter,
                    exitTransition = TabExit,
                    popEnterTransition = TabPopEnter,
                    popExitTransition = TabPopExit,
                ) {
                    ContainersScreen(
                        onContainerClick = { id ->
                            navController.navigate("container/$id")
                        },
                    )
                }
                composable(
                    route = Routes.CONTAINER_DETAIL,
                    arguments = listOf(navArgument("containerId") { type = NavType.StringType }),
                    enterTransition = DetailEnter,
                    exitTransition = DetailExit,
                    popEnterTransition = DetailPopEnter,
                    popExitTransition = DetailPopExit,
                ) { entry ->
                    val containerId = entry.arguments?.getString("containerId").orEmpty()
                    ContainerDetailScreen(
                        containerId = containerId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    Routes.DRIVERS,
                    enterTransition = TabEnter,
                    exitTransition = TabExit,
                    popEnterTransition = TabPopEnter,
                    popExitTransition = TabPopExit,
                ) { DriversScreen() }
                composable(
                    Routes.ASSETS,
                    enterTransition = TabEnter,
                    exitTransition = TabExit,
                    popEnterTransition = TabPopEnter,
                    popExitTransition = TabPopExit,
                ) { AssetsScreen() }
                composable(
                    Routes.SETTINGS,
                    enterTransition = TabEnter,
                    exitTransition = TabExit,
                    popEnterTransition = TabPopEnter,
                    popExitTransition = TabPopExit,
                ) { SettingsScreen() }
            }

            if (showAddApp) {
                AddAppSheet(onDismiss = { showAddApp = false })
            }

            // Floats above the dock on tabs and near the bottom edge on pushed screens.
            val snackbarBottom by animateDpAsState(
                targetValue = if (isTopLevel) DockMetrics.Clearance else Dp.Hairline,
                animationSpec = tween(250),
                label = "snackbarBottom",
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = snackbarBottom + 8.dp, start = 12.dp, end = 12.dp),
            ) { data ->
                val shape = RoundedCornerShape(16.dp)
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.border(Dp.Hairline, FableControlBorder, shape),
                    shape = shape,
                    containerColor = FableSurfaceRaised,
                    contentColor = FableText,
                    actionColor = FableAccent,
                )
            }

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
