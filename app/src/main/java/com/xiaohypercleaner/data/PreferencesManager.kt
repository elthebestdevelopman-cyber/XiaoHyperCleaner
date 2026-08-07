package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xhc_settings")

/** Ключи предпочтений с типобезопасным описанием. */
sealed interface PreferenceKey {
    val name: String

    data object DarkTheme : PreferenceKey {
        override val name = "dark_theme"
    }

    data object HiddenSettingsApplied : PreferenceKey {
        override val name = "hidden_settings_applied"
    }
}

/**
 * Хранит только пользовательские флаги (тема, факт применения настроек).
 * Конфиденциальных данных нет, шифрование не требуется.
 */
class PreferencesManager(private val context: Context) {

    val isDarkTheme: Flow<Boolean> = readBool(PreferenceKey.DarkTheme, false)
    val isHiddenSettingsApplied: Flow<Boolean> =
        readBool(PreferenceKey.HiddenSettingsApplied, false)

    suspend fun setDarkTheme(enabled: Boolean) = writeBool(PreferenceKey.DarkTheme, enabled)

    suspend fun setHiddenSettingsApplied(applied: Boolean) =
        writeBool(PreferenceKey.HiddenSettingsApplied, applied)

    private fun readBool(key: PreferenceKey, default: Boolean): Flow<Boolean> =
        context.dataStore.data.map { it[booleanPreferencesKey(key.name)] ?: default }

    private suspend fun writeBool(key: PreferenceKey, value: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey(key.name)] = value }
    }
}