package com.kerneldroid.karchiver.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    private data class ChildHit(
        val docId: String,
        val mime: String?
    )

    private class DirectLookup(val hit: ChildHit?)

    private fun isGuessable(treeUri: Uri): Boolean {
        return try {
            treeUri.authority == SafGrants.EXTERNAL_STORAGE_AUTHORITY
        } catch (_: Exception) {
            false
        }
    }

    private fun appendSegment(docId: String, segment: String): String? {
        val idx = docId.indexOf(':')
        if (idx <= 0) return null
        val rel = docId.substring(idx + 1)
        return if (rel.isEmpty()) {
            docId.substring(0, idx) + ":" + segment
        } else {
            docId + "/" + segment
        }
    }

    private fun directLookupChild(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        name: String
    ): DirectLookup? {
        if (!isGuessable(treeUri)) return null
        val childId = try {
            appendSegment(parentDocId, name)
        } catch (_: Exception) {
            null
        } ?: return null
        if (childId.contains("..")) return null
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) {
                    DirectLookup(null)
                } else {
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val mime = if (mimeIdx >= 0) {
                        try {
                            c.getString(mimeIdx)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    DirectLookup(ChildHit(childId, mime))
                }
            } ?: DirectLookup(null)
        } catch (fnf: java.io.FileNotFoundException) {
            DirectLookup(null)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun openReadPfd(context: Context, treeUri: Uri, rel: String): android.os.ParcelFileDescriptor? =
        withContext(Dispatchers.IO) {
            try {
                val docUri = resolveUri(context, treeUri, rel) ?: return@withContext null
                context.contentResolver.openFileDescriptor(docUri, "r")
            } catch (_: Throwable) {
                null
            }
        }

    fun resolve(context: Context, treeUri: Uri, rel: String): DocumentFile? {
        return try {
            val uri = resolveUri(context, treeUri, rel) ?: return null
            DocumentFile.fromSingleUri(context, uri)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listEntries(context: Context, treeUri: Uri, rel: String): List<SafEntry>? =
        withContext(Dispatchers.IO) {
            try {
                val dirUri = resolveUri(context, treeUri, rel) ?: return@withContext null
                val dirMime = getMime(context, dirUri)
                if (dirMime != null && dirMime != DocumentsContract.Document.MIME_TYPE_DIR) {
                    return@withContext null
                }
                val docId = try {
                    DocumentsContract.getDocumentId(dirUri)
                } catch (_: Exception) {
                    return@withContext null
                }
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    val nameIdx =
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx =
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx =
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx =
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (nameIdx < 0) return@withContext null
                    val out = ArrayList<SafEntry>()
                    while (c.moveToNext()) {
                        try {
                            val name = c.getString(nameIdx) ?: continue
                            val mime = if (mimeIdx >= 0) {
                                try {
                                    c.getString(mimeIdx)
                                } catch (_: Exception) {
                                    null
                                }
                            } else {
                                null
                            }
                            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                            val size = if (isDir || sizeIdx < 0) {
                                0L
                            } else {
                                try {
                                    c.getLong(sizeIdx)
                                } catch (_: Exception) {
                                    0L
                                }
                            }
                            val modified = if (modIdx < 0) {
                                0L
                            } else {
                                try {
                                    c.getLong(modIdx)
                                } catch (_: Exception) {
                                    0L
                                }
                            }
                            out.add(SafEntry(name, isDir, size, modified))
                        } catch (_: Exception) {
                        }
                    }
                    out
                } ?: return@withContext null
            } catch (_: Exception) {
                null
            }
        }

    suspend fun makeDirs(context: Context, treeUri: Uri, rel: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val treeDocId = try {
                    DocumentsContract.getTreeDocumentId(treeUri)
                } catch (_: Exception) {
                    return@withContext false
                }
                var parentDocId = treeDocId
                var parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                val clean = rel.trim().trim { it == '/' }
                if (clean.isEmpty()) {
                    return@withContext resolveUri(context, treeUri, rel) != null
                }
                for (segment in clean.split('/')) {
                    if (segment.isEmpty() || segment == ".") continue
                    if (segment == "..") return@withContext false
                    val hit = lookupChild(context, treeUri, parentDocId, segment)
                    if (hit == null) {
                        val created = try {
                            DocumentsContract.createDocument(
                                context.contentResolver,
                                parentUri,
                                DocumentsContract.Document.MIME_TYPE_DIR,
                                segment
                            )
                        } catch (_: Exception) {
                            null
                        } ?: return@withContext false
                        val newId = try {
                            DocumentsContract.getDocumentId(created)
                        } catch (_: Exception) {
                            return@withContext false
                        }
                        parentDocId = newId
                        parentUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                    } else {
                        val mime = hit.mime ?: getMime(
                            context,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, hit.docId)
                        )
                        if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                            return@withContext false
                        }
                        parentDocId = hit.docId
                        parentUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
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
                val docUri = resolveUri(context, treeUri, clean) ?: return@withContext false
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, docUri)
                } catch (_: Exception) {
                    false
                }
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
                val parentUri = resolveUri(context, treeUri, relParent) ?: return@withContext false
                val parentMime = getMime(context, parentUri)
                if (parentMime != null && parentMime != DocumentsContract.Document.MIME_TYPE_DIR) {
                    return@withContext false
                }
                val parentDocId = try {
                    DocumentsContract.getDocumentId(parentUri)
                } catch (_: Exception) {
                    return@withContext false
                }
                val existing = lookupChild(context, treeUri, parentDocId, trimmed)
                if (existing != null) return@withContext false
                val created = try {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        mimeOf(trimmed),
                        trimmed
                    )
                } catch (_: Exception) {
                    null
                }
                created != null
            } catch (_: Exception) {
                false
            }
        }

    suspend fun copyIn(context: Context, treeUri: Uri, relParent: String, src: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!src.exists()) return@withContext false
                if (!makeDirs(context, treeUri, relParent)) return@withContext false
                val parentUri = resolveUri(context, treeUri, relParent) ?: return@withContext false
                val parentMime = getMime(context, parentUri)
                if (parentMime != null && parentMime != DocumentsContract.Document.MIME_TYPE_DIR) {
                    return@withContext false
                }
                val parentDocId = try {
                    DocumentsContract.getDocumentId(parentUri)
                } catch (_: Exception) {
                    return@withContext false
                }
                copyInRecursive(context, treeUri, parentUri, parentDocId, src)
            } catch (_: Exception) {
                false
            }
        }

    suspend fun copyOut(context: Context, treeUri: Uri, rel: String, dst: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val clean = rel.trim().trim { it == '/' }
                if (clean.isEmpty()) return@withContext false
                val docUri = resolveUri(context, treeUri, clean) ?: return@withContext false
                copyOutRecursive(context, treeUri, docUri, dst)
            } catch (_: Exception) {
                false
            }
        }

    private fun resolveUri(context: Context, treeUri: Uri, rel: String): Uri? {
        return try {
            val treeDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                return null
            }
            var parentDocId = treeDocId
            val clean = rel.trim().trim { it == '/' }
            if (clean.isEmpty()) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            }
            for (segment in clean.split('/')) {
                if (segment.isEmpty() || segment == ".") continue
                if (segment == "..") return null
                val hit = lookupChild(context, treeUri, parentDocId, segment) ?: return null
                parentDocId = hit.docId
            }
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        } catch (_: Exception) {
            null
        }
    }

    private fun lookupChild(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        name: String
    ): ChildHit? {
        val direct = directLookupChild(context, treeUri, parentDocId, name)
        if (direct != null) return direct.hit
        return try {
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIdx < 0 || nameIdx < 0) return null
                while (c.moveToNext()) {
                    val display = try {
                        c.getString(nameIdx)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (display != name) continue
                    val id = try {
                        c.getString(idIdx)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    val mime = if (mimeIdx < 0) {
                        null
                    } else {
                        try {
                            c.getString(mimeIdx)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    return ChildHit(id, mime)
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getMime(context: Context, docUri: Uri): String? {
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idx < 0) return null
                try {
                    c.getString(idx)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getModified(context: Context, docUri: Uri): Long {
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) return 0L
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idx < 0) return 0L
                try {
                    c.getLong(idx)
                } catch (_: Exception) {
                    0L
                }
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun copyInRecursive(
        context: Context,
        treeUri: Uri,
        parentUri: Uri,
        parentDocId: String,
        src: File
    ): Boolean {
        return try {
            val resolver = context.contentResolver
            if (src.isDirectory) {
                val kids = src.listFiles() ?: return false
                var dirDocId: String? = null
                var dirUri: Uri? = null
                val existing = lookupChild(context, treeUri, parentDocId, src.name)
                if (existing != null) {
                    val existingUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId)
                    val existingMime = existing.mime ?: getMime(context, existingUri)
                    if (existingMime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        try {
                            DocumentsContract.deleteDocument(resolver, existingUri)
                        } catch (_: Exception) {
                            return false
                        }
                    } else {
                        dirDocId = existing.docId
                        dirUri = existingUri
                    }
                }
                if (dirDocId == null) {
                    val created = try {
                        DocumentsContract.createDocument(
                            resolver,
                            parentUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            src.name
                        )
                    } catch (_: Exception) {
                        null
                    } ?: return false
                    val newId = try {
                        DocumentsContract.getDocumentId(created)
                    } catch (_: Exception) {
                        return false
                    }
                    dirDocId = newId
                    dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, newId)
                }
                for (kid in kids) {
                    if (!copyInRecursive(context, treeUri, dirUri!!, dirDocId!!, kid)) return false
                }
                true
            } else {
                val existing = lookupChild(context, treeUri, parentDocId, src.name)
                if (existing != null) {
                    val existingUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId)
                    val existingMime = existing.mime ?: getMime(context, existingUri)
                    if (existingMime == DocumentsContract.Document.MIME_TYPE_DIR) return false
                }
                var bakUri: Uri? = null
                if (existing != null) {
                    val existingUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId)
                    val bakName = src.name + ".karchiver-bak"
                    val staleBak = lookupChild(context, treeUri, parentDocId, bakName)
                    if (staleBak != null) {
                        try {
                            DocumentsContract.deleteDocument(
                                resolver,
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, staleBak.docId)
                            )
                        } catch (_: Exception) {
                        }
                    }
                    val moved = try {
                        DocumentsContract.renameDocument(resolver, existingUri, bakName)
                    } catch (_: Exception) {
                        null
                    }
                    if (moved != null) {
                        bakUri = moved
                    } else {
                        try {
                            DocumentsContract.deleteDocument(resolver, existingUri)
                        } catch (_: Exception) {
                            return false
                        }
                    }
                }
                val tempName = src.name + ".karchiver-part"
                val staleTemp = lookupChild(context, treeUri, parentDocId, tempName)
                if (staleTemp != null) {
                    try {
                        DocumentsContract.deleteDocument(
                            resolver,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, staleTemp.docId)
                        )
                    } catch (_: Exception) {
                    }
                }
                val tempUri = try {
                    DocumentsContract.createDocument(
                        resolver,
                        parentUri,
                        mimeOf(src.name),
                        tempName
                    )
                } catch (_: Exception) {
                    null
                } ?: run {
                    restoreBak(context, treeUri, bakUri, src.name)
                    return false
                }
                var writeOk = false
                try {
                    val out = resolver.openOutputStream(tempUri)
                    if (out != null) {
                        out.use { stream ->
                            src.inputStream().use { ins -> ins.copyTo(stream) }
                        }
                        writeOk = true
                    }
                } catch (_: Exception) {
                    writeOk = false
                }
                if (!writeOk) {
                    try {
                        DocumentsContract.deleteDocument(resolver, tempUri)
                    } catch (_: Exception) {
                    }
                    restoreBak(context, treeUri, bakUri, src.name)
                    return false
                }
                var finalUri: Uri? = null
                try {
                    finalUri = DocumentsContract.renameDocument(resolver, tempUri, src.name)
                } catch (_: Exception) {
                    null
                }
                if (finalUri == null) {
                    try {
                        DocumentsContract.deleteDocument(resolver, tempUri)
                    } catch (_: Exception) {
                    }
                    restoreBak(context, treeUri, bakUri, src.name)
                    return false
                }
                if (isGuessable(treeUri)) {
                    val finalId = try {
                        DocumentsContract.getDocumentId(finalUri)
                    } catch (_: Exception) {
                        null
                    }
                    if (finalId == null || !finalId.endsWith("/" + src.name)) {
                        try {
                            DocumentsContract.deleteDocument(resolver, finalUri)
                        } catch (_: Exception) {
                        }
                        restoreBak(context, treeUri, bakUri, src.name)
                        return false
                    }
                }
                if (bakUri != null) {
                    try {
                        DocumentsContract.deleteDocument(resolver, bakUri)
                    } catch (_: Exception) {
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreBak(context: Context, treeUri: Uri, bakUri: Uri?, finalName: String): Boolean {
        val bak = bakUri ?: return false
        return try {
            DocumentsContract.renameDocument(context.contentResolver, bak, finalName) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun copyOutRecursive(
        context: Context,
        treeUri: Uri,
        docUri: Uri,
        dst: File
    ): Boolean {
        return try {
            val resolver = context.contentResolver
            val mime = getMime(context, docUri)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                dst.mkdirs()
                if (!dst.isDirectory) return false
                val docId = try {
                    DocumentsContract.getDocumentId(docUri)
                } catch (_: Exception) {
                    return false
                }
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx =
                        c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idIdx < 0 || nameIdx < 0) return false
                    while (c.moveToNext()) {
                        val name = try {
                            c.getString(nameIdx)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        val childId = try {
                            c.getString(idIdx)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        val childUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        if (!copyOutRecursive(context, treeUri, childUri, File(dst, name))) {
                            return false
                        }
                    }
                    true
                } ?: return false
            } else {
                try {
                    dst.parentFile?.mkdirs()
                } catch (_: Exception) {
                }
                val parent = dst.parentFile
                val part = if (parent != null) {
                    File(parent, dst.name + ".karchiver-part")
                } else {
                    File(dst.path + ".karchiver-part")
                }
                val modified = getModified(context, docUri)
                try {
                    resolver.openInputStream(docUri)?.use { ins ->
                        part.outputStream().use { out -> ins.copyTo(out) }
                    } ?: run {
                        try {
                            part.delete()
                        } catch (_: Exception) {
                        }
                        return false
                    }
                } catch (_: Exception) {
                    try {
                        part.delete()
                    } catch (_: Exception) {
                    }
                    return false
                }
                try {
                    val bak = if (dst.exists()) File(dst.parentFile, dst.name + ".karchiver-bak") else null
                    if (bak != null) {
                        try { bak.delete() } catch (_: Exception) {
                        }
                    }
                    if (dst.exists()) {
                        val movedAside = if (bak != null) {
                            try { dst.renameTo(bak) } catch (_: Exception) { false }
                        } else {
                            false
                        }
                        if (!movedAside) {
                            try { dst.delete() } catch (_: Exception) {
                            }
                            if (dst.exists()) {
                                try { part.delete() } catch (_: Exception) {
                                }
                                return false
                            }
                        }
                    }
                    if (!part.renameTo(dst)) {
                        if (bak != null && bak.exists()) {
                            try { bak.renameTo(dst) } catch (_: Exception) {
                            }
                        }
                        try { part.delete() } catch (_: Exception) {
                        }
                        return false
                    }
                    if (bak != null) {
                        try { bak.delete() } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    try {
                        part.delete()
                    } catch (_: Exception) {
                    }
                    return false
                }
                if (modified > 0L) {
                    try {
                        dst.setLastModified(modified)
                    } catch (_: Exception) {
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
