package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val IS_HIDDEN_SETTINGS_APPLIED = booleanPreferencesKey("is_hidden_settings_applied")
    }

    val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_DARK_THEME] ?: false
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_DARK_THEME] = enabled
        }
    }

    val isHiddenSettingsApplied: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_HIDDEN_SETTINGS_APPLIED] ?: false
    }

    suspend fun setHiddenSettingsApplied(applied: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_HIDDEN_SETTINGS_APPLIED] = applied
        }
    }
}