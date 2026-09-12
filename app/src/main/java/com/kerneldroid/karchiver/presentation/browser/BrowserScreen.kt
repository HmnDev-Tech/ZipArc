@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class
)

package com.kerneldroid.karchiver.presentation.browser

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kerneldroid.karchiver.data.FileItem
import com.kerneldroid.karchiver.data.FormatRegistry
import com.kerneldroid.karchiver.data.SortBy
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CreateKind { FOLDER, FILE }

@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    showMainMenu: Boolean,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var pendingExtract by remember { mutableStateOf<File?>(null) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    val pullRefreshState = rememberPullToRefreshState()

    val selectedItems = state.items.filter { state.selected.contains(it.file.absolutePath) }
    val singleArchive = selectedItems.singleOrNull()?.takeIf { FormatRegistry.isArchive(it.extension) }

    val handleItemClick: (FileItem) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        when {
            state.isSelectionMode -> vm.toggleSelect(item.file.absolutePath)
            item.isDirectory -> vm.navigateTo(item.file)
            FormatRegistry.isArchive(item.extension) -> pendingExtract = item.file
            else -> vm.openFile(context, item.file)
        }
    }

    val handleItemLongClick: (FileItem) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.toggleSelect(item.file.absolutePath)
    }

    BackHandler(enabled = searchActive || state.isSelectionMode || vm.canGoUp()) {
        when {
            searchActive -> {
                searchActive = false
                vm.setQuery("")
            }
            state.isSelectionMode -> vm.clearSelection()
            else -> {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                vm.navigateUp()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!searchActive && !state.isSelectionMode) {
                CreateFabMenu(
                    onCreateFolder = { createKind = CreateKind.FOLDER },
                    onCreateFile = { createKind = CreateKind.FILE }
                )
            }
        },
        topBar = {
            if (searchActive) {
                SearchTopBar(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onClose = { searchActive = false; vm.setQuery("") }
                )
            } else if (state.isSelectionMode) {
                SelectionTopBar(
                    count = state.selected.size,
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll
                )
            } else {
                Column {
                    BrowserTopBar(
                        current = state.currentDir,
                        itemCount = state.items.size,
                        showMainMenu = showMainMenu,
                        canGoUp = vm.canGoUp(),
                        onNavigateUp = { vm.navigateUp() },
                        onOpenHome = onOpenHome,
                        onOpenSettings = onOpenSettings,
                        onToggleSearch = { searchActive = true },
                        onOpenSort = { showSortSheet = true }
                    )
                    Breadcrumbs(current = state.currentDir, onNavigate = vm::navigateTo)
                }
            }
        },
        bottomBar = {
            SelectionBottomBar(
                visible = state.isSelectionMode,
                canExtract = singleArchive != null,
                onCopy = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    vm.copySelection()
                },
                onCut = {
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    vm.cutSelection()
                },
                onDelete = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.deleteSelection { r ->
                        scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Deleted" else "Delete failed") }
                    }
                },
                onCompress = { showCompressDialog = true },
                onExtract = { pendingExtract = singleArchive?.file }
            )
            if (!state.isSelectionMode && vm.clipboard != null) {
                ClipboardBottomBar(
                    visible = true,
                    count = vm.clipboard?.first?.size ?: 0,
                    onPaste = {
                        vm.paste { r ->
                            scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Pasted" else "Paste failed") }
                        }
                    },
                    onCancel = vm::cancelClipboard
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.refresh()
                    },
                    state = pullRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {}
                ) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            state.isLoading && state.items.isEmpty() -> CenterLoading()
                            state.items.isEmpty() -> EmptyState(query = state.query)
                            state.viewMode == ViewMode.LIST -> FileList(
                                state = state,
                                onItemClick = handleItemClick,
                                onItemLongClick = handleItemLongClick
                            )
                            else -> FileGrid(
                                state = state,
                                onItemClick = handleItemClick,
                                onItemLongClick = handleItemLongClick
                            )
                        }
                    }
                }
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullRefreshState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                )
            }
        }
    }

    if (showSortSheet) {
        SortSheet(state = state, vm = vm, onDismiss = { showSortSheet = false })
    }

    if (showCompressDialog) {
        var name by rememberSaveable { mutableStateOf("archive.zip") }
        AlertDialog(
            onDismissRequest = { showCompressDialog = false },
            icon = { Icon(Icons.Filled.Archive, null) },
            title = { Text("Compress to archive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Archive name") },
                        singleLine = true
                    )
                    Text(
                        "Will be created: ${state.currentDir.absolutePath}/$name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCompressDialog = false
                    vm.compressSelection(name) { r ->
                        scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Archive created" else "Compression failed") }
                    }
                }) { Text("Compress") }
            },
            dismissButton = { TextButton(onClick = { showCompressDialog = false }) { Text("Cancel") } }
        )
    }

    pendingExtract?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingExtract = null },
            icon = { Icon(Icons.Filled.FolderOpen, null) },
            title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text("Extract this archive into the folder \"${file.nameWithoutExtension}\"?") },
            confirmButton = {
                Button(onClick = {
                    pendingExtract = null
                    vm.extractArchive(file) { r ->
                        scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Extracted" else "Extraction failed") }
                    }
                }) { Text("Extract") }
            },
            dismissButton = { TextButton(onClick = { pendingExtract = null }) { Text("Cancel") } }
        )
    }

    createKind?.let { kind ->
        var name by rememberSaveable(kind) {
            mutableStateOf(if (kind == CreateKind.FOLDER) "New folder" else "New file.txt")
        }
        AlertDialog(
            onDismissRequest = { createKind = null },
            icon = {
                Icon(
                    if (kind == CreateKind.FOLDER) Icons.Filled.CreateNewFolder else Icons.AutoMirrored.Filled.NoteAdd,
                    null
                )
            },
            title = { Text(if (kind == CreateKind.FOLDER) "New folder" else "New file") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = kind
                    createKind = null
                    if (target == CreateKind.FOLDER) {
                        vm.createFolder(name) { r ->
                            scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Folder created" else "Could not create folder") }
                        }
                    } else {
                        vm.createFile(name) { r ->
                            scope.launch { snackbar.showSnackbar(if (r.isSuccess) "File created" else "Could not create file") }
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createKind = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CreateFabMenu(onCreateFolder: () -> Unit, onCreateFile: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it }
            ) {
                val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
                Icon(Icons.Filled.Add, "Create", Modifier.rotate(rotation))
            }
        }
    ) {
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onCreateFolder() },
            icon = { Icon(Icons.Filled.CreateNewFolder, null) },
            text = { Text("New folder") }
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onCreateFile() },
            icon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
            text = { Text("New file") }
        )
    }
}

