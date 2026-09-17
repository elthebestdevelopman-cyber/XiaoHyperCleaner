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

    data object PendingSimpleMode : PreferenceKey {
        override val name = "pending_simple_mode"
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

    /** Прозрачность уведомлений: OFF исключает notif_* из плана (Аддендум C4). */
    data object NotifTransparency : PreferenceKey {
        override val name = "notif_transparency"
    }

    data object HasSeenDnsWarning : PreferenceKey {
        override val name = "has_seen_dns_warning"
    }

    data object LastReportJson : PreferenceKey {
        override val name = "last_report_json"
    }

    data object SimpleToggledSteps : PreferenceKey {
        override val name = "simple_toggled_steps"
    }

    /** Уровень диагностики в release: OFF/COMPACT/FULL (7 тапов по версии). */
    data object DiagLevelOverride : PreferenceKey {
        override val name = "diag_level_override"
    }

    data object OptimizationModeKey : PreferenceKey {
        override val name = "optimization_mode"
    }

    data object RestoreSnapshotJson : PreferenceKey {
        override val name = "restore_snapshot_json"
    }

    /** Динамический ключ кэша активностей: `act_cache_<pkg>_<versionCode>_<incremental>`. */
    data class ActivityCache(override val name: String) : PreferenceKey
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
class PreferencesManager(private val context: Context) : RestoreSnapshotStore, ActivityCacheStore {

    companion object {
        private const val TAG = "PreferencesManager"

        // Константы для строковых ключей — избегаем повторного создания
        private val LAST_REPORT_KEY = stringPreferencesKey(PreferenceKey.LastReportJson.name)
        private val OPTIMIZATION_MODE_KEY =
            stringPreferencesKey(PreferenceKey.OptimizationModeKey.name)
        private val SIMPLE_TOGGLED_KEY =
            stringPreferencesKey(PreferenceKey.SimpleToggledSteps.name)
        private val RESTORE_SNAPSHOT_KEY =
            stringPreferencesKey(PreferenceKey.RestoreSnapshotJson.name)
        private val DIAG_LEVEL_KEY =
            stringPreferencesKey(PreferenceKey.DiagLevelOverride.name)

        /** Префикс динамических ключей кэша активностей (ActivityCacheStore). */
        private const val ACTIVITY_CACHE_PREFIX = "act_cache_"
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

    val pendingSimpleMode: Flow<Boolean> =
        readBool(PreferenceKey.PendingSimpleMode, false)

    val hasShownRestrictedDialog: Flow<Boolean> =
        readBool(PreferenceKey.HasShownRestrictedDialog, false)

    val dnsFilterEnabled: Flow<Boolean> =
        readBool(PreferenceKey.DnsFilterEnabled, false)

    val hasSeenDnsWarning: Flow<Boolean> =
        readBool(PreferenceKey.HasSeenDnsWarning, false)

    suspend fun setDarkTheme(enabled: Boolean) =
        writeBool(PreferenceKey.DarkTheme, enabled)

    suspend fun setHasManuallyChosenTheme(chosen: Boolean) =
        writeBool(PreferenceKey.HasManuallyChosenTheme, chosen)

    suspend fun setHiddenSettingsApplied(applied: Boolean) =
        writeBool(PreferenceKey.HiddenSettingsApplied, applied)

    suspend fun setPendingOptimization(pending: Boolean) =
        writeBool(PreferenceKey.PendingOptimization, pending)

    suspend fun setPendingSimpleMode(pending: Boolean) =
        writeBool(PreferenceKey.PendingSimpleMode, pending)

    suspend fun setHasShownRestrictedDialog(shown: Boolean) =
        writeBool(PreferenceKey.HasShownRestrictedDialog, shown)

    suspend fun setDnsFilterEnabled(enabled: Boolean) =
        writeBool(PreferenceKey.DnsFilterEnabled, enabled)

    /** Прозрачность уведомлений (дефолт ON): фильтр notif_* в PlanBuilder. */
    val notifTransparency: Flow<Boolean> = readBool(PreferenceKey.NotifTransparency, true)

    suspend fun setNotifTransparency(enabled: Boolean) =
        writeBool(PreferenceKey.NotifTransparency, enabled)

    suspend fun getNotifTransparency(): Boolean = runCatching {
        notifTransparency.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getNotifTransparency failed: ${e.message}")
        true
    }

    /**
     * Override уровня диагностики (OFF/COMPACT/FULL) для release-сборок.
     * null/пусто — уровень определяется дефолтом сборки (debug=FULL, release=COMPACT).
     */
    val diagLevelOverride: Flow<String?> = context.dataStore.data
        .map { it[DIAG_LEVEL_KEY]?.takeIf { raw -> raw.isNotBlank() } }
        .catch { e ->
            AppLog.e(TAG, "diagLevelOverride flow error: ${e.message}")
            emit(null)
        }

    suspend fun setDiagLevelOverride(level: String?) = runCatching {
        context.dataStore.edit { prefs ->
            if (level == null) prefs.remove(DIAG_LEVEL_KEY) else prefs[DIAG_LEVEL_KEY] = level
        }
    }.onFailure { e ->
        AppLog.e(TAG, "setDiagLevelOverride failed: ${e.message}")
    }

    suspend fun getDiagLevelOverride(): String? = runCatching {
        diagLevelOverride.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getDiagLevelOverride failed: ${e.message}")
        null
    }

    suspend fun setHasSeenDnsWarning(seen: Boolean) =
        writeBool(PreferenceKey.HasSeenDnsWarning, seen)

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

    suspend fun getPendingSimpleMode(): Boolean = runCatching {
        pendingSimpleMode.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getPendingSimpleMode failed: ${e.message}")
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
    // RestoreSnapshotStore — оригиналы настроек для точного отката
    // ═══════════════════════════════════════════════════════════════

    override suspend fun save(snapshot: RestoreSnapshot) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[RESTORE_SNAPSHOT_KEY] = snapshot.toJson()
            }
        }.onFailure { e ->
            AppLog.e(TAG, "save restore snapshot failed: ${e.message}")
        }
    }

    override suspend fun load(): RestoreSnapshot? = runCatching {
        context.dataStore.data.first()[RESTORE_SNAPSHOT_KEY]
    }.getOrNull()?.let { RestoreSnapshot.fromJson(it) }

    override suspend fun clear() {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs.remove(RESTORE_SNAPSHOT_KEY)
            }
        }.onFailure { e ->
            AppLog.e(TAG, "clear restore snapshot failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // checked_before простых тумблеров (блок 6: честный откат)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Записывает фактическое состояние тумблера в момент переключения
     * (`checked_before`) в снапшот отката. Если снапшота ещё не было —
     * создаёт пустой контейнер: состояние тумблеров должно переживать
     * перезапуск приложения.
     */
    suspend fun recordSimpleToggleState(stepId: String, checkedBefore: Boolean) = runCatching {
        context.dataStore.edit { prefs ->
            val current = prefs[RESTORE_SNAPSHOT_KEY]?.let { RestoreSnapshot.fromJson(it) }
                ?: RestoreSnapshot(
                    settings = emptyMap(),
                    dnsApplied = false,
                    dnsMode = null,
                    dnsHost = null
                )
            val updated = current.copy(
                simpleToggleStates = current.simpleToggleStates + (stepId to checkedBefore)
            )
            prefs[RESTORE_SNAPSHOT_KEY] = updated.toJson()
            AppLog.i(TAG, "recordSimpleToggleState: $stepId checked_before=$checkedBefore")
        }
    }.onFailure { e ->
        AppLog.e(TAG, "recordSimpleToggleState($stepId) failed: ${e.message}")
    }

    /**
     * Сохранённые `checked_before` простых тумблеров.
     * Пустая map у снапшотов старого формата (миграция: откат по инверсии target).
     */
    suspend fun getSimpleToggleStates(): Map<String, Boolean> = runCatching {
        context.dataStore.data.first()[RESTORE_SNAPSHOT_KEY]
    }.getOrNull()?.let { RestoreSnapshot.fromJson(it)?.simpleToggleStates } ?: emptyMap()

    // ═══════════════════════════════════════════════════════════════
    // Кэш найденных активностей (ActivityCacheStore)
    // ══════════════════════════════════════════════════════════════

    override suspend fun loadActivityCache(cacheKey: String): String? = runCatching {
        context.dataStore.data.first()[stringPreferencesKey(cacheKey)]
    }.getOrNull()

    /**
     * Сохраняет кэш скана. Ключ включает версию пакета и incremental прошивки,
     * поэтому старые записи того же пакета удаляются — кэш не растёт бесконечно.
     */
    override suspend fun saveActivityCache(cacheKey: String, json: String) {
        runCatching {
            context.dataStore.edit { prefs ->
                val pkg = cacheKey.substringAfter("act_cache_").substringBeforeLast('_')
                prefs.asMap().keys
                    .filter { it.name.startsWith(ACTIVITY_CACHE_PREFIX) && it.name != cacheKey }
                    .filter { it.name.startsWith("$ACTIVITY_CACHE_PREFIX${pkg}_") }
                    .forEach { prefs.remove(it) }
                prefs[stringPreferencesKey(cacheKey)] = json
            }
        }.onFailure { e ->
            AppLog.e(TAG, "saveActivityCache failed: ${e.message}")
        }
    }

    override suspend fun clearActivityCache(pkg: String) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs.asMap().keys
                    .filter { it.name.startsWith("$ACTIVITY_CACHE_PREFIX${pkg}_") }
                    .forEach { prefs.remove(it) }
            }
        }.onFailure { e ->
            AppLog.e(TAG, "clearActivityCache($pkg) failed: ${e.message}")
        }
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

    /** ID шагов Simple Mode, где тумблер реально переключали (для «Вернуть назад») */
    suspend fun addSimpleToggledStep(stepId: String) = runCatching {
        context.dataStore.edit { prefs ->
            val cur = prefs[SIMPLE_TOGGLED_KEY].orEmpty()
            val set = cur.split(',').filter { it.isNotBlank() }.toMutableSet()
            set.add(stepId)
            prefs[SIMPLE_TOGGLED_KEY] = set.joinToString(",")
        }
    }

    suspend fun getSimpleToggledSteps(): Set<String> = runCatching {
        val raw = context.dataStore.data.first()[SIMPLE_TOGGLED_KEY].orEmpty()
        raw.split(',').filter { it.isNotBlank() }.toSet()
    }.getOrElse { emptySet() }

    suspend fun clearSimpleToggledSteps() = runCatching {
        context.dataStore.edit { prefs ->
            prefs.remove(SIMPLE_TOGGLED_KEY)
            // checked_before больше не нужен: откат завершён (или отменён).
            val current = prefs[RESTORE_SNAPSHOT_KEY]?.let { RestoreSnapshot.fromJson(it) }
            if (current != null && current.simpleToggleStates.isNotEmpty()) {
                prefs[RESTORE_SNAPSHOT_KEY] = current.copy(simpleToggleStates = emptyMap()).toJson()
            }
        }
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