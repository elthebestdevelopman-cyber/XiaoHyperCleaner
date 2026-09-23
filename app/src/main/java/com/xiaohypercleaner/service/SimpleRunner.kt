package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.ActivityScanner
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.ComponentVerifier
import com.xiaohypercleaner.data.ConsentWallHandler
import com.xiaohypercleaner.data.DirectIntentNavigator
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.ScanOrchestrator
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SemanticGate
import com.xiaohypercleaner.data.SimplePlan
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.data.SwitchFinder
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
import com.xiaohypercleaner.util.DiagnosticSnapshotManager
import com.xiaohypercleaner.util.StepDiagnostics
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Автоматизация шагов Simple Mode через Accessibility.
 *
 * ИНТЕГРАЦИЯ (пункты оптимизации 1–6):
 * П.1 — Базовая автоматизация и Accessibility Service.
 * П.2 — Адаптивные тайм-ауты (учет HyperOS и глубины маршрута).
 * П.3 — Полная интеграция с AdaptiveCatalog (мердж текстов, пакетов, путей).
 * П.4 — Структурный поиск ⋮/⚙ (4 уровня: текст, description, позиция, жест).
 * П.5 — Перехват системных диалогов (аудио, приложения по умолчанию).
 * П.6 — Пропуск повторного сброса настроек (переиспользование окна).
 */
class SimpleRunner(private val service: AdbEnablerService) {

    companion object {
        private const val TAG = "SimpleRunner"

        // ═══════════════════════════════════════════════════════════════
        // П.2: Адаптивные таймауты
        // ═══════════════════════════════════════════════════════════════
        private const val BASE_TIMEOUT_MS = 16_000L
        private const val LONG_PATH_TIMEOUT_MS = 22_000L
        private const val VERY_LONG_PATH_TIMEOUT_MS = 26_000L
        private const val APP_TIMEOUT_MS = 20_000L
        private const val APP_LONG_PATH_TIMEOUT_MS = 26_000L
        private const val APP_VERY_LONG_PATH_TIMEOUT_MS = 32_000L
        private const val HYPEROS_MULTIPLIER = 1.3f
        private const val MSA_TIMEOUT_MS = 40_000L
        private const val SECURITY_CLEANER_TIMEOUT_MS = 22_500L

        private val SPECIAL_TIMEOUTS = mapOf(
            "msa" to MSA_TIMEOUT_MS,
            "security_sys" to SECURITY_CLEANER_TIMEOUT_MS,
            "cleaner" to SECURITY_CLEANER_TIMEOUT_MS,
            // Перебор кандидатов-папок и активностей установщика: шаги дольше базовых.
            "folder_recommendations" to 34_000L,
            "installer_recommendations" to 26_000L,
            // Mi Music: три уровня drill (☰ → «Настройки» → «Расширенные настройки»), целевые
            // тумблеры лежат в разделе ниже сгиба — к бюджету добавляются прокрутки (rmuef7nbf).
            "music_sys" to 26_000L
        )

        // ═══════════════════════════════════════════════════════════════
        // П.6: Возобновляемые шаги (не требуют сброса настроек)
        // ═══════════════════════════════════════════════════════════════
        private val SETTINGS_RESUMABLE_STEPS = setOf(
            "msa", "sys_recommendations", "ads_personalization", "ux_program",
            "google_diagnostics", "carousel"
        )

        // ═══════════════════════════════════════════════════════════════
        // П.5: Системные диалоги
        // ═══════════════════════════════════════════════════════════════
        // MEDIA-шаги перенесены в ConsentWallHandler (allow аудио-разрешения для music_sys/mivideo).

        // ═══════════════════════════════════════════════════════════════
        // П.4: Структурный поиск ⋮/⚙
        // ═══════════════════════════════════════════════════════════════
        /**
         * Кнопки-меню в шапке экрана. Отдельный список: в списках элементов встречается
         * «Больше меню» (Музыка: `item_menu` у каждого трека) — общий overflow-поиск
         * цеплял строку списка, тапал не туда, и меню шапки не открывалось
         * (прогон rmua2sd7x: music_sys drill_failed на уровне 1).
         */
        private val HEADER_MENU_TEXTS = listOf(
            "Показать меню", "Show menu", "Открыть меню", "Меню", "Menu"
        )

        private val OVERFLOW_TEXTS = listOf(
            "⋮", "Ещё", "Еще", "Больше", "Меню",
            "⚙", "⚙️", "Настройки", "Настройка",
            "More", "More options", "Menu", "Settings",
            "Больше опций", "Дополнительно", "Дополнительные настройки"
        )

        private val OVERFLOW_GESTURE_POINTS = listOf(
            Pair(0.96f, 0.06f), Pair(0.90f, 0.06f),
            Pair(0.96f, 0.12f), Pair(0.90f, 0.12f)
        )

        /** Уровни-меню drill: открываются overflow-поиском (текст или contentDescription). */
        private val MENU_LEVEL_TEXTS = setOf(
            "⋮", "", "⚙️", "Ещё", "Еще", "Больше", "Дополнительно", "More", "Más", "Menu"
        )

        /** Пакет системных настроек (проверка foreground для системных шагов). */
        private const val SETTINGS_PACKAGE = "com.android.settings"
        /** Сколько папок рабочего стола перебираем, прежде чем признать шаг неприменимым. */
        private const val MAX_HOME_FOLDERS = 3

        /** Предельная глубина обхода превью папки лаунчера (icon_container → preview → itemN). */
        private const val FOLDER_PREVIEW_DEPTH = 3

        /** Долгий тап: контекстное меню папки (HyperOS 2/3 → «Изменить папку»). */
        private const val LONG_PRESS_MS = 800L

        /** Пауза после открытия папки: поповер анимируется. */
        private const val FOLDER_OPEN_DELAY_MS = 700L

        /** Сколько активностей установщика пробуем, прежде чем признать шаг неприменимым. */
        private const val MAX_INSTALLER_CANDIDATES = 5

        /** Сколько иконок рабочего стола проверяем на «это папка», прежде чем сдаться. */
        private const val MAX_FOLDER_PROBES = 3

        /** Пакеты-лаунчеры MIUI: папки рабочего стола есть только в них. */
        private val LAUNCHER_PACKAGES = listOf(
            "com.miui.home", "com.mi.android.globallauncher", "com.miui.launcher", "com.mi.global.home"
        )

        /** Префикс шагов управления уведомлениями приложений. */
        private const val NOTIF_PREFIX = "notif_"

        /** Признаки активности настроек установщика (активности установки не входят). */
        private val INSTALLER_SETTINGS_KEYWORDS =
            listOf("settings", "preference", "recommend", "advanced", "scan")

        /** Тексты подтверждения установки: по ним не тапаем никогда. */
        private val INSTALL_CONFIRM_TEXTS =
            listOf("установить", "установка", "install", "安装", "instalar", "instalir")

        /** msa: максимум ожидания включённой кнопки отзыва, поллинг и пауза на сам отзыв. */
        private const val MSA_REVOKE_WAIT_MAX_MS = 11_000L
        private const val MSA_CONFIRM_POLL_MS = 500L

        /** Отсчётный хвост кнопки MIUI: «(9 с)», «(9s)», «(9)» — кнопка ещё неактивна. */
        private val COUNTDOWN_LABEL_REGEX = Regex("\\(\\s*\\d+\\s*(с|s)?\\s*\\)")
        private const val MSA_REVOKE_SETTLE_MS = 2_500L

        /** Фолбэк-действие варианта: очистка данных + отклонение приветствия. */
        private const val FALLBACK_ACTION_CLEAR_DATA = "clear_data_decline"

        /** Точка входа варианта: маршрут через Настройки, а не через приложение. */
        private const val ENTRY_SETTINGS = "settings"

        /** Причины, при которых применяется фолбэк-действие варианта. */
        private val FALLBACK_REASONS =
            setOf("drill_failed", "switch_not_found", "low_confidence", "verify_failed")

        // ═══════════════════════════════════════════════════════════════
        // Общие константы
        // ═══════════════════════════════════════════════════════════════
        private const val UI_SETTLE_DELAY_MS = 900L
        private const val APP_LAUNCH_DELAY_MS = 2000L
        private const val CONTENT_WAIT_MS = 2500L

        /**
         * Готовность приложения после запуска: холодный старт MIUI-приложений
         * (GetApps — WebView-магазин) не укладывается в фиксированные 2 c, из-за
         * чего раннер сжигал бюджет на «App not ready» по всем кандидатам и
         * стартовал бурение на сплэше (прогон rmu8qhjhi).
         */
        private const val APP_READY_WAIT_MS = 6_000L

        /** Готовность экрана приложения перед бурением: подписи первого уровня маршрута. */
        private const val APP_SCREEN_WAIT_MS = 6_000L
        private const val APP_READY_POLL_MS = 400L
        private const val CONFIRM_RETRY_MS = 2500L
        private const val SWITCH_FALLBACK_SCROLLS = 4

        /**
         * Шаг неприменим на этом устройстве: экрана/строк шага здесь нет вовсе.
         * Отдельный исход (skip), а не FAIL — иначе отчёт врёт (POCO Launcher).
         */
        internal const val NOT_APPLICABLE = "not_applicable"

        /** Поллинг проверки входного экрана (notif_*: подпись приложения). */
        private const val ENTRY_POLL_MS = 250L

        /** Variation selector эмодзи: «⚙» и «⚙️» — один и тот же уровень-меню. */
        private const val EMOJI_VARIATION_SELECTOR = "\uFE0F"

    /** Scroll-until-found для уровней drill: до 4 прокруток на уровень. */
    private const val DRILL_SCROLL_TRIES = 4

    /** Сколько совпавших узлов уровня пробуем, прежде чем признать уровень непройденным. */
    private const val DRILL_LEVEL_ATTEMPTS = 3

    /** Повторы уровня drill после закрытия всплывшего диалога (permission целевого приложения). */
    private const val DRILL_CONSENT_RETRIES = 2
        private const val FRESH_DEVICE_DISMISS_LIMIT = 3

        /** Гейт оверлея: ожидание восстановления окна (Аддендум A4). */
        private const val OVERLAY_GATE_WAIT_MS = 2000L
        private const val OVERLAY_GATE_POLL_MS = 100L

        private val SYSTEM_DIALOG_SKIPS = listOf(
            "Пропустить", "Пропустить настройку", "Не сейчас", "Закрыть", "Отозвать", "Отмена"
        )

        @Volatile
        var isRunning: Boolean = false; private set
        @Volatile
        var currentStepId: String? = null; private set
        @Volatile
        var lastFailureReason: String? = null; private set
    }

    data class Result(val success: Boolean, val reason: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: kotlinx.coroutines.Job? = null

    @Volatile
    private var cancelled: Boolean = false

    /**
     * Последний провал уровня drill случился потому, что строки уровня НЕТ на экране
     * (в отличие от «нашли, но тап не дал эффекта»). Признак неприменимости app-шага.
     */
    // internal — для тестируемости (SimpleRunnerDrillTest).
    internal var lastDrillLevelNotFound: Boolean = false
    // lateinit: профиль устанавливается в run(). Прежний eager-detect был мёртвым:
    // результат перезаписывался в run() и никогда не читался, а на mock-сервисе
    // ронял конструктор (NPE на resources.configuration).
    private lateinit var romProfile: RomProfile

    // П.6: Флаг переиспользования окна настроек
    private var canResumeSettings: Boolean = false

    /** Подписи приложений для проверки входа notif_*-шагов (кэш на прогон). */
    private val appLabelCache = HashMap<String, String>()

    // ─── Fresh-device state ───────────────────────────────────────────────
    private var freshDeviceDismisses = 0
    private var freshDeviceActive = false
    private var mutedForFreshDevice = false
    private var originalVolume: Int = -1

    private fun muteMediaVolume(): Int {
        val audio = service.getSystemService(AudioManager::class.java) ?: return -1
        val prev = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (prev > 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            AppLog.i(TAG, "media muted (was=$prev)")
        }
        return prev
    }

    private fun restoreMediaVolume(prev: Int) {
        if (prev < 0) return
        service.getSystemService(AudioManager::class.java)
            ?.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
        AppLog.i(TAG, "media volume restored to $prev")
    }

    private fun markFreshDevice() {
        if (freshDeviceActive) return
        freshDeviceActive = true
        freshDeviceDismisses = 0
        originalVolume = muteMediaVolume()
        mutedForFreshDevice = originalVolume > 0
    }

    private fun cleanupFreshDevice() {
        if (!freshDeviceActive) return
        if (mutedForFreshDevice) restoreMediaVolume(originalVolume)
        freshDeviceActive = false
        mutedForFreshDevice = false
        freshDeviceDismisses = 0
        originalVolume = -1
    }

    private fun freshDeviceDismiss(text: String): Boolean {
        if (!freshDeviceActive || freshDeviceDismisses >= FRESH_DEVICE_DISMISS_LIMIT) return false
        freshDeviceDismisses++
        AppLog.i(
            TAG,
            "fresh-device dismiss ($freshDeviceDismisses/$FRESH_DEVICE_DISMISS_LIMIT): '$text'"
        )
        return true
    }

    // ─── Public API ───────────────────────────────────────────────────────
    fun run(step: SimpleSteps.Step, profile: RomProfile, callback: (Result) -> Unit) {
        cancel()
        cancelled = false
        isRunning = true
        currentStepId = step.id
        lastFailureReason = null
        romProfile = profile
        // Семантика шага (keywords/markers/consent) должна быть загружена.
        SemanticCatalog.ensureLoaded(service)
        // Выбираем вариант каталога по fingerprint (global_ru / cn_hyperos).
        AdaptiveCatalog.selectVariant(service, profile)

        val timeout = computeTimeout(step, profile)
        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${timeout}ms)")

        job = scope.launch {
            val start = System.currentTimeMillis()
            // Размер плана (префильтр) в диагностике: total на оверлее = размер плана.
            val planTotal = SimplePlan.total().takeIf { it > 0 } ?: SimpleSteps.ALL.size
            StepDiagnostics.stepStart(step.id, 0, planTotal, null, profile)

            val result = try {
                val r = withTimeoutOrNull(timeout) { runInternal(step, profile) } ?: Result(
                    false,
                    "timeout"
                )

                val root = service.rootInActiveWindow
                StepDiagnostics.stepResult(
                    step.id,
                    r.success,
                    r.reason ?: if (r.success) "ok" else "unknown",
                    System.currentTimeMillis() - start,
                    root,
                    service
                )

                if (!r.success) {
                    val failureReason = r.reason ?: "unknown"
                    DiagnosticSnapshotManager.captureAndSaveSnapshot(
                        service,
                        step.id,
                        failureReason,
                        root,
                        profile,
                        root?.packageName?.toString()
                    )
                    DiagnosticSnapshotManager.captureScreenshot(service, step.id)
                }
                recycleNode(root)
                r
            } catch (e: Exception) {
                AppLog.e(TAG, "Step ${step.id} failed: ${e.message}", e)
                lastFailureReason = "error"
                Result(false, "error")
            } finally {
                cleanupFreshDevice()
                isRunning = false
                currentStepId = null
            }
            callback(result)
        }
    }

