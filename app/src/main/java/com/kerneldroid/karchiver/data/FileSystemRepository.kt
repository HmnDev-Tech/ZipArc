package com.kerneldroid.karchiver.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.kerneldroid.karchiver.data.elevation.ElevatedFS
import com.kerneldroid.karchiver.data.elevation.RootEngine
import com.kerneldroid.karchiver.data.elevation.ShizukuEngine
import com.kerneldroid.karchiver.data.elevation.requireCaps
import com.kerneldroid.karchiver.data.storage.AppVolume
import com.kerneldroid.karchiver.data.storage.SafBridge

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

const val RAR_DISABLED_MESSAGE = "RAR support is disabled. Enable it in Settings."

open class RarAccessException(msg: String) : Exception(msg)

class RarDisabledException : RarAccessException(RAR_DISABLED_MESSAGE)

class RarWriteLockedException : RarAccessException("RAR packing is locked. Hold the RAR row in Settings to unlock it.")

fun isRarArchive(file: File): Boolean = file.extension.lowercase() in setOf("rar", "cbr")

enum class CompressFormat(val extension: String, val label: String, val supportsPassword: Boolean) {
    ZIP("zip", "ZIP", true),
    SEVEN_Z("7z", "7Z", true),
    TAR("tar", "TAR", false),
    TAR_GZ("tar.gz", "TAR.GZ", false),
    TAR_BZ2("tar.bz2", "TAR.BZ2", false),
    TAR_XZ("tar.xz", "TAR.XZ", false),
    TAR_ZST("tar.zst", "TAR.ZST", false),
    RAR("rar", "RAR", true)
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

data class FileProperties(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long?,
    val modified: Long,
    val mime: String,
    val modeSymbolic: String?,
    val modeOctal: Int?,
    val canModify: Boolean,
    val elevated: Boolean
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

    var tempDir: File? = null
    var safBridge: SafBridge? = null
    var safVolumes: List<AppVolume> = emptyList()
    var safAutoFallback: Boolean = true
    var safPreferredVolumes: Set<String> = emptySet()

    private suspend fun useSafFirst(dir: File): Boolean {
        if (!safAutoFallback) return false
        val bridge = safBridge ?: return false
        return try {
            bridge.safFirstReady(dir, safVolumes, safPreferredVolumes)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun listDir(
        path: File,
        sortBy: SortBy = SortBy.NAME,
        ascending: Boolean = true,
        foldersFirst: Boolean = true,
        elevated: ElevatedFS? = null
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val listed = path.listFiles()
        val safFirst = useSafFirst(path)
        val raw = (if (safFirst) safItemsFor(path) else null)
            ?: listed?.map { FileItem(it) }
            ?: (if (!safFirst) safItemsFor(path) else null)
            ?: elevated?.listDetailed(path)?.map { entry ->
                FileItem(
                    file = entry.file,
                    name = entry.file.name,
                    isDirectory = entry.isDirectory,
                    size = if (entry.isDirectory) 0 else entry.size,
                    lastModified = entry.modified
                )
            }
            ?: emptyList()
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

    suspend fun delete(files: List<File>, elevated: ElevatedFS? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val safTargets = files.filter { it.exists() && useSafFirst(it) }
            if (safTargets.isNotEmpty() && !trySafDelete(safTargets)) error("Delete failed")
            val rest = files.filter { it !in safTargets }
            val failed = rest.filter { it.exists() && !deleteSingle(it) }
            if (failed.isEmpty()) return@runCatching
            if (elevated == null || !elevated.deleteRecursively(failed)) error("Delete failed")
        }.recoverCatching { e ->
            val failed = files.filter { it.exists() }
            if (failed.isEmpty()) return@recoverCatching
            if (!trySafDelete(failed)) throw e
        }
    }

    private fun deleteSingle(file: File): Boolean =
        if (file.isDirectory) file.deleteRecursively() else file.delete()

    suspend fun createDirectory(parent: File, name: String, elevated: ElevatedFS? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (useSafFirst(parent) && trySafMakeDir(parent, name)) return@runCatching
            val dir = File(parent, name)
            if (dir.exists()) error("Already exists")
            if (!dir.mkdirs()) error("Could not create directory")
        }.recoverCatching { e ->
            if (elevated != null && elevated.mkdirs(File(parent, name))) return@recoverCatching
            if (!trySafMakeDir(parent, name)) throw e
        }
    }

    suspend fun chmod(path: File, mode: Int, elevated: ElevatedFS? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (elevated != null && elevated.chmod(path, mode)) return@runCatching
            applyModeBits(path, mode)
        }
    }

    private fun applyModeBits(path: File, mode: Int) {
        val ownerOnly = false
        if (!path.setReadable(mode and 0x124 != 0, ownerOnly)) error("Could not set mode")
        if (!path.setWritable(mode and 0x92 != 0, ownerOnly)) error("Could not set mode")
        if (!path.setExecutable(mode and 0x49 != 0, ownerOnly)) error("Could not set mode")
    }

    suspend fun loadProperties(file: File, elevated: Boolean = false): FileProperties = withContext(Dispatchers.IO) {
        val sizeBytes = if (file.isDirectory) dirSizeCapped(file) else file.length().takeIf { file.exists() }
        var symbolic: String? = null
        var octal: Int? = null
        runCatching {
            val attrs = Files.readAttributes(file.toPath(), java.nio.file.attribute.PosixFileAttributes::class.java)
            symbolic = java.nio.file.attribute.PosixFilePermissions.toString(attrs.permissions())
            octal = posixToOctal(attrs.permissions())
        }
        FileProperties(
            name = file.name.ifEmpty { file.absolutePath },
            path = file.absolutePath,
            isDir = file.isDirectory,
            sizeBytes = sizeBytes,
            modified = file.lastModified(),
            mime = if (file.isDirectory) "inode/directory" else FormatRegistry.forExtension(file.extension).mime,
            modeSymbolic = symbolic,
            modeOctal = octal,
            canModify = elevated || file.canWrite(),
            elevated = elevated
        )
    }

    private fun dirSizeCapped(root: File, maxEntries: Int = 50_000): Long? {
        var total = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: return null
            for (kid in kids) {
                if (++count > maxEntries) return null
                if (kid.isDirectory) stack.add(kid) else total += kid.length()
            }
        }
        return total
    }

