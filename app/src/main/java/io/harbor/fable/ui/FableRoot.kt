package io.harbor.fable.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.harbor.fable.ui.components.DockTab
import io.harbor.fable.ui.components.GlassDock
import io.harbor.fable.ui.screens.*
import io.harbor.fable.ui.theme.FableBg

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FableRoot() {
    var activeTab by remember { mutableIntStateOf(0) }

    val tabs = remember {
        listOf(
            DockTab("Home", Icons.Outlined.Home),
            DockTab("Containers", Icons.Outlined.Apps),
            DockTab("Drivers", Icons.Outlined.Memory),
            DockTab("Assets", Icons.Outlined.Download),
            DockTab("Settings", Icons.Outlined.Settings),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FableBg)
    ) {
        // Animated screen transitions
        AnimatedContent(
            targetState = activeTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) + slideInHorizontally(
                    initialOffsetX = { it / 6 },
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "screenTransition",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                0 -> HomeScreen()
                1 -> ContainersScreen()
                2 -> DriversScreen()
                3 -> AssetsScreen()
                4 -> SettingsScreen()
            }
        }

        // Floating glass dock at bottom
        GlassDock(
            items = tabs,
            activeIndex = activeTab,
            onTabSelected = { activeTab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
