@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class
)

package com.kerneldroid.karchiver.presentation.browser

import android.os.Environment
import android.content.pm.ResolveInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kerneldroid.karchiver.data.CompressFormat
import com.kerneldroid.karchiver.data.FileItem
import com.kerneldroid.karchiver.data.FormatRegistry
import com.kerneldroid.karchiver.data.FileSystemRepository
import com.kerneldroid.karchiver.data.RAR_DISABLED_MESSAGE
import com.kerneldroid.karchiver.data.isRarArchive
import com.kerneldroid.karchiver.data.SortBy
import com.kerneldroid.karchiver.data.normalizeArchiveName
import com.kerneldroid.karchiver.presentation.components.RoundedTopScaffold
import com.kerneldroid.karchiver.presentation.components.detectBarHold
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CreateKind { FOLDER, FILE }

private const val SCROLL_TOP_JUMP_THRESHOLD = 12

@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    confirmDelete: Boolean = true,
    rarEnabled: Boolean = false,
    barLifted: Boolean,
    onToggleBar: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val archiveOpActive by vm.archiveOpActive.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val verify by vm.verify.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var propsFile by remember { mutableStateOf<File?>(null) }
    var openWithFile by remember { mutableStateOf<File?>(null) }
    var openWithApps by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
    var pendingExtract by remember { mutableStateOf<File?>(null) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val showScrollTop by remember {
        derivedStateOf {
            if (state.viewMode == ViewMode.LIST) listState.firstVisibleItemIndex > 3
            else gridState.firstVisibleItemIndex > 5
        }
    }

    val selectedItems = state.items.filter { state.selected.contains(it.file.absolutePath) }
    val singleArchive = selectedItems.singleOrNull()?.takeIf { FormatRegistry.isArchive(it.extension) }

    fun notifyRarDisabled() {
        scope.launch {
            val res = snackbar.showSnackbar(RAR_DISABLED_MESSAGE, actionLabel = "Settings")
            if (res == SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    val handleItemClick: (FileItem) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        when {
            state.isSelectionMode -> vm.toggleSelect(item.file.absolutePath)
            item.isDirectory -> vm.navigateTo(item.file)
            FormatRegistry.isArchive(item.extension) -> {
                if (isRarArchive(item.file) && !rarEnabled) notifyRarDisabled()
                else pendingExtract = item.file
            }
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

    RoundedTopScaffold(
        barLifted = barLifted,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!searchActive && !state.isSelectionMode && vm.clipboard == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScrollTopButton(
                        visible = showScrollTop,
                        onClick = {
                            scope.launch {
                                if (state.viewMode == ViewMode.LIST) {
                                    if (listState.firstVisibleItemIndex > SCROLL_TOP_JUMP_THRESHOLD) {
                                        listState.scrollToItem(SCROLL_TOP_JUMP_THRESHOLD)
                                    }
                                    listState.animateScrollToItem(0)
                                } else {
                                    if (gridState.firstVisibleItemIndex > SCROLL_TOP_JUMP_THRESHOLD) {
                                        gridState.scrollToItem(SCROLL_TOP_JUMP_THRESHOLD)
                                    }
                                    gridState.animateScrollToItem(0)
                                }
                            }
                        }
                    )
                    CreateFabMenu(
                        onCreateFolder = { createKind = CreateKind.FOLDER },
                        onCreateFile = { createKind = CreateKind.FILE }
                    )
                }
            }
        },
        topBar = {
            Box(
                Modifier.detectBarHold {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleBar()
                }
            ) {
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
                        canGoUp = vm.canGoUp(),
                        onNavigateUp = { vm.navigateUp() },
                        onOpenDrawer = onOpenDrawer,
                        onToggleSearch = { searchActive = true },
                        onOpenSort = { showSortSheet = true }
                    )
                    Breadcrumbs(current = state.currentDir, onNavigate = vm::navigateTo)
                }
            }
            }
        },
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
                                listState = listState,
                                onItemClick = handleItemClick,
                                onItemLongClick = handleItemLongClick
                            )
                            else -> FileGrid(
                                state = state,
                                gridState = gridState,
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
                SelectionBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
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
                        if (confirmDelete) showDeleteConfirm = true
                        else vm.deleteSelection { r ->
                            scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Deleted" else "Delete failed") }
                        }
                    },
                    onCompress = { showCompressDialog = true },
                    onExtract = {
                        val target = singleArchive?.file
                        if (target != null && isRarArchive(target) && !rarEnabled) notifyRarDisabled()
                        else pendingExtract = target
                    },
                    onOpenOverflow = { showOverflow = true },
                    overflowContent = {
                        val files = selectedItems.map { it.file }
                        val single = files.singleOrNull()
                        FileOverflowMenu(
                            expanded = showOverflow,
                            onDismiss = { showOverflow = false },
                            single = single != null,
                            onProperties = { if (single != null) propsFile = single },
                            onShare = {
                                shareFiles(context, files).onFailure {
                                    scope.launch { snackbar.showSnackbar("Cannot share") }
                                }
                            },
                            onOpenWith = {
                                if (single != null) {
                                    scope.launch {
                                        val mime = if (single.isDirectory) "*/*"
                                        else FormatRegistry.forExtension(single.extension).mime
                                        openWithApps = queryOpenWith(context, single, mime)
                                        openWithFile = single
                                    }
                                }
                            },
                            onCopyPath = {
                                copyPaths(context, files)
                                scope.launch {
                                    snackbar.showSnackbar(if (files.size == 1) "Path copied" else "Paths copied")
                                }
                            }
                        )
                    }
                )
                ClipboardFloatingBar(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, bottom = 16.dp),
                    visible = !state.isSelectionMode && vm.clipboard != null,
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
    }

    if (showSortSheet) {
        SortSheet(state = state, vm = vm, onDismiss = { showSortSheet = false })
    }

    propsFile?.let { file ->
        PropertiesSheet(
            file = file,
            repo = remember { FileSystemRepository() },
            elevated = state.elevationMode != "off",
            onChmod = { mode, onDone ->
                vm.chmodFile(file, mode) { r ->
                    scope.launch {
                        snackbar.showSnackbar(if (r.isSuccess) "Permissions updated" else "Could not set permissions")
                    }
                    onDone(r)
                }
            },
            onDismiss = { propsFile = null }
        )
    }

    val openWithTarget = openWithFile
    if (openWithTarget != null) {
        OpenWithDialog(
            fileName = openWithTarget.name,
            apps = openWithApps,
            packageManager = context.packageManager,
            onDismiss = { openWithFile = null },
            onPick = { app ->
                val mime = if (openWithTarget.isDirectory) "*/*"
                else FormatRegistry.forExtension(openWithTarget.extension).mime
                launchOpenWith(context, openWithTarget, mime, app).onFailure {
                    scope.launch { snackbar.showSnackbar("Cannot open with this app") }
                }
                openWithFile = null
            }
        )
    }

    if (showDeleteConfirm) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (count == 1) "Delete 1 item?" else "Delete $count items?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteSelection { r ->
                        scope.launch { snackbar.showSnackbar(if (r.isSuccess) "Deleted" else "Delete failed") }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showCompressDialog) {
        var name by rememberSaveable { mutableStateOf("archive.zip") }
        var password by remember { mutableStateOf("") }
        var format by remember { mutableStateOf(CompressFormat.ZIP) }
        val formatScroll = rememberScrollState()
        val finalName = normalizeArchiveName(name.ifBlank { "archive" }, format)
        AlertDialog(
            onDismissRequest = { showCompressDialog = false },
            icon = { Icon(Icons.Filled.Archive, null) },
            title = { Text("Compress to archive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(formatScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompressFormat.entries.forEach { entry ->
                            FilterChip(
                                selected = format == entry,
                                onClick = {
                                    format = entry
                                    name = normalizeArchiveName(name.ifBlank { "archive" }, entry)
                                },
                                label = { Text(entry.label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Archive name") },
                        singleLine = true
                    )
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password (optional)"
                    )
                    if (format.supportsPassword) {
                        Text(
                            "Password protection uses AES-256 for ZIP and 7Z.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Password is not supported for ${format.label} archives.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!format.supportsPassword && password.isNotEmpty()) {
                        Text(
                            "Compression with a password will fail for ${format.label}. Clear the password or pick ZIP or 7Z.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "Will be created: ${state.currentDir.absolutePath}/$finalName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val chosenName = finalName
                    val chosenPassword = password
                    val chosenFormat = format
                    showCompressDialog = false
                    vm.compressSelection(chosenName, chosenFormat, chosenPassword) { r ->
                        scope.launch {
                            snackbar.showSnackbar(vm.archiveOpMessage(r.exceptionOrNull(), "Archive created", "Compression failed"))
                        }
                    }
                }) { Text("Compress") }
            },
            dismissButton = { TextButton(onClick = { showCompressDialog = false }) { Text("Cancel") } }
        )
    }

    pendingExtract?.let { file ->
        var password by remember(file.absolutePath) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingExtract = null },
            icon = { Icon(Icons.Filled.FolderOpen, null) },
            title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Extract this archive into the folder \"${file.nameWithoutExtension}\"?")
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password (if required)"
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val chosenPassword = password
                    pendingExtract = null
                    vm.extractArchive(file, chosenPassword) { r ->
                        scope.launch {
                            snackbar.showSnackbar(vm.archiveOpMessage(r.exceptionOrNull(), "Extracted", "Extraction failed"))
                        }
                    }
                }) { Text("Extract") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        pendingExtract = null
                        vm.openPreview(file)
                    }) { Text("Preview") }
                    TextButton(onClick = {
                        pendingExtract = null
                        vm.verifyArchive(file)
                    }) { Text("Verify") }
                    TextButton(onClick = { pendingExtract = null }) { Text("Cancel") }
                }
            }
        )
    }

    preview.file?.let { file ->
        PreviewSheet(
            fileName = file.name,
            preview = preview,
            onDismiss = vm::closePreview,
            onVerify = { vm.verifyArchive(file, preview.passwordUsed) },
            onUnlock = { password -> vm.openPreview(file, password) },
            onExtract = {
                val usedPassword = preview.passwordUsed
                vm.closePreview()
                vm.extractArchive(file, usedPassword) { r ->
                    scope.launch {
                        snackbar.showSnackbar(vm.archiveOpMessage(r.exceptionOrNull(), "Extracted", "Extraction failed"))
                    }
                }
            }
        )
    }

    LaunchedEffect(preview.error) {
        val err = preview.error
        if (preview.file != null && err != null && err != "Password required" && err != "Wrong password") {
            snackbar.showSnackbar(err)
        }
    }

    val verifyFile = verify.file
    val verifyReport = verify.report
    val verifyError = verify.error
    if (verifyFile != null && !verify.isLoading && (verifyReport != null || verifyError != null)) {
        var verifyPassword by remember(verifyFile.absolutePath, verify.passwordUsed) { mutableStateOf("") }
        val needsVerifyPassword = (verifyReport?.passwordRequired == true) ||
            verifyError == "Password required" || verifyError == "Wrong password"
        AlertDialog(
            onDismissRequest = vm::closeVerify,
            icon = {
                Icon(
                    if (verifyReport != null && verifyReport.ok) Icons.Filled.Verified else Icons.Filled.ErrorOutline,
                    null
                )
            },
            title = { Text(verifyFile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                when {
                    verifyError != null && !needsVerifyPassword -> Text(verifyError)
                    verifyReport == null && !needsVerifyPassword -> Text("Verification failed")
                    verifyReport != null && !verifyReport.passwordRequired && verifyReport.ok -> Text("Archive is OK (${verifyReport.entries} entries)")
                    verifyReport != null && !verifyReport.passwordRequired -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Archive is damaged (${verifyReport.failures.size} of ${verifyReport.entries} entries failed)")
                            verifyReport.failures.take(5).forEach { failure ->
                                Text(
                                    "${failure.name}: ${failure.reason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (verifyReport.failures.size > 5) {
                                Text(
                                    "...and ${verifyReport.failures.size - 5} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = verifyError ?: "Password required",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (verifyError == "Wrong password") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                            PasswordField(
                                value = verifyPassword,
                                onValueChange = { verifyPassword = it },
                                label = "Password"
                            )
                            Button(
                                onClick = {
                                    val entered = verifyPassword
                                    verifyPassword = ""
                                    vm.verifyArchive(verifyFile, entered)
                                },
                                enabled = verifyPassword.isNotEmpty()
                            ) { Text("Verify with password") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::closeVerify) { Text("OK") }
            }
        )
    }

    if (archiveOpActive) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Working with archive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Compressing or extracting...")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.cancelArchiveOp() }) { Text("Cancel") }
            }
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
private fun ScrollTopButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + scaleIn(),
        exit = slideOutVertically() + scaleOut()
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, "Scroll to top")
        }
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
    canGoUp: Boolean,
    onNavigateUp: () -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSort: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
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
                else -> IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, "Menu")
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = onOpenSort) { Icon(Icons.Filled.SortByAlpha, "Sort and view") }
        }
    )
}

