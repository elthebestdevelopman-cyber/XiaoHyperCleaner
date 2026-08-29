package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ✅ ИСПРАВЛЕНО: имя берётся из AppConstants (было захардкожено "xhc_settings")
private val Context.dataStore by preferencesDataStore(name = AppConstants.DATASTORE_NAME)

/**
 * Ключи для DataStore.
 * Используется sealed interface для type-safety и автодополнения.
 */
sealed interface PreferenceKey {
    val name: String

    data object HasCompletedOnboarding : PreferenceKey {
        override val name = "has_completed_onboarding"
    }

    data object DarkTheme : PreferenceKey {
        override val name = "dark_theme"
    }

    data object HasManuallyChosenTheme : PreferenceKey {
        override val name = "has_manually_chosen_theme"
    }

    data object HiddenSettingsApplied : PreferenceKey {
        override val name = "hidden_settings_applied"
    }

    data object PendingOptimization : PreferenceKey {
        override val name = "pending_optimization"
    }

    data object HasShownRestrictedDialog : PreferenceKey {
        override val name = "has_shown_restricted_dialog"
    }

    data object DnsFilterEnabled : PreferenceKey {
        override val name = "dns_filter_enabled"
    }

    data object HasSeenDnsWarning : PreferenceKey {
        override val name = "has_seen_dns_warning"
    }

    data object AggressiveMode : PreferenceKey {
        override val name = "aggressive_mode"
    }

    data object LastReportJson : PreferenceKey {
        override val name = "last_report_json"
    }

    data object OptimizationModeKey : PreferenceKey {
        override val name = "optimization_mode"
    }
}

