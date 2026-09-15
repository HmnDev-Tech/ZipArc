package com.kerneldroid.karchiver.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.safGrantsStore by preferencesDataStore(name = "saf_grants")

class SafGrants(private val context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val TREE_GRANTS = stringSetPreferencesKey("tree_grants")
        val FORCE_SAF = stringSetPreferencesKey("force_saf")
    }

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
}
