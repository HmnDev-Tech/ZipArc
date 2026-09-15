@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class
)

package com.kerneldroid.karchiver.presentation.browser

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kerneldroid.karchiver.data.CompressFormat
import com.kerneldroid.karchiver.data.FileItem
import com.kerneldroid.karchiver.data.FormatRegistry
import com.kerneldroid.karchiver.data.archive.ArchiveService
import com.kerneldroid.karchiver.data.isRarArchive
import com.kerneldroid.karchiver.data.normalizeArchiveName
import com.kerneldroid.karchiver.data.storage.SafFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun ArchiveExplorerRoute(
    archive: File,
    password: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onExitToFolder: (File) -> Unit = {}
) {
    val vm: ArchiveExplorerViewModel = viewModel(key = "explorer:" + archive.absolutePath) {
        ArchiveExplorerViewModel(archive, password)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val writable = archive.isFile && archive.canWrite()
    val canEdit = writable && !isRarArchive(archive)
    val selectionMode = state.selected.isNotEmpty()

    var viewMode by rememberSaveable { mutableStateOf(ViewMode.LIST) }
    var addError by remember(archive.absolutePath) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showDestMenu by remember { mutableStateOf(false) }
    var destCut by remember { mutableStateOf(false) }
    var propsTarget by remember { mutableStateOf<String?>(null) }
    var pendingTreeCut by remember { mutableStateOf(false) }
    var openWithFile by remember { mutableStateOf<File?>(null) }
    var openWithApps by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    BackHandler {
        if (!vm.navigateUp()) onClose()
    }

    val rowsByPath = remember(state.rows) { state.rows.associateBy { it.path } }
    val items = remember(state.rows) {
        state.rows.map { row ->
            val vFile = if (row.path.isEmpty()) archive else File(archive, row.path)
            FileItem(
                file = vFile,
                name = row.displayName,
                isDirectory = row.isDir,
                extension = if (row.isDir) "" else row.displayName.substringAfterLast('.', "").lowercase(),
                size = row.size,
                lastModified = 0
            )
        }
    }
    val virtualSelected = remember(state.selected) {
        state.selected.map { path ->
            if (path.isEmpty()) archive.absolutePath else File(archive, path).absolutePath
        }.toSet()
    }
    val browserState = remember(items, virtualSelected, viewMode) {
        BrowserUiState(items = items, selected = virtualSelected, viewMode = viewMode)
    }

    fun entryPathOf(item: FileItem): String {
        val abs = item.file.absolutePath
        val base = archive.absolutePath
        return if (abs == base) "" else abs.removePrefix(base + "/")
    }

    fun selectedVirtualFiles(): List<File> {
        return state.selected.map { path ->
            if (path.isEmpty()) archive else File(archive, path)
        }
    }

    suspend fun extractPaths(paths: List<String>, destDir: File): Boolean {
        if (paths.isEmpty()) return false
        val prev = vm.state.value.selected.toList()
        vm.clearSelection()
        for (path in paths) vm.toggleSelect(path)
        val ok = vm.extractSelected(destDir)
        vm.clearSelection()
        for (path in prev) vm.toggleSelect(path)
        return ok
    }

    suspend fun stageSelectionToCache(tag: String): File? {
        val dir = File(context.cacheDir, "explorer-" + tag + "-" + System.nanoTime())
        withContext(Dispatchers.IO) { dir.mkdirs() }
        return if (vm.extractSelected(dir)) dir else null
    }

    fun openStagedFile(file: File) {
        val ext = file.extension.lowercase()
        if (FormatRegistry.isArchive(ext)) return
        val mime = FormatRegistry.forExtension(ext).mime
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (_: Exception) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(fallback, file.name))
            } catch (_: Exception) {
            }
        }
    }

    fun openEntryFile(entryPath: String) {
        scope.launch {
            val dir = File(context.cacheDir, "explorer-open-" + System.nanoTime())
            withContext(Dispatchers.IO) { dir.mkdirs() }
            if (!extractPaths(listOf(entryPath), dir)) return@launch
            val staged = File(dir, entryPath.trimStart('/'))
            if (staged.isFile) openStagedFile(staged)
        }
    }

    fun shareSelection() {
        scope.launch {
            val dir = stageSelectionToCache("share") ?: return@launch
            val files = state.selected.mapNotNull { path ->
                File(dir, path.trimStart('/')).takeIf { it.isFile }
            }
            if (files.isEmpty()) {
                addError = "Nothing to share"
                return@launch
            }
            shareFiles(context, files).onFailure {
                addError = "Cannot share"
            }
        }
    }

    fun openWithSelection() {
        val path = state.selected.singleOrNull() ?: return
        val row = rowsByPath[path] ?: return
        if (row.isDir) return
        scope.launch {
            val dir = File(context.cacheDir, "explorer-openwith-" + System.nanoTime())
            withContext(Dispatchers.IO) { dir.mkdirs() }
            if (!extractPaths(listOf(path), dir)) return@launch
            val staged = File(dir, path.trimStart('/'))
            if (!staged.isFile) return@launch
            val mime = FormatRegistry.forExtension(staged.extension).mime
            openWithApps = queryOpenWith(context, staged, mime)
            openWithFile = staged
        }
    }

    fun extractSelectionHere(thenDelete: Boolean) {
        scope.launch {
            val dest = File(archive.parentFile, archive.nameWithoutExtension)
            withContext(Dispatchers.IO) { dest.mkdirs() }
            val ok = if (state.selected.isEmpty()) vm.extractAll(dest) else vm.extractSelected(dest)
            if (ok && thenDelete) vm.deleteSelected()
        }
    }

    fun showDestMenu(cut: Boolean) {
        destCut = cut
        showDestMenu = true
    }

    val addPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var failures = 0
                val staged = ArrayList<File>()
                val stageDir = File(context.cacheDir, "explorer-add-" + System.nanoTime())
                withContext(Dispatchers.IO) {
                    stageDir.mkdirs()
                    for (uri in uris) {
                        try {
                            val name = queryDisplayName(context, uri) ?: ("file-" + System.nanoTime())
                            val dst = uniqueChild(stageDir, name)
                            context.contentResolver.openInputStream(uri)?.use { ins ->
                                dst.outputStream().use { out -> ins.copyTo(out) }
                            } ?: throw IOException("Cannot read file")
                            staged.add(dst)
                        } catch (_: Exception) {
                            failures++
                        }
                    }
                }
                if (staged.isNotEmpty()) vm.addFiles(staged)
                withContext(Dispatchers.IO) {
                    runCatching { stageDir.deleteRecursively() }
                }
                addError = if (failures > 0) "Could not add $failures file(s)" else null
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingTreeCut = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val rawDir = treeUriToPrimaryPath(uri)
            if (rawDir != null && (rawDir.isDirectory || rawDir.mkdirs()) && rawDir.canWrite()) {
                if (state.selected.isEmpty()) {
                    addError = "Selection changed"
                } else {
                    val ok = vm.extractSelected(rawDir)
                    if (ok && pendingTreeCut) vm.deleteSelected()
                }
            } else {
                val staging = File(context.cacheDir, "explorer-tree-" + System.nanoTime())
                withContext(Dispatchers.IO) { staging.mkdirs() }
                val ok = if (state.selected.isEmpty()) {
                    addError = "Selection changed"
                    false
                } else {
                    vm.extractSelected(staging)
                }
                if (ok) {
                    var failures = 0
                    val kids = withContext(Dispatchers.IO) { staging.listFiles() } ?: emptyArray()
                    for (kid in kids) {
                        if (!SafFs.copyIn(context, uri, "", kid)) failures++
                    }
                    if (failures > 0) {
                        addError = "Could not copy $failures file(s) to selected folder"
                    } else if (pendingTreeCut) {
                        vm.deleteSelected()
                    }
                }
                withContext(Dispatchers.IO) {
                    runCatching { staging.deleteRecursively() }
                }
            }
            pendingTreeCut = false
        }
    }

    val handleItemClick: (FileItem) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        val rel = entryPathOf(item)
        val row = rowsByPath[rel]
        if (row != null) {
            when {
                row.isDir -> vm.openDir(row.path)
                selectionMode -> vm.toggleSelect(row.path)
                else -> openEntryFile(row.path)
            }
        }
    }

    val handleItemLongClick: (FileItem) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val rel = entryPathOf(item)
        if (rowsByPath.containsKey(rel)) vm.toggleSelect(rel)
    }

    Column(modifier = modifier.fillMaxHeight()) {
        if (selectionMode) {
            SelectionTopBar(
                count = state.selected.size,
                onClose = vm::clearSelection,
                onSelectAll = {
                    for (row in state.rows) {
                        if (!state.selected.contains(row.path)) vm.toggleSelect(row.path)
                    }
                }
            )
        } else {
            TopAppBar(
                title = {
                    Text(
                        text = archive.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.insidePath.isEmpty()) onClose() else vm.navigateUp()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (canEdit) {
                        IconButton(onClick = { addPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Add, "Add files")
                        }
                    }
                    IconButton(onClick = {
                        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                    }) {
                        Icon(
                            if (viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewAgenda,
                            "Toggle view"
                        )
                    }
                }
            )
        }
        Breadcrumbs(
            current = if (state.insidePath.isEmpty()) archive else File(archive, state.insidePath),
            volumes = emptyList(),
            onNavigate = { f ->
                val abs = f.absolutePath
                val base = archive.absolutePath
                val rel = abs.removePrefix(base).trim('/')
                if (abs == base || abs.startsWith(base + "/")) vm.openDir(rel)
                else onExitToFolder(f)
            },
            onOpenVolumes = {}
        )
        if (state.isLoading) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
        val errorText = state.error ?: addError
        if (errorText != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.ErrorOutline, null, Modifier.size(20.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { vm.dismissError(); addError = null },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Close, "Dismiss", Modifier.size(18.dp))
                    }
                }
            }
        }
        if (!writable) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(20.dp))
                    Text(
                        text = "Archive is read-only",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading && state.rows.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
                state.rows.isEmpty() -> {
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
                                "Empty folder",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                viewMode == ViewMode.LIST -> FileList(
                    state = browserState,
                    listState = listState,
                    onItemClick = handleItemClick,
                    onItemLongClick = handleItemLongClick
                )
                else -> FileGrid(
                    state = browserState,
                    gridState = gridState,
                    onItemClick = handleItemClick,
                    onItemLongClick = handleItemLongClick
                )
            }
            Box(
                modifier = Modifier.align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                if (canEdit) {
                    SelectionBottomBar(
                        visible = selectionMode,
                        canExtract = selectionMode,
                        onCopy = { showDestMenu(false) },
                        onCut = { showDestMenu(true) },
                        onDelete = { showDeleteConfirm = true },
                        onCompress = { showCompressDialog = true },
                        onExtract = { showDestMenu(false) },
                        onOpenOverflow = { showOverflow = true },
                        overflowContent = {
                            FileOverflowMenu(
                                expanded = showOverflow,
                                onDismiss = { showOverflow = false },
                                single = state.selected.size == 1,
                                onProperties = { propsTarget = state.selected.singleOrNull() },
                                onShare = { shareSelection() },
                                onOpenWith = { openWithSelection() },
                                onCopyPath = { copyPaths(context, selectedVirtualFiles()) }
                            )
                        }
                    )
                } else if (selectionMode) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            toolbarContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        expandedShadowElevation = 6.dp,
                        collapsedShadowElevation = 6.dp,
                        content = {
                            IconButton(onClick = { showDestMenu(false) }) {
                                Icon(Icons.Filled.ContentCopy, "Copy")
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalIconButton(
                                    onClick = { showDestMenu(false) },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(Icons.Filled.FolderOpen, "Extract")
                                }
                                Box {
                                    IconButton(onClick = { showOverflow = true }) {
                                        Icon(Icons.Filled.MoreVert, "More actions")
                                    }
                                    FileOverflowMenu(
                                        expanded = showOverflow,
                                        onDismiss = { showOverflow = false },
                                        single = state.selected.size == 1,
                                        onProperties = { propsTarget = state.selected.singleOrNull() },
                                        onShare = { shareSelection() },
                                        onOpenWith = { openWithSelection() },
                                        onCopyPath = { copyPaths(context, selectedVirtualFiles()) }
                                    )
                                }
                            }
                        }
                    )
                }
                DropdownMenu(expanded = showDestMenu, onDismissRequest = { showDestMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Extract here") },
                        onClick = { showDestMenu = false; extractSelectionHere(destCut) }
                    )
                    DropdownMenuItem(
                        text = { Text("Choose folder") },
                        onClick = {
                            showDestMenu = false
                            pendingTreeCut = destCut
                            treePicker.launch(null)
                        }
                    )
                }
            }
        }
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
                    scope.launch { vm.deleteSelected() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showCompressDialog) {
        val firstBase = remember(state.selected) {
            state.rows.firstOrNull { state.selected.contains(it.path) }
                ?.displayName?.substringBeforeLast('.')?.ifBlank { "archive" } ?: "archive"
        }
        var name by rememberSaveable(firstBase) { mutableStateOf("$firstBase-archive.zip") }
        var format by remember { mutableStateOf(CompressFormat.ZIP) }
        var compressPassword by remember { mutableStateOf("") }
        var formatMenu by remember { mutableStateOf(false) }
        val finalName = normalizeArchiveName(name.ifBlank { "archive" }, format)
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
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box {
                        TextButton(onClick = { formatMenu = true }) {
                            Text(format.label)
                        }
                        DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("ZIP") },
                                onClick = { format = CompressFormat.ZIP; formatMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("7Z") },
                                onClick = { format = CompressFormat.SEVEN_Z; formatMenu = false }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = compressPassword,
                        onValueChange = { compressPassword = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Will be created: ${archive.parentFile?.absolutePath}/$finalName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val chosenName = finalName
                    val chosenFormat = format
                    val chosenPassword = compressPassword
                    showCompressDialog = false
                    scope.launch {
                        val staging = File(context.cacheDir, "explorer-compress-" + System.nanoTime())
                        withContext(Dispatchers.IO) { staging.mkdirs() }
                        if (vm.extractSelected(staging)) {
                            val files = withContext(Dispatchers.IO) {
                                staging.listFiles()?.toList()
                            } ?: emptyList()
                            if (files.isNotEmpty()) {
                                val dest = File(archive.parentFile, chosenName)
                                ArchiveService.startCompress(
                                    context,
                                    files,
                                    dest,
                                    chosenFormat,
                                    chosenPassword.ifEmpty { null },
                                    "off"
                                )
                            } else {
                                addError = "Nothing to compress"
                            }
                        }
                        vm.clearSelection()
                    }
                }) { Text("Compress") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressDialog = false }) { Text("Cancel") }
            }
        )
    }

    propsTarget?.let { target ->
        val entry = rowsByPath[target]
        EntryPropertiesSheet(
            entryPath = target,
            displayName = entry?.displayName ?: target.trimEnd('/').substringAfterLast('/'),
            isDir = entry?.isDir ?: false,
            size = entry?.size ?: 0L,
            canEdit = canEdit,
            onDismiss = { propsTarget = null },
            onSave = { newName ->
                scope.launch {
                    if (vm.renameEntry(target, newName)) propsTarget = null
                }
            }
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
                val mime = FormatRegistry.forExtension(openWithTarget.extension).mime
                launchOpenWith(context, openWithTarget, mime, app).onFailure {
                    addError = "Cannot open with this app"
                }
                openWithFile = null
            }
        )
    }
}

