@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.hmndev.ziparc.presentation.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmndev.ziparc.data.history.HistoryEntry
import com.hmndev.ziparc.data.history.HistorySection
import com.hmndev.ziparc.data.history.HistoryTypeFilter
import com.hmndev.ziparc.data.history.groupHistory
import com.hmndev.ziparc.data.history.relativeTime
import com.hmndev.ziparc.presentation.browser.copyPath
import com.hmndev.ziparc.presentation.components.FileSearchField
import com.hmndev.ziparc.presentation.components.RoundedTopScaffold
import com.hmndev.ziparc.presentation.components.detectBarHold
import java.io.File
import kotlinx.coroutines.launch

private sealed interface HistoryRow {
    data class Header(val section: HistorySection, val count: Int) : HistoryRow
    data class Entry(val entry: HistoryEntry, val index: Int, val count: Int) : HistoryRow
}

private fun sectionLabel(section: HistorySection): String = when (section) {
    HistorySection.TODAY -> "Today"
    HistorySection.YESTERDAY -> "Yesterday"
    HistorySection.EARLIER -> "Earlier"
}

private fun filterLabel(filter: HistoryTypeFilter): String = when (filter) {
    HistoryTypeFilter.ALL -> "All"
    HistoryTypeFilter.FOLDERS -> "Folders"
    HistoryTypeFilter.FILES -> "Files"
}

private fun buildRows(sections: List<Pair<HistorySection, List<HistoryEntry>>>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    sections.forEach { (section, entries) ->
        rows += HistoryRow.Header(section, entries.size)
        entries.forEachIndexed { index, entry ->
            rows += HistoryRow.Entry(entry, index, entries.size)
        }
    }
    return rows
}

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenEntry: (HistoryEntry) -> Boolean,
    barLifted: Boolean = false,
    onToggleBar: () -> Unit = {}
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vm: HistoryViewModel = viewModel()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val hasAny by vm.hasAnyEntries.collectAsStateWithLifecycle()
    val query by vm.queryText.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val newestFirst by vm.sortNewestFirst.collectAsStateWithLifecycle()
    var searchActive by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    val now = remember(entries) { System.currentTimeMillis() }
    val rows = remember(entries, now) { buildRows(groupHistory(entries, now)) }

    fun open(entry: HistoryEntry) {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        if (!onOpenEntry(entry)) {
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = "This item is no longer available",
                    actionLabel = "Remove"
                )
                if (result == SnackbarResult.ActionPerformed) vm.remove(entry.path)
            }
        }
    }

    RoundedTopScaffold(
        barLifted = barLifted,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                if (searchActive) {
                    FileSearchField(
                        query = query,
                        onQueryChange = vm::setQuery,
                        onClose = {
                            searchActive = false
                            vm.setQuery("")
                        },
                        placeholder = "Search history"
                    )
                } else {
                    TopAppBar(
                        modifier = Modifier.detectBarHold {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleBar()
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        ),
                        title = { Text("History") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(Icons.Filled.Search, "Search history")
                            }
                            IconButton(onClick = { confirmClear = true }, enabled = hasAny) {
                                Icon(Icons.Filled.DeleteSweep, "Clear history")
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryTypeFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            vm.setFilter(option)
                        },
                        label = { Text(filterLabel(option)) }
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = vm::toggleSort) {
                    Icon(
                        Icons.Filled.Sort,
                        if (newestFirst) "Newest first" else "Oldest first"
                    )
                }
            }
            if (rows.isEmpty()) {
                HistoryEmptyState(hasAny = hasAny)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    rows.forEach { row ->
                        when (row) {
                            is HistoryRow.Header -> stickyHeader(key = "header-${row.section}") {
                                SectionLabel(sectionLabel(row.section), row.count)
                            }
                            is HistoryRow.Entry -> item(key = row.entry.path) {
                                HistoryItemRow(
                                    entry = row.entry,
                                    index = row.index,
                                    count = row.count,
                                    now = now,
                                    menuOpen = openMenu == row.entry.path,
                                    onMenuOpenChange = { open ->
                                        openMenu = if (open) row.entry.path else null
                                    },
                                    onClick = { open(row.entry) },
                                    onCopyPath = {
                                        copyPath(context, File(row.entry.path))
                                        scope.launch { snackbar.showSnackbar("Path copied") }
                                    },
                                    onRemove = { vm.remove(row.entry.path) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes all entries from the history. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        vm.clear()
                        scope.launch { snackbar.showSnackbar("History cleared") }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryItemRow(
    entry: HistoryEntry,
    index: Int,
    count: Int,
    now: Long,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onCopyPath: () -> Unit,
    onRemove: () -> Unit
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        leadingContent = {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder
                else Icons.AutoMirrored.Filled.InsertDriveFile,
                null
            )
        },
        supportingContent = {
            Text(
                text = entry.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(Icons.Filled.MoreVert, "More options")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy path") },
                        onClick = {
                            onMenuOpenChange(false)
                            onCopyPath()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from history") },
                        onClick = {
                            onMenuOpenChange(false)
                            onRemove()
                        }
                    )
                }
            }
        }
    ) {
        Column {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = buildString {
                    append(relativeTime(entry.lastVisitedAt, now))
                    if (entry.visitCount > 1) append(" • ${entry.visitCount} visits")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HistoryEmptyState(hasAny: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasAny) "No matching entries" else "No history yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (hasAny) "Try a different search or filter"
            else "Folders and files you open will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
