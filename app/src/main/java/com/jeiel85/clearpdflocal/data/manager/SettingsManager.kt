package com.jeiel85.clearpdflocal.data.manager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clearpdf_settings")

class SettingsManager(private val context: Context) {

    companion object {
        val KEY_THEME = stringPreferencesKey("app_theme") // "LIGHT", "DARK", "SYSTEM"
        val KEY_MAX_RECENTS = intPreferencesKey("max_recents")
        val KEY_SORT_ORDER = stringPreferencesKey("sort_order") // "DATE_DESC", "NAME_ASC", "SIZE_DESC"
    }

    val themeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_THEME] ?: "SYSTEM"
    }

    val maxRecentsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_MAX_RECENTS] ?: 20
    }

    val sortOrderFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_SORT_ORDER] ?: "DATE_DESC"
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_THEME] = theme
        }
    }

    suspend fun setMaxRecents(max: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MAX_RECENTS] = max
        }
    }

    suspend fun setSortOrder(sortOrder: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SORT_ORDER] = sortOrder
        }
    }
}