@Composable
private fun BrowserTopBar(
    current: File,
    itemCount: Int,
    showMainMenu: Boolean,
    canGoUp: Boolean,
    onNavigateUp: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSort: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = current.name.ifEmpty { "/" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$itemCount items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        navigationIcon = {
            when {
                canGoUp -> IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                }
                showMainMenu -> IconButton(onClick = onOpenHome) {
                    Icon(Icons.Filled.Home, "Main menu")
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = onOpenSort) { Icon(Icons.Filled.SortByAlpha, "Sort and view") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }
    )
}

@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search files and archives...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, null) }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        }
    )
}

@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel") }
        },
        title = { Text("Selected: $count", style = MaterialTheme.typography.titleLarge) },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
        }
    )
}

@Composable
private fun Breadcrumbs(current: File, onNavigate: (File) -> Unit) {
    val segments = remember(current) { ancestorsOf(current) }
    if (segments.size <= 1) return
    val scroll = rememberScrollState()
    LaunchedEffect(current.absolutePath) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, (file, label) ->
            if (index > 0) {
                Icon(
                    Icons.Filled.ChevronRight, null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val isCurrent = index == segments.lastIndex
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(enabled = !isCurrent, onClick = { onNavigate(file) })
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun FileList(
    state: BrowserUiState,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(state.items, key = { it.file.absolutePath }) { item ->
            FileRow(
                item = item,
                selected = state.selected.contains(item.file.absolutePath),
                onClick = { onItemClick(item) },
                onLongClick = { onItemLongClick(item) },
                modifier = Modifier.animateItem()
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FileGrid(
    state: BrowserUiState,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.items, key = { it.file.absolutePath }) { item ->
            FileGridCard(
                item = item,
                selected = state.selected.contains(item.file.absolutePath),
                onClick = { onItemClick(item) },
                onLongClick = { onItemLongClick(item) },
                modifier = Modifier.animateItem()
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(if (selected) 16.dp else 12.dp)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        ListItem(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(if (item.isDirectory) CircleShape else RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (item.isDirectory) Icons.Filled.Folder else item.format.icon,
                        null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            supportingContent = {
                Text(
                    metaText(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                when {
                    selected -> Icon(
                        Icons.Filled.CheckCircle, "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    item.isDirectory -> Icon(
                        Icons.Filled.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FileGridCard(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(if (selected) 20.dp else 16.dp)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (item.isDirectory) Icons.Filled.Folder else item.format.icon,
                    null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                item.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                if (item.isDirectory) "Folder" else item.extension.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected) {
                Icon(Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SelectionBottomBar(
    visible: Boolean,
    canExtract: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                    toolbarContainerColor = Color.Transparent,
                    toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                expandedShadowElevation = 0.dp,
                collapsedShadowElevation = 0.dp,
                content = {
                    IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
                    IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, "Cut") }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onCompress) { Icon(Icons.Filled.Archive, "Compress") }
                },
                trailingContent = if (canExtract) {
                    {
                        FilledTonalButton(onClick = onExtract) {
                            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Extract")
                        }
                    }
                } else null
            )
        }
    }
}

@Composable
private fun ClipboardBottomBar(visible: Boolean, count: Int, onPaste: () -> Unit, onCancel: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("$count in clipboard", style = MaterialTheme.typography.labelLarge)
                    FilledTonalButton(onClick = onPaste) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
                }
            }
        }
    }
}

@Composable
private fun SortSheet(state: BrowserUiState, vm: BrowserViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sort and view", style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SortBy.entries.forEachIndexed { index, sort ->
                    SegmentedButton(
                        selected = state.sortBy == sort,
                        onClick = { vm.setSort(sort) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SortBy.entries.size),
                        label = { Text(sortLabel(sort), maxLines = 1) }
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.ascending,
                    onClick = { vm.setAscending(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Filled.ArrowUpward, null) },
                    label = { Text("Ascending", maxLines = 1) }
                )
                SegmentedButton(
                    selected = !state.ascending,
                    onClick = { vm.setAscending(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Filled.ArrowDownward, null) },
                    label = { Text("Descending", maxLines = 1) }
                )
            }
            Text("View", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.viewMode == ViewMode.LIST,
                    onClick = { vm.setViewMode(ViewMode.LIST) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Filled.ViewAgenda, null) },
                    label = { Text("List") }
                )
                SegmentedButton(
                    selected = state.viewMode == ViewMode.GRID,
                    onClick = { vm.setViewMode(ViewMode.GRID) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Filled.GridView, null) },
                    label = { Text("Grid") }
                )
            }
        }
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

@Composable
private fun EmptyState(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.FolderOpen, null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (query.isBlank()) "Folder is empty" else "Nothing found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ancestorsOf(current: File): List<Pair<File, String>> {
    val root = Environment.getExternalStorageDirectory()
    val stack = ArrayDeque<File>()
    var f: File? = current
    while (f != null && f.absolutePath.length >= root.absolutePath.length) {
        stack.addFirst(f)
        if (f.absolutePath == root.absolutePath) break
        f = f.parentFile
    }
    return stack.map { file ->
        file to when {
            file.absolutePath == root.absolutePath -> "Internal storage"
            file.parentFile == null -> "/"
            else -> file.name
        }
    }
}

private fun metaText(item: FileItem): String {
    val type = if (item.isDirectory) "Folder" else item.extension.uppercase().ifEmpty { "File" }
    val size = if (item.isDirectory) "" else " | ${formatSize(item.size)}"
    return "$type$size | ${formatDate(item.lastModified)}"
}

private fun sortLabel(sort: SortBy): String = when (sort) {
    SortBy.NAME -> "Name"
    SortBy.DATE -> "Date"
    SortBy.SIZE -> "Size"
    SortBy.TYPE -> "Type"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0; if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0; if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0; return String.format("%.2f GB", gb)
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))
