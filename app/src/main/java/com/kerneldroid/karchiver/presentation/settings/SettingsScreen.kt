package com.kerneldroid.karchiver.presentation.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kerneldroid.karchiver.data.AppSettings
import com.kerneldroid.karchiver.data.SettingsRepository
import com.kerneldroid.karchiver.data.SortBy
import com.kerneldroid.karchiver.data.ThemeMode
import com.kerneldroid.karchiver.presentation.components.RoundedTopScaffold
import com.kerneldroid.karchiver.presentation.components.detectBarHold
import kotlinx.coroutines.launch

private data class SeedColor(val color: Color, val name: String)

private val seedColors = listOf(
    SeedColor(Color(0xFFFFA79B), "Red"),
    SeedColor(Color(0xFFFFB2BD), "Rose"),
    SeedColor(Color(0xFFD7BBFC), "Purple"),
    SeedColor(Color(0xFFC5C0FF), "Indigo"),
    SeedColor(Color(0xFFB0C6FF), "Blue"),
    SeedColor(Color(0xFF86D1EA), "Cyan"),
    SeedColor(Color(0xFF82D5C7), "Teal"),
    SeedColor(Color(0xFF9CD59F), "Green"),
    SeedColor(Color(0xFFC3CD7C), "Chartreuse"),
    SeedColor(Color(0xFFE8C16C), "Yellow"),
    SeedColor(Color(0xFFFFB68D), "Orange")
)

private data class ThemeOption(val mode: ThemeMode, val label: String, val icon: ImageVector)

private val themeOptions = listOf(
    ThemeOption(ThemeMode.SYSTEM, "System", Icons.Filled.BrightnessAuto),
    ThemeOption(ThemeMode.LIGHT, "Light", Icons.Filled.LightMode),
    ThemeOption(ThemeMode.DARK, "Dark", Icons.Filled.DarkMode),
    ThemeOption(ThemeMode.OLED, "OLED", Icons.Filled.Contrast)
)

