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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
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
import io.harbor.fable.ui.components.NoticeHost
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import io.harbor.fable.ui.icons.FableIcons

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
 * Switches to the top-level tab [route]. Every tab sits directly on top of Home, so back from any
 * tab returns Home and back from Home leaves the app.
 *
 * Home is reached by popping back to it rather than navigating with `restoreState`: a
 * non-inclusive `popUpTo(HOME) { saveState = true }` also files the popped tab's saved stack under
 * Home's id, so `navigate(HOME) { restoreState = true }` brought the previous tab (Containers after
 * "See all") straight back instead of Home, and the stale mapping later made other tabs (Assets)
 * fail to open. Other tabs keep their state across switches with save/restore.
 */
private fun NavHostController.navigateToTab(route: String) {
    if (route == Routes.HOME) {
        // Save the tab being left so its scroll position survives the round trip.
        if (currentDestination?.route != Routes.HOME) {
            popBackStack(Routes.HOME, inclusive = false, saveState = true)
        }
        return
    }
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Root of the UI. Shows the first-run [SetupScreen] until setup has been finished or skipped,
 * then the main shell (tabs, tab bar, sheets). The hand-over is one continuous motion: the setup
 * canvas zooms through and fades while the shell settles in from slightly below.
 */
@Composable
fun FableRoot() {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    // Process-wide, not per composition: the notice it holds must outlive every screen.
    val fableUi = app.fableUi
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
                    MainShell()
                } else {
                    SetupScreen(onFinished = { app.settingsRepository.markSetupComplete() })
                }
            }

            // The one message host for the whole app. It sits above the NavHost and the setup
            // screen, so navigating never recreates it: a message slides in once and stays put
            // while screens change beneath it. It rests above the floating tab bar when the tab
            // bar is up, and glides (rather than re-entering) when that changes.
            val tabClearance by animateDpAsState(
                targetValue = if (settings.setupComplete && fableUi.tabBarVisible) TabBarMetrics.Clearance else 0.dp,
                animationSpec = Motion.inPlace(),
                label = "noticeClearance",
            )
            NoticeHost(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .navigationBarsPadding()
                    .padding(bottom = tabClearance + Spacing.sm),
            )
        }
    }
}

/**
 * Tabs, tab bar and sheets: the app once setup is out of the way. Messages are shown by the
 * app-level `NoticeHost` in [FableRoot], not by the screens or a snackbar host here.
 */
@Composable
private fun MainShell() {
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
            // Outline glyph when idle, the filled weight of the same glyph when active.
            TabBarTab("Home", FableIcons.Home, FableIcons.HomeFill),
            TabBarTab("Containers", FableIcons.Containers, FableIcons.ContainersFill),
            TabBarTab("Drivers", FableIcons.Drivers, FableIcons.DriversFill),
            TabBarTab("Assets", FableIcons.Assets, FableIcons.AssetsFill),
            TabBarTab("Settings", FableIcons.Settings, FableIcons.SettingsFill),
        )
    }

    val activeTab = tabIndex(currentRoute)

    // Tell the app-level notice host where the tab bar is, so messages sit above it.
    val fableUi = LocalFableUi.current
    SideEffect { fableUi.tabBarVisible = isTopLevel }
    DisposableEffect(Unit) { onDispose { fableUi.tabBarVisible = false } }

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
                    // "See all" is the Containers tab, so it uses the tab back stack: back returns Home.
                    onNavigateToContainers = { navController.navigateToTab(Routes.CONTAINERS) },
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
                    onOpenAssets = { navController.navigateToTab(Routes.ASSETS) },
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
                    navController.navigateToTab(route)
                },
            )
        }
    }
}
