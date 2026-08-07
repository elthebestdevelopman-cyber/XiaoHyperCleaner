package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
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
 * Менеджер предпочтений приложения.
 * Хранит только пользовательские флаги (тема, факт применения настроек).
 * Конфиденциальных данных нет, шифрование не требуется.
 * Реализует кэширование ключей для избежания дублирования.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        // Кэшированные ключи предпочтений для избежания повторного создания
        private val CACHE_DARK_THEME = booleanPreferencesKey(PreferenceKey.DarkTheme.name)
        private val CACHE_HIDDEN_SETTINGS = booleanPreferencesKey(PreferenceKey.HiddenSettingsApplied.name)
    }

    val isDarkTheme: Flow<Boolean> = readBool(CACHE_DARK_THEME, false)
    val isHiddenSettingsApplied: Flow<Boolean> =
        readBool(CACHE_HIDDEN_SETTINGS, false)

    suspend fun setDarkTheme(enabled: Boolean) = writeBool(CACHE_DARK_THEME, enabled)

    suspend fun setHiddenSettingsApplied(applied: Boolean) =
        writeBool(CACHE_HIDDEN_SETTINGS, applied)

    private fun readBool(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        context.dataStore.data.map { it[key] ?: default }

    private suspend fun writeBool(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
}