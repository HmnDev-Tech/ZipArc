package com.kerneldroid.karchiver.presentation.settings

import android.os.Build
import android.os.SystemClock
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kerneldroid.karchiver.data.AppSettings
import com.kerneldroid.karchiver.data.SettingsRepository
import com.kerneldroid.karchiver.data.SortBy
import com.kerneldroid.karchiver.data.ThemeMode
import com.kerneldroid.karchiver.data.elevation.RootEngine
import com.kerneldroid.karchiver.data.elevation.RootStatus
import com.kerneldroid.karchiver.data.elevation.ShizukuEngine
import com.kerneldroid.karchiver.data.elevation.ShizukuEngine.ShizukuStatus
import com.kerneldroid.karchiver.presentation.components.RoundedTopScaffold
import com.kerneldroid.karchiver.presentation.components.detectBarHold
import com.kerneldroid.karchiver.data.storage.AppVolume
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    onToggleBar: () -> Unit = {},
    safAutoFallback: Boolean = true,
    onSetSafAutoFallback: (Boolean) -> Unit = {},
    safGrants: Map<String, Uri> = emptyMap(),
    storageVolumes: List<AppVolume> = emptyList(),
    forcedSaf: Set<String> = emptySet(),
    onForgetGrant: (String) -> Unit = {},
    onSetForceSaf: (String, Boolean) -> Unit = { _, _ -> },
    onGrantPicked: (Uri, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val activity = LocalContext.current as? Activity
    var rarUnlockAt by remember { mutableLongStateOf(0L) }
    var showElevationDialog by remember { mutableStateOf(false) }
    val rootStatus by RootEngine.status.collectAsStateWithLifecycle(initialValue = RootStatus.Unknown)
    val shizukuStatus by ShizukuEngine.status.collectAsStateWithLifecycle(initialValue = ShizukuStatus.NoBinder)
    val rarInteractions = remember { MutableInteractionSource() }
    var rarUnlockJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(rarInteractions) {
        rarInteractions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release || interaction is PressInteraction.Cancel) {
                rarUnlockJob?.cancel()
                rarUnlockJob = null
            }
        }
    }

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
                    subtitle = if (settings.rarWriteEnabled) "Read and write. Packing unlocked."
                        else "Read-only. Hold this row 5 seconds to unlock RAR packing.",
                    checked = settings.rarEnabled,
                    onCheckedChange = {
                        if (SystemClock.uptimeMillis() - rarUnlockAt < 1000L) return@SettingSwitch
                        scope.launch { repo.setRarEnabled(it) }
                    },
                    interactionSource = rarInteractions,
                    onLongClick = {
                        if (!settings.rarWriteEnabled) {
                            rarUnlockJob?.cancel()
                            rarUnlockJob = scope.launch {
                                delay(5000L)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                rarUnlockAt = SystemClock.uptimeMillis()
                                repo.setRarWriteEnabled(true)
                            }
                        }
                    }
                )
            }
            SectionHeader("Storage")
            StorageSection(
                safAutoFallback = safAutoFallback,
                onSetSafAutoFallback = onSetSafAutoFallback,
                safGrants = safGrants,
                volumes = storageVolumes,
                forcedSaf = forcedSaf,
                onForgetGrant = onForgetGrant,
                onSetForceSaf = onSetForceSaf,
                onGrantPicked = onGrantPicked
            )
            SectionHeader("Elevation")
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    onClick = { showElevationDialog = true },
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    leadingContent = { Icon(Icons.Filled.Security, null) },
                    supportingContent = {
                        Text(
                            when (settings.elevationMode) {
                                "shizuku" -> "Shizuku · ${shizukuStatusLabel(shizukuStatus)}"
                                "root" -> "Root · ${rootStatusLabel(rootStatus)}"
                                else -> "Off"
                            }
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                ) {
                    Text("Elevation")
                }
            }
            SectionHeader("Interface")
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingSwitch(
                    index = 0,
                    count = 2,
                    title = "Open last folder",
                    subtitle = "Return to the folder you were in when the app starts.",
                    checked = settings.openLastFolder,
                    onCheckedChange = { scope.launch { repo.setOpenLastFolder(it) } }
                )
                SettingSwitch(
                    index = 1,
                    count = 2,
                    title = "Hide hidden files",
                    subtitle = "Do not show files and folders whose name starts with a dot.",
                    checked = settings.hideHidden,
                    onCheckedChange = { scope.launch { repo.setHideHidden(it) } }
                )
            }
        }
    }

    if (showElevationDialog) {
        AlertDialog(
            onDismissRequest = { showElevationDialog = false },
            icon = { Icon(Icons.Filled.Security, null) },
            title = { Text("Elevation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ElevationOption(
                        selected = settings.elevationMode == "off",
                        title = "Off",
                        subtitle = "Never use elevated access.",
                        onSelect = { scope.launch { repo.setElevationMode("off") } }
                    )
                    ElevationOption(
                        selected = settings.elevationMode == "shizuku",
                        title = "Shizuku",
                        subtitle = shizukuStatusLabel(shizukuStatus),
                        onSelect = { scope.launch { repo.setElevationMode("shizuku") } }
                    )
                    ElevationOption(
                        selected = settings.elevationMode == "root",
                        title = "Root",
                        subtitle = rootStatusLabel(rootStatus),
                        onSelect = { scope.launch { repo.setElevationMode("root") } }
                    )
                    Text(
                        "Shell-backed Shizuku cannot open app-private data (SELinux policy). Root-backed Shizuku and Root see everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showElevationDialog = false }) { Text("Close") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (settings.elevationMode == "shizuku" && shizukuStatus == ShizukuStatus.PermissionRequired) {
                        TextButton(
                            enabled = activity != null,
                            onClick = {
                                activity?.let { ShizukuEngine.requestPermission(it) }
                            }
                        ) { Text("Request") }
                    }
                    if (settings.elevationMode == "root") {
                        TextButton(onClick = { scope.launch { RootEngine.refresh() } }) { Text("Recheck") }
                    }
                }
            }
        )
    }
}