    // П.2: Расчёт адаптивного таймаута
    private fun computeTimeout(step: SimpleSteps.Step, profile: RomProfile): Long {
        SPECIAL_TIMEOUTS[step.id]?.let { return it }
        val isApp = step.launchPackage != null
        val drillDepth = step.drillPath.size

        val base = when {
            isApp && drillDepth >= 4 -> APP_VERY_LONG_PATH_TIMEOUT_MS
            isApp && drillDepth == 3 -> APP_LONG_PATH_TIMEOUT_MS
            isApp -> APP_TIMEOUT_MS
            drillDepth >= 5 -> VERY_LONG_PATH_TIMEOUT_MS
            drillDepth == 4 -> LONG_PATH_TIMEOUT_MS
            else -> BASE_TIMEOUT_MS
        }

        val timeout = if (profile.hyperOsHint) (base * HYPEROS_MULTIPLIER).toLong() else base
        return timeout.coerceIn(12_000L, 45_000L)
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        isRunning = false
        currentStepId = null
        AppLog.i(TAG, "Runner cancellation requested")
    }

    // ─── Internal execution ───────────────────────────────────────────────
    private suspend fun runInternal(stepParam: SimpleSteps.Step, profile: RomProfile): Result {
        // Семантический launchPackage (каталог) дополняет legacy-структуру шага.
        var step = applySemanticLaunchPackage(stepParam)
        if (cancelled) return Result(false, "cancelled")

        // Гейт целостности оверлея (Аддендум A4): навигация без окна прогресса запрещена.
        if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")

        // Папки рабочего стола: вход — лаунчер MIUI, маршрут Настроек не используется.
        if (step.actionType == SimpleSteps.ActionType.HOME_FOLDER_TOGGLE) {
            return toggleHomeFolderSuggestions(step)
        }

        // Настройки установщика: активность настроек проверки, установка APK не запускается.
        if (step.actionType == SimpleSteps.ActionType.INSTALLER_SETTINGS_TOGGLE) {
            return toggleInstallerRecommendations(step)
        }

        // Резолвинг пакета: вариантный каталог + семантическая таблица (visibility-aware).
        // Маршрут варианта может идти через Настройки (appvault_*: Рабочий стол → Лента
        // виджетов) — тогда приложение не запускаем и пакет не резолвим.
        // notif_*: точка входа — системный экран уведомлений приложения
        // (ACTION_APP_NOTIFICATION_SETTINGS). Discovery-скан приложения здесь вреден:
        // его явная компонента открывала ленту App Vault вместо уведомлений (rmu8lzcu9).
        val notifStep = step.id.startsWith("notif_")
        // Пакет-цель notif_*-шага: подпись приложения на экране уведомлений отличает
        // целевой экран от чужого (MIUI 13: неоткрывшийся интент оставлял шаг на
        // «Заблокированном экране», где ключевые слова шага тоже встречаются).
        val notifTarget = if (notifStep) resolveNotifTarget(step) else null
        val settingsEntry = SemanticCatalog.entry(step.id) == ENTRY_SETTINGS || notifStep
        if (notifStep) {
            AppLog.i(TAG, "notif step ${step.id}: маршрут через экран уведомлений Настроек")
            step = step.copy(launchPackage = null)
        } else if (settingsEntry) {
            AppLog.i(TAG, "variant entry=settings for ${step.id} — маршрут через Настройки")
            step = step.copy(launchPackage = null)
        }
        val resolvedPkg = if (settingsEntry) {
            null
        } else {
            AdaptiveCatalog.resolveInstalledPackageForGroup(service, step.id, profile)
                ?: AdaptiveCatalog.packagesForStep(service, step.id, candidatePackages(step), profile)
                    .firstOrNull { isInstalled(it) }
        }

        // Для app-шагов фактический целевой пакет важнее статического launchPackage:
        // иначе GetApps/App Vault уходят на несуществующие market/personalassistant.
        if (step.launchPackage != null && resolvedPkg != null && resolvedPkg != step.launchPackage) {
            AppLog.i(TAG, "launch package for ${step.id}: ${step.launchPackage} -> $resolvedPkg")
            step = step.copy(launchPackage = resolvedPkg)
        }

        // П.6: Умный сброс настроек
        if (step.launchPackage == null) {
            if (!canResumeSettings) resetSettingsToRoot()
        } else {
            resetToHome()
            delay(300)
        }

        // П.3: Открытие экрана через DirectIntentNavigator + discovery-скан активностей
        val legacyIntents = DirectIntentNavigator.buildIntentsForStep(service, step, resolvedPkg, profile)
        // Stage 3: после каждого интента проверяем экран по merged-маркерам и,
        // если открылся не тот экран, пробуем следующий интент.
        val verifyTexts = searchTextsFor(step)
        // Для шагов-приложений целевой экран достигается бурением, поэтому
        // проверка на этапе интента не нужна (иначе Settings-фолбэк уводит из приложения).
        // CLEAR_DATA_DECLINE идёт в App Info (APPLICATION_DETAILS_SETTINGS), а не в
        // приложение: discovery-скан приложения здесь запрещён (он открывал главный экран).
        // Вариант ОС переопределяет тип шага (Проводник: CLEAR_DATA_DECLINE → тумблер):
        // тогда нужен экран приложения, а не «Сведения о приложении», иначе шаг уходил
        // в App Info и падал (прогон rmu8lzcu9, filemanager).
        val variantToggle =
            SemanticCatalog.variantControl(step.id) == SemanticCatalog.ActionType.TOGGLE
        val isAppStep = step.launchPackage != null &&
            (step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE || variantToggle)
        // Discovery: явная компонента из candidates первична, неявный LAUNCHER — фолбэк.
        val plan = ScanOrchestrator.planNavigation(
            context = service,
            pkg = resolvedPkg.takeIf { isAppStep },
            legacyIntents = legacyIntents,
            keywords = verifyTexts + step.id.split('_'),
            cache = prefs
        )
        // Причина нуля скана видна в StepDiag: тихая деградация запрещена.
        StepDiagnostics.note(
            step.id,
            "SCAN",
            "reason=${plan.scanReason} candidates=${plan.candidates.size} cached=${plan.fromCache}"
        )
        val intents = plan.orderedIntents()
        // Для шагов-приложений первым идёт launcher-интент от PackageManager: только
        // он открывает приложение стабильно (GetApps: внутренние активности сканера
        // окно не поднимают, и 4 попытки × 6 c съедали бюджет шага — прогон rmua0pt7i,
        // getapps → timeout). Остальная цепочка остаётся фолбэком.
        val pmLauncher = if (isAppStep) {
            resolvedPkg?.let { pkg ->
                runCatching { service.packageManager.getLaunchIntentForPackage(pkg) }
                    .getOrNull()
                    ?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
            }
        } else {
            null
        }
        val attemptIntents = if (pmLauncher != null) listOf(pmLauncher) + intents else intents
        var screenOpened = false
        for (intent in attemptIntents) {
            if (cancelled) return Result(false, "cancelled")
            try {
                service.startActivity(intent)
                screenOpened = true
                if (isAppStep) {
                    // Для app-шагов: ждём целевой пакет в foreground с непустым
                    // деревом. Жёсткие 2 c не покрывали холодный старт MIUI.
                    if (awaitForegroundApp(resolvedPkg, APP_READY_WAIT_MS)) {
                        AppLog.i(TAG, "App launched: $resolvedPkg, tree=true")
                        break
                    }
                    screenOpened = false
                } else if (awaitEntryScreen(step, notifTarget, verifyTexts, CONTENT_WAIT_MS)) {
                    break
                } else {
                    AppLog.w(TAG, "Экран не подтверждён после интента, пробуем следующий")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "DirectIntentNavigator failed: ${e.message}")
            }
        }

        if (!screenOpened) {
            for (intent in step.intents) {
                if (cancelled) return Result(false, "cancelled")
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    service.startActivity(intent)
                    screenOpened = true
                    break
                } catch (e: Exception) {
                    AppLog.w(TAG, "step.intents fallback failed: ${e.message}")
                }
            }
        }

