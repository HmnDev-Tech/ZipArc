package com.kerneldroid.karchiver.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kerneldroid.karchiver.data.AppSettings
import com.kerneldroid.karchiver.data.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettings, repo: SettingsRepository, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Interface")
            SettingSwitch(
                title = "Main menu",
                subtitle = "Show a button that opens the main menu. By default the app opens a folder directly.",
                checked = settings.showMainMenu,
                onCheckedChange = { scope.launch { repo.setShowMainMenu(it) } }
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Open last folder",
                subtitle = "Return to the folder you were in when the app starts.",
                checked = settings.openLastFolder,
                onCheckedChange = { scope.launch { repo.setOpenLastFolder(it) } }
            )
            HorizontalDivider()
            SettingSwitch(
                title = "Hide hidden files",
                subtitle = "Do not show files and folders whose name starts with a dot.",
                checked = settings.hideHidden,
                onCheckedChange = { scope.launch { repo.setHideHidden(it) } }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {
        Text(title)
    }
}
