package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /** Момент открытия установщика предшагом (0 — предшаг не запускался). */
    data object SelfApkOpenedAt : PreferenceKey {
        override val name = "self_apk_opened_at"
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

    /**
     * Ловец окна установки: снимает «Получать рекомендации» на экране настроек
     * установщика, пока живо окно установки. Дефолт ON — решение владельца:
     * галочка возвращается после обновления системы, механизм нужен постоянный.
     */
    data object InstallerWatcherEnabled : PreferenceKey {
        override val name = "installer_watcher_enabled"
    }

    /**
     * Отпечаток сборки, для которой ловец уже отработал (флаг цикла). Сброс данных
     * приложения стирает значение, обновление системы меняет отпечаток — оба события
     * снимают признак автоматически, вмешательства пользователя не требуется.
     */
    data object InstallerWatcherCycle : PreferenceKey {
        override val name = "installer_watcher_cycle"
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

    /** Последний отчёт read-only обхода экранов (краулер). */
    data object CrawlReportJson : PreferenceKey {
        override val name = "crawl_report_json"
    }

    /** Запомненные имена папок рабочего стола по шагам (обучение шага папок). */
    data object FolderNameHintsJson : PreferenceKey {
        override val name = "folder_name_hints_json"
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
        private val CRAWL_REPORT_KEY =
            stringPreferencesKey(PreferenceKey.CrawlReportJson.name)
        private val FOLDER_NAME_HINTS_KEY =
            stringPreferencesKey(PreferenceKey.FolderNameHintsJson.name)

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

    /**
     * Момент открытия установщика предшагом. 0 — предшаг не запускался (штатный старт):
     * такие pending восстанавливаются как раньше, без сверки с установкой.
     */
    suspend fun getSelfApkOpenedAt(): Long = runCatching {
        context.dataStore.data.first()[longPreferencesKey(PreferenceKey.SelfApkOpenedAt.name)] ?: 0L
    }.getOrElse { e ->
        AppLog.w(TAG, "getSelfApkOpenedAt failed: ${e.message}")
        0L
    }

    suspend fun setSelfApkOpenedAt(openedAtMs: Long) = runCatching {
        context.dataStore.edit { it[longPreferencesKey(PreferenceKey.SelfApkOpenedAt.name)] = openedAtMs }
    }.onFailure { e ->
        AppLog.w(TAG, "setSelfApkOpenedAt($openedAtMs) failed: ${e.message}")
    }

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
     * Ловец окна установки: снятие «Получать рекомендации» при установке.
     *
     * Legacy-флаг: до 1.0-beta2 тумблер был в меню (пункт снят — ловец не должен спорить
     * с пользователем). Сохранённое значение помнится и уважается, отсутствие значения =
     * включён; пишет флаг только legacy-UI, поэтому сеттера здесь нет.
     */
    val installerWatcherEnabled: Flow<Boolean> =
        readBool(PreferenceKey.InstallerWatcherEnabled, true)

    suspend fun getInstallerWatcherEnabled(): Boolean = runCatching {
        installerWatcherEnabled.first()
    }.getOrElse { e ->
        AppLog.w(TAG, "getInstallerWatcherEnabled failed: ${e.message}")
        true
    }

    /** Отпечаток сборки, для которой цикл ловца закрыт (пусто — цикл открыт). */
    val installerWatcherCycle: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[stringPreferencesKey(PreferenceKey.InstallerWatcherCycle.name)] }
        .catch { e ->
            AppLog.e(TAG, "installerWatcherCycle flow error: ${e.message}")
            emit(null)
        }

    suspend fun setInstallerWatcherCycle(fingerprint: String) = runCatching {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(PreferenceKey.InstallerWatcherCycle.name)] = fingerprint
        }
    }.onFailure { e ->
        AppLog.w(TAG, "setInstallerWatcherCycle failed: ${e.message}")
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
     * In-memory зеркало checked_before: тумблерный путь раннера НЕ ждёт DataStore.
     * На MIUI запись под нагрузкой упиралась в таймаут DataStore (~5 с) и съедала
     * бюджет шага — шаг падал `timeout`. Зеркало отдаёт состояние сразу, запись
     * снапшота уходит в фон.
     */
    private val simpleToggleMirror = ConcurrentHashMap<String, Boolean>()

    /** In-memory зеркало «шаг тумблерили»: список отката не ждёт фоновой записи снапшота. */
    private val simpleToggledMirror: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Снимок зеркала checked_before. internal — для тестируемости порядка записи. */
    internal val toggleStateMirrorSnapshot: Map<String, Boolean>
        get() = simpleToggleMirror.toMap()

    /** Снимок зеркала «шаг тумблерили». internal — для тестируемости. */
    internal val toggledMirrorSnapshot: Set<String>
        get() = simpleToggledMirror.toSet()

    /** Фоновые записи зеркала: шаг не блокируют, ошибка только логируется. */
    private val mirrorWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Записывает фактическое состояние тумблера в момент переключения
     * (`checked_before`). Не приостанавливает вызывающего: сначала зеркало
     * (его видит откат), затем фоном — снапшот отката. Если снапшота ещё не было,
     * создаётся пустой контейнер: состояние тумблеров переживает перезапуск.
     */
    fun recordSimpleToggleState(
        stepId: String,
        checkedBefore: Boolean,
        markToggled: Boolean = true
    ) {
        // Самое раннее значение побеждает: повторная запись того же шага не перезаписывает
        // исходное состояние первого прогона (D2).
        simpleToggleMirror.putIfAbsent(stepId, checkedBefore)
        if (markToggled) simpleToggledMirror.add(stepId)
        AppLog.i(
            TAG,
            "recordSimpleToggleState: $stepId checked_before=$checkedBefore " +
                "markToggled=$markToggled (mirror)"
        )
        mirrorWriteScope.launch { persistSimpleToggleState(stepId, checkedBefore, markToggled) }
    }

    // internal — для тестируемости (PreferencesManagerSnapshotTest): детерминированная запись
    // снапшота вместо ожидания фонового IO за `recordSimpleToggleState`.
    internal suspend fun persistSimpleToggleState(
        stepId: String,
        checkedBefore: Boolean,
        markToggled: Boolean
    ) = runCatching {
        context.dataStore.edit { prefs ->
            val current = prefs[RESTORE_SNAPSHOT_KEY]?.let { RestoreSnapshot.fromJson(it) }
                ?: RestoreSnapshot(
                    settings = emptyMap(),
                    dnsApplied = false,
                    dnsMode = null,
                    dnsHost = null
                )
            // Оба факта (checked_before + «шаг тумблерили») — одной записью: иначе крах между
            // тапом и вердиктом снова терял бы шаг из отката (дефект D2, msa в rmvgvhbzu).
            prefs[RESTORE_SNAPSHOT_KEY] = current
                .withSimpleToggle(stepId, checkedBefore, markToggled)
                .toJson()
        }
    }.onFailure { e ->
        AppLog.w(TAG, "recordSimpleToggleState($stepId) persist failed: ${e.message}")
    }

    /**
     * Сохранённые `checked_before` простых тумблеров: снапшот + in-memory зеркало
     * (зеркало приоритетнее: оно новее, пока фоновая запись не завершилась).
     * Пустая map у снапшотов старого формата (миграция: откат по инверсии target).
     */
    suspend fun getSimpleToggleStates(): Map<String, Boolean> {
        val stored = runCatching {
            context.dataStore.data.first()[RESTORE_SNAPSHOT_KEY]
        }.getOrNull()?.let { RestoreSnapshot.fromJson(it)?.simpleToggleStates } ?: emptyMap()
        // Приоритет у сохранённого снапшота, а не у зеркала: правило «первое исходное значение
        // побеждает» держится записью только при отсутствии ключа (D2). Зеркало добавляет
        // лишь то, что ещё не успело сохраниться.
        return simpleToggleMirror + stored
    }

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
    // Отчёт read-only обхода экранов (ScreenCrawler)
    // ══════════════════════════════════════════════════════════════

    /** Сохраняет последний отчёт краулера (перезаписывается, кэш не растёт). */
    suspend fun saveCrawlReport(json: String) {
        runCatching {
            context.dataStore.edit { prefs -> prefs[stringPreferencesKey(CRAWL_REPORT_KEY.name)] = json }
        }.onFailure { e -> AppLog.e(TAG, "saveCrawlReport failed: ${e.message}") }
    }

    suspend fun getCrawlReport(): String? = runCatching {
        context.dataStore.data.first()[stringPreferencesKey(CRAWL_REPORT_KEY.name)]
    }.getOrNull()

    // ═══════════════════════════════════════════════════════════════
    // Имена папок рабочего стола (обучение шага «Рекомендации в папках»)
    // ══════════════════════════════════════════════════════════════

    /**
     * Запоминает имена папок, в которых реально нашлась секция рекомендаций: следующий
     * прогон на этом устройстве открывает их сразу, без перебора. Карта `ключ → имя`
     * в JSON перезаписывается целиком (ключ = шаг + регион + локаль).
     */
    suspend fun saveFolderNameHints(json: String) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[stringPreferencesKey(FOLDER_NAME_HINTS_KEY.name)] = json
            }
        }.onFailure { e -> AppLog.e(TAG, "saveFolderNameHints failed: ${e.message}") }
    }

    suspend fun getFolderNameHints(): String? = runCatching {
        context.dataStore.data.first()[stringPreferencesKey(FOLDER_NAME_HINTS_KEY.name)]
    }.getOrNull()

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

    /**
     * Шаги для отката: объединение нового множества снапшота, легаси-ключа и зеркала.
     * Легаси-ключ остаётся источником для снапшотов старого формата (в них множества нет),
     * поэтому объединение, а не замена (D2 п.4).
     */
    suspend fun getSimpleToggledSteps(): Set<String> {
        val prefs = runCatching { context.dataStore.data.first() }.getOrNull()
        val fromSnapshot = prefs?.get(RESTORE_SNAPSHOT_KEY)
            ?.let { RestoreSnapshot.fromJson(it)?.simpleToggledSteps } ?: emptySet()
        val legacy = prefs?.get(SIMPLE_TOGGLED_KEY).orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()
        return fromSnapshot + legacy + simpleToggledMirror
    }

    suspend fun clearSimpleToggledSteps() = runCatching {
        clearToggledState {
            context.dataStore.edit { prefs ->
                prefs.remove(SIMPLE_TOGGLED_KEY)
                // Откат завершён (или отменён): оба представления в снапшоте снимаются одной
                // записью, чтобы список отката не мог разъехаться с checked_before.
                val current = prefs[RESTORE_SNAPSHOT_KEY]?.let { RestoreSnapshot.fromJson(it) }
                if (current != null && (
                    current.simpleToggleStates.isNotEmpty() || current.simpleToggledSteps.isNotEmpty()
                    )
                ) {
                    prefs[RESTORE_SNAPSHOT_KEY] = current.copy(
                        simpleToggleStates = emptyMap(),
                        simpleToggledSteps = emptySet()
                    ).toJson()
                }
            }
            true
        }
    }

    /**
     * Снятие состояния отката: зеркало чистится ТОЛЬКО после успешной записи — при сбое
     * DataStore значения нужны следующей попытке отката (инвариант целостности).
     * [writeStore] возвращает true, если состояние снято в хранилище; исключение внутри
     * пробрасывается, и тогда зеркало тоже не чистится.
     * internal — для тестируемости (PreferencesManagerSnapshotTest).
     */
    internal suspend fun clearToggledState(writeStore: suspend () -> Boolean): Boolean {
        val written = writeStore()
        if (written) {
            simpleToggleMirror.clear()
            simpleToggledMirror.clear()
        }
        return written
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