@Composable
private fun EntryPropertiesSheet(
    entryPath: String,
    displayName: String,
    isDir: Boolean,
    size: Long,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(entryPath) { mutableStateOf(displayName) }
    var validationError by remember(entryPath) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Properties", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    validationError = null
                },
                label = { Text("Name") },
                singleLine = true,
                enabled = canEdit,
                readOnly = !canEdit,
                isError = validationError != null,
                supportingText = {
                    val message = validationError
                        ?: if (canEdit) null else "Archive is read-only"
                    if (message != null) {
                        Text(
                            message,
                            color = if (validationError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            PropLine("Path", entryPath)
            PropLine("Type", if (isDir) "Folder" else "File")
            PropLine("Size", if (isDir) "Folder" else formatSize(size))
            if (canEdit) {
                Button(
                    onClick = {
                        val trimmed = name.trim()
                        validationError = when {
                            trimmed.isEmpty() -> "Name cannot be empty"
                            trimmed.contains("/") -> "Name cannot contain /"
                            else -> null
                        }
                        if (validationError == null) onSave(trimmed)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun PropLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (_: Exception) {
        null
    }
}

private fun uniqueChild(dir: File, name: String): File {
    val clean = name.ifBlank { "file" }
    var candidate = File(dir, clean)
    if (!candidate.exists()) return candidate
    val dot = clean.lastIndexOf('.')
    val base = if (dot > 0) clean.substring(0, dot) else clean
    val ext = if (dot > 0) clean.substring(dot) else ""
    var counter = 1
    while (candidate.exists() && counter < 9999) {
        counter++
        candidate = File(dir, base + "-" + counter + ext)
    }
    return candidate
}

private fun treeUriToPrimaryPath(uri: Uri): File? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val parts = docId.split(":")
        if (parts.size < 2 || !parts[0].equals("primary", ignoreCase = true)) return null
        File(Environment.getExternalStorageDirectory(), parts[1])
    } catch (_: Exception) {
        null
    }
}
