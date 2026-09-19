package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
            "cleaner" to SECURITY_CLEANER_TIMEOUT_MS
        )

        // ═══════════════════════════════════════════════════════════════
        // П.6: Возобновляемые шаги (не требуют сброса настроек)
        // ═══════════════════════════════════════════════════════════════
        private val SETTINGS_RESUMABLE_STEPS = setOf(
            "msa", "sys_recommendations", "ads_personalization", "ux_program", "carousel"
        )

        // ═══════════════════════════════════════════════════════════════
        // П.5: Системные диалоги
        // ═══════════════════════════════════════════════════════════════
        // MEDIA-шаги перенесены в ConsentWallHandler (allow аудио-разрешения для music_sys/mivideo).

        // ═══════════════════════════════════════════════════════════════
        // П.4: Структурный поиск ⋮/⚙
        // ═══════════════════════════════════════════════════════════════
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

        /** msa: максимум ожидания включённой кнопки отзыва, поллинг и пауза на сам отзыв. */
        private const val MSA_REVOKE_WAIT_MAX_MS = 11_000L
        private const val MSA_CONFIRM_POLL_MS = 500L
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
        private const val CONFIRM_RETRY_MS = 2500L
        private const val SWITCH_FALLBACK_SCROLLS = 4

    /** Scroll-until-found для уровней drill: до 4 прокруток на уровень. */
    private const val DRILL_SCROLL_TRIES = 4
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
    // lateinit: профиль устанавливается в run(). Прежний eager-detect был мёртвым:
    // результат перезаписывался в run() и никогда не читался, а на mock-сервисе
    // ронял конструктор (NPE на resources.configuration).
    private lateinit var romProfile: RomProfile

    // П.6: Флаг переиспользования окна настроек
    private var canResumeSettings: Boolean = false

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

        // Резолвинг пакета: вариантный каталог + семантическая таблица (visibility-aware).
        // Маршрут варианта может идти через Настройки (appvault_*: Рабочий стол → Лента
        // виджетов) — тогда приложение не запускаем и пакет не резолвим.
        val settingsEntry = SemanticCatalog.entry(step.id) == ENTRY_SETTINGS
        if (settingsEntry) {
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
        val isAppStep = step.launchPackage != null &&
            step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE
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
        var screenOpened = false
        for (intent in intents) {
            if (cancelled) return Result(false, "cancelled")
            try {
                service.startActivity(intent)
                screenOpened = true
                if (isAppStep) {
                    // Для app-шагов: проверяем что целевой пакет в foreground и дерево непустое
                    delay(APP_LAUNCH_DELAY_MS)
                    val root = service.rootInActiveWindow
                    val fgPkg = root?.packageName?.toString()
                    val hasTree = root != null && root.childCount > 0
                    recycleNode(root)
                    if (fgPkg == resolvedPkg && hasTree) {
                        AppLog.i(TAG, "App launched: $fgPkg, tree=$hasTree")
                        break
                    }
                    AppLog.w(TAG, "App not ready: fg=$fgPkg target=$resolvedPkg tree=$hasTree, retrying")
                    screenOpened = false
                } else if (awaitScreen(verifyTexts, timeoutMs = CONTENT_WAIT_MS)) {
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

        if (!screenOpened) return Result(false, "no_screen_opened")

        delay(if (step.launchPackage != null) APP_LAUNCH_DELAY_MS else UI_SETTLE_DELAY_MS)
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
        // Resume: если интент уже открыл нужный экран, начинаем с текущего уровня
        // (совпадение с целью шага отменяет бурение вовсе).
        val startLevel = if (mergedDrillPath.isEmpty()) 0 else resumeDrillIndex(step, mergedDrillPath)
        var drillFailure: String? = null
        if (mergedDrillPath.isNotEmpty() && startLevel < mergedDrillPath.size) {
            if (startLevel > 0) AppLog.i(TAG, "drill: resume at level $startLevel for ${step.id}")
            if (step.preDrillWaitMs > 0) delay(step.preDrillWaitMs)
            for (levelIndex in startLevel until mergedDrillPath.size) {
                if (cancelled) return Result(false, "cancelled")
                // Навигационное действие — только при целостном оверлее.
                if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")
                if (!drillIntoLevel(step, levelIndex, mergedDrillPath[levelIndex])) {
                    drillFailure = "drill_failed"
                    break
                }
                delay(UI_SETTLE_DELAY_MS)
                // Диалоги могут появиться после любого навигационного действия.
                handleConsentWalls(step)
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

        val result = if (drillFailure != null) {
            Result(false, drillFailure)
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

    private suspend fun drillIntoLevel(
        step: SimpleSteps.Step,
        levelIndex: Int,
        levelTexts: List<String>,
        path: List<List<String>> = emptyList()
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)

        // Уровень-меню (⋮/«Ещё»/«Дополнительно») открывается структурным поиском
        // overflow, в том числе по contentDescription.
        val isMenuLevel = levelTexts.any { it.trim() in MENU_LEVEL_TEXTS }
        if (isMenuLevel && findAndTapOverflow(levelTexts)) return true

        val node = findClickableByTextWithScroll(
            levelTexts,
            attempts = DRILL_SCROLL_TRIES,
            logLabel = levelTexts.firstOrNull()
        )
        if (node == null) {
            AppLog.w(
                TAG,
                "Drill level '${levelTexts.firstOrNull()}' not found, screen=[${screenText.take(120)}]"
            )
            return false
        }

        if (!tapNode(node)) {
            recycleNode(node); return false
        }
        recycleNode(node)

        val effectivePath = path.ifEmpty {
            AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
        }
        val nextTexts = effectivePath.getOrNull(levelIndex + 1)?.firstOrNull() ?: return true
        return awaitScreen(listOf(nextTexts))
    }

    /**
     * Индекс, с которого продолжать бурение:
     * - экран уже совпал с целью шага → путь исчерпан (бурение не нужно);
     * - экран совпал с текстами уровня N → начинаем с N (resume после сбоя);
     * - иначе — с начала.
     */
    internal fun resumeDrillIndex(step: SimpleSteps.Step, path: List<List<String>>): Int {
        val root = service.rootInActiveWindow ?: return 0
        val screenText = collectAllText(root)
        recycleNode(root)
        if (screenText.isBlank()) return 0

        val targets = searchTextsFor(step)
        val markers = SemanticCatalog.screenMarkers(step.id)
        val targetVisible = targets.isNotEmpty() &&
            targets.any { TextMatcher.normalizedContains(screenText, it) } &&
            (markers.isEmpty() || markers.any { TextMatcher.normalizedContains(screenText, it) })
        if (targetVisible) return path.size

        for (levelIndex in path.indices) {
            if (path[levelIndex].any { TextMatcher.normalizedContains(screenText, it) }) return levelIndex
        }
        return 0
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
            while (!cancelled) {
                val root = service.rootInActiveWindow
                val node = root?.let { findEnabledClickableByText(it, confirmTexts) }
                if (root != null) recycleNode(root)
                if (node != null) {
                    val ok = tapNode(node)
                    recycleNode(node)
                    if (ok) return@withTimeoutOrNull true
                }
                delay(MSA_CONFIRM_POLL_MS)
            }
            false
        } ?: false

        if (!tapped) {
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

    /** Кнопка по тексту, доступная для нажатия (enabled) — для отсчётных диалогов. */
    private fun findEnabledClickableByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        val node = findInTree(root) { matchesAny(it, texts) && it.isEnabled } ?: return null
        return clickableAncestorOrSelf(node) ?: node
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
            val tapped = if (checked == step.targetChecked) true else tapNode(switch)
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

    // ─── Switch finding and toggling ──────────────────────────────────────
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        val mergedSearchTexts = searchTextsFor(step)
        if (mergedSearchTexts.isEmpty()) return Result(false, "no_switch")

        if (!awaitScreen(mergedSearchTexts)) return Result(false, "switch_not_found")

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
                val scrollable = findScrollableContainer(r)
                if (scrollable == null) {
                    recycleNode(r); return@repeat
                }

                val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                recycleNode(scrollable); recycleNode(r)
                if (!scrolled) swipeUp()
                delay(400)

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

        val hit = SwitchFinder.describe(switchNode, mergedSearchTexts.first())
        val isChecked = hit.checkedBefore
        val text = hit.label
        val desc = hit.desc
        val bounds = hit.bounds

        if (isChecked == step.targetChecked) {
            recycleNode(switchNode); recycleNode(currentRoot)
            // Уже в целевом состоянии: тумблить нечего, откат этот шаг не трогает.
            return Result(true, if (step.targetChecked) "already_done" else "already_off")
        }

        // checked_before фиксируется в снапшоте отката в момент тумблера (блок 6).
        recordCheckedBefore(step.id, isChecked)

        if (!tapNode(switchNode)) {
            recycleNode(switchNode); recycleNode(currentRoot)
            return Result(false, "tap_failed")
        }
        recycleNode(switchNode); recycleNode(currentRoot)

        delay(600)
        if (!verifySwitchState(step, mergedSearchTexts)) {
            val retryRoot = service.rootInActiveWindow ?: return Result(false, "no_root_window")
            val retryNode = findSwitchByText(retryRoot, mergedSearchTexts)
            if (retryNode != null) tapNode(retryNode)
            recycleNode(retryNode); recycleNode(retryRoot)
            delay(600)
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

        // П.3: Дополнительные переключатели
        val mergedAdditionalToggles =
            AdaptiveCatalog.mergeAdditionalToggles(service, step.id, step.additionalToggles)
        for (toggleText in mergedAdditionalToggles) {
            if (cancelled) break
            val addRoot = service.rootInActiveWindow ?: continue
            val addNode = findSwitchByText(addRoot, listOf(toggleText))
            if (addNode != null && SwitchFinder.isChecked(addNode) != step.targetChecked) tapNode(addNode)
            recycleNode(addNode); recycleNode(addRoot)
            delay(400)
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
    private suspend fun findAndTapOverflow(texts: List<String> = OVERFLOW_TEXTS): Boolean {
        val root = service.rootInActiveWindow ?: return false

        findClickableByText(root, texts)?.let {
            val tapped = tapNode(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByContentDescription(root)?.let {
            val tapped = tapNode(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByPosition(root)?.let {
            val tapped = tapNode(it); recycleNode(it); recycleNode(root); return tapped
        }
        recycleNode(root)
        return performOverflowGesture()
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

    private suspend fun performOverflowGesture(): Boolean {
        val dm = service.resources.displayMetrics
        for ((fx, fy) in OVERFLOW_GESTURE_POINTS) {
            if (cancelled) return false
            if (tapAt((dm.widthPixels * fx).toInt(), (dm.heightPixels * fy).toInt())) {
                delay(UI_SETTLE_DELAY_MS); return true
            }
        }
        return false
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return suspendCancellableCoroutine { cont ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Consent-стены (welcome/permission) — см. handleConsentWalls + ConsentWallHandler
    // ═════════════════════════════════════════════════════════════════════
    /** Мост нажатий для ConsentWallHandler (поиск кликабельного узла по текстам). */
    private val consentTapBridge = object : ConsentWallHandler.TapBridge {
        override suspend fun tapByTexts(texts: List<String>): Boolean = tapSystemDialogButton(texts)

        override suspend fun tapEnabledByTexts(texts: List<String>): Boolean =
            tapSystemDialogButton(texts, requireEnabled = true)
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

    private suspend fun tapSystemDialogButton(
        texts: List<String>,
        requireEnabled: Boolean = false
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = if (requireEnabled) {
            findEnabledClickableByText(root, texts)
        } else {
            findClickableByText(root, texts)
        }
        if (node != null) {
            val tapped = tapNode(node)
            recycleNode(node); recycleNode(root)
            if (!tapped && requireEnabled) AppLog.w(TAG, "consent: button found but not tappable")
            return tapped
        }
        recycleNode(root)
        return false
    }

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
            val scrollable = findScrollableContainer(root)
            if (scrollable == null) {
                recycleNode(root); return null
            }
            if (logLabel != null) {
                AppLog.i(TAG, "drill: scroll attempt ${attempt + 1}/$attempts for '$logLabel'")
            }
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) swipeUp()
            recycleNode(scrollable); recycleNode(root)
            delay(400)
        }
        return findClickableByText(texts = texts)
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = root
        while (node != null) {
            if (node.isScrollable) return node; node = node.parent
        }
        return null
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