@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
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
    listState: LazyListState,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
    ) {
        items(state.items.size, key = { state.items[it].file.absolutePath }) { index ->
            val item = state.items[index]
            FileRow(
                item = item,
                index = index,
                count = state.items.size,
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
    gridState: LazyGridState,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        state = gridState,
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
    index: Int,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = modifier,
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
    modifier: Modifier = Modifier,
    visible: Boolean,
    canExtract: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onOpenOverflow: (() -> Unit)? = null,
    overflowContent: (@Composable () -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                toolbarContentColor = MaterialTheme.colorScheme.onSurface
            ),
            expandedShadowElevation = 6.dp,
            collapsedShadowElevation = 6.dp,
            content = {
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
                IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, "Cut") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = onCompress) { Icon(Icons.Filled.Archive, "Compress") }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canExtract) {
                        FilledTonalIconButton(
                            onClick = onExtract,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Filled.FolderOpen, "Extract")
                        }
                    }
                    if (onOpenOverflow != null) {
                        Box {
                            IconButton(onClick = onOpenOverflow) {
                                Icon(Icons.Filled.MoreVert, "More actions")
                            }
                            overflowContent?.invoke()
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ClipboardFloatingBar(
    visible: Boolean,
    count: Int,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = onPaste,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste ($count)")
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Cancel", Modifier.size(18.dp))
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
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Password"
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (visible) "Hide password" else "Show password"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PreviewSheet(
    fileName: String,
    preview: ArchivePreviewUiState,
    onDismiss: () -> Unit,
    onVerify: () -> Unit,
    onUnlock: (String) -> Unit,
    onExtract: () -> Unit
) {
    var password by remember(fileName) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onVerify) { Text("Verify") }
                FilledTonalButton(onClick = onExtract) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Extract")
                }
            }
            val listing = preview.listing
            val needsPassword = preview.error == "Password required" ||
                preview.error == "Wrong password" ||
                (listing != null && listing.encrypted && preview.passwordUsed.isEmpty())
            when {
                preview.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }
                preview.error != null && !needsPassword -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = preview.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                needsPassword -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = preview.error ?: "Password required",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (preview.error == "Wrong password") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PasswordField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Password"
                        )
                        Button(
                            onClick = {
                                val entered = password
                                password = ""
                                onUnlock(entered)
                            },
                            enabled = password.isNotEmpty()
                        ) { Text("Unlock") }
                    }
                }
                listing != null && listing.entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Archive is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                listing != null -> {
                    Text(
                        text = "${listing.entries.size} items",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(listing.entries, key = { it.name }) { entry ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        if (entry.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = if (entry.isDir) "Folder" else formatSize(entry.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            ) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
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
