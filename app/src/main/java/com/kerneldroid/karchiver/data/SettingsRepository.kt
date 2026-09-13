package com.kerneldroid.karchiver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "karchiver_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

data class AppSettings(
    val showMainMenu: Boolean = false,
    val openLastFolder: Boolean = true,
    val hideHidden: Boolean = false,
    val lastPath: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val seedColor: Long? = null,
    val defaultSort: SortBy = SortBy.NAME,
    val defaultView: String = "list",
    val foldersFirst: Boolean = true,
    val confirmDelete: Boolean = true,
    val rarEnabled: Boolean = false,
    val rarWriteEnabled: Boolean = false
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
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SEED_COLOR = longPreferencesKey("seed_color")
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
        val DEFAULT_VIEW = stringPreferencesKey("default_view")
        val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
        val RAR_ENABLED = booleanPreferencesKey("rar_enabled")
        val RAR_WRITE_ENABLED = booleanPreferencesKey("rar_write_enabled")
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
            lastPath = p[Keys.LAST_PATH],
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            seedColor = p[Keys.SEED_COLOR],
            defaultSort = p[Keys.DEFAULT_SORT]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }
                ?: SortBy.NAME,
            defaultView = p[Keys.DEFAULT_VIEW] ?: "list",
            foldersFirst = p[Keys.FOLDERS_FIRST] ?: true,
            confirmDelete = p[Keys.CONFIRM_DELETE] ?: true,
            rarEnabled = p[Keys.RAR_ENABLED] ?: false,
            rarWriteEnabled = p[Keys.RAR_WRITE_ENABLED] ?: false
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

    suspend fun setThemeMode(value: ThemeMode) =
        appContext.dataStore.edit { it[Keys.THEME_MODE] = value.name }

    suspend fun setDynamicColor(value: Boolean) = appContext.dataStore.edit { p ->
        p[Keys.DYNAMIC_COLOR] = value
        if (value) p.remove(Keys.SEED_COLOR)
    }

    suspend fun setSeedColor(argb: Long) = appContext.dataStore.edit { p ->
        p[Keys.SEED_COLOR] = argb
        p[Keys.DYNAMIC_COLOR] = false
    }

    suspend fun setDefaultSort(value: SortBy) =
        appContext.dataStore.edit { it[Keys.DEFAULT_SORT] = value.name }

    suspend fun setDefaultView(value: String) =
        appContext.dataStore.edit { it[Keys.DEFAULT_VIEW] = value }

    suspend fun setFoldersFirst(value: Boolean) =
        appContext.dataStore.edit { it[Keys.FOLDERS_FIRST] = value }

    suspend fun setConfirmDelete(value: Boolean) =
        appContext.dataStore.edit { it[Keys.CONFIRM_DELETE] = value }

    suspend fun setRarEnabled(value: Boolean) =
        appContext.dataStore.edit {
            it[Keys.RAR_ENABLED] = value
            if (!value) it[Keys.RAR_WRITE_ENABLED] = false
        }

    suspend fun setRarWriteEnabled(value: Boolean) =
        appContext.dataStore.edit {
            it[Keys.RAR_WRITE_ENABLED] = value
            if (value) it[Keys.RAR_ENABLED] = true
        }
}