/**
 * Менеджер предпочтений (DataStore) для хранения настроек пользователя.
 *
 * УЛУЧШЕНИЯ:
 * 1. Константы для `stringPreferencesKey` — избегаем повторного создания
 * 2. `getLastReportJson()` — синхронный доступ к последнему отчёту
 * 3. `clearAll()` — сброс всех настроек
 * 4. Обработка ошибок DataStore через `runCatching` и `catch`
 * 5. Защита от corrupt DataStore
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private const val TAG = "PreferencesManager"

        // Константы для строковых ключей — избегаем повторного создания
        private val LAST_REPORT_KEY = stringPreferencesKey(PreferenceKey.LastReportJson.name)
        private val OPTIMIZATION_MODE_KEY =
            stringPreferencesKey(PreferenceKey.OptimizationModeKey.name)
    }

    // ═══════════════════════════════════════════════════════════════
    // Boolean preferences
    // ═══════════════════════════════════════════════════════════════

    val hasCompletedOnboarding: Flow<Boolean> =
        readBool(PreferenceKey.HasCompletedOnboarding, false)

    suspend fun setHasCompletedOnboarding(completed: Boolean) =
        writeBool(PreferenceKey.HasCompletedOnboarding, completed)

    val isDarkTheme: Flow<Boolean> = readBool(PreferenceKey.DarkTheme, false)

    val hasManuallyChosenTheme: Flow<Boolean> =
        readBool(PreferenceKey.HasManuallyChosenTheme, false)

    val isHiddenSettingsApplied: Flow<Boolean> =
        readBool(PreferenceKey.HiddenSettingsApplied, false)

    val pendingOptimization: Flow<Boolean> =
        readBool(PreferenceKey.PendingOptimization, false)

    val hasShownRestrictedDialog: Flow<Boolean> =
        readBool(PreferenceKey.HasShownRestrictedDialog, false)

    val dnsFilterEnabled: Flow<Boolean> =
        readBool(PreferenceKey.DnsFilterEnabled, false)

    val hasSeenDnsWarning: Flow<Boolean> =
        readBool(PreferenceKey.HasSeenDnsWarning, false)

    val aggressiveMode: Flow<Boolean> =
        readBool(PreferenceKey.AggressiveMode, false)

    suspend fun setDarkTheme(enabled: Boolean) =
        writeBool(PreferenceKey.DarkTheme, enabled)

    suspend fun setHasManuallyChosenTheme(chosen: Boolean) =
        writeBool(PreferenceKey.HasManuallyChosenTheme, chosen)

    suspend fun setHiddenSettingsApplied(applied: Boolean) =
        writeBool(PreferenceKey.HiddenSettingsApplied, applied)

    suspend fun setPendingOptimization(pending: Boolean) =
        writeBool(PreferenceKey.PendingOptimization, pending)

    suspend fun setHasShownRestrictedDialog(shown: Boolean) =
        writeBool(PreferenceKey.HasShownRestrictedDialog, shown)

    suspend fun setDnsFilterEnabled(enabled: Boolean) =
        writeBool(PreferenceKey.DnsFilterEnabled, enabled)

    suspend fun setHasSeenDnsWarning(seen: Boolean) =
        writeBool(PreferenceKey.HasSeenDnsWarning, seen)

    suspend fun setAggressiveMode(enabled: Boolean) =
        writeBool(PreferenceKey.AggressiveMode, enabled)

    suspend fun clearPendingOptimization() =
        writeBool(PreferenceKey.PendingOptimization, false)

    /**
     * Синхронное получение статуса pending optimization.
     * Возвращает false при ошибке DataStore.
     */
    suspend fun getPendingOptimization(): Boolean = runCatching {
        pendingOptimization.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getPendingOptimization failed: ${e.message}")
        false
    }

    /**
     * Синхронное получение статуса DNS filter.
     * Возвращает false при ошибке DataStore.
     */
    suspend fun getDnsFilterEnabled(): Boolean = runCatching {
        dnsFilterEnabled.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getDnsFilterEnabled failed: ${e.message}")
        false
    }

    // ═══════════════════════════════════════════════════════════════
    // String preferences
    // ═══════════════════════════════════════════════════════════════

    /**
     * Сохраняет JSON последнего отчёта оптимизации.
     * Используется для отображения истории и экспорта.
     */
    suspend fun setLastReportJson(json: String) = runCatching {
        context.dataStore.edit { prefs ->
            prefs[LAST_REPORT_KEY] = json
        }
    }.onFailure { e ->
        AppLog.e(TAG, "setLastReportJson failed: ${e.message}")
    }

    /**
     * Flow с JSON последнего отчёта.
     * Использует `catch` для защиты от corrupt DataStore.
     */
    val lastReportJson: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[LAST_REPORT_KEY] ?: "" }
        .catch { e ->
            AppLog.e(TAG, "lastReportJson flow error: ${e.message}")
            emit("")
        }

    /**
     * Синхронное получение JSON последнего отчёта.
     * Возвращает пустую строку при ошибке или отсутствии данных.
     */
    suspend fun getLastReportJson(): String = runCatching {
        lastReportJson.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getLastReportJson failed: ${e.message}")
        ""
    }

    // ═══════════════════════════════════════════════════════════════
    // OptimizationMode
    // ═══════════════════════════════════════════════════════════════

    /**
     * Flow с текущим режимом оптимизации (SIMPLE/PRO).
     * Использует `catch` для защиты от corrupt DataStore.
     */
    val optimizationMode: Flow<OptimizationMode> = context.dataStore.data
        .map { prefs ->
            OptimizationMode.fromString(prefs[OPTIMIZATION_MODE_KEY])
        }
        .catch { e ->
            AppLog.e(TAG, "optimizationMode flow error: ${e.message}")
            emit(OptimizationMode.SIMPLE)
        }

    /**
     * Устанавливает режим оптимизации.
     */
    suspend fun setOptimizationMode(mode: OptimizationMode) = runCatching {
        context.dataStore.edit { prefs ->
            prefs[OPTIMIZATION_MODE_KEY] = mode.name
        }
    }.onFailure { e ->
        AppLog.e(TAG, "setOptimizationMode failed: ${e.message}")
    }

    /**
     * Синхронное получение текущего режима оптимизации.
     * Возвращает SIMPLE при ошибке.
     */
    suspend fun getOptimizationMode(): OptimizationMode = runCatching {
        optimizationMode.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getOptimizationMode failed: ${e.message}")
        OptimizationMode.SIMPLE
    }

    // ═══════════════════════════════════════════════════════════════
    // Сброс всех настроек
    // ═══════════════════════════════════════════════════════════════

    /**
     * Сбрасывает все настройки в значения по умолчанию.
     * Используется при reinstall или по запросу пользователя.
     */
    suspend fun clearAll() = runCatching {
        context.dataStore.edit { prefs -> prefs.clear() }
        AppLog.i(TAG, "clearAll: все настройки сброшены")
    }.onFailure { e ->
        AppLog.e(TAG, "clearAll failed: ${e.message}")
    }

    // ═══════════════════════════════════════════════════════════════
    // Приватные хелперы
    // ═══════════════════════════════════════════════════════════════

    /**
     * Читает Boolean preference с защитой от ошибок.
     */
    private fun readBool(key: PreferenceKey, default: Boolean): Flow<Boolean> =
        context.dataStore.data
            .map { it[booleanPreferencesKey(key.name)] ?: default }
            .catch { e ->
                AppLog.e(TAG, "readBool(${key.name}) flow error: ${e.message}")
                emit(default)
            }

    /**
     * Записывает Boolean preference с защитой от ошибок.
     */
    private suspend fun writeBool(key: PreferenceKey, value: Boolean) = runCatching {
        context.dataStore.edit { it[booleanPreferencesKey(key.name)] = value }
    }.onFailure { e ->
        AppLog.e(TAG, "writeBool(${key.name}) failed: ${e.message}")
    }
}