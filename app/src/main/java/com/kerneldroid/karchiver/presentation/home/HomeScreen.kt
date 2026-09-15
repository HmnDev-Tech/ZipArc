package com.kerneldroid.karchiver.presentation.home

import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kerneldroid.karchiver.data.VolumeStats
import com.kerneldroid.karchiver.data.formatBytes
import com.kerneldroid.karchiver.data.loadVolumeStats
import com.kerneldroid.karchiver.presentation.components.RoundedTopScaffold
import com.kerneldroid.karchiver.presentation.components.detectBarHold
import com.kerneldroid.karchiver.data.storage.AppVolume
import com.kerneldroid.karchiver.data.storage.VolumeKind
import com.kerneldroid.karchiver.data.storage.VolumeMonitor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HomeEntry(val title: String, val path: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onOpenPath: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    recentFolders: List<String> = emptyList(),
    barLifted: Boolean = false,
    onToggleBar: () -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val entries = remember { homeEntries() }
    val recents = remember(recentFolders) {
        recentFolders.filter { File(it).isDirectory }.take(3)
    }
    val volumes by produceState(initialValue = emptyList<VolumeStats>(), context) {
        value = withContext(Dispatchers.IO) { loadVolumeStats(context.applicationContext) }
    }
    var appVolumes by remember { mutableStateOf(emptyList<AppVolume>()) }
    val appContext = remember(context) { context.applicationContext }
    DisposableEffect(appContext) {
        val monitor = VolumeMonitor(appContext) { appVolumes = it }
        monitor.start()
        onDispose { monitor.stop() }
    }
    val kindByPath = remember(appVolumes) {
        appVolumes.associate { it.root.absolutePath to it.kind }
    }
    val mergedVolumes = remember(volumes, appVolumes) {
        val known = volumes.map { it.path }.toSet()
        volumes + appVolumes
            .filter { it.root.absolutePath !in known }
            .mapNotNull { statOfVolume(it.label, it.root) }
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
                title = { Text("KArchiver") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, "Menu")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader("Storage")
            }
            items(mergedVolumes, key = { it.path }) { stats ->
                StorageCard(stats = stats, kind = kindByPath[stats.path] ?: VolumeKind.INTERNAL, onClick = { onOpenPath(stats.path) })
            }
            item {
                SectionHeader("Recent folders")
            }
            if (recents.isEmpty()) {
                item {
                    Text(
                        "Folders you open will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        recents.forEachIndexed { index, path ->
                            val file = File(path)
                            val name = folderLabel(file)
                            SegmentedListItem(
                                onClick = { onOpenPath(path) },
                                shapes = ListItemDefaults.segmentedShapes(index = index, count = recents.size),
                                colors = ListItemDefaults.segmentedColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                leadingContent = { Icon(Icons.Filled.Folder, null) },
                                supportingContent = {
                                    Text(
                                        file.parent ?: path,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingContent = { Icon(Icons.Filled.ChevronRight, null) }
                            ) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item {
                SectionHeader("Quick access")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    entries.forEachIndexed { index, entry ->
                        SegmentedListItem(
                            onClick = { onOpenPath(entry.path) },
                            shapes = ListItemDefaults.segmentedShapes(index = index, count = entries.size),
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            leadingContent = { Icon(entry.icon, null) },
                            trailingContent = { Icon(Icons.Filled.ChevronRight, null) }
                        ) {
                            Text(entry.title)
                        }
                    }
                }
            }
        }
    }
}

private fun folderLabel(file: File): String {
    if (file.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
        return "Internal storage"
    }
    return file.name.ifEmpty { file.absolutePath }
}

private fun statOfVolume(label: String, root: File): VolumeStats? {
    return try {
        if (!root.exists()) return null
        val stat = StatFs(root.absolutePath)
        VolumeStats(label, root.absolutePath, stat.availableBytes, stat.totalBytes)
    } catch (_: Exception) {
        VolumeStats(label, root.absolutePath, 0L, 0L)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageCard(stats: VolumeStats, kind: VolumeKind, onClick: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = stats.usedFraction,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "storageProgress"
    )
    val lowSpace = stats.totalBytes > 0 && stats.freeBytes < stats.totalBytes * 0.1
    val progressColor = if (lowSpace) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    val icon = when (kind) {
        VolumeKind.USB -> Icons.Filled.Usb
        VolumeKind.SD_CARD -> Icons.Filled.SdStorage
        VolumeKind.INTERNAL -> Icons.Filled.Save
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(160.dp)
                    .offset(y = 30.dp)
                    .alpha(0.2f)
            )
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularWavyProgressIndicator(
                    progress = { progress },
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f),
                    wavelength = 25.dp,
                    modifier = Modifier.size(104.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(
                            text = stats.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "${formatBytes(stats.freeBytes)} free of ${formatBytes(stats.totalBytes)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBadge(
                            text = "${(stats.usedFraction * 100).toInt()}% used",
                            containerColor = progressColor,
                            contentColor = if (lowSpace) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary
                        )
                        StatBadge(
                            text = "${formatBytes(stats.usedBytes)} used",
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        )
                    }
                    if (lowSpace) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Running low on space",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
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

private fun homeEntries(): List<HomeEntry> {
    fun publicDir(type: String, title: String, icon: ImageVector): HomeEntry =
        HomeEntry(title, Environment.getExternalStoragePublicDirectory(type).absolutePath, icon)

    return listOf(
        HomeEntry("Internal storage", Environment.getExternalStorageDirectory().absolutePath, Icons.Filled.Folder),
        publicDir(Environment.DIRECTORY_DOWNLOADS, "Downloads", Icons.Filled.Download),
        publicDir(Environment.DIRECTORY_DOCUMENTS, "Documents", Icons.Filled.Description),
        publicDir(Environment.DIRECTORY_PICTURES, "Pictures", Icons.Filled.Image),
        publicDir(Environment.DIRECTORY_MUSIC, "Music", Icons.Filled.AudioFile),
        publicDir(Environment.DIRECTORY_MOVIES, "Movies", Icons.Filled.VideoFile)
    )
}
