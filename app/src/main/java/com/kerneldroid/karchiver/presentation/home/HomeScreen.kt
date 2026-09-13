package com.kerneldroid.karchiver.presentation.home

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class HomeEntry(val title: String, val path: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onOpenPath: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val entries = remember { homeEntries() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KArchiver") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
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
                Text(
                    "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
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
