package com.kerneldroid.karchiver.presentation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kerneldroid.karchiver.data.AppSettings
import com.kerneldroid.karchiver.data.SettingsRepository
import com.kerneldroid.karchiver.presentation.browser.BrowserScreen
import com.kerneldroid.karchiver.presentation.browser.BrowserViewModel
import com.kerneldroid.karchiver.presentation.browser.ViewMode
import com.kerneldroid.karchiver.presentation.home.HomeScreen
import com.kerneldroid.karchiver.presentation.settings.SettingsScreen
import java.io.File

private object RootRoute {
    const val BROWSER = "browser"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

@Composable
fun KArchiverRoot() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context.applicationContext) }
    val settings by produceState<AppSettings?>(initialValue = null, settingsRepo) {
        settingsRepo.settings.collect { value = it }
    }
    val vm: BrowserViewModel = viewModel()
    val browserState by vm.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val motionScheme = MaterialTheme.motionScheme
    val recents by settingsRepo.recentFolders.collectAsStateWithLifecycle(initialValue = emptyList())
    var ready by remember { mutableStateOf(false) }
    var historyPrimed by remember { mutableStateOf(false) }
    var barLifted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        vm.initialize(
            s.lastPath.takeIf { s.openLastFolder },
            s.hideHidden,
            s.defaultSort,
            if (s.defaultView == "grid") ViewMode.GRID else ViewMode.LIST,
            s.foldersFirst,
            s.rarEnabled
        )
        vm.setHideHidden(s.hideHidden)
        vm.setRarEnabled(s.rarEnabled)
        ready = true
    }

    LaunchedEffect(
        settings?.defaultSort,
        settings?.defaultView,
        settings?.foldersFirst,
        ready
    ) {
        val s = settings ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        vm.applyExplorerPrefs(
            s.defaultSort,
            if (s.defaultView == "grid") ViewMode.GRID else ViewMode.LIST,
            s.foldersFirst
        )
    }

    LaunchedEffect(browserState.currentDir.absolutePath, ready) {
        if (ready) {
            val path = browserState.currentDir.absolutePath
            settingsRepo.setLastPath(path)
            if (historyPrimed) settingsRepo.pushRecentFolder(path)
            historyPrimed = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = RootRoute.BROWSER,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = motionScheme.defaultSpatialSpec()
            ) + fadeIn(animationSpec = motionScheme.fastEffectsSpec())
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = motionScheme.defaultSpatialSpec()
            ) + fadeOut(animationSpec = motionScheme.fastEffectsSpec())
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = motionScheme.defaultSpatialSpec()
            ) + fadeIn(animationSpec = motionScheme.fastEffectsSpec())
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = motionScheme.defaultSpatialSpec()
            ) + fadeOut(animationSpec = motionScheme.fastEffectsSpec())
        }
    ) {
        composable(RootRoute.BROWSER) {
            BrowserScreen(
                vm = vm,
                showMainMenu = settings?.showMainMenu == true,
                confirmDelete = settings?.confirmDelete != false,
                rarEnabled = settings?.rarEnabled == true,
                barLifted = barLifted,
                onToggleBar = { barLifted = !barLifted },
                onOpenHome = { navController.navigate(RootRoute.HOME) },
                onOpenSettings = { navController.navigate(RootRoute.SETTINGS) }
            )
        }
        composable(RootRoute.HOME) {
            HomeScreen(
                onOpenPath = { path ->
                    vm.navigateTo(File(path))
                    navController.popBackStack(RootRoute.BROWSER, inclusive = false)
                },
                onOpenSettings = { navController.navigate(RootRoute.SETTINGS) },
                onBack = { navController.popBackStack() },
                recentFolders = recents,
                barLifted = barLifted,
                onToggleBar = { barLifted = !barLifted }
            )
        }
        composable(RootRoute.SETTINGS) {
            SettingsScreen(
                settings = settings ?: AppSettings(),
                repo = settingsRepo,
                onBack = { navController.popBackStack() },
                barLifted = barLifted,
                onToggleBar = { barLifted = !barLifted }
            )
        }
    }
}
