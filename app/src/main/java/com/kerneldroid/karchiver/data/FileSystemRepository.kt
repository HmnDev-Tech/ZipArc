package com.kerneldroid.karchiver.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val extension: String = if (file.isDirectory) "" else file.extension.lowercase(),
    val size: Long = if (file.isDirectory) 0 else file.length(),
    val lastModified: Long = file.lastModified()
) {
    val format: FormatInfo = if (isDirectory) FormatInfo.DIRECTORY else FormatRegistry.forExtension(extension)
}

enum class SortBy { NAME, DATE, SIZE, TYPE }

enum class CompressFormat(val extension: String, val label: String, val supportsPassword: Boolean) {
    ZIP("zip", "ZIP", true),
    SEVEN_Z("7z", "7Z", true),
    TAR("tar", "TAR", false),
    TAR_GZ("tar.gz", "TAR.GZ", false),
    TAR_BZ2("tar.bz2", "TAR.BZ2", false),
    TAR_XZ("tar.xz", "TAR.XZ", false),
    TAR_ZST("tar.zst", "TAR.ZST", false)
}

fun normalizeArchiveName(raw: String, format: CompressFormat): String {
    val trimmed = raw.trim().trimEnd('.', ' ', '\t')
    if (trimmed.isEmpty()) return "archive." + format.extension
    val lower = trimmed.lowercase()
    val known = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4",
        ".tar", ".tgz", ".tbz2", ".tbz", ".txz", ".tzst", ".tlz4",
        ".zip", ".cbz", ".7z", ".rar", ".cbr",
        ".gz", ".bz2", ".xz", ".zst", ".lz4"
    ).sortedByDescending { it.length }
    var base = trimmed
    var strippedKnown = false
    for (s in known) {
        if (lower.endsWith(s) && trimmed.length > s.length) {
            base = trimmed.substring(0, trimmed.length - s.length)
            strippedKnown = true
            break
        }
    }
    if (!strippedKnown) {
        val dot = base.lastIndexOf('.')
        if (dot > 0 && dot < base.length - 1) {
            val tail = base.substring(dot + 1)
            if (tail.length in 1..5 && tail.all { it.isLetterOrDigit() }) {
                base = base.substring(0, dot)
            }
        }
    }
    base = base.trim().trimEnd('.', ' ', '\t')
    if (base.isEmpty()) base = "archive"
    return base + "." + format.extension
}

data class PreviewEntry(
    val name: String,
    val size: Long,
    val isDir: Boolean,
    val encrypted: Boolean
)

data class PreviewListing(
    val entries: List<PreviewEntry>,
    val encrypted: Boolean
)

data class TestFailure(
    val name: String,
    val reason: String
)

data class TestReport(
    val entries: Int,
    val totalSize: Long,
    val failures: List<TestFailure>,
    val passwordRequired: Boolean
) {
    val ok: Boolean get() = failures.isEmpty() && !passwordRequired
}

class FileSystemRepository {

