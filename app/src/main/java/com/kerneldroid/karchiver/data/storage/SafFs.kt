package com.kerneldroid.karchiver.data.storage

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SafFs {

    data class SafEntry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long
    )

    fun resolve(context: Context, treeUri: Uri, rel: String): DocumentFile? {
        return try {
            var doc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val clean = rel.trim().trim { it == '/' }
            if (clean.isEmpty()) return doc
            for (segment in clean.split('/')) {
                if (segment.isEmpty() || segment == ".") continue
                if (segment == "..") return null
                doc = doc.findFile(segment) ?: return null
            }
            doc
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listEntries(context: Context, treeUri: Uri, rel: String): List<SafEntry>? =
        withContext(Dispatchers.IO) {
            try {
                val dir = resolve(context, treeUri, rel) ?: return@withContext null
                if (!dir.isDirectory) return@withContext null
                val out = ArrayList<SafEntry>()
                for (kid in dir.listFiles()) {
                    try {
                        val name = kid.name ?: continue
                        val isDir = kid.isDirectory
                        out.add(
                            SafEntry(
                                name = name,
                                isDir = isDir,
                                size = if (isDir) 0L else kid.length(),
                                modified = kid.lastModified()
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                out
            } catch (_: Exception) {
                null
            }
        }

    suspend fun makeDirs(context: Context, treeUri: Uri, rel: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var doc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
                val clean = rel.trim().trim { it == '/' }
                if (clean.isEmpty()) return@withContext doc.exists()
                for (segment in clean.split('/')) {
                    if (segment.isEmpty() || segment == ".") continue
                    if (segment == "..") return@withContext false
                    val existing = try { doc.findFile(segment) } catch (_: Exception) { null }
                    doc = when {
                        existing == null -> doc.createDirectory(segment) ?: return@withContext false
                        existing.isDirectory -> existing
                        else -> return@withContext false
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    suspend fun deleteRecursively(context: Context, treeUri: Uri, rel: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val clean = rel.trim().trim { it == '/' }
                if (clean.isEmpty()) return@withContext false
                val doc = resolve(context, treeUri, clean) ?: return@withContext false
                doc.delete()
            } catch (_: Exception) {
                false
            }
        }

    suspend fun createFile(context: Context, treeUri: Uri, relParent: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = name.trim()
                if (trimmed.isEmpty() || trimmed.contains('/')) return@withContext false
                if (!makeDirs(context, treeUri, relParent)) return@withContext false
                val parent = resolve(context, treeUri, relParent) ?: return@withContext false
                if (!parent.isDirectory) return@withContext false
                val existing = try { parent.findFile(trimmed) } catch (_: Exception) { null }
                if (existing != null) return@withContext false
                parent.createFile(mimeOf(trimmed), trimmed) != null
            } catch (_: Exception) {
                false
            }
        }

    suspend fun copyIn(context: Context, treeUri: Uri, relParent: String, src: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!src.exists()) return@withContext false
                if (!makeDirs(context, treeUri, relParent)) return@withContext false
                val parent = resolve(context, treeUri, relParent) ?: return@withContext false
                if (!parent.isDirectory) return@withContext false
                copyInRecursive(context, parent, src)
            } catch (_: Exception) {
                false
            }
        }

    suspend fun copyOut(context: Context, treeUri: Uri, rel: String, dst: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val clean = rel.trim().trim { it == '/' }
                if (clean.isEmpty()) return@withContext false
                val doc = resolve(context, treeUri, clean) ?: return@withContext false
                copyOutRecursive(context, doc, dst)
            } catch (_: Exception) {
                false
            }
        }

    private fun copyInRecursive(context: Context, parent: DocumentFile, src: File): Boolean {
        return try {
            if (src.isDirectory) {
                val kids = src.listFiles() ?: return false
                var dirDoc = try { parent.findFile(src.name) } catch (_: Exception) { null }
                if (dirDoc != null && !dirDoc.isDirectory) {
                    try { dirDoc.delete() } catch (_: Exception) { return false }
                    dirDoc = null
                }
                if (dirDoc == null) {
                    dirDoc = parent.createDirectory(src.name) ?: return false
                }
                for (kid in kids) {
                    if (!copyInRecursive(context, dirDoc, kid)) return false
                }
                true
            } else {
                val existing = try { parent.findFile(src.name) } catch (_: Exception) { null }
                if (existing != null) {
                    if (existing.isDirectory) return false
                    try { existing.delete() } catch (_: Exception) { return false }
                }
                val doc = parent.createFile(mimeOf(src.name), src.name) ?: return false
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    src.inputStream().use { ins -> ins.copyTo(out) }
                } ?: return false
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyOutRecursive(context: Context, doc: DocumentFile, dst: File): Boolean {
        return try {
            if (doc.isDirectory) {
                dst.mkdirs()
                if (!dst.isDirectory) return false
                for (kid in doc.listFiles()) {
                    val name = kid.name ?: continue
                    if (!copyOutRecursive(context, kid, File(dst, name))) return false
                }
                true
            } else {
                dst.parentFile?.mkdirs()
                context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                    dst.outputStream().use { out -> ins.copyTo(out) }
                } ?: return false
                val modified = try { doc.lastModified() } catch (_: Exception) { 0L }
                if (modified > 0L) {
                    try { dst.setLastModified(modified) } catch (_: Exception) {
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeOf(name: String): String {
        return try {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } catch (_: Exception) {
            "application/octet-stream"
        }
    }
}
