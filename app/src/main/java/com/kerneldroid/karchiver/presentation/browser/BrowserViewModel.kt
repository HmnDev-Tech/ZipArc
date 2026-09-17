package com.kerneldroid.karchiver.presentation.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kerneldroid.karchiver.data.FileItem
import com.kerneldroid.karchiver.data.FileSystemRepository
import com.kerneldroid.karchiver.data.CompressFormat
import com.kerneldroid.karchiver.data.ConflictPolicy
import com.kerneldroid.karchiver.data.conflictsAmong
import com.kerneldroid.karchiver.data.topLevelNames
import com.kerneldroid.karchiver.data.uniqueName
import com.kerneldroid.karchiver.data.FormatRegistry
import com.kerneldroid.karchiver.data.RAR_DISABLED_MESSAGE
import com.kerneldroid.karchiver.data.RarAccessException
import com.kerneldroid.karchiver.data.RarDisabledException
import com.kerneldroid.karchiver.data.RarWriteLockedException
import com.kerneldroid.karchiver.data.elevation.ElevatedFS
import com.kerneldroid.karchiver.data.elevation.RootEngine
import com.kerneldroid.karchiver.data.elevation.ShizukuEngine
import com.kerneldroid.karchiver.data.history.HistoryRepository
import com.kerneldroid.karchiver.data.isRarArchive
import com.kerneldroid.karchiver.data.normalizeArchiveName
import com.kerneldroid.karchiver.data.PreviewListing
import com.kerneldroid.karchiver.data.search.matchesSearch
import com.kerneldroid.karchiver.data.search.parseSearchQuery
import com.kerneldroid.karchiver.data.RustBridge
import com.kerneldroid.karchiver.data.SettingsRepository
import com.kerneldroid.karchiver.data.archive.ActiveOp
import com.kerneldroid.karchiver.data.archive.ArchiveOpManager
import com.kerneldroid.karchiver.data.archive.ArchiveService
import com.kerneldroid.karchiver.data.archive.OpOutcome
import com.kerneldroid.karchiver.data.SortBy
import com.kerneldroid.karchiver.data.TestReport
import com.kerneldroid.karchiver.data.storage.AppVolume
import com.kerneldroid.karchiver.data.storage.SafBridge
import com.kerneldroid.karchiver.data.storage.SafGrants
import com.kerneldroid.karchiver.data.storage.VolumeMonitor
import com.kerneldroid.karchiver.data.storage.loadAppVolumes
import com.kerneldroid.karchiver.presentation.storage.deepestVolumeFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class ViewMode { LIST, GRID }

data class ArchivePreviewUiState(
    val file: File? = null,
    val isLoading: Boolean = false,
    val listing: PreviewListing? = null,
    val error: String? = null,
    val passwordUsed: String = ""
)

data class VerifyUiState(
    val file: File? = null,
    val isLoading: Boolean = false,
    val report: TestReport? = null,
    val error: String? = null,
    val passwordUsed: String = ""
)

sealed interface ConflictRequest {
    val destDir: File
    val existingNames: Set<String>
    val names: List<String>

    data class Paste(
        val files: List<File>,
        val move: Boolean,
        override val destDir: File,
        override val existingNames: Set<String>,
        override val names: List<String>
    ) : ConflictRequest

    data class Extract(
        val archive: File,
        val password: String,
        val topLevel: List<String>,
        override val destDir: File,
        override val existingNames: Set<String>,
        override val names: List<String>
    ) : ConflictRequest
}

data class BrowserUiState(
    val currentDir: File = Environment.getExternalStorageDirectory(),
    val items: List<FileItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val query: String = "",
    val favorites: Set<String> = emptySet(),
    val hideHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val rarEnabled: Boolean = false,
    val rarWriteEnabled: Boolean = false,
    val elevationMode: String = "off",
    val isLoading: Boolean = false,
    val isSelectionMode: Boolean = false
)

