package com.kerneldroid.karchiver.presentation.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kerneldroid.karchiver.data.FileSystemRepository
import com.kerneldroid.karchiver.data.PreviewListing
import com.kerneldroid.karchiver.data.RAR_DISABLED_MESSAGE
import com.kerneldroid.karchiver.data.RarAccessException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ExplorerRow(
    val path: String,
    val displayName: String,
    val isDir: Boolean,
    val size: Long
)

data class ExplorerUiState(
    val insidePath: String = "",
    val rows: List<ExplorerRow> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val archiveName: String = ""
)

class ArchiveExplorerViewModel(
    private val archive: File,
    private val password: String = "",
    private val repo: FileSystemRepository = FileSystemRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(ExplorerUiState(archiveName = archive.name, isLoading = true))
    val state: StateFlow<ExplorerUiState> = _state
    val uiState: StateFlow<ExplorerUiState> = _state

    private var cachedListing: PreviewListing? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            doLoad()
        }
    }

    fun openDir(path: String) {
        val normalized = normalizeInside(path)
        if (normalized == _state.value.insidePath) return
        val rows = buildRows(normalized, cachedListing)
        _state.value = _state.value.copy(insidePath = normalized, rows = rows, selected = emptySet())
    }

    fun navigateUp(): Boolean {
        val current = _state.value.insidePath
        if (current.isEmpty()) return false
        val trimmed = current.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        val parent = if (idx < 0) "" else trimmed.substring(0, idx + 1)
        val rows = buildRows(parent, cachedListing)
        _state.value = _state.value.copy(insidePath = parent, rows = rows, selected = emptySet())
        return true
    }

    fun toggleSelect(path: String) {
        val current = _state.value.selected
        val updated = if (current.contains(path)) current - path else current + path
        _state.value = _state.value.copy(selected = updated)
    }

    fun clearSelection() {
        if (_state.value.selected.isEmpty()) return
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun dismissError() {
        if (_state.value.error == null) return
        _state.value = _state.value.copy(error = null)
    }

    suspend fun deleteSelected(): Boolean {
        val selected = _state.value.selected
        if (selected.isEmpty()) return false
        _state.value = _state.value.copy(isLoading = true, error = null)
        val result = repo.deleteArchiveEntries(archive, selected.toList(), password.ifEmpty { null })
        return result.fold(
            onSuccess = {
                _state.value = _state.value.copy(selected = emptySet())
                doLoad()
                true
            },
            onFailure = { e ->
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Could not update archive")
                false
            }
        )
    }

    suspend fun renameEntry(from: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains("/")) {
            _state.value = _state.value.copy(error = "Invalid name")
            return false
        }
        if (from.isEmpty()) {
            _state.value = _state.value.copy(error = "Invalid name")
            return false
        }
        val base = normalizeInside(_state.value.insidePath)
        val target = base + clean
        if (target == from) return true
        _state.value = _state.value.copy(isLoading = true, error = null)
        val result = repo.renameArchiveEntry(archive, from, target, password.ifEmpty { null })
        return result.fold(
            onSuccess = {
                doLoad()
                true
            },
            onFailure = { e ->
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Could not update archive")
                false
            }
        )
    }

    suspend fun addFiles(sources: List<File>) {
        if (sources.isEmpty()) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        val dest = _state.value.insidePath
        val result = repo.addFilesToArchive(archive, sources, dest, password.ifEmpty { null })
        result.fold(
            onSuccess = {
                doLoad()
            },
            onFailure = { e ->
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Could not update archive")
            }
        )
    }

    suspend fun extractSelected(destDir: File): Boolean {
        val selected = _state.value.selected
        if (selected.isEmpty()) return false
        _state.value = _state.value.copy(isLoading = true, error = null)
        val result = repo.extractArchiveEntries(archive, selected.toList(), destDir, password.ifEmpty { null })
        return result.fold(
            onSuccess = {
                _state.value = _state.value.copy(isLoading = false)
                true
            },
            onFailure = { e ->
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Could not extract entries")
                false
            }
        )
    }

    suspend fun extractAll(destDir: File): Boolean {
        _state.value = _state.value.copy(isLoading = true, error = null)
        val result = repo.extract(archive, destDir, password.ifEmpty { null })
        return result.fold(
            onSuccess = {
                _state.value = _state.value.copy(isLoading = false)
                true
            },
            onFailure = { e ->
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Could not extract archive")
                false
            }
        )
    }

    private suspend fun doLoad() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        val result = repo.previewArchive(archive, password.ifEmpty { null }, null, "off")
        result.fold(
            onSuccess = { listing ->
                cachedListing = listing
                val rows = buildRows(_state.value.insidePath, listing)
                _state.value = _state.value.copy(rows = rows, isLoading = false, error = null)
            },
            onFailure = { e ->
                _state.value = _state.value.copy(isLoading = false, error = previewMessage(e))
            }
        )
    }

    private fun normalizeInside(path: String): String {
        val t = path.trim().trimStart('/')
        if (t.isEmpty()) return ""
        return t.trimEnd('/') + "/"
    }

    private fun buildRows(insidePath: String, listing: PreviewListing?): List<ExplorerRow> {
        if (listing == null) return emptyList()
        val prefix = normalizeInside(insidePath)
        val sizes = HashMap<String, Long>()
        val dirFlags = HashSet<String>()
        val names = HashMap<String, String>()
        for (entry in listing.entries) {
            val raw = entry.name.trim().trimStart('/')
            if (raw.isEmpty()) continue
            val isDirEntry = entry.isDir || raw.endsWith("/")
            val clean = raw.trimEnd('/')
            if (clean.isEmpty()) continue
            if (prefix.isNotEmpty()) {
                if (clean == prefix.trimEnd('/')) continue
                if (!clean.startsWith(prefix)) continue
            }
            val relative = if (prefix.isEmpty()) clean else clean.removePrefix(prefix)
            if (relative.isEmpty()) continue
            val parts = relative.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val first = parts[0]
            val childPath = prefix + first
            names[childPath] = first
            if (parts.size > 1) {
                dirFlags.add(childPath)
                if (!isDirEntry) {
                    sizes[childPath] = (sizes[childPath] ?: 0L) + entry.size
                }
            } else {
                if (isDirEntry) {
                    dirFlags.add(childPath)
                    if (!sizes.containsKey(childPath)) {
                        sizes[childPath] = 0L
                    }
                } else {
                    if (dirFlags.contains(childPath)) {
                        sizes[childPath] = (sizes[childPath] ?: 0L) + entry.size
                    } else {
                        sizes[childPath] = entry.size
                    }
                }
            }
        }
        return names.map { (path, display) ->
            val isDir = dirFlags.contains(path)
            ExplorerRow(path = path, displayName = display, isDir = isDir, size = sizes[path] ?: 0L)
        }.sortedWith(compareBy<ExplorerRow> { !it.isDir }.thenBy { it.displayName.lowercase() }.thenBy { it.displayName })
    }

    private fun previewMessage(e: Throwable): String {
        if (e is RarAccessException) return e.message ?: RAR_DISABLED_MESSAGE
        val msg = e.message ?: ""
        return when {
            msg.contains("wrong password", ignoreCase = true) -> "Wrong password"
            msg.contains("password required", ignoreCase = true) -> "Password required"
            msg.contains("cancel", ignoreCase = true) -> "Cancelled"
            msg.contains("unsupported", ignoreCase = true) -> "Preview not supported for this format"
            else -> "Could not read archive"
        }
    }
}
