package com.kerneldroid.karchiver.presentation

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
import com.kerneldroid.karchiver.data.AppSettings
import com.kerneldroid.karchiver.data.SettingsRepository
import com.kerneldroid.karchiver.presentation.browser.BrowserScreen
import com.kerneldroid.karchiver.presentation.browser.BrowserViewModel
import com.kerneldroid.karchiver.presentation.home.HomeScreen
import com.kerneldroid.karchiver.presentation.settings.SettingsScreen
import java.io.File

private enum class RootRoute { BROWSER, HOME, SETTINGS }

@Composable
fun KArchiverRoot() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context.applicationContext) }
    val settings by produceState<AppSettings?>(initialValue = null, settingsRepo) {
        settingsRepo.settings.collect { value = it }
    }
    val vm: BrowserViewModel = viewModel()
    val browserState by vm.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(RootRoute.BROWSER.name) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        vm.initialize(s.lastPath.takeIf { s.openLastFolder }, s.hideHidden)
        vm.setHideHidden(s.hideHidden)
        ready = true
    }

    LaunchedEffect(browserState.currentDir.absolutePath, ready) {
        if (ready) settingsRepo.setLastPath(browserState.currentDir.absolutePath)
    }

    when (RootRoute.valueOf(route)) {
        RootRoute.BROWSER -> BrowserScreen(
            vm = vm,
            showMainMenu = settings?.showMainMenu == true,
            onOpenHome = { route = RootRoute.HOME.name },
            onOpenSettings = { route = RootRoute.SETTINGS.name }
        )

        RootRoute.HOME -> HomeScreen(
            onOpenPath = { path -> vm.navigateTo(File(path)); route = RootRoute.BROWSER.name },
            onOpenSettings = { route = RootRoute.SETTINGS.name },
            onBack = { route = RootRoute.BROWSER.name }
        )

        RootRoute.SETTINGS -> SettingsScreen(
            settings = settings ?: AppSettings(),
            repo = settingsRepo,
            onBack = { route = RootRoute.BROWSER.name }
        )
    }
}
