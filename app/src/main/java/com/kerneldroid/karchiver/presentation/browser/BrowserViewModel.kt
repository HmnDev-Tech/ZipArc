package com.kerneldroid.karchiver.presentation.browser

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kerneldroid.karchiver.data.FileItem
import com.kerneldroid.karchiver.data.FileSystemRepository
import com.kerneldroid.karchiver.data.FormatRegistry
import com.kerneldroid.karchiver.data.SortBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class ViewMode { LIST, GRID }

data class BrowserUiState(
    val currentDir: File = Environment.getExternalStorageDirectory(),
    val items: List<FileItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val query: String = "",
    val hideHidden: Boolean = false,
    val isLoading: Boolean = false,
    val isSelectionMode: Boolean = false
)

class BrowserViewModel(
    private val repo: FileSystemRepository = FileSystemRepository()
) : ViewModel() {

    private val rootDir: File = Environment.getExternalStorageDirectory()

    private val _state = MutableStateFlow(BrowserUiState(currentDir = rootDir))
    val state: StateFlow<BrowserUiState> = _state

    var clipboard by mutableStateOf<Pair<List<File>, Boolean>?>(null)
        private set

    private var initialized = false
    private var loadToken = 0
    fun initialize(initialPath: String?, hideHidden: Boolean) {
        if (initialized) return
        initialized = true
        val dir = initialPath?.let { File(it) }?.takeIf { it.isDirectory } ?: rootDir
        _state.value = _state.value.copy(currentDir = dir, hideHidden = hideHidden)
        refresh()
    }

    fun setHideHidden(value: Boolean) {
        if (_state.value.hideHidden == value) return
        _state.value = _state.value.copy(hideHidden = value)
        refresh()
    }

    fun refresh() {
        val s = _state.value
        val token = ++loadToken
        _state.value = s.copy(isLoading = true)
        viewModelScope.launch {
            val items = repo.listDir(s.currentDir, s.sortBy, s.ascending)
                .asSequence()
                .filter { !s.hideHidden || !it.name.startsWith(".") }
                .filter { s.query.isBlank() || it.name.contains(s.query, ignoreCase = true) }
                .toList()
            if (token != loadToken) return@launch
            _state.value = _state.value.copy(
                items = items,
                isLoading = false,
                selected = if (s.isSelectionMode) s.selected else emptySet()
            )
        }
    }

    fun canGoUp(): Boolean {
        val current = _state.value.currentDir
        return current.parentFile != null &&
            current.absolutePath != rootDir.absolutePath
    }

    fun navigateTo(dir: File) {
        if (!dir.isDirectory) return
        _state.value = _state.value.copy(
            currentDir = dir,
            selected = emptySet(),
            isSelectionMode = false,
            query = ""
        )
        refresh()
    }

    fun navigateUp(): Boolean {
        val parent = _state.value.currentDir.parentFile ?: return false
        if (!canGoUp()) return false
        navigateTo(parent)
        return true
    }

    fun toggleSelect(path: String) {
        val s = _state.value
        val newSel = if (s.selected.contains(path)) s.selected - path else s.selected + path
        _state.value = s.copy(selected = newSel, isSelectionMode = newSel.isNotEmpty())
    }

    fun selectAll() {
        val s = _state.value
        _state.value = s.copy(
            selected = s.items.map { it.file.absolutePath }.toSet(),
            isSelectionMode = true
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet(), isSelectionMode = false)
    }

    fun setSort(sort: SortBy) {
        val s = _state.value
        val asc = if (s.sortBy == sort) !s.ascending else true
        _state.value = s.copy(sortBy = sort, ascending = asc)
        refresh()
    }

    fun setAscending(ascending: Boolean) {
        if (_state.value.ascending == ascending) return
        _state.value = _state.value.copy(ascending = ascending)
        refresh()
    }

    fun setViewMode(mode: ViewMode) {
        _state.value = _state.value.copy(viewMode = mode)
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        refresh()
    }

    fun copySelection() {
        val files = selectedFiles(); if (files.isEmpty()) return
        clipboard = files to false
        clearSelection()
    }

    fun cutSelection() {
        val files = selectedFiles(); if (files.isEmpty()) return
        clipboard = files to true
        clearSelection()
    }

    fun cancelClipboard() { clipboard = null }

    fun paste(onDone: (Result<Unit>) -> Unit = {}) {
        val (files, isCut) = clipboard ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val r = if (isCut) repo.cut(files, _state.value.currentDir)
            else repo.copy(files, _state.value.currentDir)
            clipboard = null
            refresh()
            onDone(r)
        }
    }

    fun deleteSelection(onDone: (Result<Unit>) -> Unit = {}) {
        val files = selectedFiles(); if (files.isEmpty()) return
        viewModelScope.launch {
            val r = repo.delete(files)
            clearSelection(); refresh(); onDone(r)
        }
    }

    fun compressSelection(name: String = "archive.zip", onDone: (Result<Unit>) -> Unit = {}) {
        val files = selectedFiles(); if (files.isEmpty()) return
        viewModelScope.launch {
            val dest = File(_state.value.currentDir, name)
            val r = repo.compress(files, dest)
            clearSelection(); refresh(); onDone(r)
        }
    }

    fun extractArchive(file: File, onDone: (Result<Unit>) -> Unit = {}) {
        if (!FormatRegistry.isArchive(file.extension)) {
            onDone(Result.failure(IllegalArgumentException("Not archive"))); return
        }
        viewModelScope.launch {
            val dest = File(file.parentFile, file.nameWithoutExtension)
            val r = repo.extract(file, dest)
            refresh(); onDone(r)
        }
    }

    fun selectedFiles(): List<File> {
        val sel = _state.value.selected
        return _state.value.items.filter { sel.contains(it.file.absolutePath) }.map { it.file }
    }

    fun openFile(context: Context, file: File) {
        if (file.isDirectory) { navigateTo(file); return }
        val ext = file.extension.lowercase()
        if (FormatRegistry.isArchive(ext)) return
        val mime = FormatRegistry.forExtension(ext).mime
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (_: Exception) { return }
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
            try { context.startActivity(Intent.createChooser(fallback, file.name)) } catch (_: Exception) {}
        }
    }
}
