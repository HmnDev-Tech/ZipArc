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

class FileSystemRepository {

    suspend fun listDir(path: File, sortBy: SortBy = SortBy.NAME, ascending: Boolean = true): List<FileItem> = withContext(Dispatchers.IO) {
        val raw = path.listFiles()?.map { FileItem(it) } ?: emptyList()
        val sorted = when (sortBy) {
            SortBy.NAME -> raw.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
            SortBy.DATE -> raw.sortedBy { it.lastModified }
            SortBy.SIZE -> raw.sortedBy { it.size }
            SortBy.TYPE -> raw.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.extension })
        }
        if (ascending) sorted else sorted.reversed()
    }

    suspend fun delete(files: List<File>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { files.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
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

    suspend fun compress(sources: List<File>, destZip: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!RustBridge.isLoaded()) {
                fallbackZip(sources, destZip)
            } else {
                val srcPaths = sources.map { it.absolutePath }.toTypedArray()
                val code = RustBridge.compress(srcPaths, destZip.absolutePath)
                if (code != 0) error("Rust compress failed code=$code")
            }
        }
    }

    suspend fun extract(archive: File, destDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            if (!RustBridge.isLoaded()) {
                fallbackUnzip(archive, destDir)
            } else {
                val code = RustBridge.extract(archive.absolutePath, destDir.absolutePath)
                if (code != 0) error("Rust extract failed code=$code")
            }
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
    fun isLoaded(): Boolean = try { System.loadLibrary("karchiver_rs"); true } catch (_: Throwable) { false }
    @JvmStatic external fun compress(srcPaths: Array<String>, destPath: String): Int
    @JvmStatic external fun extract(archivePath: String, destDir: String): Int
    @JvmStatic external fun listArchive(archivePath: String): Array<String>
}
