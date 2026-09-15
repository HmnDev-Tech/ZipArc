package com.kerneldroid.karchiver.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.safGrantsStore by preferencesDataStore(name = "saf_grants")

data class SafGrantBinding(
    val volume: AppVolume,
    val treeUri: Uri,
    val baseDir: File
)

class SafGrants(private val context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val TREE_GRANTS = stringSetPreferencesKey("tree_grants")
        val FORCE_SAF = stringSetPreferencesKey("force_saf")
    }

    @Volatile
    private var persistedCache: List<PersistedInfo>? = null

    @Volatile
    private var persistedCacheAt: Long = 0L

    val grants: Flow<Map<String, Uri>> = appContext.safGrantsStore.data.map { prefs ->
        val out = LinkedHashMap<String, Uri>()
        for (entry in prefs[Keys.TREE_GRANTS] ?: emptySet()) {
            val sep = entry.indexOf('|')
            if (sep <= 0) continue
            val volumeId = entry.substring(0, sep)
            val uriString = entry.substring(sep + 1)
            if (uriString.isEmpty()) continue
            try {
                out[volumeId] = Uri.parse(uriString)
            } catch (_: Exception) {
            }
        }
        out
    }

    val forcedSaf: Flow<Set<String>> = appContext.safGrantsStore.data.map { prefs ->
        prefs[Keys.FORCE_SAF] ?: emptySet()
    }

    suspend fun takeGrant(volumeId: String, treeUri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        appContext.safGrantsStore.edit { prefs ->
            val current = prefs[Keys.TREE_GRANTS] ?: emptySet()
            prefs[Keys.TREE_GRANTS] =
                current.filter { !it.startsWith(volumeId + "|") }.toSet() + (volumeId + "|" + treeUri.toString())
        }
        persistedCache = null
        persistedCacheAt = 0L
    }

    suspend fun forget(volumeId: String) {
        val current = try { grants.first()[volumeId] } catch (_: Exception) { null }
        if (current != null) {
            try {
                appContext.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        appContext.safGrantsStore.edit { prefs ->
            val currentGrants = prefs[Keys.TREE_GRANTS] ?: emptySet()
            prefs[Keys.TREE_GRANTS] = currentGrants.filter { !it.startsWith(volumeId + "|") }.toSet()
            val forced = prefs[Keys.FORCE_SAF] ?: emptySet()
            prefs[Keys.FORCE_SAF] = forced - volumeId
        }
    }

    suspend fun grantFor(volumeId: String): Uri? {
        return try { grants.first()[volumeId] } catch (_: Exception) { null }
    }

    suspend fun setForcedSaf(volumeId: String, forced: Boolean) {
        try {
            appContext.safGrantsStore.edit { prefs ->
                val current = prefs[Keys.FORCE_SAF] ?: emptySet()
                prefs[Keys.FORCE_SAF] = if (forced) current + volumeId else current - volumeId
            }
        } catch (_: Exception) {
        }
    }

    fun volumeIdFor(path: File, volumes: List<AppVolume>): String? {
        return try {
            val target = try { path.canonicalPath } catch (_: Exception) { path.absolutePath }
            var best: AppVolume? = null
            var bestLength = -1
            for (volume in volumes) {
                val root = try { volume.root.canonicalPath } catch (_: Exception) { volume.root.absolutePath }
                val base = root.trimEnd('/')
                if (target == base || target.startsWith(base + "/")) {
                    if (base.length > bestLength) {
                        best = volume
                        bestLength = base.length
                    }
                }
            }
            best?.id
        } catch (_: Exception) {
            null
        }
    }

    suspend fun bindingForFile(
        file: File,
        volumes: List<AppVolume>,
        forWrite: Boolean = false
    ): SafGrantBinding? {
        return try {
            val target = canonicalOf(file)
            val keyedId = volumeIdFor(file, volumes)
            if (keyedId != null) {
                val uri = grantFor(keyedId)
                val volume = volumes.firstOrNull { it.id == keyedId }
                if (uri != null && volume != null && hasReadPermission(uri)) {
                    val binding = buildBinding(volume, uri, target, forWrite)
                    if (binding != null) return binding
                }
            }
            selfHealBinding(target, volumes, forWrite)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun buildBinding(
        volume: AppVolume,
        treeUri: Uri,
        target: String,
        forWrite: Boolean
    ): SafGrantBinding? {
        if (forWrite && !hasWritePermission(treeUri)) return null
        val base = baseDirFor(treeUri, volume)
        val baseCanon = canonicalOf(base)
        if (target != baseCanon && !target.startsWith(baseCanon + "/")) return null
        return SafGrantBinding(volume, treeUri, base)
    }

    private fun baseDirFor(treeUri: Uri, volume: AppVolume): File {
        if (!isExternalStorageTree(treeUri)) return volume.root
        val rel = treeRelativePath(treeUri) ?: return volume.root
        return if (rel.isEmpty()) volume.root else File(volume.root, rel)
    }

    private suspend fun selfHealBinding(
        target: String,
        volumes: List<AppVolume>,
        forWrite: Boolean
    ): SafGrantBinding? {
        for (perm in persistedPermissions()) {
            if (!perm.read) continue
            if (forWrite && !perm.write) continue
            val uri = perm.uri
            if (!isExternalStorageTree(uri)) continue
            val rootId = treeRootId(uri) ?: continue
            val volume = volumeForRootId(rootId, volumes) ?: continue
            val base = baseDirFor(uri, volume)
            val baseCanon = canonicalOf(base)
            if (target == baseCanon || target.startsWith(baseCanon + "/")) {
                try {
                    takeGrant(volume.id, uri)
                } catch (_: Exception) {
                }
                return SafGrantBinding(volume, uri, base)
            }
        }
        return null
    }

    fun hasReadPermission(treeUri: Uri): Boolean {
        val cached = persistedSnapshot()
        if (cached != null) return cached.any { it.uri == treeUri && it.read }
        return try {
            appContext.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        } catch (_: Exception) {
            false
        }
    }

    fun hasWritePermission(treeUri: Uri): Boolean {
        return try {
            appContext.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isWritePermission
            }
        } catch (_: Exception) {
            false
        }
    }

    private class PersistedInfo(val uri: Uri, val read: Boolean, val write: Boolean)

    private fun persistedSnapshot(): List<PersistedInfo>? {
        val cached = persistedCache
        val now = android.os.SystemClock.elapsedRealtime()
        return if (cached != null && now - persistedCacheAt < PERSISTED_TTL_MS) cached else null
    }

    private fun persistedPermissions(): List<PersistedInfo> {
        val cached = persistedSnapshot()
        if (cached != null) return cached
        return try {
            val list = appContext.contentResolver.persistedUriPermissions.map {
                PersistedInfo(it.uri, it.isReadPermission, it.isWritePermission)
            }
            persistedCache = list
            persistedCacheAt = android.os.SystemClock.elapsedRealtime()
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isExternalStorageTree(treeUri: Uri): Boolean {
        return try {
            treeUri.authority == EXTERNAL_STORAGE_AUTHORITY
        } catch (_: Exception) {
            false
        }
    }

    private fun parseTreeDocId(treeUri: Uri): Pair<String, String>? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return null
        } ?: return null
        return splitTreeDocId(docId)
    }

    private fun treeRelativePath(treeUri: Uri): String? = parseTreeDocId(treeUri)?.second

    private fun treeRootId(treeUri: Uri): String? = parseTreeDocId(treeUri)?.first

    private fun volumeForRootId(rootId: String, volumes: List<AppVolume>): AppVolume? {
        if (rootId.equals("primary", ignoreCase = true)) {
            return volumes.firstOrNull { it.isPrimary }
        }
        val rid = rootId.lowercase()
        return volumes.firstOrNull { v ->
            !v.isPrimary && (
                v.id.lowercase() == "vol-$rid" ||
                    (try { v.root.name } catch (_: Exception) { null })?.lowercase() == rid
                )
        }
    }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val PERSISTED_TTL_MS = 2000L

        internal fun splitTreeDocId(docId: String): Pair<String, String>? {
            val idx = docId.indexOf(':')
            if (idx <= 0) return null
            val rootId = docId.substring(0, idx)
            if (rootId.isEmpty()) return null
            val rel = docId.substring(idx + 1).trim('/')
            return rootId to rel
        }
    }
}

private fun canonicalOf(file: File): String {
    return try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
}