class BrowserViewModel(
    private val repo: FileSystemRepository = FileSystemRepository()
) : ViewModel() {

    private val rootDir: File = Environment.getExternalStorageDirectory()

    private val _state = MutableStateFlow(BrowserUiState(currentDir = rootDir))
    val state: StateFlow<BrowserUiState> = _state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _verifyActive = MutableStateFlow(false)
    val verifyActive: StateFlow<Boolean> = _verifyActive
    val archiveOpActive: StateFlow<Boolean> = _verifyActive
    val archiveOp: StateFlow<ActiveOp?> = ArchiveOpManager.active
    val progressDialogVisible = MutableStateFlow(true)
    private var pendingCompletion: ((Result<Unit>) -> Unit)? = null

    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict
    private var pendingPasteDone: ((Result<Unit>) -> Unit)? = null
    private var pendingExtractDone: ((Result<Unit>) -> Unit)? = null
    private var pendingExtractContext: Context? = null

    init {
        viewModelScope.launch {
            ArchiveOpManager.finished.collect { finished ->
                if (finished != null) {
                    refresh()
                    val result = when (val outcome = finished.outcome) {
                        is OpOutcome.Success -> Result.success(Unit)
                        is OpOutcome.Cancelled -> Result.failure(Exception("Cancelled"))
                        is OpOutcome.Failed -> Result.failure(Exception(outcome.message))
                    }
                    pendingCompletion?.invoke(result)
                    pendingCompletion = null
                    ArchiveOpManager.consumeFinished()
                }
            }
        }
    }

    fun hideProgressDialog() {
        progressDialogVisible.value = false
    }

    fun showProgressDialog() {
        progressDialogVisible.value = true
    }

    private val _preview = MutableStateFlow(ArchivePreviewUiState())
    val preview: StateFlow<ArchivePreviewUiState> = _preview

    private val _verify = MutableStateFlow(VerifyUiState())
    val verify: StateFlow<VerifyUiState> = _verify

    private var previewToken = 0

    private var settingsRepo: SettingsRepository? = null
    private var favoritesJob: Job? = null

    fun bindFavorites(repo: SettingsRepository) {
        settingsRepo = repo
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            repo.favorites.collect { favs ->
                _state.value = _state.value.copy(favorites = favs)
            }
        }
    }

    suspend fun toggleFavorite(path: String): Boolean =
        settingsRepo?.toggleFavorite(path) ?: false

    fun openPreview(file: File, password: String = "") {
        if (isRarArchive(file) && !_state.value.rarEnabled) {
            _preview.value = ArchivePreviewUiState(file = file, error = RAR_DISABLED_MESSAGE)
            return
        }
        recordInteraction(file)
        val token = ++previewToken
        _preview.value = ArchivePreviewUiState(file = file, isLoading = true, passwordUsed = password)
        viewModelScope.launch {
            val result = repo.previewArchive(file, password.ifEmpty { null }, elevationEngine(), _state.value.elevationMode)
            if (token != previewToken) return@launch
            result.fold(
                onSuccess = { listing ->
                    _preview.value = ArchivePreviewUiState(file = file, listing = listing, passwordUsed = password)
                },
                onFailure = { e ->
                    _preview.value = ArchivePreviewUiState(file = file, error = previewMessage(e), passwordUsed = password)
                }
            )
        }
    }

    fun closePreview() {
        previewToken++
        _preview.value = ArchivePreviewUiState()
    }

    fun verifyArchive(file: File, password: String = "") {
        if (isRarArchive(file) && !_state.value.rarEnabled) {
            _verify.value = VerifyUiState(file = file, error = RAR_DISABLED_MESSAGE)
            return
        }
        recordInteraction(file)
        _verify.value = VerifyUiState(file = file, isLoading = true, passwordUsed = password)
        viewModelScope.launch {
            _verifyActive.value = true
            try {
                val result = repo.testArchive(file, password.ifEmpty { null }, elevationEngine(), _state.value.elevationMode)
                result.fold(
                    onSuccess = { report ->
                        _verify.value = VerifyUiState(file = file, report = report, passwordUsed = password)
                    },
                    onFailure = { e ->
                        _verify.value = VerifyUiState(file = file, error = verifyMessage(e), passwordUsed = password)
                    }
                )
            } finally {
                _verifyActive.value = false
            }
        }
    }

    fun closeVerify() {
        _verify.value = VerifyUiState()
    }

    private fun verifyMessage(e: Throwable): String {
        if (e is RarAccessException) return e.message ?: RAR_DISABLED_MESSAGE
        val msg = e.message ?: ""
        return when {
            msg.contains("wrong password", ignoreCase = true) -> "Wrong password"
            msg.contains("password required", ignoreCase = true) -> "Password required"
            msg.contains("cancel", ignoreCase = true) -> "Cancelled"
            else -> "Verification failed"
        }
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

    fun archiveOpMessage(e: Throwable?, successText: String, failureText: String): String {
        if (e is RarAccessException) return e.message ?: failureText
        val msg = e?.message ?: ""
        return when {
            e == null -> successText
            msg.contains("wrong password", ignoreCase = true) -> "Wrong password"
            msg.contains("password required", ignoreCase = true) -> "Password required"
            msg.contains("another operation", ignoreCase = true) -> "Another operation is in progress"
            msg.contains("cancel", ignoreCase = true) -> "Cancelled"
            else -> failureText
        }
    }

    fun cancelArchiveOp() {
        try {
            RustBridge.cancel()
        } catch (_: Throwable) {
        }
    }

    fun cancelArchiveOp(context: Context) {
        try {
            ArchiveService.cancel(context)
        } catch (_: Throwable) {
        }
    }

    var clipboard by mutableStateOf<Pair<List<File>, Boolean>?>(null)
        private set

    private var initialized = false
    private var loadToken = 0

    val volumes = MutableStateFlow<List<AppVolume>>(emptyList())

    private var volumeMonitor: VolumeMonitor? = null
    private var appCtx: Context? = null
    private var safHelper: SafGrants? = null
    private var historyRepo: HistoryRepository? = null
    private var historyEnabled = true

    private val _safGrants = MutableStateFlow<Map<String, Uri>>(emptyMap())
    val safGrants: StateFlow<Map<String, Uri>> = _safGrants

    private val _forcedSaf = MutableStateFlow<Set<String>>(emptySet())
    val forcedSaf: StateFlow<Set<String>> = _forcedSaf

    private val _safAutoFallback = MutableStateFlow(true)
    val safAutoFallback: StateFlow<Boolean> = _safAutoFallback

    val grantRequest = MutableStateFlow<AppVolume?>(null)

    fun initialize(
        initialPath: String?,
        hideHidden: Boolean,
        sortBy: SortBy = SortBy.NAME,
        viewMode: ViewMode = ViewMode.LIST,
        foldersFirst: Boolean = true,
        rarEnabled: Boolean = false,
        rarWriteEnabled: Boolean = false,
        elevationMode: String = "off",
        appContext: Context,
        safAutoFallback: Boolean = true
    ) {
        if (initialized) return
        initialized = true
        val ctx = appContext.applicationContext ?: appContext
        appCtx = ctx
        historyRepo = HistoryRepository.get(ctx)
        if (repo.tempDir == null) {
            runCatching { repo.tempDir = ctx.cacheDir }
        }
        val dir = initialPath?.let { File(it) }?.takeIf { it.isDirectory } ?: rootDir
        _state.value = _state.value.copy(
            currentDir = dir,
            hideHidden = hideHidden,
            sortBy = sortBy,
            viewMode = viewMode,
            foldersFirst = foldersFirst,
            rarEnabled = rarEnabled,
            rarWriteEnabled = rarWriteEnabled,
            elevationMode = elevationMode
        )
        volumeMonitor?.stop()
        volumeMonitor = VolumeMonitor(ctx) { newVolumes ->
            volumes.value = newVolumes
            repo.safVolumes = newVolumes
            try {
                val current = _state.value.currentDir
                if (!current.exists()) {
                    navigateTo(deepestVolumeFor(current, newVolumes)?.root ?: rootDir)
                }
            } catch (_: Exception) {
            }
        }
        volumeMonitor?.start()
        val grants = SafGrants(ctx)
        safHelper = grants
        repo.safBridge = SafBridge(ctx, grants)
        repo.safAutoFallback = safAutoFallback
        _safAutoFallback.value = safAutoFallback
        viewModelScope.launch {
            grants.grants.collect { _safGrants.value = it }
        }
        viewModelScope.launch {
            grants.forcedSaf.collect {
                _forcedSaf.value = it
                repo.safPreferredVolumes = it
            }
        }
        refresh()
    }

    fun refreshVolumes() {
        val ctx = appCtx ?: return
        viewModelScope.launch {
            volumes.value = loadAppVolumes(ctx)
            repo.safVolumes = volumes.value
        }
    }

    fun currentVolumeRoot(): File {
        return deepestVolumeFor(_state.value.currentDir, volumes.value)?.root ?: rootDir
    }

    fun switchVolume(v: AppVolume) {
        navigateTo(v.root)
    }

    fun forgetGrant(volumeId: String) {
        viewModelScope.launch {
            safHelper?.forget(volumeId)
        }
    }

    fun setForceSaf(volumeId: String, force: Boolean) {
        viewModelScope.launch {
            safHelper?.setForcedSaf(volumeId, force)
        }
    }

    fun syncSafPrefs(value: Boolean) {
        _safAutoFallback.value = value
        repo.safAutoFallback = value
    }

    suspend fun onTreeGranted(uri: Uri, volumeId: String) {
        safHelper?.takeGrant(volumeId, uri)
        if (grantRequest.value?.id == volumeId) grantRequest.value = null
        refreshVolumes()
    }

    fun dismissGrantRequest() {
        grantRequest.value = null
    }

    private fun maybeRequestGrant(result: Result<Unit>) {
        if (result.isSuccess) return
        val msg = result.exceptionOrNull()?.message.orEmpty()
        val denied = msg.contains("denied", ignoreCase = true) ||
            msg.contains("permission", ignoreCase = true) ||
            msg.contains("EACCES", ignoreCase = true) ||
            msg.contains("not allowed", ignoreCase = true) ||
            msg.contains("could not create", ignoreCase = true) ||
            msg.contains("failed", ignoreCase = true)
        if (!denied) return
        val volume = deepestVolumeFor(_state.value.currentDir, volumes.value)
            ?.takeIf { it.isRemovable }
            ?: return
        if (_safGrants.value.containsKey(volume.id)) return
        grantRequest.value = volume
    }

    fun setElevationMode(value: String) {
        if (_state.value.elevationMode == value) return
        _state.value = _state.value.copy(elevationMode = value)
        refresh()
    }

    fun setTempDir(dir: File) {
        repo.tempDir = dir
    }

    private fun elevationEngine(): ElevatedFS? = when (_state.value.elevationMode) {
        "shizuku" -> ShizukuEngine
        "root" -> RootEngine
        else -> null
    }

    fun setRarEnabled(value: Boolean) {
        if (_state.value.rarEnabled == value) return
        _state.value = _state.value.copy(rarEnabled = value)
    }

    fun setRarWriteEnabled(value: Boolean) {
        if (_state.value.rarWriteEnabled == value) return
        _state.value = _state.value.copy(rarWriteEnabled = value)
    }

    fun applyExplorerPrefs(sortBy: SortBy, viewMode: ViewMode, foldersFirst: Boolean) {
        val s = _state.value
        if (s.sortBy == sortBy && s.viewMode == viewMode && s.foldersFirst == foldersFirst) return
        _state.value = s.copy(sortBy = sortBy, viewMode = viewMode, foldersFirst = foldersFirst)
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
        _refreshing.value = true
        viewModelScope.launch {
            val search = parseSearchQuery(s.query)
            val items = repo.listDir(s.currentDir, s.sortBy, s.ascending, s.foldersFirst, elevationEngine())
                .asSequence()
                .filter { !s.hideHidden || !it.name.startsWith(".") }
                .filter { search.isEmpty || it.matchesSearch(search) }
                .toList()
            if (token != loadToken) return@launch
            _state.value = _state.value.copy(
                items = items,
                isLoading = false,
                selected = if (s.isSelectionMode) s.selected else emptySet()
            )
            _refreshing.value = false
        }
    }

    fun canGoUp(): Boolean {
        val current = _state.value.currentDir
        if (current.absolutePath == currentVolumeRoot().absolutePath) return false
        return current.parentFile != null
    }

    fun recordInteraction(file: File) {
        if (!historyEnabled) return
        val repo = historyRepo ?: return
        viewModelScope.launch { repo.record(file) }
    }

    fun setHistoryEnabled(value: Boolean) {
        historyEnabled = value
    }

    fun navigateTo(dir: File) {
        if (!dir.isDirectory) return
        _state.value = _state.value.copy(
            currentDir = dir,
            selected = emptySet(),
            isSelectionMode = false,
            query = ""
        )
        recordInteraction(dir)
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
            val dest = _state.value.currentDir
            val existing = repo.listNames(dest, elevationEngine())
            val conflicts = conflictsAmong(existing, files.map { it.name })
            if (conflicts.isNotEmpty()) {
                pendingPasteDone = onDone
                _conflict.value = ConflictRequest.Paste(
                    files = files,
                    move = isCut,
                    destDir = dest,
                    existingNames = existing,
                    names = conflicts
                )
                return@launch
            }
            performPaste(files, dest, isCut, existing, ConflictPolicy.REPLACE, onDone)
        }
    }

    private fun performPaste(
        files: List<File>,
        destDir: File,
        move: Boolean,
        existingNames: Set<String>,
        policy: ConflictPolicy,
        onDone: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val r = if (move) {
                repo.cut(files, destDir, policy, existingNames)
            } else {
                repo.copy(files, destDir, policy, existingNames)
            }
            clipboard = null
            refresh()
            maybeRequestGrant(r)
            onDone(r)
        }
    }

    fun resolveConflict(policy: ConflictPolicy) {
        val request = _conflict.value ?: return
        _conflict.value = null
        when (request) {
            is ConflictRequest.Paste -> {
                val done = pendingPasteDone ?: {}
                pendingPasteDone = null
                performPaste(
                    request.files,
                    request.destDir,
                    request.move,
                    request.existingNames,
                    policy,
                    done
                )
            }
            is ConflictRequest.Extract -> {
                val context = pendingExtractContext
                val done = pendingExtractDone ?: {}
                pendingExtractContext = null
                pendingExtractDone = null
                if (context == null) {
                    done(Result.failure(Exception("Extraction context lost")))
                    return
                }
                when (policy) {
                    ConflictPolicy.REPLACE -> performExtract(
                        context, request.archive, request.destDir, request.password, null, done
                    )
                    ConflictPolicy.SKIP -> {
                        val only = request.topLevel.filterNot { request.existingNames.contains(it) }
                        if (only.isEmpty()) {
                            done(Result.failure(Exception("Nothing to extract")))
                            return
                        }
                        performExtract(
                            context, request.archive, request.destDir, request.password, only, done
                        )
                    }
                    ConflictPolicy.KEEP_BOTH -> {
                        val parent = request.destDir.parentFile ?: request.destDir
                        viewModelScope.launch {
                            val taken = repo.listNames(parent, elevationEngine())
                            val renamed = uniqueName(request.destDir.name, taken)
                            performExtract(
                                context, request.archive, File(parent, renamed), request.password, null, done
                            )
                        }
                    }
                }
            }
        }
    }

    fun dismissConflict() {
        _conflict.value = null
        pendingPasteDone = null
        pendingExtractDone = null
        pendingExtractContext = null
    }

    fun deleteSelection(onDone: (Result<Unit>) -> Unit = {}) {
        val files = selectedFiles(); if (files.isEmpty()) return
        viewModelScope.launch {
            val r = repo.delete(files, elevationEngine())
            clearSelection(); refresh(); maybeRequestGrant(r); onDone(r)
        }
    }

    fun createFolder(name: String, onDone: (Result<Unit>) -> Unit = {}) {
        val trimmed = name.trim(); if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val r = repo.createDirectory(_state.value.currentDir, trimmed, elevationEngine())
            refresh(); maybeRequestGrant(r); onDone(r)
        }
    }

    fun createFile(name: String, onDone: (Result<Unit>) -> Unit = {}) {
        val trimmed = name.trim(); if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val r = repo.createFile(_state.value.currentDir, trimmed)
            refresh(); maybeRequestGrant(r); onDone(r)
        }
    }

    fun startCompress(context: Context, name: String = "archive.zip", format: CompressFormat = CompressFormat.ZIP, password: String = "", onDone: (Result<Unit>) -> Unit = {}) {
        val files = selectedFiles(); if (files.isEmpty()) return
        if (format == CompressFormat.RAR && !_state.value.rarWriteEnabled) {
            onDone(Result.failure(RarWriteLockedException()))
            return
        }
        if (ArchiveOpManager.active.value != null || pendingCompletion != null) {
            onDone(Result.failure(Exception("Another operation is in progress")))
            return
        }
        pendingCompletion = onDone
        progressDialogVisible.value = true
        val safeName = normalizeArchiveName(name, format)
        val dest = File(_state.value.currentDir, safeName)
        clearSelection()
        ArchiveService.startCompress(context, files, dest, format, password.ifEmpty { null }, _state.value.elevationMode)
    }

    fun compressSelection(context: Context, name: String = "archive.zip", format: CompressFormat = CompressFormat.ZIP, password: String = "", onDone: (Result<Unit>) -> Unit = {}) {
        startCompress(context, name, format, password, onDone)
    }

    fun startExtract(context: Context, file: File, password: String = "", onDone: (Result<Unit>) -> Unit = {}) {
        if (isRarArchive(file) && !_state.value.rarEnabled) {
            onDone(Result.failure(RarDisabledException()))
            return
        }
        if (!FormatRegistry.isArchive(file.extension)) {
            onDone(Result.failure(IllegalArgumentException("Not archive"))); return
        }
        if (ArchiveOpManager.active.value != null || pendingCompletion != null) {
            onDone(Result.failure(Exception("Another operation is in progress")))
            return
        }
        viewModelScope.launch {
            val dest = File(file.parentFile, file.nameWithoutExtension)
            val existing = repo.listNames(dest, elevationEngine())
            val topLevel = if (existing.isEmpty()) {
                emptyList()
            } else {
                val listing = repo.previewArchive(
                    file,
                    password.ifEmpty { null },
                    elevationEngine(),
                    _state.value.elevationMode
                )
                topLevelNames(listing.getOrNull()?.entries?.map { it.name } ?: emptyList())
            }
            val conflicts = conflictsAmong(existing, topLevel)
            if (conflicts.isNotEmpty()) {
                pendingExtractDone = onDone
                pendingExtractContext = context.applicationContext
                _conflict.value = ConflictRequest.Extract(
                    archive = file,
                    password = password,
                    topLevel = topLevel,
                    destDir = dest,
                    existingNames = existing,
                    names = conflicts
                )
                return@launch
            }
            performExtract(context, file, dest, password, null, onDone)
        }
    }

    private fun performExtract(
        context: Context,
        file: File,
        destDir: File,
        password: String,
        onlyNames: List<String>?,
        onDone: (Result<Unit>) -> Unit
    ) {
        if (ArchiveOpManager.active.value != null || pendingCompletion != null) {
            onDone(Result.failure(Exception("Another operation is in progress")))
            return
        }
        pendingCompletion = onDone
        progressDialogVisible.value = true
        ArchiveService.startExtract(
            context,
            file,
            destDir,
            password.ifEmpty { null },
            _state.value.elevationMode,
            onlyNames
        )
    }

    fun extractArchive(context: Context, file: File, password: String = "", onDone: (Result<Unit>) -> Unit = {}) {
        startExtract(context, file, password, onDone)
    }

    fun chmodFile(file: File, mode: Int, onDone: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val r = repo.chmod(file, mode, elevationEngine())
            refresh()
            onDone(r)
        }
    }

    fun renameFile(file: File, newName: String, onDone: (Result<File>) -> Unit = {}) {
        viewModelScope.launch {
            val r = repo.renameFile(file, newName, elevationEngine())
            refresh()
            onDone(r)
        }
    }

    fun setFileModified(file: File, millis: Long, onDone: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val r = repo.setLastModified(file, millis, elevationEngine())
            refresh()
            onDone(r)
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
        recordInteraction(file)
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

    override fun onCleared() {
        super.onCleared()
        volumeMonitor?.stop()
        volumeMonitor = null
    }
}
