package io.harbor.fable.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.harbor.fable.app.FableApp
import io.harbor.fable.ui.components.TabBarTab
import io.harbor.fable.ui.components.FableUi
import io.harbor.fable.ui.components.FableTabBar
import io.harbor.fable.ui.components.SurfaceLevel
import io.harbor.fable.ui.components.LocalTabBarClearance
import io.harbor.fable.ui.components.LocalFableUi
import io.harbor.fable.ui.screens.*
import io.harbor.fable.ui.theme.ControlRadius
import io.harbor.fable.ui.theme.TabBarMetrics
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.HairlineStroke
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.ScreenPadding
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private object Routes {
    const val HOME = "home"
    const val CONTAINERS = "containers"
    const val CONTAINER_DETAIL = "container/{containerId}"
    const val DRIVERS = "drivers"
    const val VULKAN_EXTENSIONS = "drivers/vulkan"
    const val ASSETS = "assets"
    const val SETTINGS = "settings"

    val topLevel = setOf(HOME, CONTAINERS, DRIVERS, ASSETS, SETTINGS)
}

private fun tabIndex(route: String?): Int = when (route) {
    Routes.HOME -> 0
    Routes.CONTAINERS, Routes.CONTAINER_DETAIL -> 1
    Routes.DRIVERS, Routes.VULKAN_EXTENSIONS -> 2
    Routes.ASSETS -> 3
    Routes.SETTINGS -> 4
    else -> 0
}

/** +1 when moving to a tab on the right of the current one, -1 for the left. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.direction(): Int =
    if (tabIndex(targetState.destination.route) >= tabIndex(initialState.destination.route)) 1 else -1

// Tabs: a soft fade with a short slide in the direction of travel. The incoming screen settles
// (Motion.enter) while the outgoing one accelerates away (Motion.exit).
private val TabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    val sign = direction()
    fadeIn(Motion.enter()) + slideInHorizontally(Motion.enter()) { sign * it / 5 }
}
private val TabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    val sign = direction()
    fadeOut(Motion.exit()) + slideOutHorizontally(Motion.exit(Motion.Standard)) { -sign * it / 8 }
}
private val TabPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(Motion.enter()) + slideInHorizontally(Motion.enter()) { -it / 8 }
}
private val TabPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(Motion.exit()) + slideOutHorizontally(Motion.exit(Motion.Standard)) { it / 5 }
}

// Pushed screens (container detail, Vulkan extensions): slide in from the right over the list,
// which drifts away behind them; popping reverses the motion.
private val DetailEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(Motion.enter(Motion.Slow)) { it } + fadeIn(Motion.enter())
}
private val DetailExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(Motion.exit(Motion.Slow)) { -it / 5 } + fadeOut(Motion.exit(Motion.Standard))
}
private val DetailPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(Motion.enter(Motion.Slow)) { -it / 5 } + fadeIn(Motion.enter())
}
private val DetailPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(Motion.exit(Motion.Slow)) { it } + fadeOut(Motion.exit(Motion.Standard))
}

/**
 * Root of the UI. Shows the first-run [SetupScreen] until setup has been finished or skipped,
 * then the main shell (tabs, tab bar, sheets). The hand-over is one continuous motion: the setup
 * canvas zooms through and fades while the shell settles in from slightly below.
 */