private fun sortLabel(sort: SortBy): String = when (sort) {
    SortBy.NAME -> "Name"
    SortBy.DATE -> "Date"
    SortBy.SIZE -> "Size"
    SortBy.TYPE -> "Type"
}

private fun rootStatusLabel(status: RootStatus): String = when (status) {
    RootStatus.Available -> "Root available"
    RootStatus.Denied -> "Root denied"
    RootStatus.Unavailable -> "No root"
    RootStatus.Unknown -> "Checking"
}

private fun shizukuStatusLabel(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.Ready -> "Ready"
    ShizukuStatus.PermissionRequired -> "Permission required"
    ShizukuStatus.NoBinder -> "Shizuku not running"
    ShizukuStatus.Unavailable -> "Unavailable"
}

@Composable
private fun ElevationOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StorageSection(
    safAutoFallback: Boolean,
    onSetSafAutoFallback: (Boolean) -> Unit,
    safGrants: Map<String, Uri>,
    volumes: List<AppVolume>,
    forcedSaf: Set<String>,
    onForgetGrant: (String) -> Unit,
    onSetForceSaf: (String, Boolean) -> Unit,
    onGrantPicked: (Uri, String) -> Unit
) {
    var grantTargetId by remember { mutableStateOf<String?>(null) }
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val id = grantTargetId
        grantTargetId = null
        if (uri != null && id != null) onGrantPicked(uri, id)
    }
    fun labelFor(volumeId: String): String =
        volumes.firstOrNull { it.id == volumeId }?.label ?: volumeId
    fun iconFor(volumeId: String): ImageVector {
        val volume = volumes.firstOrNull { it.id == volumeId }
        return when {
            volume == null -> Icons.Filled.SdStorage
            !volume.isRemovable -> Icons.Filled.Smartphone
            volume.label.contains("usb", ignoreCase = true) -> Icons.Filled.Usb
            else -> Icons.Filled.SdStorage
        }
    }
    val ungranted = volumes.filter { it.isRemovable && !safGrants.containsKey(it.id) }
    val grantRows = safGrants.toList()
    val count = 2 + grantRows.size + ungranted.size
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            onClick = {},
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
            colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            leadingContent = { Icon(Icons.Filled.Storage, null) },
            supportingContent = {
                Text("SD card and USB-OTG use direct file access on Android 11+. SAF is only a fallback.")
            }
        ) {
            Text("Native access")
        }
        SettingSwitch(
            index = 1,
            count = count,
            title = "Automatic SAF fallback",
            subtitle = "Use granted folders when direct access fails.",
            checked = safAutoFallback,
            onCheckedChange = onSetSafAutoFallback
        )
        grantRows.forEachIndexed { offset, (volumeId, _) ->
            val index = 2 + offset
            SegmentedListItem(
                onClick = {},
                shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                leadingContent = { Icon(iconFor(volumeId), null) },
                supportingContent = { Text("Granted folder. Switch prefers SAF over direct access.") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = volumeId in forcedSaf,
                            onCheckedChange = { onSetForceSaf(volumeId, it) }
                        )
                        TextButton(onClick = { onForgetGrant(volumeId) }) { Text("Forget") }
                    }
                }
            ) {
                Text(labelFor(volumeId), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ungranted.forEachIndexed { offset, volume ->
            val index = 2 + grantRows.size + offset
            SegmentedListItem(
                onClick = {
                    grantTargetId = volume.id
                    treePicker.launch(null)
                },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                leadingContent = { Icon(iconFor(volume.id), null) },
                supportingContent = { Text("Direct access preferred. Grant a folder as fallback.") },
                trailingContent = {
                    TextButton(
                        onClick = {
                            grantTargetId = volume.id
                            treePicker.launch(null)
                        }
                    ) { Text("Grant") }
                }
            ) {
                Text(volume.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        interactionSource = interactionSource,
        onLongClick = onLongClick
    ) {
        Text(title)
    }
}