    private fun posixToOctal(perms: Set<java.nio.file.attribute.PosixFilePermission>): Int {
        var mode = 0
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ)) mode += 0x100
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) mode += 0x80
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)) mode += 0x40
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)) mode += 0x20
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)) mode += 0x10
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE)) mode += 0x8
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)) mode += 0x4
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) mode += 0x2
        if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)) mode += 0x1
        return mode
    }

    suspend fun createFile(parent: File, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (useSafFirst(parent) && trySafCreateFile(parent, name)) return@runCatching
            val file = File(parent, name)
            if (file.exists()) error("Already exists")
            if (!file.createNewFile()) error("Could not create file")
        }.recoverCatching { e ->
            if (!trySafCreateFile(parent, name)) throw e
        }
    }

    suspend fun copy(sources: List<File>, destDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (useSafFirst(destDir) && trySafCopy(sources, destDir, move = false)) return@runCatching
            nativeCopy(sources, destDir)
        }.recoverCatching { e ->
            if (!trySafCopy(sources, destDir, move = false)) throw e
        }
    }

    suspend fun cut(sources: List<File>, destDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (useSafFirst(destDir) && trySafCopy(sources, destDir, move = true)) return@runCatching
            nativeCopy(sources, destDir)
            nativeDelete(sources)
        }.recoverCatching { e ->
            if (!trySafCopy(sources, destDir, move = true)) throw e
        }
    }

    private fun nativeCopy(sources: List<File>, destDir: File) {
        destDir.mkdirs()
        sources.forEach { src ->
            val dst = File(destDir, src.name)
            if (src.isDirectory) {
                if (!src.copyRecursively(dst, overwrite = true)) error("Copy failed")
            } else {
                Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun nativeDelete(sources: List<File>) {
        val failed = sources.filter { it.exists() && !deleteSingle(it) }
        if (failed.isNotEmpty()) error("Delete failed")
    }

    private suspend fun safItemsFor(dir: File): List<FileItem>? {
        if (!safAutoFallback) return null
        val bridge = safBridge ?: return null
        return try {
            bridge.itemsFor(dir, safVolumes)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun trySafDelete(files: List<File>): Boolean {
        if (!safAutoFallback) return false
        val bridge = safBridge ?: return false
        return try {
            bridge.deleteTargets(files, safVolumes)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun trySafMakeDir(parent: File, name: String): Boolean {
        if (!safAutoFallback) return false
        val bridge = safBridge ?: return false
        return try {
            bridge.makeDir(parent, name, safVolumes)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun trySafCreateFile(parent: File, name: String): Boolean {
        if (!safAutoFallback) return false
        val bridge = safBridge ?: return false
        return try {
            bridge.createFile(parent, name, safVolumes)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun trySafCopy(sources: List<File>, destDir: File, move: Boolean): Boolean {
        if (!safAutoFallback) return false
        val bridge = safBridge ?: return false
        return try {
            bridge.copyInTree(sources, destDir, move, safVolumes)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun compress(sources: List<File>, dest: File, format: CompressFormat = CompressFormat.ZIP, password: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val fixed = File(dest.parentFile, normalizeArchiveName(dest.name, format))
        val bridge = if (safAutoFallback) safBridge else null
        val tmp = tempDir
        var stagingRoot: File? = null
        var effectiveSources = sources
        var outFile = fixed
        var destViaSaf = false
        if (bridge != null && tmp != null) {
            try {
                if (sources.any { bridge.needsStaging(it) }) {
                    tmp.mkdirs()
                    stagingRoot = File(tmp, "saf-compress-" + System.nanoTime()).apply { mkdirs() }
                    val root = stagingRoot
                    val staged = ArrayList<File>(sources.size)
                    var stagedOk = true
                    for (src in sources) {
                        if (!bridge.needsStaging(src)) {
                            staged.add(src)
                            continue
                        }
                        val one = bridge.stageInTree(src, safVolumes, root)
                        if (one == null) {
                            stagedOk = false
                            break
                        }
                        staged.add(one)
                    }
                    if (stagedOk) effectiveSources = staged
                }
                val destParent = fixed.parentFile
                if (destParent != null && !destParent.canWrite() && bridge.hasGrantFor(destParent, safVolumes)) {
                    if (stagingRoot == null) {
                        tmp.mkdirs()
                        stagingRoot = File(tmp, "saf-compress-" + System.nanoTime()).apply { mkdirs() }
                    }
                    outFile = File(stagingRoot, fixed.name)
                    destViaSaf = true
                }
            } catch (_: Exception) {
                effectiveSources = sources
                outFile = fixed
                destViaSaf = false
            }
        }
        val result = runCatching {
            if (!RustBridge.isLoaded()) {
                if (!password.isNullOrEmpty()) error("Password protection requires the native engine")
                if (format != CompressFormat.ZIP) error("Native engine required for " + format.label)
                fallbackZip(effectiveSources, outFile)
            } else {
                val srcPaths = effectiveSources.map { it.absolutePath }.toTypedArray()
                val code = if (password.isNullOrEmpty()) {
                    RustBridge.compress(srcPaths, outFile.absolutePath)
                } else {
                    RustBridge.compressWithPassword(srcPaths, outFile.absolutePath, password)
                }
                if (code != 0) error("Rust compress failed code=$code")
            }
        }
        val final = if (result.isSuccess && destViaSaf) {
            val destParent = fixed.parentFile
            val pushed = if (bridge == null || destParent == null) false else try {
                bridge.copyInTree(listOf(outFile), destParent, false, safVolumes)
            } catch (_: Exception) {
                false
            }
            if (pushed) Result.success(Unit) else Result.failure(Exception("Could not write file"))
        } else {
            result
        }
        try {
            stagingRoot?.deleteRecursively()
        } catch (_: Exception) {
        }
        final
    }

    suspend fun extract(
        archive: File,
        destDir: File,
        password: String? = null,
        elevated: ElevatedFS? = null,
        elevationMode: String = "off"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            normalExtract(archive, destDir, password)
        }.recoverCatching { e ->
            if (trySafExtract(archive, destDir, password)) return@recoverCatching
            val eng = elevated ?: throw e
            if (archive.canRead()) throw e
            extractElevated(archive, destDir, password, eng, elevationMode).getOrThrow()
        }
    }

    private suspend fun trySafExtract(archive: File, destDir: File, password: String?): Boolean {
        if (!safAutoFallback) return false
        val bridge = safBridge ?: return false
        val tmp = tempDir ?: return false
        return try {
            tmp.mkdirs()
            val staging = File(tmp, "saf-extract-" + System.nanoTime())
            staging.mkdirs()
            if (!staging.isDirectory) return false
            try {
                val effective = bridge.stageArchiveIn(archive, safVolumes, staging) ?: archive
                if (destDir.canWrite()) {
                    try {
                        normalExtract(effective, destDir, password)
                        true
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    val work = File(staging, "out")
                    work.mkdirs()
                    if (!work.isDirectory) return false
                    try {
                        normalExtract(effective, work, password)
                    } catch (_: Exception) {
                        return false
                    }
                    !bridge.stageExtractOut(work, destDir, safVolumes)
                }
            } finally {
                try {
                    staging.deleteRecursively()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun normalExtract(archive: File, destDir: File, password: String?) {
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

    private suspend fun extractElevated(
        archive: File,
        destDir: File,
        password: String?,
        eng: ElevatedFS,
        mode: String
    ): Result<Unit> {
        try {
            requireCaps(archive, mode, (eng as? ShizukuEngine)?.shizukuUid())
            if (!RustBridge.isLoaded()) error("Native engine required")
            when (eng) {
                is ShizukuEngine -> {
                    val pfd = eng.openReadFd(archive.absolutePath) ?: error("Cannot open file")
                    try {
                        val code = if (password.isNullOrEmpty()) {
                            RustBridge.extractFd(pfd.fd, destDir.absolutePath)
                        } else {
                            RustBridge.extractWithPasswordFd(pfd.fd, destDir.absolutePath, password)
                        }
                        if (code != 0) error("Rust extract failed code=$code")
                    } finally {
                        closeQuietly(pfd)
                    }
                }
                is RootEngine -> {
                    val tmp = tempDir ?: error("No temp dir")
                    tmp.mkdirs()
                    val staged = File(tmp, "elevated-" + archive.name)
                    if (!eng.copyInto(archive, staged)) error("Cannot read file")
                    try {
                        normalExtract(staged, destDir, password)
                    } finally {
                        staged.delete()
                    }
                }
                else -> error("Cannot read file")
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun closeQuietly(pfd: android.os.ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }

    suspend fun previewArchive(
        archive: File,
        password: String? = null,
        elevated: ElevatedFS? = null,
        elevationMode: String = "off"
    ): Result<PreviewListing> = withContext(Dispatchers.IO) {
        runCatching {
            normalPreview(archive, password)
        }.recoverCatching { e ->
            val eng = elevated ?: throw e
            if (archive.canRead()) throw e
            previewElevated(archive, password, eng, elevationMode).getOrThrow()
        }
    }

    private fun normalPreview(archive: File, password: String?): PreviewListing {
        if (!RustBridge.isLoaded()) {
            return fallbackPreview(archive)
        }
        val json = if (password.isNullOrEmpty()) {
            RustBridge.listArchiveDetailed(archive.absolutePath)
        } else {
            RustBridge.listArchiveDetailedWithPassword(archive.absolutePath, password)
        }
        return parsePreviewJson(json)
    }

    private suspend fun previewElevated(
        archive: File,
        password: String?,
        eng: ElevatedFS,
        mode: String
    ): Result<PreviewListing> {
        return try {
            requireCaps(archive, mode, (eng as? ShizukuEngine)?.shizukuUid())
            if (!RustBridge.isLoaded()) error("Native engine required")
            val out: PreviewListing = when (eng) {
                is ShizukuEngine -> {
                    val pfd = eng.openReadFd(archive.absolutePath) ?: error("Cannot open file")
                    try {
                        val json = if (password.isNullOrEmpty()) {
                            RustBridge.listArchiveDetailedFd(pfd.fd)
                        } else {
                            RustBridge.listArchiveDetailedWithPasswordFd(pfd.fd, password)
                        }
                        parsePreviewJson(json)
                    } finally {
                        closeQuietly(pfd)
                    }
                }
                is RootEngine -> {
                    val tmp = tempDir ?: error("No temp dir")
                    tmp.mkdirs()
                    val staged = File(tmp, "elevated-" + archive.name)
                    if (!eng.copyInto(archive, staged)) error("Cannot read file")
                    try {
                        normalPreview(staged, password)
                    } finally {
                        staged.delete()
                    }
                }
                else -> error("Cannot read file")
            }
            Result.success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testArchive(
        archive: File,
        password: String? = null,
        elevated: ElevatedFS? = null,
        elevationMode: String = "off"
    ): Result<TestReport> = withContext(Dispatchers.IO) {
        runCatching {
            normalTest(archive, password)
        }.recoverCatching { e ->
            val eng = elevated ?: throw e
            if (archive.canRead()) throw e
            testElevated(archive, password, eng, elevationMode).getOrThrow()
        }
    }

    private fun normalTest(archive: File, password: String?): TestReport {
        if (!RustBridge.isLoaded()) {
            return fallbackTest(archive)
        }
        val json = if (password.isNullOrEmpty()) {
            RustBridge.testArchive(archive.absolutePath)
        } else {
            RustBridge.testArchiveWithPassword(archive.absolutePath, password)
        }
        return parseTestJson(json)
    }

    private suspend fun testElevated(
        archive: File,
        password: String?,
        eng: ElevatedFS,
        mode: String
    ): Result<TestReport> {
        return try {
            requireCaps(archive, mode, (eng as? ShizukuEngine)?.shizukuUid())
            if (!RustBridge.isLoaded()) error("Native engine required")
            val out: TestReport = when (eng) {
                is ShizukuEngine -> {
                    val pfd = eng.openReadFd(archive.absolutePath) ?: error("Cannot open file")
                    try {
                        val json = if (password.isNullOrEmpty()) {
                            RustBridge.testArchiveFd(pfd.fd)
                        } else {
                            RustBridge.testArchiveWithPasswordFd(pfd.fd, password)
                        }
                        parseTestJson(json)
                    } finally {
                        closeQuietly(pfd)
                    }
                }
                is RootEngine -> {
                    val tmp = tempDir ?: error("No temp dir")
                    tmp.mkdirs()
                    val staged = File(tmp, "elevated-" + archive.name)
                    if (!eng.copyInto(archive, staged)) error("Cannot read file")
                    try {
                        normalTest(staged, password)
                    } finally {
                        staged.delete()
                    }
                }
                else -> error("Cannot read file")
            }
            Result.success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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
    @JvmStatic external fun extractFd(fd: Int, destDir: String): Int
    @JvmStatic external fun extractWithPasswordFd(fd: Int, destDir: String, password: String): Int
    @JvmStatic external fun listArchiveDetailedFd(fd: Int): String
    @JvmStatic external fun listArchiveDetailedWithPasswordFd(fd: Int, password: String): String
    @JvmStatic external fun testArchiveFd(fd: Int): String
    @JvmStatic external fun testArchiveWithPasswordFd(fd: Int, password: String): String
    @JvmStatic external fun getProgress(): LongArray
    @JvmStatic external fun cancel()
}