@Composable
fun FableRoot() {
    val snackbarHostState = remember { SnackbarHostState() }
    val fableUi = remember {
        FableUi(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            snackbarHostState = snackbarHostState,
        )
    }
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()

    // An install that predates the setup screen but already has containers is not a first run.
    LaunchedEffect(Unit) {
        if (!app.settingsRepository.current.setupComplete && app.containerRepository.list().isNotEmpty()) {
            app.settingsRepository.markSetupComplete()
        }
    }

    CompositionLocalProvider(
        LocalFableUi provides fableUi,
        LocalTabBarClearance provides TabBarMetrics.Clearance,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FableBg),
        ) {
            AnimatedContent(
                targetState = settings.setupComplete,
                transitionSpec = {
                    if (targetState) {
                        // Setup -> app: the shell rises and settles while setup zooms away.
                        (
                            fadeIn(Motion.enter(Motion.Slow, delay = 120)) +
                                scaleIn(Motion.enter(Motion.Entrance, delay = 120), initialScale = 0.94f) +
                                slideInVertically(Motion.enter(Motion.Entrance, delay = 120)) { it / 14 }
                            ) togetherWith (
                            fadeOut(Motion.exit(Motion.Standard)) +
                                scaleOut(Motion.exit(Motion.Slow), targetScale = 1.06f)
                            )
                    } else {
                        fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit())
                    }
                },
                label = "rootShell",
                modifier = Modifier.fillMaxSize(),
            ) { setupComplete ->
                if (setupComplete) {
                    MainShell(snackbarHostState = snackbarHostState)
                } else {
                    SetupScreen(onFinished = { app.settingsRepository.markSetupComplete() })
                }
            }
        }
    }
}

/** Tabs, tab bar, snackbar and sheets: the app once setup is out of the way. */
@Composable
private fun MainShell(snackbarHostState: SnackbarHostState) {
    val navController = rememberNavController()

    // Refresh the catalog once per app launch when the user has it enabled.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val app = FableApp.from(context)
        if (app.settingsRepository.current.refreshCatalogOnLaunch) {
            runCatching { app.assetRepository.refresh() }
            runCatching { app.driverRepository.refresh() }
        }
    }

    var showAddApp by remember { mutableStateOf(false) }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isTopLevel = currentRoute in Routes.topLevel

    val tabs = remember {
        listOf(
            TabBarTab("Home", Icons.Outlined.Home),
            TabBarTab("Containers", Icons.Outlined.Apps),
            TabBarTab("Drivers", Icons.Outlined.Memory),
            TabBarTab("Assets", Icons.Outlined.Download),
            TabBarTab("Settings", Icons.Outlined.Settings),
        )
    }

    val activeTab = tabIndex(currentRoute)

    Box(
        Modifier
            .fillMaxSize()
            .background(FableBg),
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
            ) {
                DriversScreen(onOpenVulkanExtensions = { navController.navigate(Routes.VULKAN_EXTENSIONS) })
            }
            composable(
                route = Routes.VULKAN_EXTENSIONS,
                enterTransition = DetailEnter,
                exitTransition = DetailExit,
                popEnterTransition = DetailPopEnter,
                popExitTransition = DetailPopExit,
            ) {
                VulkanExtensionsScreen(onBack = { navController.popBackStack() })
            }
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

        // Floats above the tab bar on tabs and near the bottom edge on pushed screens.
        val snackbarBottom by animateDpAsState(
            targetValue = if (isTopLevel) TabBarMetrics.Clearance else Dp.Hairline,
            animationSpec = Motion.settle(),
            label = "snackbarBottom",
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = snackbarBottom + Spacing.sm, start = ScreenPadding, end = ScreenPadding),
        ) { data ->
            val shape = RoundedCornerShape(ControlRadius + 2.dp)
            // Always dismissible: the close icon (withDismissAction) or a tap anywhere on it.
            Snackbar(
                snackbarData = data,
                modifier = Modifier
                    .border(HairlineStroke, FableBorder, shape)
                    .clip(shape)
                    .clickable(onClick = { data.dismiss() }),
                shape = shape,
                containerColor = SurfaceLevel.Sheet.fill,
                contentColor = FableText,
                actionColor = FableAccent,
                dismissActionContentColor = FableTextDim,
            )
        }

        // Tab bar — only on top-level tabs. It rises with the pushed screen's pop and drops away
        // as a detail screen slides in, on the same clock as those transitions.
        AnimatedVisibility(
            visible = isTopLevel,
            enter = slideInVertically(Motion.enter(Motion.Slow)) { it } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.exit(Motion.Standard)) { it } + fadeOut(Motion.exit()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FableTabBar(
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