    suspend fun listDir(
        path: File,
        sortBy: SortBy = SortBy.NAME,
        ascending: Boolean = true,
        foldersFirst: Boolean = true
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val raw = path.listFiles()?.map { FileItem(it) } ?: emptyList()
        val key: Comparator<FileItem> = when (sortBy) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.TYPE -> compareBy { it.extension }
        }
        val comparator = if (foldersFirst) compareBy<FileItem> { !it.isDirectory }.then(key) else key
        val sorted = raw.sortedWith(comparator)
        if (ascending) sorted else sorted.reversed()
    }

    suspend fun delete(files: List<File>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { files.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
    }

    suspend fun createDirectory(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(parent, name)
            if (dir.exists()) error("Already exists")
            if (!dir.mkdirs()) error("Could not create directory")
        }
    }

    suspend fun createFile(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(parent, name)
            if (file.exists()) error("Already exists")
            if (!file.createNewFile()) error("Could not create file")
        }
    }

    suspend fun copy(sources: List<File>, destDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            sources.forEach { src ->
                val dst = File(destDir, src.name)
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
                else Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    suspend fun cut(sources: List<File>, destDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            copy(sources, destDir).getOrThrow()
            delete(sources).getOrThrow()
        }
    }

    suspend fun compress(sources: List<File>, dest: File, format: CompressFormat = CompressFormat.ZIP, password: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fixed = File(dest.parentFile, normalizeArchiveName(dest.name, format))
            if (!RustBridge.isLoaded()) {
                if (!password.isNullOrEmpty()) error("Password protection requires the native engine")
                if (format != CompressFormat.ZIP) error("Native engine required for " + format.label)
                fallbackZip(sources, fixed)
            } else {
                val srcPaths = sources.map { it.absolutePath }.toTypedArray()
                val code = if (password.isNullOrEmpty()) {
                    RustBridge.compress(srcPaths, fixed.absolutePath)
                } else {
                    RustBridge.compressWithPassword(srcPaths, fixed.absolutePath, password)
                }
                if (code != 0) error("Rust compress failed code=$code")
            }
        }
    }

    suspend fun extract(archive: File, destDir: File, password: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            if (!RustBridge.isLoaded()) {
                if (!password.isNullOrEmpty()) error("Password protection requires the native engine")
                fallbackUnzip(archive, destDir)
            } else {
                val code = if (password.isNullOrEmpty()) {
                    RustBridge.extract(archive.absolutePath, destDir.absolutePath)
                } else {
                    RustBridge.extractWithPassword(archive.absolutePath, destDir.absolutePath, password)
                }
                if (code != 0) error("Rust extract failed code=$code")
            }
        }
    }

    suspend fun previewArchive(archive: File, password: String? = null): Result<PreviewListing> = withContext(Dispatchers.IO) {
        runCatching {
            if (!RustBridge.isLoaded()) {
                fallbackPreview(archive)
            } else {
                val json = if (password.isNullOrEmpty()) {
                    RustBridge.listArchiveDetailed(archive.absolutePath)
                } else {
                    RustBridge.listArchiveDetailedWithPassword(archive.absolutePath, password)
                }
                parsePreviewJson(json)
            }
        }
    }

    suspend fun testArchive(archive: File, password: String? = null): Result<TestReport> = withContext(Dispatchers.IO) {
        runCatching {
            if (!RustBridge.isLoaded()) {
                fallbackTest(archive)
            } else {
                val json = if (password.isNullOrEmpty()) {
                    RustBridge.testArchive(archive.absolutePath)
                } else {
                    RustBridge.testArchiveWithPassword(archive.absolutePath, password)
                }
                parseTestJson(json)
            }
        }
    }

    private fun parseTestJson(json: String): TestReport {
        val root = org.json.JSONObject(json)
        val array = root.optJSONArray("failures") ?: org.json.JSONArray()
        val failures = ArrayList<TestFailure>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            failures.add(
                TestFailure(
                    name = o.optString("name", ""),
                    reason = o.optString("reason", "")
                )
            )
        }
        return TestReport(
            entries = root.optInt("entries", 0),
            totalSize = root.optLong("totalSize", 0L),
            failures = failures,
            passwordRequired = root.optBoolean("passwordRequired", false)
        )
    }

    private fun fallbackTest(archive: File): TestReport {
        val zip = java.util.zip.ZipFile(archive)
        try {
            var total = 0L
            var count = 0
            val failures = ArrayList<TestFailure>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                count++
                if (e.isDirectory) continue
                try {
                    zip.getInputStream(e).use { ins ->
                        val buf = ByteArray(8192)
                        var n = ins.read(buf)
                        while (n >= 0) {
                            total += n
                            n = ins.read(buf)
                        }
                    }
                } catch (t: Throwable) {
                    failures.add(TestFailure(name = e.name, reason = t.message ?: "Read failed"))
                }
            }
            return TestReport(entries = count, totalSize = total, failures = failures, passwordRequired = false)
        } finally {
            zip.close()
        }
    }

    private fun parsePreviewJson(json: String): PreviewListing {
        val root = org.json.JSONObject(json)
        val encrypted = root.optBoolean("encrypted", false)
        val array = root.optJSONArray("entries") ?: org.json.JSONArray()
        val entries = ArrayList<PreviewEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            entries.add(
                PreviewEntry(
                    name = o.optString("name", ""),
                    size = o.optLong("size", 0L),
                    isDir = o.optBoolean("isDir", false),
                    encrypted = o.optBoolean("encrypted", false)
                )
            )
        }
        return PreviewListing(entries = entries, encrypted = encrypted)
    }

    private fun fallbackPreview(archive: File): PreviewListing {
        val zip = java.util.zip.ZipFile(archive)
        try {
            val entries = zip.entries().asSequence().map { e ->
                PreviewEntry(
                    name = e.name,
                    size = if (e.isDirectory) 0L else e.size.coerceAtLeast(0L),
                    isDir = e.isDirectory,
                    encrypted = false
                )
            }.sortedBy { it.name }.toList()
            return PreviewListing(entries = entries, encrypted = false)
        } finally {
            zip.close()
        }
    }

    private fun fallbackZip(sources: List<File>, dest: File) {
        java.util.zip.ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            fun add(file: File, base: String) {
                val entryName = if (base.isEmpty()) file.name else "$base/${file.name}"
                if (file.isDirectory) {
                    file.listFiles()?.forEach { add(it, entryName) }
                } else {
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            sources.forEach { add(it, "") }
        }
    }

    private fun fallbackUnzip(zip: File, destDir: File) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

object RustBridge {
    const val CODE_OK: Int = 0
    const val CODE_CANCELLED: Int = 2
    fun isLoaded(): Boolean = try { System.loadLibrary("karchiver_rs"); true } catch (_: Throwable) { false }
    @JvmStatic external fun compress(srcPaths: Array<String>, destPath: String): Int
    @JvmStatic external fun extract(archivePath: String, destDir: String): Int
    @JvmStatic external fun compressWithPassword(srcPaths: Array<String>, destPath: String, password: String): Int
    @JvmStatic external fun extractWithPassword(archivePath: String, destDir: String, password: String): Int
    @JvmStatic external fun listArchive(archivePath: String): Array<String>
    @JvmStatic external fun listArchiveDetailed(archivePath: String): String
    @JvmStatic external fun listArchiveDetailedWithPassword(archivePath: String, password: String): String
    @JvmStatic external fun testArchive(archivePath: String): String
    @JvmStatic external fun testArchiveWithPassword(archivePath: String, password: String): String
    @JvmStatic external fun cancel()
}