        // Последний рубеж для app-шагов без CATEGORY_LAUNCHER (Безопасность, Загрузки,
        // GetApps): launcher-интент у PackageManager — он не зависит от того, нашёл ли
        // его queryIntentActivities. Без этого шаги объявлялись no_screen_opened.
        if (!screenOpened) {
            val pkg = step.launchPackage
            if (pkg != null) {
                val launch = runCatching { service.packageManager.getLaunchIntentForPackage(pkg) }
                    .getOrNull()
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { service.startActivity(launch) }
                        .onSuccess {
                            AppLog.i(TAG, "launcher intent from PackageManager for $pkg")
                            screenOpened = true
                        }
                        .onFailure { AppLog.w(TAG, "pm launcher intent failed for $pkg: ${it.message}") }
                } else {
                    AppLog.w(TAG, "no launcher intent for $pkg — шаг без точки входа")
                }
            }
        }

        if (!screenOpened) return Result(false, "no_screen_opened")

        delay(if (step.launchPackage != null) APP_LAUNCH_DELAY_MS else UI_SETTLE_DELAY_MS)
        // Закрытие видеорекламы при запуске приложения (Mi Music, GetApps и др.).
        // Реклама блокирует доступ к настройкам — ищем крестик или кнопку "Пропустить".
        if (step.launchPackage != null) {
            ConsentWallHandler.dismissVideoAdsUntilSettled(service, step.id)
        }
        // Единая точка входа для системных диалогов (welcome/permission/dismiss, Аддендум B)
        val consentHandled = handleConsentWalls(step)
        if (consentHandled > 0 && !isForegroundTarget(step, resolvedPkg)) {
            // Стена согласия могла увести с целевого экрана: один relaunch, дальше — без догадок.
            AppLog.w(TAG, "consent accepted but fg not target for ${step.id} — relaunch once")
            if (relaunchOnce(intents)) handleConsentWalls(step)
        }

        // Навигация по маршруту: авторитетный drillPath варианта ОС, иначе legacy + подсказки каталога.
        // skipDrill каталога означает «интент уже открыл целевой экран» — бурение не нужно.
        val mergedDrillPath = if (AdaptiveCatalog.isDrillSkipped(service, step.id)) {
            AppLog.i(TAG, "drill skipped by catalog flag id=${step.id}")
            emptyList()
        } else {
            semanticDrillPath(
                step,
                AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
            )
        }
        // Интенты MIUI умеют открывать не тот подэкран (msa: APP_PERM_EDITOR ведёт в
        // «Конфиденциальность» → «Разрешения»). Если после интентов мы не на корне
        // Настроек и цель шага не видна — возвращаемся в корень: drill пойдёт от
        // известной точки, а не с середины чужого экрана (прогон rmu8lzcu9).
        if (step.launchPackage == null && mergedDrillPath.isNotEmpty() &&
            !onTargetScreen(step) && !isSettingsRoot()
        ) {
            AppLog.i(TAG, "settings step ${step.id}: intent screen unusable — re-anchor to root")
            canResumeSettings = false
            resetSettingsToRoot()
        }
        // Resume: если интент уже открыл нужный экран, начинаем с текущего уровня
        // (совпадение с целью шага отменяет бурение вовсе).
        val startLevel = if (mergedDrillPath.isEmpty()) 0 else resumeDrillIndex(step, mergedDrillPath)
        // Экран приложения готов к бурению только после загрузки его UI (GetApps:
        // сплэш «Официальный магазин от Xiaomi» → магазин с нижней навигацией).
        // Resume (startLevel>0) и шаги Настроек/CLEAR_DATA (App Info) не ждут:
        // экран уже подтверждён интентом либо приложение шага не открывается.
        if (isAppStep && mergedDrillPath.isNotEmpty()) {
            awaitAppScreenReady(step, mergedDrillPath, APP_SCREEN_WAIT_MS, startLevel)
        }
        var drillFailure: String? = null
        if (mergedDrillPath.isNotEmpty() && startLevel < mergedDrillPath.size) {
            if (startLevel > 0) AppLog.i(TAG, "drill: resume at level $startLevel for ${step.id}")
            if (step.preDrillWaitMs > 0) delay(step.preDrillWaitMs)
            for (levelIndex in startLevel until mergedDrillPath.size) {
                if (cancelled) return Result(false, "cancelled")
                // Навигационное действие — только при целостном оверлее.
                if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")
                var levelOk = drillIntoLevel(step, levelIndex, mergedDrillPath[levelIndex])
                if (!levelOk) {
                    // Поверх навигации мог встать диалог (runtime-permission целевого
                    // приложения: Mi Браузер уперся в «Разрешить доступ к фото…»,
                    // прогон rmua2sd7x): закрываем его и повторяем уровень.
                    for (retry in 1..DRILL_CONSENT_RETRIES) {
                        var progressed = handleConsentWalls(step) > 0
                        // MIUI-interstitial (Темы/GetApps: 2ГИС) кнопки закрытия в дереве
                        // не имеет — классификатор диалогов его не ведёт, закрываем BACK.
                        if (!progressed) {
                            progressed = ConsentWallHandler.dismissVideoAdsUntilSettled(
                                service, step.id
                            ) > 0
                        }
                        if (!progressed) break
                        delay(UI_SETTLE_DELAY_MS)
                        levelOk = drillIntoLevel(step, levelIndex, mergedDrillPath[levelIndex])
                        if (levelOk) break
                    }
                }
                if (!levelOk) {
                    drillFailure = "drill_failed"
                    break
                }
                delay(UI_SETTLE_DELAY_MS)
                // Диалоги могут появиться после любого навигационного действия.
                handleConsentWalls(step)
                // Целевой экран может быть достигнут раньше конца маршрута: Музыка —
                // ☰ → «Настройки» открывает экран сразу с тумблерами, а уровень
                // «Расширенные настройки» на этой версии отсутствует (дамп owner_03:
                // заголовок «Аккаунт и настройки»), прежний код падал drill_failed.
                if (onTargetScreen(step)) {
                    AppLog.i(TAG, "drill: target screen reached at level $levelIndex for ${step.id}")
                    StepDiagnostics.note(step.id, "DRILL", "target_reached level=$levelIndex")
                    break
                }
            }
        } else if (mergedDrillPath.isNotEmpty()) {
            AppLog.i(TAG, "drill skipped: already on target screen for ${step.id}")
        }

        // CLEAR_DATA_DECLINE — отдельный сценарий: очистка данных приложения
        // и отклонение приветственного экрана (Проводник и подобные).
        // CONTROL варианта ОС переопределяет legacy-тип шага (Проводник: основной путь —
        // тумблер «Получать рекомендации», CLEAR_DATA остаётся фолбэком варианта).
        if (step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE &&
            SemanticCatalog.variantControl(step.id) != SemanticCatalog.ActionType.TOGGLE
        ) {
            return executeClearDataDecline(step)
        }

        // notif_*: экран уведомлений приложения подтверждается ДО тумблера. На чужом экране
        // (неоткрывшийся интент) ключевые слова шага встречаются в другом месте, и по
        // ошибке тумблится, например, «Показывать уведомления полностью» экрана блокировки.
        if (notifStep && !verifyNotifEntry(step, notifTarget, verifyTexts)) {
            return Result(false, NOT_APPLICABLE)
        }

        val result = if (drillFailure != null) {
            if (isForeignScreenForSettingsStep(step, resolvedPkg)) {
                AppLog.w(TAG, "step ${step.id}: экран другого приложения — шаг неприменим")
                StepDiagnostics.note(
                    step.id, "APPLICABILITY",
                    "foreign_screen fg=${activePackage() ?: "-"} route=${mergedDrillPath.size}"
                )
                Result(false, NOT_APPLICABLE)
            } else if (isAppStep && lastDrillLevelNotFound &&
                activePackage().equals(resolvedPkg, ignoreCase = true) &&
                // Экран занят рекламой (Темы: интерстишл 2ГИС) — это не «экрана нет»:
                // честный drill_failed вместо ложного not_applicable.
                !ConsentWallHandler.isAdScreenNow(service)
            ) {
                // Приложение шага открыто, но строки последнего уровня маршрута на его
                // экранах нет вовсе (GetApps 20.4.5: в «Настройках» нет раздела
                // «Конфиденциальность») — настройки на этой версии нет: честное
                // «неприменимо» вместо FAIL (инвариант: отчёт не должен врать).
                AppLog.w(TAG, "step ${step.id}: уровень маршрута отсутствует у цели — шаг неприменим")
                StepDiagnostics.note(
                    step.id, "APPLICABILITY",
                    "drill_level_absent fg=${activePackage() ?: "-"} route=${mergedDrillPath.size}"
                )
                Result(false, NOT_APPLICABLE)
            } else {
                Result(false, drillFailure)
            }
        } else {
            findAndToggleSwitch(step)
        }
        // Фолбэк варианта (Проводник: основной путь через меню не найден → CLEAR_DATA_DECLINE).
        if (!result.success && result.reason in FALLBACK_REASONS) {
            fallbackAfterFailure(step)?.let { fallback ->
                canResumeSettings = false
                return fallback
            }
        }

        // П.6: Помечаем, что следующий шаг может переиспользовать окно
        canResumeSettings = result.success && step.id in SETTINGS_RESUMABLE_STEPS
        return result
    }

    // ─── Drill navigation ─────────────────────────────────────────────────
    /**
     * Сценарий CLEAR_DATA_DECLINE: очистить данные приложения и отклонить
     * приветственный экран при следующем запуске (Проводник и подобные).
     *
     * Основной результат определяется успехом очистки данных; отклонение
     * приветствия — best-effort и не влияет на статус шага.
     */
    // internal — для тестируемости (SimpleRunnerClearDataTest).
    internal suspend fun executeClearDataDecline(step: SimpleSteps.Step): Result {
        // Явно ждём экран сведений о приложении: на медленных устройствах кнопка
        // очистки может не успеть отрисоваться к моменту APP_LAUNCH_DELAY_MS.
        awaitScreen(
            listOf(
                "Очистить данные", "Clear data",
                "Очистить хранилище", "Clear storage",
                "Очистить", "Clear"
            ),
            timeoutMs = CONTENT_WAIT_MS
        )
        val root = service.rootInActiveWindow
        if (root == null) {
            AppLog.w(TAG, "CLEAR_DATA: нет активного окна (${step.id})")
            return Result(false, "no_active_window")
        }
        // 1. Кнопка очистки данных: сначала специфичная, затем общая.
        val specificClear = listOf(
            "Очистить данные", "Clear data",
            "Очистить хранилище", "Clear storage"
        )
        val genericClear = listOf("Очистить", "Clear")
        var clearNode = findClickableByText(root, specificClear)
            ?: findClickableByText(root, genericClear)

        // MIUI: на странице «О приложении» кнопок очистки может не быть — они
        // скрыты за пунктом «Память» (id am_storage_view на дампе прогона).
        if (clearNode == null) {
            recycleNode(root)
            val storageNode = findClickableByText(
                texts = listOf("Память", "Storage", "Хранилище", "Очистить")
            )
            if (storageNode != null) {
                val tappedStorage = tapNode(storageNode)
                recycleNode(storageNode)
                AppLog.i(TAG, "CLEAR_DATA: кнопка очистки не найдена, открываю «Память» ($tappedStorage)")
                if (tappedStorage) {
                    delay(UI_SETTLE_DELAY_MS)
                    val inner = service.rootInActiveWindow
                    if (inner != null) {
                        clearNode = findClickableByText(inner, specificClear)
                            ?: findClickableByText(inner, genericClear)
                        recycleNode(inner)
                    }
                }
            }
        } else {
            recycleNode(root)
        }
        if (clearNode == null) {
            AppLog.w(TAG, "CLEAR_DATA: кнопка очистки не найдена (${step.id})")
            return Result(false, "clear_button_not_found")
        }
        val tappedClear = tapNode(clearNode)
        recycleNode(clearNode)
        if (!tappedClear) return Result(false, "clear_button_tap_failed")
        delay(UI_SETTLE_DELAY_MS)

        // 2. Подтверждение очистки (возможен промежуточный экран + диалог).
        val confirmMarkers = (
            AdaptiveCatalog.mergeConfirmTexts(service, step.id, step.confirmTexts) +
                listOf("Очистить все данные", "Clear all data", "Очистить", "Clear", "OK")
            ).distinct()
        var confirmed = false
        repeat(2) {
            if (tapButtonByMarkers(confirmMarkers, CONFIRM_RETRY_MS)) {
                confirmed = true
                delay(UI_SETTLE_DELAY_MS)
            }
        }
        if (!confirmed) AppLog.w(TAG, "CLEAR_DATA: подтверждение не найдено (${step.id})")

        // 3. Best-effort: отклонить приветственный экран после сброса данных.
        declineWelcomeScreen(step)

        return Result(true, if (confirmed) "clear_data_done" else "clear_data_tapped")
    }

    /**
     * Best-effort: перезапускает приложение и отклоняет приветственный/промо-экран
     * кнопками «Отмена»/«Пропустить»/«Не сейчас». Не влияет на результат шага.
     */
    private suspend fun declineWelcomeScreen(step: SimpleSteps.Step) {
        val pkg = step.launchPackage ?: return
        if (!isInstalled(pkg)) return
        delay(400)
        try {
            service.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "CLEAR_DATA: запуск $pkg не удался: ${e.message}")
            return
        }
        val declineMarkers = listOf(
            "Отмена", "Cancel", "Пропустить", "Skip", "Не сейчас", "Not now"
        )
        if (!tapButtonByMarkers(declineMarkers, CONTENT_WAIT_MS * 2)) {
            AppLog.w(TAG, "CLEAR_DATA: приветственный экран не отклонён (${step.id})")
        }
    }

    /**
     * Ждёт появления кликабельного узла с одним из маркеров и нажимает его.
     * В отличие от tapSystemDialogButton, не блокирует «OK»/«Отмена» —
     * в сценарии CLEAR_DATA_DECLINE эти кнопки являются целевыми.
     */
    private suspend fun tapButtonByMarkers(markers: List<String>, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (cancelled) return false
            val node = findClickableByText(texts = markers)
            if (node != null) {
                val tapped = tapNode(node)
                recycleNode(node)
                return tapped
            }
            delay(250)
        }
        return false
    }

    /**
     * Навигация по одному уровню маршрута с ПРОВЕРКОЙ результата.
     *
     * Проверка идёт по «эффективному» маршруту (вариант ОС или legacy) и только по
     * пригодным для поиска текстам: прежний `firstOrNull()` брал первый элемент
     * уровня, а у уровней-меню («⚙️/Настройки») им оказывался иконочный или пустой
     * текст — `awaitScreen` гарантированно падал, и шаг завершался `drill_failed`
     * без единой строки в логе (прогон rmu8qhjhi: browser_sys, mivideo, security_sys,
     * cleaner, downloads).
     */
    // internal — для тестируемости (SimpleRunnerDrillTest).
    internal suspend fun drillIntoLevel(
        step: SimpleSteps.Step,
        levelIndex: Int,
        levelTexts: List<String>,
        path: List<List<String>> = emptyList()
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)

        val effectivePath = path.ifEmpty {
            AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
        }
        val nextTexts = nextLevelVerificationTexts(effectivePath, levelIndex + 1)

        // Уровень-меню (/⚙/«Ещё»/«Дополнительно») открывается структурным поиском
        // overflow, в том числе по contentDescription, и ПРОВЕРЯЕТСЯ по пунктам меню:
        // прежний возврат true сразу после тапа продолжал маршрут с закрытого меню.
        if (isMenuLevel(levelTexts)) {
            var tapPoint: Pair<Int, Int>? = null
            if (!findAndTapOverflow(levelTexts, { x, y -> tapPoint = x to y })) return false
            if (nextTexts.isEmpty() || awaitScreen(nextTexts)) return true
            // Уровень-меню открывает список, а следующий пункт бывает НИЖЕ сгиба (Mi Браузер:
            // «Дополнительные настройки» лежат в разделе «Прочее» под лентой настроек — прогон
            // rmuef7nbf: awaitScreen пункт не видел, drill уходил в слепые тапы по позиции,
            // которые открыли чужое приложение). Прокрутку пробуем только на экране со
            // списком: на поповере прокручивать нечего.
            if (hasScrollableScreen() && scrollUntilScreenHasAny(nextTexts)) return true
            // MIUI не всегда реагирует на ACTION_CLICK: шестерёнка Mi Браузера открывает
            // настройки только тапом по центру узла (проверено на устройстве), а слепой тап
            // угла уходил в голосовой ввод (прогоны rmuef7nbf, rmuei6lp7).
            tapPoint?.let { (x, y) ->
                if (tapAt(x, y)) {
                    delay(UI_SETTLE_DELAY_MS)
                    if (nextTexts.isEmpty() || awaitScreen(nextTexts)) return true
                    if (hasScrollableScreen() && scrollUntilScreenHasAny(nextTexts)) return true
                }
            }
            val packageBeforeGesture = activePackage()
            if (performOverflowGesture(packageBeforeGesture) && awaitScreen(nextTexts)) return true
            AppLog.w(TAG, "drill: меню открыто, но '${nextTexts.first()}' не видно")
            return false
        }

        // Уже пройденный уровень: подпись уровня и следующий уровень видны одновременно
        // (sys_recommendations: экран «Приложения» открыт интентом — «Приложения» в
        // заголовке, «Все приложения» строкой ниже; повторный тап по одноимённому ряду
        // уводит на другой экран и ломает маршрут).
        // Только для ПЕРВОГО уровня: дальше подпись уровня может совпасть с пунктом уже
        // открытого меню — downloads: «Настройки» внутри overflow-меню ⋮ считалось
        // пройденным, тап не делался и шаг падал switch_not_found (прогон rmubgvwm5).
        if (levelIndex == 0 && nextTexts.isNotEmpty() &&
            screenHasAny(levelTexts) && screenHasAny(nextTexts)
        ) {
            AppLog.i(TAG, "drill: уровень '${levelTexts.firstOrNull()}' уже пройден")
            return true
        }

        val candidates = levelCandidates(levelTexts)

        if (candidates.isEmpty()) {
            // Уровень отсутствует на экране вовсе (GetApps: раздела «Конфиденциальность»
            // в нативных настройках 20.4.5 нет) — признак неприменимости, а не сбоя тапа.
            lastDrillLevelNotFound = true
            AppLog.w(
                TAG,
                "Drill level '${levelTexts.firstOrNull()}' not found, screen=[${screenText.take(120)}]"
            )
            return false
        }
        lastDrillLevelNotFound = false

        // Перебор совпавших узлов уровня: на экране бывает НЕСКОЛЬКО подписей «Настройки»,
        // и первая ведёт не туда (GetApps: профиль → нативные настройки магазина вместо
        // «гайки» с экраном «Конфиденциальность» → шаг падал drill_failed, прогон rmua2sd7x).
        var attempt = 0
        for (candidate in candidates) {
            attempt++
            if (attempt > 1 && !returnToDrillBase(screenText)) break
            val rect = Rect().also { candidate.getBoundsInScreen(it) }
            // База для проверки «экран сменился» — текст НЕПОСРЕДСТВЕННО перед тапом:
            // screenText снят до прокруток поиска кандидатов, и один только скролл давал
            // ложное «уровень пройден» (ux_program: прокрутили главный список Настроек
            // вместо тапа, шаг ушёл искать тумблер и падал switch_not_found, прогон rmubgvwm5).
            val beforeTapText = currentScreenText()
            val tapped = tapNode(candidate)
            recycleNode(candidate)
            if (!tapped) continue
            if (levelLanded(nextTexts, beforeTapText)) return true
            if (tapAt(rect.centerX(), rect.centerY())) {
                delay(UI_SETTLE_DELAY_MS)
                if (levelLanded(nextTexts, beforeTapText)) return true
            }
        }
        AppLog.w(
            TAG,
            "drill: '${levelTexts.firstOrNull()}' not passed after $attempt attempt(s) " +
                "(screen=[${screenText.take(80)}])"
        )
        return false
    }

    /** Уровень пройден: виден следующий уровень либо экран фактически сменился. */
    private suspend fun levelLanded(nextTexts: List<String>, baseScreenText: String): Boolean =
        if (nextTexts.isNotEmpty()) {
            // Переход засчитывается и по смене экрана: тексты следующего уровня бывают
            // видны только после полной отрисовки/скролла, и шаг ошибочно сообщал
            // «not passed after 1 attempt» при фактически открытом экране
            // (sys_recommendations, прогон rmubgvwm5).
            awaitScreen(nextTexts) || screenChangedSince(baseScreenText)
        } else {
            screenChangedSince(baseScreenText)
        }

    /**
     * Кандидаты-узлы уровня: первый — обычным путём (scroll-until-found и
     * горизонтальный скролл), затем остальные совпавшие кликабельные подписи.
     */
    private suspend fun levelCandidates(levelTexts: List<String>): List<AccessibilityNodeInfo> {
        val primary = findClickableByTextWithScroll(
            levelTexts,
            attempts = DRILL_SCROLL_TRIES,
            logLabel = levelTexts.firstOrNull { it.isNotBlank() }
        ) ?: findAfterHorizontalScroll(levelTexts)
        val root = service.rootInActiveWindow ?: return listOfNotNull(primary)
        val all = NodeTree.findAllInTree(root = root, predicate = { node ->
            node.isClickable && NodeTree.matchesAny(node, levelTexts)
        })
        recycleNode(root)
        val result = ArrayList<AccessibilityNodeInfo>(DRILL_LEVEL_ATTEMPTS)
        if (primary != null) result.add(primary)
        for (node in all) {
            if (result.size >= DRILL_LEVEL_ATTEMPTS) { recycleNode(node); continue }
            if (result.none { it === node || it == node }) result.add(node) else recycleNode(node)
        }
        return result
    }

    /** Возврат на исходный экран уровня после неудачной попытки альтернативного узла. */
    private suspend fun returnToDrillBase(baseScreenText: String): Boolean {
        if (!screenChangedSince(baseScreenText)) return true
        AppLog.i(TAG, "drill: возврат назад после альтернативного узла уровня")
        pressBack()
        delay(UI_SETTLE_DELAY_MS)
        return !screenChangedSince(baseScreenText)
    }

    private suspend fun pressBack() {
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    /** Экран изменился с момента снятия подписи [before] (для подтверждения уровня). */
    private fun screenChangedSince(before: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val now = collectAllText(root)
        recycleNode(root)
        return now.isNotBlank() && now != before
    }

    /** Узел целиком внутри рабочего окна: тап по краю/под навбаром эффекта не даёт. */
    private fun isFullyVisible(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val root = service.rootInActiveWindow ?: return false
        val window = Rect().also { root.getBoundsInScreen(it) }
        recycleNode(root)
        return window.width() > 0 && window.height() > 0 && window.contains(rect)
    }

    /**
     * Тексты уровня, которые годятся как маркер экрана: иконочные (⋮/⚙️) и пустые
     * отбрасываются — иначе проверка ждёт текст, которого на экране не бывает.
     */
    internal fun nextLevelVerificationTexts(path: List<List<String>>, fromIndex: Int): List<String> =
        path.getOrNull(fromIndex).orEmpty().filter { isVerifiableText(it) }

    /** Текст годится как маркер: содержит буквы/цифры (не иконочный глиф без подписи). */
    internal fun isVerifiableText(text: String): Boolean =
        text.trim().replace(EMOJI_VARIATION_SELECTOR, "").any { it.isLetterOrDigit() }

    /** Уровень-меню: подпись overflow-кнопки MIUI (⋮/⚙/«Ещё»/«Дополнительно») или иконка. */
    internal fun isMenuLevel(levelTexts: List<String>): Boolean =
        levelTexts.any { text ->
            val t = text.trim().replace(EMOJI_VARIATION_SELECTOR, "")
            t.isEmpty() ||
                MENU_LEVEL_TEXTS.any { it.replace(EMOJI_VARIATION_SELECTOR, "") == t }
        }

    /** Текст уровня — только символ-иконка (шестерёнка/три точки/гамбургер), без слов. */
    private fun isGlyphOnly(text: String): Boolean {
        val t = text.replace(EMOJI_VARIATION_SELECTOR, "").trim()
        return t.isNotEmpty() && t.none { it.isLetterOrDigit() || it.isWhitespace() }
    }

    /** Есть ли хоть один из текстов где-нибудь на текущем экране. */
    private fun screenHasAny(texts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)
        return texts.any { text ->
            text.isNotBlank() && TextMatcher.normalizedContains(screenText, text)
        }
    }

    // ─── Готовность приложения (после запуска и перед бурением) ───────────

    /**
     * Ждёт появления целевого пакета в foreground с непустым деревом.
     *
     * Единственное состояние, в котором приложение шага считается готовым к
     * навигации; иначе раннер пробует следующий интент. Прежние жёсткие 2 c
     * объявляли «App not ready» даже поднимающемуся приложению (GetApps: холодный
     * старт ~4 c), и остаток бюджета уходил на перебор кандидатов.
     */
    private suspend fun awaitForegroundApp(targetPkg: String?, timeoutMs: Long): Boolean {
        if (targetPkg == null) return false
        val attempts = (timeoutMs / APP_READY_POLL_MS).toInt().coerceAtLeast(1)
        var lastFg: String? = null
        var lastTree = false
        repeat(attempts) { attempt ->
            if (cancelled) return false
            val root = service.rootInActiveWindow
            val fg = root?.packageName?.toString()
            val hasTree = root != null && root.childCount > 0
            recycleNode(root)
            if (fg == targetPkg && hasTree) return true
            lastFg = fg
            lastTree = hasTree
            if (attempt < attempts - 1) delay(APP_READY_POLL_MS)
        }
        AppLog.w(
            TAG,
            "App not ready after ${timeoutMs}ms: fg=$lastFg target=$targetPkg tree=$lastTree, retrying"
        )
        return false
    }

    /**
     * Ждёт готовности экрана приложения по подписям первого уровня маршрута.
     *
     * Опрос, а не слепая пауза: как только уровень виден, бурение идёт сразу.
     * Исчерпание бюджета не фатально — бурение продолжается как раньше (честный
     * `drill_failed`), но факт ожидания виден в логе и диагностике.
     * Resume (startLevel>0) ожидания не требует: экран уже подтверждён.
     */
    internal suspend fun awaitAppScreenReady(
        step: SimpleSteps.Step,
        path: List<List<String>>,
        timeoutMs: Long,
        startLevel: Int = 0
    ): Boolean {
        if (startLevel > 0 || path.isEmpty()) return true
        val levelTexts = nextLevelVerificationTexts(path, startLevel)
        if (levelTexts.isEmpty()) return true
        val attempts = (timeoutMs / APP_READY_POLL_MS).toInt().coerceAtLeast(1)
        repeat(attempts) { attempt ->
            if (cancelled) return false
            if (screenHasAny(levelTexts)) {
                AppLog.i(
                    TAG,
                    "app entry: level '${levelTexts.first()}' visible after " +
                        "~${attempt * APP_READY_POLL_MS}ms"
                )
                return true
            }
            if (attempt < attempts - 1) delay(APP_READY_POLL_MS)
        }
        AppLog.w(
            TAG,
            "app entry: '${levelTexts.first()}' not visible after ${timeoutMs}ms — drilling anyway"
        )
        StepDiagnostics.note(step.id, "ENTRY", "reason=timeout level=${levelTexts.first()}")
        return false
    }

    /** Повторный поиск узла после горизонтальной прокрутки: вкладка может быть за краем. */
    private suspend fun findAfterHorizontalScroll(texts: List<String>): AccessibilityNodeInfo? {
        if (!scrollRightOnce()) return null
        return findClickableByText(texts = texts)
    }

    /**
     * Индекс, с которого продолжать бурение:
     * - экран уже совпал с целью шага → путь исчерпан (бурение не нужно);
     * - экран совпал с текстами уровня N → начинаем с N (resume после сбоя);
     * - иначе — с начала.
     */
    internal fun resumeDrillIndex(step: SimpleSteps.Step, path: List<List<String>>): Int {
        val root = service.rootInActiveWindow ?: return 0
        // Заголовок тулбара — не уровень маршрута: прежний сбор текста ловил
        // «Конфиденциальность» из action_bar и resume уходил в середину пути
        // (ux_program, прогон rmu8lzcu9).
        val screenText = collectBodyText(root)
        recycleNode(root)
        if (screenText.isBlank()) return 0

        if (onTargetScreen(step)) return path.size

        for (levelIndex in path.indices) {
            if (path[levelIndex].any { TextMatcher.normalizedContains(screenText, it) }) return levelIndex
        }
        return 0
    }

    /** Экран уже совпал с целью шага: keyword-match И screenMarkers (как в resume). */
    private fun onTargetScreen(step: SimpleSteps.Step): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectBodyText(root)
        recycleNode(root)
        val targets = searchTextsFor(step)
        if (targets.isEmpty() || !targets.any { TextMatcher.normalizedContains(screenText, it) }) {
            return false
        }
        val markers = SemanticCatalog.screenMarkers(step.id)
        return markers.isEmpty() || markers.any { TextMatcher.normalizedContains(screenText, it) }
    }

    /**
     * Подтверждение с задержкой (msa): кнопка «Отозвать»/«ОК» становится активной
     * только после отсчёта (~10 c). Ждём включённую кнопку, тапаем, даём системе
     * 2–3 c на фактический отзыв и подтверждаем результат. Без подтверждения — fail.
     */
    internal suspend fun confirmDelayedRevoke(
        step: SimpleSteps.Step,
        confirmTexts: List<String>,
        switchTexts: List<String>
    ): Boolean {
        if (confirmTexts.isEmpty()) return false
        val waitMs = SemanticCatalog.confirmWaitMs(step.id, step.confirmWaitMs)
            .takeIf { it > 0L }?.coerceAtMost(MSA_REVOKE_WAIT_MAX_MS) ?: MSA_REVOKE_WAIT_MAX_MS
        val tapped = withTimeoutOrNull(waitMs) {
            var countdownLogged = false
            while (!cancelled) {
                val root = service.rootInActiveWindow
                val node = root?.let { findDialogConfirmButton(it, confirmTexts) }
                if (root != null) recycleNode(root)
                if (node != null) {
                    val label = buttonLabel(node)
                    if (isCountdownLabel(label)) {
                        // MIUI держит «Отозвать (N с)» неактивной до конца отсчёта:
                        // тап в это время не нажимает кнопку (прогон rmua0pt7i).
                        if (!countdownLogged) {
                            AppLog.i(TAG, "msa: waiting for revoke countdown '$label'")
                            countdownLogged = true
                        }
                    } else {
                        val ok = tapNode(node)
                        recycleNode(node)
                        if (ok) return@withTimeoutOrNull true
                    }
                }
                delay(MSA_CONFIRM_POLL_MS)
            }
            false
        } ?: false

        if (!tapped) {
            // Кнопки подтверждения нет вовсе: MIUI 13 отзывает доступ прямо по чекбоксу
            // строки («Доступ к личным данным») — подтверждаем по состоянию тумблера,
            // но только если тумблер реально найден (иначе экран чужой).
            val stateRoot = service.rootInActiveWindow
            val stateNode = stateRoot?.let { findSwitchByText(it, switchTexts) }
            val stateOk = stateNode != null &&
                SwitchFinder.isChecked(stateNode) == step.targetChecked
            recycleNode(stateNode); recycleNode(stateRoot)
            if (stateOk) {
                AppLog.i(TAG, "msa: нет кнопки подтверждения, тумблер в целевом состоянии")
                return true
            }
            AppLog.w(TAG, "msa: revoke button not enabled within ${waitMs}ms (step=${step.id})")
            return false
        }
        AppLog.i(TAG, "msa: revoke tapped, waiting ${MSA_REVOKE_SETTLE_MS}ms for revocation")

        // Отзыв не всегда происходит моментально — ждём и подтверждаем факт.
        delay(MSA_REVOKE_SETTLE_MS)
        val confirmation = service.rootInActiveWindow
        val confirmText = ComponentVerifier.screenText(confirmation)
        if (confirmation != null) recycleNode(confirmation)
        val dialogGone = confirmTexts.none { TextMatcher.normalizedContains(confirmText, it) }
        val confirmed = dialogGone && verifySwitchState(step, switchTexts)
        if (confirmed) {
            AppLog.i(TAG, "msa: revoke confirmed step=${step.id}")
        } else {
            AppLog.w(TAG, "msa: revoke NOT confirmed step=${step.id} dialogGone=$dialogGone")
        }
        return confirmed
    }

    /**
     * Дополнительная цель варианта (второй экран/второй тумблер): «Назад» (back раз),
     * drillPath, затем тумблер или кнопка-действие.
     */
    private suspend fun executeExtraTarget(
        step: SimpleSteps.Step,
        target: SemanticCatalog.ExtraTarget
    ): Boolean {
        repeat(target.back) {
            if (cancelled) return false
            if (!awaitOverlayReadyOrPause()) return false
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }
        for ((levelIndex, levelTexts) in target.drillPath.withIndex()) {
            if (cancelled) return false
            if (!awaitOverlayReadyOrPause()) return false
            if (!drillIntoLevel(step, levelIndex, levelTexts, target.drillPath)) return false
            delay(UI_SETTLE_DELAY_MS)
            handleConsentWalls(step)
        }
        if (target.itemTexts.isEmpty()) return false
        handleConsentWalls(step)

        val root = service.rootInActiveWindow ?: return false
        val switch = findSwitchByText(root, target.itemTexts)
        if (switch != null) {
            val checked = SwitchFinder.isChecked(switch)
            // Цель может требовать включения (carousel: «Пользовательские обои»), поэтому
            // целевое состояние берём у цели, а не у шага.
            val tapped = if (checked == target.targetChecked) true else tapNode(switch)
            recycleNode(switch); recycleNode(root)
            delay(600)
            if (tapped) {
                AppLog.i(TAG, "extra target toggled step=${step.id} text='${target.itemTexts.first()}'")
            }
            return tapped
        }
        recycleNode(root)
        if (target.control == SemanticCatalog.ActionType.TAP_CONFIRM) {
            return tapActionButton(step, target.itemTexts).success
        }
        AppLog.w(TAG, "extra target switch_not_found step=${step.id} text='${target.itemTexts.first()}'")
        return false
    }

    /**
     * Фолбэк варианта при неудаче drill/switch/verify (например Проводник:
     * основной путь через меню не найден → CLEAR_DATA_DECLINE).
     */
    private suspend fun fallbackAfterFailure(step: SimpleSteps.Step): Result? {
        val action = SemanticCatalog.fallbackAction(step.id) ?: return null
        if (action != FALLBACK_ACTION_CLEAR_DATA) return null
        AppLog.i(TAG, "variant fallback action=$action step=${step.id}")
        return executeClearDataDecline(step)
    }

    /** Целевой экран в foreground: приложение шага или Настройки для системных шагов. */
    private fun isForegroundTarget(step: SimpleSteps.Step, resolvedPkg: String?): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val fg = root.packageName?.toString()
        recycleNode(root)
        val expected = resolvedPkg ?: step.launchPackage
        return if (expected != null) fg == expected else fg == SETTINGS_PACKAGE
    }

    /** Один relaunch интентов, если согласие увело с целевого экрана. */
    private suspend fun relaunchOnce(intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (cancelled) return false
            try {
                service.startActivity(intent)
                delay(CONTENT_WAIT_MS)
                return true
            } catch (e: Exception) {
                AppLog.w(TAG, "relaunch after consent failed: ${e.message}")
            }
        }
        return false
    }

    // ── Применимость шага: вход notif_* и чужой экран ────────────────────

    /**
     * Пакет-цель notif_*-шага: подпись приложения на экране уведомлений — признак
     * того, что открыт именно целевой экран, а не похожий чужой.
     */
    internal fun resolveNotifTarget(step: SimpleSteps.Step): String? {
        val candidates =
            (listOfNotNull(SemanticCatalog.launchPackage(step.id)) + candidatePackages(step))
                .distinct()
        return candidates.firstOrNull { isInstalled(it) } ?: candidates.firstOrNull()
    }

    /**
     * Вход notif_*-шага подтверждён: открыт экран уведомлений целевого приложения.
     * Если нет — повторяем собственный интент шага (EXTRA_APP_PACKAGE = цель): это
     * тот же интент, что MIUI открывает по «Уведомления» в сведениях о приложении.
     */
    private suspend fun verifyNotifEntry(
        step: SimpleSteps.Step,
        notifTarget: String?,
        keywords: List<String>
    ): Boolean {
        if (isAppNotificationScreen(notifTarget, keywords)) return true
        AppLog.w(TAG, "notif entry: экран '${step.id}' не подтверждён — повтор интента")
        StepDiagnostics.note(step.id, "NOTIF", "entry_retry pkg=${notifTarget ?: "-"}")
        if (!retryNotifIntent(step, notifTarget)) {
            StepDiagnostics.note(
                step.id, "NOTIF", "entry_intent_unavailable pkg=${notifTarget ?: "-"}"
            )
            return false
        }
        val ok = awaitEntryScreen(step, notifTarget, keywords, CONTENT_WAIT_MS)
        if (!ok) {
            StepDiagnostics.note(
                step.id, "NOTIF", "entry_not_verified pkg=${notifTarget ?: "-"}"
            )
        }
        return ok
    }

    /** Интент шага для конкретного пакета (EXTRA_APP_PACKAGE) — повтор входа notif_*. */
    private fun retryNotifIntent(step: SimpleSteps.Step, notifTarget: String?): Boolean {
        notifTarget ?: return false
        val intent = step.intents.firstOrNull {
            it.getStringExtra(Settings.EXTRA_APP_PACKAGE) == notifTarget
        } ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.startActivity(intent)
            true
        }.onFailure { AppLog.w(TAG, "notif entry retry failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Экран уведомлений приложения: есть маркеры шага И подпись целевого приложения.
     * Вторая проверка обязательна — на «Заблокированном экране» MIUI ключевые слова
     * шага тоже встречаются («Показывать уведомления полностью»), а подписи
     * приложения там нет (прогон rmu8qhjhi: notif_appvault, notif_getapps).
     */
    internal fun isAppNotificationScreen(pkg: String?, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)
        if (keywords.none { TextMatcher.normalizedContains(screenText, it) }) return false
        val label = appLabel(pkg)
        return label.isBlank() || TextMatcher.normalizedContains(screenText, label)
    }

    /** Подпись приложения (кэш в пределах прогона): «Темы», «GetApps», «Лента виджетов». */
    private fun appLabel(pkg: String?): String {
        pkg ?: return ""
        appLabelCache[pkg]?.let { return it }
        val label = runCatching {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull().orEmpty()
        appLabelCache[pkg] = label
        return label
    }

    /**
     * Проверка входного экрана: для notif_* — экран уведомлений приложения, для
     * остальных — любой из маркеров. Неподтверждённый вход дальше не разбирается.
     */
    private suspend fun awaitEntryScreen(
        step: SimpleSteps.Step,
        notifTarget: String?,
        markers: List<String>,
        timeoutMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        while (true) {
            if (cancelled) return false
            if (step.id.startsWith(NOTIF_PREFIX)) {
                if (isAppNotificationScreen(notifTarget, markers)) return true
            } else if (screenHasAny(markers)) {
                return true
            }
            if (System.currentTimeMillis() - start >= timeoutMs) return false
            delay(ENTRY_POLL_MS)
        }
    }

    /**
     * Шаг с маршрутом через Настройки оказался в чужом приложении (лаунчер вместо
     * Настроек) и целевых строк там нет: экрана шага на этом устройстве не существует.
     * Возвращаем `not_applicable` (skip), а не FAIL — отчёт не должен врать
     * (home_suggestions/appvault_* на POCO Launcher, прогон rmu8qhjhi).
     */
    internal fun isForeignScreenForSettingsStep(
        step: SimpleSteps.Step,
        resolvedPkg: String?
    ): Boolean {
        if (resolvedPkg != null || step.launchPackage != null) return false
        val fg = activePackage() ?: return false
        if (fg == SETTINGS_PACKAGE) return false
        val targets = searchTextsFor(step) + SemanticCatalog.itemTexts(step.id)
        return targets.isNotEmpty() && !screenHasAny(targets)
    }

    // ─── Switch finding and toggling ──────────────────────────────────────
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        val mergedSearchTexts = searchTextsFor(step)
        if (mergedSearchTexts.isEmpty()) return Result(false, "no_switch")

        if (!awaitScreen(mergedSearchTexts)) {
            // Экран шага не открылся: если маркеров целевого экрана тоже нет, это не провал
            // автоматизации, а отсутствие настройки на прошивке (ux_program: пункта
            // «Программа улучшения качества» нет вовсе — прогон rmubgvwm5, отчёт врал FAIL).
            // Целевые строки могли оказаться ниже сгиба (Mi Music: тумблеры рекламы живут
            // в разделе «Дополнительные настройки» экрана «Расширенные настройки» — прогон
            // rmueebsvu, шаг зря объявлялся not_applicable), поэтому сначала прокручиваем.
            val markers = SemanticCatalog.screenMarkers(step.id)
            if (!scrollUntilScreenHasAny(mergedSearchTexts + markers)) {
                if (markers.isEmpty() || screenHasAny(markers)) {
                    return Result(false, "switch_not_found")
                }
                AppLog.w(TAG, "step ${step.id}: экран шага не открылся, маркеров нет — шаг неприменим")
                StepDiagnostics.note(step.id, "APPLICABILITY", "screen_markers_absent")
                return Result(false, NOT_APPLICABLE)
            }
        }

        var currentRoot: AccessibilityNodeInfo? =
            service.rootInActiveWindow ?: return Result(false, "no_root_window")
        var switchNode: AccessibilityNodeInfo? = findSwitchByText(currentRoot, mergedSearchTexts)

        // Fallback: скролл (ИСПРАВЛЕНО: не ресайклим root, если нашли ноду, чтобы избежать IllegalStateException)
        if (switchNode == null && mergedSearchTexts.isNotEmpty()) {
            recycleNode(currentRoot)
            repeat(SWITCH_FALLBACK_SCROLLS) { attempt ->
                if (cancelled) return Result(false, "cancelled")
                AppLog.i(
                    TAG,
                    "switch: scroll attempt ${attempt + 1}/$SWITCH_FALLBACK_SCROLLS " +
                        "for '${mergedSearchTexts.firstOrNull()}'"
                )
                val r = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                recycleNode(r)
                scrollDownOnce()

                currentRoot = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                switchNode = findSwitchByText(currentRoot, mergedSearchTexts)
                if (switchNode != null) return@repeat // Оставляем currentRoot в живых для switchNode
                recycleNode(currentRoot)
                currentRoot = null
            }
        }

        val tapTexts = tapFallbackTextsFor(step)
        // Гейт уверенности: keyword-match И переключатель (или tap-fallback) И screenMarkers.
        val screenRoot = currentRoot ?: service.rootInActiveWindow
        val screenText = ComponentVerifier.screenText(screenRoot)
        if (currentRoot == null) recycleNode(screenRoot)
        val decision = SemanticGate.decide(
            keywords = mergedSearchTexts,
            screenText = screenText,
            screenMarkers = SemanticCatalog.screenMarkers(step.id),
            switchFound = switchNode != null,
            hasTapFallback = tapTexts.isNotEmpty()
        )
        SemanticGate.log(step.id, decision)
        if (!decision.act) {
            recycleNode(switchNode)
            recycleNode(currentRoot)
            // Никаких угадываний: пропускаем шаг, ложные нажатия недопустимы.
            return Result(false, "low_confidence")
        }

        if (switchNode == null) {
            recycleNode(currentRoot)
            // Variant-aware фолбэк: на экранах без тумблера (Global: Google «Реклама»
            // с кнопкой «Удалить рекламный идентификатор») тапаем кнопку-действие.
            if (tapTexts.isNotEmpty()) return tapActionButton(step, tapTexts)
            return Result(false, "switch_not_found")
        }

        // Тумблер мог найтись ЗА нижней границей экрана (Mi Видео: строка «Онлайн-
        // рекомендации» в самом низу, bounds уходят под навбар) — тап по невидимой
        // области не переключает, шаг падал `verify_failed` (прогон rmua2sd7x).
        val firstSwitch = switchNode ?: run {
            recycleNode(currentRoot)
            return Result(false, "switch_not_found")
        }
        var targetSwitch: AccessibilityNodeInfo = firstSwitch
        for (attempt in 0 until SWITCH_FALLBACK_SCROLLS) {
            if (isFullyVisible(targetSwitch)) break
            if (cancelled) {
                recycleNode(targetSwitch); recycleNode(currentRoot)
                return Result(false, "cancelled")
            }
            AppLog.i(
                TAG,
                "switch: scroll to off-screen row attempt ${attempt + 1}/$SWITCH_FALLBACK_SCROLLS " +
                    "for '${mergedSearchTexts.firstOrNull()}'"
            )
            scrollDownOnce()
            val scrolledRoot = service.rootInActiveWindow ?: break
            val again = findSwitchByText(scrolledRoot, mergedSearchTexts)
            recycleNode(scrolledRoot)
            if (again != null && again !== targetSwitch) {
                recycleNode(targetSwitch)
                targetSwitch = again
            }
        }

        // Состояние читается у АКТУАЛЬНОГО узла (после прокрутки это может быть
        // другой экземпляр той же строки).
        val hit = SwitchFinder.describe(targetSwitch, mergedSearchTexts.first())
        val isChecked = hit.checkedBefore
        val text = hit.label
        val desc = hit.desc
        val bounds = hit.bounds

        if (isChecked == step.targetChecked) {
            recycleNode(targetSwitch); recycleNode(currentRoot)
            // Уже в целевом состоянии: тумблить нечего, откат этот шаг не трогает.
            return Result(true, if (step.targetChecked) "already_done" else "already_off")
        }

        // checked_before фиксируется в снапшоте отката в момент тумблера (блок 6).
        recordCheckedBefore(step.id, isChecked)

        if (!tapNode(targetSwitch)) {
            recycleNode(targetSwitch); recycleNode(currentRoot)
            return Result(false, "tap_failed")
        }
        recycleNode(targetSwitch); recycleNode(currentRoot)

        delay(600)
        // Диалог подтверждения появляется сразу после тапа («Отключение Ленты виджетов:
        // Вы не сможете использовать Ленту виджетов… Отключить её?» — дамп owner_06).
        // Подтверждаем ДО проверки состояния, иначе verify не видит переключения и шаг
        // уходит в retry → verify_failed.
        tapConfirmIfNeeded(step)
        if (!verifySwitchState(step, mergedSearchTexts)) {
            val retryRoot = service.rootInActiveWindow ?: return Result(false, "no_root_window")
            val retryNode = findSwitchByText(retryRoot, mergedSearchTexts)
            if (retryNode != null) tapNode(retryNode)
            recycleNode(retryNode); recycleNode(retryRoot)
            delay(600)
            tapConfirmIfNeeded(step)
            if (!verifySwitchState(step, mergedSearchTexts)) return Result(false, "verify_failed")
        }

        // П.3: Подтверждение. Для DELAYED_CONFIRM (msa) кнопка включается только после
        // отсчёта: ждём её, тапаем и подтверждаем фактический отзыв.
        if (SemanticCatalog.sequenceKind(step.id) == SemanticCatalog.SequenceKind.DELAYED_CONFIRM) {
            if (!confirmDelayedRevoke(step, confirmTextsFor(step), mergedSearchTexts)) {
                return Result(false, "revoke_not_confirmed")
            }
        } else {
            tapConfirmIfNeeded(step)
        }

        // П.3: Дополнительные переключатели. В списке лежат переводы одной и той же строки
        // на все локали: поиск тумблера по отсутствующей на экране подписи — это полный
        // обход дерева на каждую локаль (Mi Music: 5 бесполезных проходов ≈ 4 с из бюджета
        // шага, прогон rmuef7nbf — шаг завершился timeout). Фильтруем по тексту экрана.
        val mergedAdditionalToggles =
            AdaptiveCatalog.mergeAdditionalToggles(service, step.id, step.additionalToggles)
        if (mergedAdditionalToggles.isNotEmpty()) {
            val screenText = currentScreenText()
            for (toggleText in mergedAdditionalToggles) {
                if (cancelled) break
                if (!TextMatcher.normalizedContains(screenText, toggleText)) continue
                val addRoot = service.rootInActiveWindow ?: continue
                val addNode = findSwitchByText(addRoot, listOf(toggleText))
                if (addNode != null && SwitchFinder.isChecked(addNode) != step.targetChecked) tapNode(addNode)
                recycleNode(addNode); recycleNode(addRoot)
                delay(400)
            }
        }

        // Вариант каталога может требовать второй экран (Браузер: «Показывать рекламу»
        // → назад → «Персональные рекомендации»).
        for (target in SemanticCatalog.extraTargets(step.id)) {
            if (cancelled) break
            if (!executeExtraTarget(step, target)) {
                AppLog.w(TAG, "extra target failed for ${step.id}: ${target.itemTexts.firstOrNull()}")
            }
        }

        // Диалоги-заглушки после тумблера (Карусель: «Нет, спасибо»).
        handleConsentWalls(step)

        AppLog.i(
            TAG,
            "toggled: text='$text' desc='$desc' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] step=${step.id}"
        )
        
        return Result(true, "toggled")
    }

    /**
     * Фолбэк для экранов без переключателя: тапает кликабельную кнопку-действие
     * (например, «Удалить рекламный идентификатор» на Google-экране «Реклама»)
     * и обрабатывает подтверждение. Вызывается только когда тумблер не найден
     * и настроены tapFallbackTexts.
     */
    private suspend fun tapActionButton(step: SimpleSteps.Step, tapTexts: List<String>): Result {
        val node = findClickableByTextWithScroll(tapTexts)
            ?: return Result(false, "switch_not_found")
        if (!tapNode(node)) {
            recycleNode(node)
            return Result(false, "tap_failed")
        }
        recycleNode(node)
        delay(600)
        tapConfirmIfNeeded(step)
        return Result(true, "tapped_fallback")
    }

    /** Тапает кнопку подтверждения диалога, если он появился (confirmTexts шага). */
    private suspend fun tapConfirmIfNeeded(step: SimpleSteps.Step) {
        // У DELAYED_CONFIRM (msa) свой путь: кнопка активируется только после отсчёта,
        // здесь она не кликабельна и только жгла бы повторы.
        if (SemanticCatalog.sequenceKind(step.id) == SemanticCatalog.SequenceKind.DELAYED_CONFIRM) return
        val mergedConfirmTexts = confirmTextsFor(step)
        if (mergedConfirmTexts.isEmpty()) return
        val waitMs = SemanticCatalog.confirmWaitMs(step.id, step.confirmWaitMs)
        if (waitMs > 0) delay(waitMs)
        for (attempt in 1..3) {
            val confirmRoot = service.rootInActiveWindow ?: break
            val confirmNode = findClickableByText(confirmRoot, mergedConfirmTexts)
            if (confirmNode != null) {
                tapNode(confirmNode)
                recycleNode(confirmNode); recycleNode(confirmRoot)
                break
            }
            recycleNode(confirmRoot)
            delay(CONFIRM_RETRY_MS)
        }
    }

    private suspend fun verifySwitchState(step: SimpleSteps.Step, texts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return true
        val switchNode = findSwitchByText(root, texts)
        val result = switchNode?.let { SwitchFinder.isChecked(it) == step.targetChecked } ?: true
        recycleNode(switchNode); recycleNode(root)
        return result
    }

    // ═════════════════════════════════════════════════════════════════════
    // П.4: Структурный поиск ⋮/⚙ (4 уровня)
    // ═════════════════════════════════════════════════════════════════════
    /**
     * Открывает уровень-меню (⋮/⚙/☰). Возвращает true, если тап отправлен;
     * [onTapped] получает центр нажатого узла — вызывающий повторяет тап координатой,
     * если MIUI проигнорировал ACTION_CLICK (шестерёнка Mi Браузера открывала настройки
     * только тапом по центру: ручная проверка (846,197) на устройстве).
     */
    private suspend fun findAndTapOverflow(
        texts: List<String> = OVERFLOW_TEXTS,
        onTapped: (Int, Int) -> Unit = { _, _ -> }
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false

        suspend fun tap(node: AccessibilityNodeInfo): Boolean {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val tapped = tapNode(node)
            if (tapped) onTapped(rect.centerX(), rect.centerY())
            return tapped
        }

        // Шапка в приоритете: «Показать меню» (Музыка) важнее «Больше меню» у строки
        // списка, иначе тап уходит в контекстное меню трека и уровень не открывается.
        findHeaderMenu(root)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        // Точное совпадение подписи уровня — до поиска по вхождению: шестерёнка Mi Браузера
        // описана ровно «Настройки», а поиск по вхождению цеплял первое похожее слово
        // (строка ленты/поисковая строка) и тап уходил в голосовой ввод вместо настроек
        // (прогон rmuehut5w: drill_failed в com.google.android.tts).
        findExactClickableByText(root, texts)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        // Символьная иконка уровня-меню («⚙», «⋮», «☰») — точнее слова: на экране профиля
        // Mi Браузера «Настройки» встречается и в ленте, тап уходил не в меню настроек
        // (прогон rmuef7nbf: drill ушёл мимо настроек браузера).
        findClickableByText(root, texts.filter { isGlyphOnly(it) })?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        findClickableByText(root, texts)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByContentDescription(root)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByPosition(root)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        recycleNode(root)
        return performOverflowGesture()
    }

    /** Кнопка меню в шапке: кликабельный узел с описанием вида «Показать меню». */
    private fun findHeaderMenu(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        NodeTree.findInTree(root) { node ->
            val id = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (id.endsWith("item_menu")) return@findInTree false
            desc.isNotBlank() && HEADER_MENU_TEXTS.any { TextMatcher.normalizedContains(desc, it) }
        }

    private fun findOverflowByContentDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.isNotBlank() && OVERFLOW_TEXTS.any { TextMatcher.normalizedContains(desc, it) }) {
                result.add(node); return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result.firstOrNull()
    }

    private fun findOverflowByPosition(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = service.resources.displayMetrics
        val xMin = (dm.widthPixels * 0.85f).toInt()
        val yMax = (dm.heightPixels * 0.15f).toInt()
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerX() >= xMin && rect.centerY() <= yMax) candidates.add(node)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return candidates.maxByOrNull {
            val r = Rect(); it.getBoundsInScreen(r); r.centerY() * 1000 - r.centerX()
        }
    }

    /**
     * Слепой тап по позиции в правом верхнем углу. [stayInPackage] — пакет экрана до тапа:
     * если тап открыл чужое приложение (Mi Браузер → голосовой ввод Google TTS, прогон
     * rmuef7nbf), уровень не продолжается на чужом экране — шаг честно уходит в drill_failed
     * вместо ложной работы в другом приложении.
     */
    private suspend fun performOverflowGesture(stayInPackage: String? = null): Boolean {
        val dm = service.resources.displayMetrics
        for ((fx, fy) in OVERFLOW_GESTURE_POINTS) {
            if (cancelled) return false
            if (tapAt((dm.widthPixels * fx).toInt(), (dm.heightPixels * fy).toInt())) {
                delay(UI_SETTLE_DELAY_MS)
                if (stayInPackage != null) {
                    val now = activePackage()
                    if (now != null && !now.equals(stayInPackage, ignoreCase = true)) {
                        AppLog.w(TAG, "overflow gesture: экран сменился на '$now' — уровень прерван")
                        return false
                    }
                }
                return true
            }
        }
        return false
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return suspendCancellableCoroutine { cont ->
            // Жест не отправлен — сразу false, иначе шаг висит до общего таймаута
            // (callback в этом случае никогда не вызывается).
            val dispatched =
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Папки рабочего стола: «Рекомендуемое сегодня» внутри папки лаунчера
    // ════════════════════════════════════════════════════════════════════

    /**
     * Рекомендации в папках рабочего стола: открываем папку лаунчера, затем её
     * редактор (тап по названию, иначе долгий тап → «Изменить папку») и выключаем
     * «Рекомендуемое сегодня». Имя папки задаёт пользователь, поэтому папка
     * ищется структурно, а тумблер — по семантике каталога и гейту уверенности.
     */
    internal suspend fun toggleHomeFolderSuggestions(step: SimpleSteps.Step): Result {
        val toggleTexts = SemanticCatalog.itemTexts(step.id)
        val editorMarkers = SemanticCatalog.screenMarkers(step.id)
        val menuTexts = SemanticCatalog.overflowMenuLabels(step.id)
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)

        val candidates = homeFolderCandidates()
        StepDiagnostics.note(step.id, "FOLDER", "candidates=${candidates.size}")
        AppLog.i(TAG, "folder: candidates=${candidates.size} step=${step.id}")
        if (candidates.isEmpty()) return Result(false, "folder_not_found")

        var probed = 0
        for (candidate in candidates) {
            if (probed >= MAX_FOLDER_PROBES) break
            probed++
            if (cancelled) return Result(false, "cancelled")
            if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")

            val label = folderLabel(candidate)
            val rect = Rect()
            candidate.getBoundsInScreen(rect)
            StepDiagnostics.note(
                step.id, "FOLDER",
                "probe=$probed name='$label' class=${candidate.className}"
            )
            val tapped = tapNode(candidate)
            recycleNode(candidate)
            if (!tapped) {
                AppLog.w(TAG, "folder: tap failed for '$label'")
                continue
            }
            delay(FOLDER_OPEN_DELAY_MS)

            // Папка внешне неотличима от иконки приложения (дамп POCO Launcher:
            // одинаковые FrameLayout с icon_container/icon_title). Признак папки —
            // лаунчер остался в фокусе: тап по приложению сменил бы пакет.
            val foreground = activePackage()
            if (foreground != null && !isLauncherPackage(foreground)) {
                StepDiagnostics.note(
                    step.id, "FOLDER",
                    "probe=$probed name='$label' not_a_folder fg=$foreground"
                )
                AppLog.i(TAG, "folder: '$label' is not a folder (fg=$foreground)")
                resetToHome()
                delay(UI_SETTLE_DELAY_MS)
                continue
            }
            AppLog.i(TAG, "folder: popup opened for '$label'")

            val editorOpened = openFolderEditor(
                folderLabel = label,
                folderX = rect.centerX().toFloat(),
                folderY = rect.centerY().toFloat(),
                menuTexts = menuTexts,
                editorMarkers = editorMarkers
            )
            if (!editorOpened) AppLog.w(TAG, "folder: editor not opened for '$label'")
            val result = if (editorOpened) {
                toggleWithGate(step, toggleTexts, editorMarkers)
            } else {
                null
            }
            leaveFolderEditor()
            if (result != null) return result
            if (editorOpened) {
                // Редактор папки открылся, но тумблера рекомендаций в нём нет: значит
                // рекомендации в папках уже выключены (владелец: после отключения
                // msa/персонализации чекбокс из папки исчез) — честное «неприменимо»
                // вместо перебора остальных иконок (прогон rmubgvwm5).
                AppLog.i(TAG, "folder: editor has no suggestions switch ('$label')")
                StepDiagnostics.note(step.id, "APPLICABILITY", "folder_switch_absent name='$label'")
                return Result(false, NOT_APPLICABLE)
            }
            AppLog.i(TAG, "folder: no switch in '$label' — next candidate")
        }
        return Result(false, "switch_not_found")
    }

    /**
     * Кандидаты-иконки рабочего стола: подпись + структура иконки лаунчера
     * (`icon_container`/`icon_title`) либо класс/описание папки. Папка внешне не
     * отличима от приложения, поэтому кандидаты упорядочены «похожие на папку» →
     * остальные, а фактический признак проверяется в рутине (фокус остался лаунчером).
     */
    internal fun homeFolderCandidates(): List<AccessibilityNodeInfo> =
        scanHomeRoot { isHomeIconNode(it) }
            .sortedByDescending { node -> if (isFolderCandidate(node)) 1 else 0 }

    /** Иконка рабочего стола: кликабельный узел с подписью и структурой иконки. */
    internal fun isHomeIconNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable || folderLabel(node).isBlank()) return false
        if (isFolderCandidate(node)) return true
        return NodeTree.findInTree(node) { child ->
            val id = child.viewIdResourceName ?: ""
            id.endsWith("icon_container") || id.endsWith("icon_title")
        } != null
    }

    /** Пакет в фокусе: отличает поповер папки от запущенного приложения. */
    private fun activePackage(): String? {
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        recycleNode(root)
        return pkg
    }

    private fun isLauncherPackage(pkg: String): Boolean =
        LAUNCHER_PACKAGES.any { it.equals(pkg, ignoreCase = true) }

    private fun scanHomeRoot(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val found = NodeTree.findAllInTree(root, predicate)
        recycleNode(root)
        return found
    }

    /** Папка лаунчера: класс/описание содержат folder, у узла есть подпись. */
    internal fun isFolderCandidate(node: AccessibilityNodeInfo): Boolean {
        if (folderLabel(node).isBlank()) return false
        if (isFolderGridCandidate(node)) return true
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (cls.contains("folder")) return true
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return desc.contains("folder") || desc.contains("папка")
    }

    /**
     * Превью папки лаунчера POCO/MIUI: контейнер `preview_icons_container` либо
     * несколько элементов `itemN` внутри иконки. У иконки приложения превью-сетки нет
     * (`icon_icon` + `cover`), поэтому признак различает их без класса/описания.
     *
     * Дамп рабочего стола POCO Launcher: папка с рекомендациями — обычный
     * кликабельный `FrameLayout desc='Russia'` без слова «folder» в классе и описании,
     * из-за чего она не попадала в первые пробы (`homeFolderCandidates` сортирует
     * «похожие на папку» вперёд) и шаг `folder_recommendations` тратил лимит
     * `MAX_FOLDER_PROBES` на обычные иконки (прогон rmua2sd7x: probes 1–6 = Проводник,
     * Заметки, Календарь, ShareMe, Погода, Безопасность; папка так и не проверена).
     */
    internal fun isFolderGridCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        if (NodeTree.findInTree(node) { child ->
                child.viewIdResourceName?.endsWith("preview_icons_container") == true
            } != null
        ) {
            return true
        }
        var items = 0
        var icons = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > FOLDER_PREVIEW_DEPTH) return
            val id = n.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (id.startsWith("item")) items++
            val cls = n.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (cls.contains("imageview") && !n.contentDescription.isNullOrBlank()) icons++
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return items >= 2 || icons >= 2
    }

    /** Подпись папки: текст узла, иначе описание (имя задаёт пользователь). */
    private fun folderLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim().orEmpty().ifEmpty {
            node.contentDescription?.toString()?.trim().orEmpty()
        }

    /**
     * Открытие редактора папки: тап по названию в поповере, иначе долгий тап по
     * иконке папки и «Изменить папку» (HyperOS 2/3).
     */
    private suspend fun openFolderEditor(
        folderLabel: String,
        folderX: Float,
        folderY: Float,
        menuTexts: List<String>,
        editorMarkers: List<String>
    ): Boolean {
        if (tapFolderTitle(folderLabel)) {
            delay(UI_SETTLE_DELAY_MS)
            if (isFolderEditorVisible(editorMarkers)) return true
        }
        if (menuTexts.isEmpty()) return false
        if (!awaitOverlayReadyOrPause()) return false
        // Контекстное меню папки живёт только на рабочем столе: возвращаемся.
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)
        longPressAt(folderX, folderY)
        delay(FOLDER_OPEN_DELAY_MS)
        val root = service.rootInActiveWindow ?: return false
        val item = findClickableByText(root, menuTexts)
        if (item == null) {
            recycleNode(root)
            return false
        }
        val tapped = tapNode(item)
        recycleNode(item); recycleNode(root)
        delay(FOLDER_OPEN_DELAY_MS)
        return tapped && isFolderEditorVisible(editorMarkers)
    }

    /**
     * Название папки в поповере: узел с её подписью, иначе самый верхний
     * кликабельный текстовый узел (имя задаёт пользователь — сравнение мягкое).
     */
    private suspend fun tapFolderTitle(label: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val candidates = NodeTree.findAllInTree(root, predicate = { node ->
            !node.text.isNullOrBlank() && clickableAncestorOrSelf(node) != null
        })
        recycleNode(root)
        val named = if (label.isNotBlank()) {
            candidates.firstOrNull { TextMatcher.normalizedContains(it.text?.toString(), label) }
        } else {
            null
        }
        val chosen = named ?: candidates.minByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.centerY()
        }
        candidates.forEach { if (it !== chosen) recycleNode(it) }
        chosen ?: return false
        val title = clickableAncestorOrSelf(chosen) ?: chosen
        val tapped = tapNode(title)
        recycleNode(chosen)
        if (title !== chosen) recycleNode(title)
        return tapped
    }

    /** Экран редактора папки: совпал хотя бы один маркер каталога. */
    private fun isFolderEditorVisible(markers: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        // Признак редактора папки POCO/MIUI — поле переименования rename_edit
        // (тап по названию папки): маркеров каталога на этом экране может не быть вовсе,
        // из-за чего шаг считал редактор неоткрытым (прогон rmubgvwm5).
        val renamed = NodeTree.findAllInTree(root, predicate = { node ->
            node.viewIdResourceName?.endsWith("rename_edit") == true
        })
        val byId = renamed.isNotEmpty()
        renamed.forEach { recycleNode(it) }
        recycleNode(root)
        return byId || (markers.isNotEmpty() && markers.any { TextMatcher.normalizedContains(text, it) })
    }

    /**
     * Тумблер на целевом экране (редактор папки, настройки установщика): гейт
     * уверенности (keywords + маркеры + тумблер), checked_before в снапшот отката,
     * валидация после тапа. `null` — тумблера нет (пробуем следующую цель), иначе
     * результат шага.
     */
    private suspend fun toggleWithGate(
        step: SimpleSteps.Step,
        toggleTexts: List<String>,
        editorMarkers: List<String>
    ): Result? {
        val root = service.rootInActiveWindow ?: return null
        val screenText = collectAllText(root)
        val switchNode = findSwitchByText(root, toggleTexts)
        val decision = SemanticGate.decide(
            keywords = toggleTexts,
            screenText = screenText,
            screenMarkers = editorMarkers,
            switchFound = switchNode != null,
            hasTapFallback = false
        )
        SemanticGate.log(step.id, decision)
        if (switchNode == null) {
            recycleNode(root)
            return null
        }
        if (!decision.act) {
            AppLog.w(TAG, "folder: low confidence in editor (${decision.detail}) — не тумблим")
            recycleNode(switchNode); recycleNode(root)
            return null
        }
        val checkedBefore = SwitchFinder.isChecked(switchNode)
        recordCheckedBefore(step.id, checkedBefore)
        if (checkedBefore == step.targetChecked) {
            AppLog.i(TAG, "folder: '${toggleTexts.firstOrNull()}' already off — nothing to toggle")
            recycleNode(switchNode); recycleNode(root)
            return Result(true, "already_off")
        }
        val tapped = tapNode(switchNode)
        recycleNode(switchNode); recycleNode(root)
        if (!tapped) return Result(false, "tap_failed")
        delay(600)
        if (!verifySwitchState(step, toggleTexts)) {
            AppLog.w(TAG, "folder: switch state not verified after tap (step=${step.id})")
            return Result(false, "verify_failed")
        }
        AppLog.i(TAG, "folder: toggled '${toggleTexts.firstOrNull()}' step=${step.id}")
        return Result(true, "toggled")
    }

    /** Закрываем редактор папки (Back ×2) и возвращаемся на рабочий стол. */
    private suspend fun leaveFolderEditor() {
        repeat(2) {
            if (cancelled) return
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)
    }

    /** Долгий тап по координатам (контекстное меню папки на HyperOS 2/3). */
    private suspend fun longPressAt(x: Float, y: Float): Boolean =
        performGesture(x, y, x, y, LONG_PRESS_MS)

    // ════════════════════════════════════════════════════════════════════
    // Установщик приложений: настройки проверки (APK не устанавливаем)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Проверка приложений при установке: открываем активность настроек установщика
     * (та же, что даёт шестерёнка в окне проверки) и выключаем «Получать
     * рекомендации». Установка APK не запускается: ни один текст-подтверждение
     * установки не тапается (guard [isInstallConfirmText]), после шага — HOME.
     */
    internal suspend fun toggleInstallerRecommendations(step: SimpleSteps.Step): Result {
        val toggleTexts = SemanticCatalog.itemTexts(step.id)
        val settingsMarkers = SemanticCatalog.screenMarkers(step.id)
        // Страховка: подписи тумблера из каталога не должны выглядеть как «Установить».
        if (toggleTexts.any { isInstallConfirmText(it) }) {
            AppLog.w(TAG, "installer: catalog label looks like an install confirmation — step skipped")
            return Result(false, "catalog_label_looks_like_install")
        }
        val candidates = installerSettingsCandidates(candidatePackages(step))
        StepDiagnostics.note(step.id, "INSTALLER", "candidates=${candidates.size}")
        AppLog.i(TAG, "installer: candidates=${candidates.size} step=${step.id}")
        if (candidates.isEmpty()) return Result(false, "installer_settings_not_found")

        var tried = 0
        for (component in candidates) {
            if (tried >= MAX_INSTALLER_CANDIDATES) break
            tried++
            if (cancelled) return Result(false, "cancelled")
            if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")
            val short = component.flattenToShortString()
            StepDiagnostics.note(step.id, "INSTALLER", "open=$tried component=$short")
            if (!launchComponent(component)) {
                AppLog.w(TAG, "installer: launch failed for $short")
                continue
            }
            delay(APP_LAUNCH_DELAY_MS)
            handleConsentWalls(step)
            if (!isInstallerSettingsScreen(settingsMarkers, toggleTexts)) {
                AppLog.w(TAG, "installer: not a settings screen ($short)")
                pressBackToNeutral()
                continue
            }
            val result = toggleWithGate(step, toggleTexts, settingsMarkers)
            pressBackToNeutral()
            if (result != null) return result
            AppLog.i(TAG, "installer: no switch on $short — next activity")
        }
        return Result(false, "switch_not_found")
    }

    /**
     * Кандидаты-активности настроек установщика: экспортируемые активности с
     * признаком настроек/проверки. Полный список пишется в StepDiag — по нему
     * debug-прогон показывает фактическую активность на конкретной прошивке.
     */
    internal fun installerSettingsCandidates(packages: List<String>): List<ComponentName> {
        val pm = runCatching { service.packageManager }.getOrNull() ?: return emptyList()
        val result = ArrayList<ComponentName>()
        for (pkg in packages) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue
            val activities = info.activities.orEmpty()
            StepDiagnostics.note(
                "installer", "ACTIVITIES",
                "pkg=$pkg exported=" + activities.filter { it.exported }.joinToString(",") { it.name }
            )
            activities.filter { it.exported && isInstallerSettingsActivity(it.name) }
                .forEach { result.add(ComponentName(pkg, it.name)) }
        }
        return result
    }

    /**
     * Признак активности настроек: settings/preference/recommend/advanced/scan.
     * Активности самой установки (`PackageInstallerActivity`, `InstallAppProgress`)
     * сюда не попадают — установку мы не открываем.
     */
    internal fun isInstallerSettingsActivity(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return INSTALLER_SETTINGS_KEYWORDS.any { n.contains(it) }
    }

    /** Тексты подтверждения установки: по ним не тапаем никогда. */
    internal fun isInstallConfirmText(text: String): Boolean {
        val n = TextMatcher.normalize(text)
        return n.isNotEmpty() && INSTALL_CONFIRM_TEXTS.any { n.contains(it) }
    }

    private fun launchComponent(component: ComponentName): Boolean = try {
        service.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        AppLog.w(TAG, "installer: startActivity failed: ${e.message}")
        false
    }

    /** Экран настроек установщика: маркеры каталога или найденный тумблер. */
    private fun isInstallerSettingsScreen(markers: List<String>, toggleTexts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        val hasSwitch = findSwitchByText(root, toggleTexts) != null
        recycleNode(root)
        val markerHit = markers.isNotEmpty() && markers.any { TextMatcher.normalizedContains(text, it) }
        return markerHit || hasSwitch
    }

    /** Закрываем экран установщика и возвращаемся на рабочий стол. */
    private suspend fun pressBackToNeutral() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(UI_SETTLE_DELAY_MS)
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)
    }

    // Consent-стены (welcome/permission) — см. handleConsentWalls + ConsentWallHandler
    // ═════════════════════════════════════════════════════════════════════
    /** Мост нажатий для ConsentWallHandler (поиск кликабельного узла по текстам). */
    private val consentTapBridge = object : ConsentWallHandler.TapBridge {
        override suspend fun tapByTexts(texts: List<String>): Boolean = tapSystemDialogButton(texts)

        override suspend fun tapEnabledByTexts(texts: List<String>): Boolean =
            tapSystemDialogButton(texts, requireEnabled = true)

        override suspend fun tapDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapSystemDialogButton(texts, avoidTexts = avoidTexts)

        override suspend fun tapEnabledDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapSystemDialogButton(texts, requireEnabled = true, avoidTexts = avoidTexts)
    }

    /**
     * Единая точка входа для системных диалогов: welcome-стены и
     * runtime-permission запросы по consentPolicy (deny по умолчанию).
     */
    private suspend fun handleConsentWalls(step: SimpleSteps.Step): Int {
        val handled = ConsentWallHandler.handleUntilSettled(
            service = service,
            bridge = consentTapBridge,
            stepId = step.id,
            stepConsentTexts = SemanticCatalog.consentTexts(step.id),
            // Confirm-тексты шага: диалог, который ведёт сам шаг (msa «Отозвать»),
            // generic-закрытие не трогает.
            stepConfirmTexts = confirmTextsFor(step),
            // Владелец диалога = пакет-цель шага: это диалог самого приложения.
            stepPackages = stepPackagesFor(step),
            // Подписи приложений-целей: MIUI-контроллер разрешений просит доступ
            // «приложению Проводник» — по подписи понимаем, что это доступ для
            // шага, и разрешаем (иначе приложение не пускает дальше).
            stepLabels = stepPackagesFor(step).mapNotNull { appLabel(it) },
            maxIterations = SemanticCatalog.maxConsentIterations(
                step.id,
                SemanticCatalog.maxConsentIterationsPolicy()
            ),
            isCancelled = { cancelled }
        )
        if (handled > 0) delay(UI_SETTLE_DELAY_MS)
        return handled
    }

    /** Тексты поиска: keywords каталога + deprecated legacy searchTexts. */
    private fun searchTextsFor(step: SimpleSteps.Step): List<String> = SemanticCatalog.keywords(
        step.id,
        AdaptiveCatalog.mergeSearchTexts(service, step.id, step.searchTexts)
    )

    /** Тексты подтверждения: каталог + вариантный каталог + legacy. */
    private fun confirmTextsFor(step: SimpleSteps.Step): List<String> = SemanticCatalog.confirmTexts(
        step.id,
        AdaptiveCatalog.mergeConfirmTexts(service, step.id, step.confirmTexts)
    )

    /** Тексты кнопки-действия (экран без тумблера): каталог + вариантный каталог. */
    private fun tapFallbackTextsFor(step: SimpleSteps.Step): List<String> =
        SemanticCatalog.tapFallbackTexts(
            step.id,
            AdaptiveCatalog.mergeTapFallbackTexts(service, step.id, step.tapFallbackTexts)
        )

    /**
     * Маршрут навигации: явно совпавший вариант ОС авторитетен (его путь заменяет
     * legacy-догадки), иначе — legacy-путь + fallbackDrillPath-подсказки каталога.
     * Поведение cn_hyperos (базовые поля) не меняется.
     */
    private fun semanticDrillPath(
        step: SimpleSteps.Step,
        legacyPath: List<List<String>>
    ): List<List<String>> {
        val variantPath = SemanticCatalog.variantDrillPath(step.id)
        if (variantPath.isNotEmpty()) return variantPath
        val semantic = SemanticCatalog.fallbackDrillPath(step.id)
        if (semantic.isEmpty()) return legacyPath
        val seen = HashSet<String>()
        val result = ArrayList<List<String>>(legacyPath.size + semantic.size)
        for (level in legacyPath + semantic) {
            if (seen.add(level.joinToString("\u0001"))) result.add(level)
        }
        return result
    }

    /** launchPackage: legacy-значение главнее, каталог дополняет. */
    private fun applySemanticLaunchPackage(step: SimpleSteps.Step): SimpleSteps.Step {
        if (step.launchPackage != null) return step
        val fromCatalog = SemanticCatalog.launchPackage(step.id) ?: return step
        AppLog.i(TAG, "semantic launchPackage for ${step.id}: $fromCatalog")
        return step.copy(launchPackage = fromCatalog)
    }

    /**
     * Кнопка диалога по тексту — для отсчётных подтверждений (msa «Отозвать (N с)»).
     *
     * Прежний поиск брал первый узел, чей текст содержит confirm-текст, и им
     * оказывалось СООБЩЕНИЕ диалога («…Отозвать разрешение?»): тап уходил по его
     * координатам, кнопка не нажималась, шаг падал `revoke_not_confirmed`
     * (прогон rmua0pt7i: экран всё ещё показывал «Отозвать (6 с)»).
     *
     * Порядок: кнопка с точной подписью (после снятия отсчётного хвоста) →
     * кнопка по роли (class Button / id button1|button2|button3).
     */
    internal fun findDialogConfirmButton(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        val exact = NodeTree.findAllInTree(root, predicate = { node ->
            node.isEnabled && isCountdownConfirmLabel(buttonLabel(node), texts)
        }).firstOrNull()
        if (exact != null) return clickableAncestorOrSelf(exact) ?: exact
        val byRole = NodeTree.findAllInTree(root, predicate = { node ->
            node.isEnabled && isDialogButtonNode(node) && matchesAny(node, texts)
        }).firstOrNull()
        return byRole?.let { clickableAncestorOrSelf(it) ?: it }
    }

    /** Подпись узла: text или contentDescription (MIUI часть кнопок подписывает только описанием). */
    private fun buttonLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

    /** Отсчётный текст MIUI: «Отозвать (9 с)», «Revoke (9s)» — кнопка ещё неактивна. */
    internal fun isCountdownLabel(label: String?): Boolean =
        COUNTDOWN_LABEL_REGEX.containsMatchIn(TextMatcher.normalize(label))

    /** Подпись кнопки совпадает с confirm-текстом после снятия отсчётного хвоста. */
    internal fun isCountdownConfirmLabel(label: String?, texts: List<String>): Boolean {
        if (label == null) return false
        val stripped = COUNTDOWN_LABEL_REGEX.replace(TextMatcher.normalize(label), "").trim()
        if (stripped.isEmpty()) return false
        return texts.any { TextMatcher.normalize(it) == stripped }
    }

    private suspend fun tapSystemDialogButton(
        texts: List<String>,
        requireEnabled: Boolean = false,
        avoidTexts: List<String> = emptyList()
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findDialogButton(root, texts, avoidTexts, requireEnabled)
        if (node != null) {
            val tapped = tapNode(node)
            recycleNode(node); recycleNode(root)
            if (!tapped && requireEnabled) AppLog.w(TAG, "consent: button found but not tappable")
            return tapped
        }
        recycleNode(root)
        return false
    }

    /**
     * Кнопка диалога: сначала узлы с button-ролью (button1/2/3 или класс Button),
     * затем прочие кликабельные. Узлы-маркеры ([avoidTexts]) и узлы заголовка/
     * сообщения не нажимаются никогда: «Закрыть принудительно?» ранее «закрывалось»
     * тапом по собственному заголовку (прогон rmu8lzcu9, filemanager).
     */
    private fun findDialogButton(
        root: AccessibilityNodeInfo?,
        texts: List<String>,
        avoidTexts: List<String>,
        requireEnabled: Boolean
    ): AccessibilityNodeInfo? {
        root ?: return null
        val button = findInTree(root) { node ->
            isDialogButtonNode(node) &&
                matchesAny(node, texts) &&
                !isDialogMarkerNode(node, avoidTexts) &&
                (!requireEnabled || node.isEnabled)
        }
        if (button != null) return clickableAncestorOrSelf(button) ?: button
        val fallback = findInTree(root) { node ->
            !isDialogMarkerNode(node, avoidTexts) &&
                matchesAny(node, texts) &&
                (!requireEnabled || node.isEnabled) &&
                clickableAncestorOrSelf(node) != null
        }
        return fallback?.let { clickableAncestorOrSelf(it) }
    }

    /** Заголовок, сообщение или маркер диалога — по таким узлам не тапаем. */
    private fun isDialogMarkerNode(node: AccessibilityNodeInfo, avoidTexts: List<String>): Boolean {
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("alertTitle") || id.endsWith("message")) return true
        return avoidTexts.isNotEmpty() && NodeTree.matchesAny(node, avoidTexts)
    }

    /** Button-роль: id кнопки AlertDialog или класс android.widget.Button. */
    private fun isDialogButtonNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("button1") || id.endsWith("button2") || id.endsWith("button3")) return true
        return node.className?.toString()?.contains("Button", ignoreCase = true) == true
    }

    /** Пакеты-цели шага: владелец такого диалога = само приложение шага. */
    private fun stepPackagesFor(step: SimpleSteps.Step): List<String> =
        (listOfNotNull(step.launchPackage) + SemanticCatalog.requiredPackages(step.id) + step.requiredPackages)
            .distinct()

    // ─── Navigation & Utilities ───────────────────────────────────────────
    private suspend fun resetSettingsToRoot(): Boolean {
        try {
            service.startActivity(
                Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "resetSettingsToRoot: startActivity failed: ${e.message}")
            return false
        }
        delay(CONTENT_WAIT_MS)
        // Корень подтверждается совпадением >= 2 маркеров одновременно;
        // если ACTION_SETTINGS открыл не корень — возвращаемся назад (до 5 раз).
        repeat(5) {
            if (isSettingsRoot()) return true
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }
        return isSettingsRoot()
    }

    /** Корень Настроек: совпадение >= 2 маркеров одновременно (не одного). */
    private fun isSettingsRoot(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        recycleNode(root)
        val markers = listOf(
            "Поиск настроек", "О телефоне", "Настройки",
            "SIM-карты и мобильные сети", "Wi-Fi", "Bluetooth"
        )
        return markers.count { TextMatcher.normalizedContains(text, it) } >= 2
    }

    private suspend fun resetToHome(): Boolean {
        // GLOBAL_ACTION_HOME — нативный переход домой; в отличие от
        // ACTION_MAIN+CATEGORY_HOME не вызывает resolver «Главный экран по умолчанию».
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(500)
        return ok
    }

    private suspend fun swipeUp() {
        val dm = service.resources.displayMetrics
        performGesture(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.8f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.2f,
            300
        )
    }

    /** Поиск тумблера вынесен в [SwitchFinder] (3 прохода + нечёткий рубеж). */
    private fun findSwitchByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? = SwitchFinder.findSwitch(root, texts)

    private fun isSwitchLike(node: AccessibilityNodeInfo): Boolean =
        SwitchFinder.isSwitchLike(node)

    /** Обход дерева вынесен в [NodeTree]. */
    private fun findInTree(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? = NodeTree.findInTree(root, predicate)

    /** Поиск тумблера рядом с подписью вынесен в [SwitchFinder]. */
    private fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        SwitchFinder.findSwitchNear(node)

    /** Нормализованное сравнение текста/описания узла вынесено в [NodeTree]. */
    private fun matchesAny(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        fuzzy: Boolean = false
    ): Boolean = NodeTree.matchesAny(node, texts, fuzzy)

    /** Ближайший кликабельный узел вынесен в [NodeTree]. */
    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        NodeTree.clickableAncestorOrSelf(node)

    /** Кликабельный узел, чьи text/contentDescription ТОЧНО равны одной из подписей уровня. */
    private fun findExactClickableByText(
        root: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        val node = NodeTree.findInTree(root) { n ->
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            texts.any { t ->
                t.isNotBlank() &&
                    (TextMatcher.normalizedEquals(text, t) || TextMatcher.normalizedEquals(desc, t))
            } && clickableAncestorOrSelf(n) != null
        }
        return node?.let { clickableAncestorOrSelf(it) }
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo? = service.rootInActiveWindow,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        return findInTree(root) { matchesAny(it, texts) && clickableAncestorOrSelf(it) != null }
            ?.let { clickableAncestorOrSelf(it) }
    }

    private suspend fun findClickableByTextWithScroll(
        texts: List<String>,
        attempts: Int = SWITCH_FALLBACK_SCROLLS,
        logLabel: String? = null
    ): AccessibilityNodeInfo? {
        repeat(attempts) { attempt ->
            findClickableByText(texts = texts)?.let { return it }
            val root = service.rootInActiveWindow ?: return null
            recycleNode(root)
            if (logLabel != null) {
                AppLog.i(TAG, "drill: scroll attempt ${attempt + 1}/$attempts for '$logLabel'")
            }
            scrollDownOnce()
        }
        return findClickableByText(texts = texts)
    }

    /**
     * Первый прокручиваемый контейнер ПОД окном. Прежняя версия поднималась вверх
     * по `parent` и всегда возвращала null (у окна нет scrollable-предка): drill и
     * поиск тумблеров не прокручивали экран вовсе — «Приложения» на корне Настроек
     * и тумблеры ниже сгиба не находились (прогон rmu8lzcu9).
     */
    internal fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val containers = NodeTree.findAllInTree(root, predicate = { it.isScrollable })
        if (containers.isEmpty()) return null
        // Предпочитаем вертикальный контейнер (основной список), а не строку вкладок:
        // на экране Тем первым в дереве идёт горизонтальный таб-стрип, и прокрутка
        // уходила в него (прогон rmu8qhjhi).
        val vertical = containers.firstOrNull { c ->
            val r = Rect().also { c.getBoundsInScreen(it) }
            r.height() > r.width()
        }
        return vertical ?: containers.first()
    }

    /** Есть ли на экране прокручиваемый контейнер (список настроек, а не поповер). */
    private fun hasScrollableScreen(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val container = findScrollableContainer(root)
        recycleNode(container)
        recycleNode(root)
        return container != null
    }

    /**
     * Прокрутка вправо горизонтального контейнера: вкладка цели может быть за правым
     * краем (Темы: вкладка «Профиль» — прогон rmu8qhjhi). Вертикальные контейнеры
     * не трогаются.
     */
    private suspend fun scrollRightOnce(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val containers = NodeTree.findAllInTree(root, predicate = { it.isScrollable })
        val horizontal = containers.firstOrNull { c ->
            val r = Rect().also { c.getBoundsInScreen(it) }
            r.width() > r.height() * 2
        }
        val scrolled = horizontal?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        containers.forEach { recycleNode(it) }
        recycleNode(root)
        if (scrolled) {
            AppLog.i(TAG, "scroll: container right")
            delay(400)
        }
        return scrolled
    }

    /**
     * Прокрутка экрана вниз. Контейнер прокручивается action'ом, а если его нет —
     * жестом: списки MIUI бывают без ScrollView-предка, доступного accessibility.
     */
    private suspend fun scrollDownOnce() {
        val root = service.rootInActiveWindow ?: return
        val scrollable = findScrollableContainer(root)
        val byContainer = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        recycleNode(scrollable); recycleNode(root)
        if (!byContainer) swipeUp()
        AppLog.i(TAG, "scroll: ${if (byContainer) "container" else "gesture"} down")
        delay(400)
    }

    /**
     * Прокрутка вниз до появления любой из строк [texts]: тумблер или маркер целевого
     * экрана мог оказаться ниже сгиба (Mi Music: «Показывать рекламу» в разделе
     * «Дополнительные настройки»). Возвращает true, если строка появилась на экране.
     */
    private suspend fun scrollUntilScreenHasAny(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        repeat(SWITCH_FALLBACK_SCROLLS) {
            if (cancelled) return false
            scrollDownOnce()
            if (screenHasAny(texts)) return true
        }
        return false
    }

    /** Ожидание экрана вынесено в [ComponentVerifier] (маркеры + нечёткий рубеж). */
    private suspend fun awaitScreen(markers: List<String>, timeoutMs: Long = 3000L): Boolean =
        ComponentVerifier.awaitScreen(
            service = service,
            markers = markers,
            timeoutMs = timeoutMs,
            minMatches = 1,
            isCancelled = { cancelled }
        )

    /** Сбор текста экрана вынесен в [NodeTree]. */
    private fun collectAllText(node: AccessibilityNodeInfo?): String = NodeTree.collectText(node)

    /** Актуальный текст активного экрана (проверка смены экрана после тапа). */
    private fun currentScreenText(): String {
        val root = service.rootInActiveWindow ?: return ""
        val text = collectAllText(root)
        recycleNode(root)
        return text
    }

    /**
     * Текст экрана без тулбара (`action_bar*`): заголовок «Конфиденциальность» не
     * должен считаться ни уровнем маршрута, ни признаком целевого экрана.
     */
    private fun collectBodyText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > NodeTree.DEFAULT_MAX_DEPTH) return
            if (n.viewIdResourceName?.contains("action_bar") == true) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return sb.toString()
    }

    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent;
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent; depth++
        }
        val rect = Rect(); node.getBoundsInScreen(rect)
        return performGesture(
            rect.centerX().toFloat(),
            rect.centerY().toFloat(),
            rect.centerX().toFloat(),
            rect.centerY().toFloat(),
            100
        )
    }

    private suspend fun performGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val dispatched =
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /** Установлен/виден ли пакет: getPackageInfo + видимость через launcher-интент. */
    private fun isInstalled(pkg: String): Boolean = ActivityScanner.isPackageVisible(service, pkg)

    /** Пакеты-кандидаты шага: семантика каталога + legacy requiredPackages. */
    private fun candidatePackages(step: SimpleSteps.Step): List<String> =
        (SemanticCatalog.requiredPackages(step.id) + step.requiredPackages).distinct()


    // Легаси: recycle() deprecated с API 33 (система перерабатывает узлы автоматически),
    // но на Android 10–12 возвращает узел в пул — поэтому версионный guard.
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        node ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // checked_before + гейт целостности оверлея (коммит 3: discovery/откат)
    // ══════════════════════════════════════════════════════════════

    /**
     * Хранилище снапшота отката (DataStore через приложение).
     * В unit-тестах mock-сервис не даёт Application → null, запись пропускается.
     */
    private val prefs: PreferencesManager? by lazy {
        runCatching { (service.applicationContext as? XiaoHyperApp)?.preferencesManager }
            .getOrNull()
    }

    /** Фиксирует фактическое состояние тумблера (checked_before) в снапшоте отката. */
    private suspend fun recordCheckedBefore(stepId: String, checkedBefore: Boolean) {
        val store = prefs ?: return
        runCatching { store.recordSimpleToggleState(stepId, checkedBefore) }
            .onFailure { AppLog.w(TAG, "recordCheckedBefore failed: ${it.message}") }
    }

    /**
     * Гейт оверлея (Аддендум A4): ждёт до 2 с восстановления окна прогресса.
     * Не восстановилось — ставит статус «пауза» и сообщает false (шаг не выполняем).
     */
    private suspend fun awaitOverlayReadyOrPause(): Boolean {
        if (OverlayController.isOverlaySolid()) return true
        var waited = 0L
        while (waited < OVERLAY_GATE_WAIT_MS && !OverlayController.isOverlaySolid()) {
            delay(OVERLAY_GATE_POLL_MS)
            waited += OVERLAY_GATE_POLL_MS
        }
        if (OverlayController.isOverlaySolid()) {
            AppLog.i(TAG, "overlay solid again after ${waited}ms")
            return true
        }
        AppLog.w(TAG, "overlay not solid after ${waited}ms — pausing step")
        val text = runCatching { service.getString(R.string.overlay_paused) }
            .getOrNull() ?: "overlay unavailable"
        OverlayController.updateStatus(service, text)
        return false
    }
}