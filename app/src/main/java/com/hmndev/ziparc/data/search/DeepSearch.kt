package com.hmndev.ziparc.data.search

import com.hmndev.ziparc.data.FileItem
import com.hmndev.ziparc.data.FileSystemRepository
import com.hmndev.ziparc.data.FormatRegistry
import com.hmndev.ziparc.data.SortBy
import com.hmndev.ziparc.data.elevation.ElevatedFS
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

data class SearchOptions(
    val searchInContent: Boolean = false,
    val searchInArchives: Boolean = true,
    val caseSensitive: Boolean = false,
    val maxScanBytes: Long = ContentScanner.DEFAULT_MAX_BYTES,
    val hideHidden: Boolean = false,
    val elevationMode: String = "off",
    val maxResults: Int = 500,
    val maxScanned: Int = 20_000
)

data class DeepSearchResult(
    val items: List<FileItem>,
    val scanned: Int,
    val capped: Boolean
)

fun SearchQuery.requiresDeepSearch(options: SearchOptions): Boolean =
    contentParts.isNotEmpty() ||
        (archiveParts.isNotEmpty() && options.searchInArchives) ||
        (options.searchInContent && nameParts.isNotEmpty())

class DeepSearch(private val repo: FileSystemRepository) {

    suspend fun run(
        root: File,
        query: SearchQuery,
        options: SearchOptions,
        elevated: ElevatedFS?,
        onProgress: (Int) -> Unit
    ): DeepSearchResult {
        val scanner = ContentScanner(maxBytes = options.maxScanBytes, caseSensitive = options.caseSensitive)
        val results = ArrayList<FileItem>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var scanned = 0
        var capped = false

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val entries = runCatching {
                repo.listDir(dir, SortBy.NAME, ascending = true, foldersFirst = true, elevated = elevated)
            }.getOrDefault(emptyList())
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (options.hideHidden && entry.name.startsWith(".")) continue
                if (entry.isDirectory) {
                    stack.addLast(entry.file)
                } else {
                    scanned++
                }
                val matched = evaluate(entry, root, query, options, scanner, elevated)
                if (matched != null) results.add(matched)
                if (results.size >= options.maxResults || scanned >= options.maxScanned) {
                    capped = true
                    break
                }
                if (scanned % PROGRESS_STEP == 0) onProgress(scanned)
            }
            if (results.size >= options.maxResults || scanned >= options.maxScanned) break
        }
        onProgress(scanned)
        return DeepSearchResult(results.sortedBy { it.name.lowercase() }, scanned, capped)
    }

    private suspend fun evaluate(
        item: FileItem,
        root: File,
        query: SearchQuery,
        options: SearchOptions,
        scanner: ContentScanner,
        elevated: ElevatedFS?
    ): FileItem? {
        if (!query.matchesBase(item.extension, item.isDirectory, item.size, item.lastModified)) return null

        if (item.isDirectory) {
            if (query.contentParts.isNotEmpty() || query.archiveParts.isNotEmpty()) return null
            if (query.nameParts.any { !item.name.contains(it, ignoreCase = true) }) return null
            return result(item, root, null)
        }

        val isArchive = FormatRegistry.isArchive(item.extension)
        if (query.archiveParts.isNotEmpty() && !isArchive) return null

        val nameMatched = query.nameParts.all { item.name.contains(it, ignoreCase = true) }

        val contentNeedles = buildList {
            addAll(query.contentParts)
            if (!nameMatched && options.searchInContent) addAll(query.nameParts)
        }
        var contentMatch: ContentMatch? = null
        if (contentNeedles.isNotEmpty()) {
            contentMatch = probeContent(item, contentNeedles, options, scanner)
            if (contentMatch == null && (query.contentParts.isNotEmpty() || !nameMatched)) return null
        } else if (!nameMatched && query.nameParts.isNotEmpty()) {
            return null
        }

        if (query.archiveParts.isNotEmpty()) {
            if (!options.searchInArchives || !isArchive) return null
            val names = archiveEntryNames(item.file, options, elevated) ?: return null
            val matched = query.archiveParts.all { part ->
                names.any { it.contains(part, ignoreCase = true) }
            }
            if (!matched) return null
        }

        return result(item, root, contentMatch?.snippet)
    }

    private suspend fun probeContent(
        item: FileItem,
        needles: List<String>,
        options: SearchOptions,
        scanner: ContentScanner
    ): ContentMatch? {
        val distinct = needles.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return null
        if (options.searchInArchives && FormatRegistry.isArchive(item.extension)) {
            var snippet: String? = null
            var line = 0
            for (needle in distinct) {
                val matches = repo.searchArchiveContent(item.file, needle, options.caseSensitive, options.maxScanBytes)
                    .getOrDefault(emptyList())
                if (matches.isEmpty()) return null
                if (snippet == null) {
                    snippet = matches.first().snippet
                    line = matches.first().line
                }
            }
            return ContentMatch(line, snippet.orEmpty())
        }
        return runCatching { scanner.scan(item.file.inputStream(), distinct) }.getOrNull()
    }

    private suspend fun archiveEntryNames(
        file: File,
        options: SearchOptions,
        elevated: ElevatedFS?
    ): List<String>? = repo.previewArchive(file, null, elevated, options.elevationMode)
        .getOrNull()
        ?.entries
        ?.map { it.name }

    private fun result(item: FileItem, root: File, snippet: String?): FileItem {
        val relative = runCatching { item.file.relativeTo(root).path }.getOrNull().orEmpty()
        return item.copy(name = relative.ifEmpty { item.name }, snippet = snippet)
    }

    private companion object {
        const val PROGRESS_STEP = 25
    }
}