private val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    repo: SettingsRepository,
    onBack: () -> Unit,
    barLifted: Boolean = false,
    onToggleBar: () -> Unit = {}
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    RoundedTopScaffold(
        barLifted = barLifted,
        topBar = {
            TopAppBar(
                modifier = Modifier.detectBarHold {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleBar()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader("Appearance")
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    onClick = {},
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    leadingContent = {
                        AnimatedContent(themeOptions.first { it.mode == settings.themeMode }.icon) {
                            Icon(it, null)
                        }
                    },
                    supportingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            themeOptions.forEachIndexed { index, option ->
                                val selected = settings.themeMode == option.mode
                                ToggleButton(
                                    checked = selected,
                                    onCheckedChange = {
                                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        scope.launch { repo.setThemeMode(option.mode) }
                                    },
                                    shapes = when (index) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        themeOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .semantics { role = Role.RadioButton }
                                ) {
                                    Icon(option.icon, option.label)
                                }
                            }
                        }
                    }
                ) {
                    Text("Theme")
                }
                SegmentedListItem(
                    onClick = {
                        if (!supportsDynamic) return@SegmentedListItem
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        scope.launch {
                            if (settings.dynamicColor) {
                                repo.setSeedColor(seedColors.first().color.value.toLong())
                            } else {
                                repo.setDynamicColor(true)
                            }
                        }
                    },
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    leadingContent = { Icon(Icons.Filled.Palette, null) },
                    supportingContent = {
                        Text(
                            if (supportsDynamic) "Follow the wallpaper colors"
                            else "Requires Android 12 or newer"
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.dynamicColor,
                            enabled = supportsDynamic,
                            onCheckedChange = { checked ->
                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                scope.launch {
                                    if (checked) repo.setDynamicColor(true)
                                    else repo.setSeedColor(
                                        settings.seedColor ?: seedColors.first().color.value.toLong()
                                    )
                                }
                            }
                        )
                    },
                ) {
                    Text("Dynamic color")
                }
                SegmentedListItem(
                    onClick = {},
                    shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    supportingContent = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(
                                if (settings.dynamicColor || settings.seedColor == null) "Dynamic"
                                else seedColors.firstOrNull { it.color.value.toLong() == settings.seedColor }?.name
                                    ?: "Custom"
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                itemsIndexed(seedColors) { index, seed ->
                                val argb = seed.color.value.toLong()
                                val selected = !settings.dynamicColor && settings.seedColor == argb
                                ToggleButton(
                                    checked = selected,
                                    onCheckedChange = {
                                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        scope.launch { repo.setSeedColor(argb) }
                                    },
                                    shapes = when (index) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        seedColors.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    colors = ToggleButtonDefaults.toggleButtonColors(
                                        containerColor = seed.color,
                                        contentColor = Color.White,
                                        checkedContainerColor = seed.color,
                                        checkedContentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .height(40.dp)
                                        .widthIn(min = 40.dp)
                                        .semantics { role = Role.RadioButton }
                                ) {
                                    AnimatedContent(selected) { isSelected ->
                                        if (isSelected) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(seed.name, style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                ) {
                    Text("Custom color")
                }
            }
            SectionHeader("File manager")
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    onClick = {},
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 5),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    leadingContent = { Icon(Icons.Filled.SortByAlpha, null) },
                    supportingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            SortBy.entries.forEachIndexed { index, sort ->
                                val selected = settings.defaultSort == sort
                                ToggleButton(
                                    checked = selected,
                                    onCheckedChange = {
                                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        scope.launch { repo.setDefaultSort(sort) }
                                    },
                                    shapes = when (index) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        SortBy.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .semantics { role = Role.RadioButton }
                                ) {
                                    Text(sortLabel(sort), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                ) {
                    Text("Default sort")
                }
                SettingSwitch(
                    index = 1,
                    count = 5,
                    title = "Folders first",
                    subtitle = "Always list folders above files, no matter the sort order.",
                    checked = settings.foldersFirst,
                    onCheckedChange = { scope.launch { repo.setFoldersFirst(it) } }
                )
                SettingSwitch(
                    index = 2,
                    count = 5,
                    title = "Confirm before delete",
                    subtitle = "Ask for confirmation before deleting files and folders.",
                    checked = settings.confirmDelete,
                    onCheckedChange = { scope.launch { repo.setConfirmDelete(it) } }
                )
                SegmentedListItem(
                    onClick = {},
                    shapes = ListItemDefaults.segmentedShapes(index = 3, count = 5),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    leadingContent = {
                        Icon(
                            if (settings.defaultView == "grid") Icons.Filled.ViewModule
                            else Icons.AutoMirrored.Filled.ViewList,
                            null
                        )
                    },
                    supportingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            val listSelected = settings.defaultView != "grid"
                            ToggleButton(
                                checked = listSelected,
                                onCheckedChange = {
                                    if (!listSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    scope.launch { repo.setDefaultView("list") }
                                },
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { role = Role.RadioButton }
                            ) {
                                Text("List", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ToggleButton(
                                checked = !listSelected,
                                onCheckedChange = {
                                    if (listSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    scope.launch { repo.setDefaultView("grid") }
                                },
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { role = Role.RadioButton }
                            ) {
                                Text("Grid", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                ) {
                    Text("Default view")
                }
                SettingSwitch(
                    index = 4,
                    count = 5,
                    title = "RAR support",
                    subtitle = "Read RAR archives: preview, verify and extract. RAR stays read-only, packing is never enabled.",
                    checked = settings.rarEnabled,
                    onCheckedChange = { scope.launch { repo.setRarEnabled(it) } }
                )
            }
            SectionHeader("Interface")
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingSwitch(
                    index = 0,
                    count = 3,
                    title = "Main menu",
                    subtitle = "Show a button that opens the main menu. By default the app opens a folder directly.",
                    checked = settings.showMainMenu,
                    onCheckedChange = { scope.launch { repo.setShowMainMenu(it) } }
                )
                SettingSwitch(
                    index = 1,
                    count = 3,
                    title = "Open last folder",
                    subtitle = "Return to the folder you were in when the app starts.",
                    checked = settings.openLastFolder,
                    onCheckedChange = { scope.launch { repo.setOpenLastFolder(it) } }
                )
                SettingSwitch(
                    index = 2,
                    count = 3,
                    title = "Hide hidden files",
                    subtitle = "Do not show files and folders whose name starts with a dot.",
                    checked = settings.hideHidden,
                    onCheckedChange = { scope.launch { repo.setHideHidden(it) } }
                )
            }
        }
    }
}

private fun sortLabel(sort: SortBy): String = when (sort) {
    SortBy.NAME -> "Name"
    SortBy.DATE -> "Date"
    SortBy.SIZE -> "Size"
    SortBy.TYPE -> "Type"
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingSwitch(
    index: Int,
    count: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    ) {
        Text(title)
    }
}
