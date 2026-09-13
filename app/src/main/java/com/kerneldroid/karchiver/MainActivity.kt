package com.kerneldroid.karchiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kerneldroid.karchiver.data.SettingsRepository
import com.kerneldroid.karchiver.data.ThemeMode
import com.kerneldroid.karchiver.presentation.KArchiverRoot
import com.kerneldroid.karchiver.presentation.onboard.OnboardScreen
import com.kerneldroid.karchiver.presentation.onboard.hasStoragePermission
import com.kerneldroid.karchiver.ui.theme.KArchiverTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settingsRepo = remember { SettingsRepository(applicationContext) }
            val prefs by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
            KArchiverTheme(
                themeMode = prefs?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = prefs?.dynamicColor ?: true,
                seedColor = prefs?.seedColor?.let { Color(it.toULong()) }
            ) {
                var hasPerm by remember { mutableStateOf(hasStoragePermission(this)) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1200)
                        val now = hasStoragePermission(this@MainActivity)
                        if (now != hasPerm) hasPerm = now
                    }
                }
                if (hasPerm) KArchiverRoot()
                else OnboardScreen(onGranted = { hasPerm = hasStoragePermission(this) })
            }
        }
    }
}
