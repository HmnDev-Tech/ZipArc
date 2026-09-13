package com.kerneldroid.karchiver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "karchiver_settings")

data class AppSettings(
    val showMainMenu: Boolean = false,
    val openLastFolder: Boolean = true,
    val hideHidden: Boolean = false,
    val lastPath: String? = null
)

class SettingsRepository(private val appContext: Context) {

    companion object {
        const val MAX_RECENT_FOLDERS = 5
    }

    private object Keys {
        val SHOW_MAIN_MENU = booleanPreferencesKey("show_main_menu")
        val OPEN_LAST_FOLDER = booleanPreferencesKey("open_last_folder")
        val HIDE_HIDDEN = booleanPreferencesKey("hide_hidden")
        val LAST_PATH = stringPreferencesKey("last_path")
        val RECENT_FOLDERS = stringPreferencesKey("recent_folders")
    }

    val recentFolders: Flow<List<String>> = appContext.dataStore.data.map { p ->
        p[Keys.RECENT_FOLDERS]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    suspend fun pushRecentFolder(path: String) = appContext.dataStore.edit { p ->
        val updated = (listOf(path) + (p[Keys.RECENT_FOLDERS]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()))
            .distinct()
            .take(MAX_RECENT_FOLDERS)
        p[Keys.RECENT_FOLDERS] = updated.joinToString("\n")
    }

    val settings: Flow<AppSettings> = appContext.dataStore.data.map { p ->
        AppSettings(
            showMainMenu = p[Keys.SHOW_MAIN_MENU] ?: false,
            openLastFolder = p[Keys.OPEN_LAST_FOLDER] ?: true,
            hideHidden = p[Keys.HIDE_HIDDEN] ?: false,
            lastPath = p[Keys.LAST_PATH]
        )
    }

    suspend fun setShowMainMenu(value: Boolean) =
        appContext.dataStore.edit { it[Keys.SHOW_MAIN_MENU] = value }

    suspend fun setOpenLastFolder(value: Boolean) =
        appContext.dataStore.edit { it[Keys.OPEN_LAST_FOLDER] = value }

    suspend fun setHideHidden(value: Boolean) =
        appContext.dataStore.edit { it[Keys.HIDE_HIDDEN] = value }

    suspend fun setLastPath(value: String?) = appContext.dataStore.edit { p ->
        if (value == null) p.remove(Keys.LAST_PATH) else p[Keys.LAST_PATH] = value
    }
}
