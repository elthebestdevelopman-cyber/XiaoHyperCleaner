package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.SimpleRunner
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OverlayPermissionProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Контроллер Simple Mode — машина состояний для процесса оптимизации через Accessibility.
 *
 * Отвечает за:
 * 1. Поток запроса разрешений (overlay → accessibility → battery)
 * 2. Последовательное выполнение 26 шагов из SimpleSteps
 * 3. Автоматическое продвижение между шагами
 * 4. Обработку ошибок и пропусков
 *
 * ИСПРАВЛЕНИЯ (beta11):
 * - Защита от повтора диалога батареи (MIUI кэширует isIgnoringBatteryOptimizations)
 * - Освобождение Wake Lock при отмене и завершении оптимизации
 */
class SimpleModeController(
    private val context: Context,
    private val permissionFlow: PermissionFlowManager,
    /**
     * Гейт предшага: `true` — шаги начинать можно. Обязательный параметр конструктора:
     * «забыл назначить» становится ошибкой компиляции, а не тихим проходом в шаги
     * без решения пользователя. ViewModel отвечает на него показом предшага установщика.
     */
    private val preStepsGate: () -> Boolean,
    private val onStateChanged: (SimpleModeState) -> Unit
) {
    companion object {
        private const val TAG = "SimpleModeController"
    }

    data class SimpleModeState(
        val active: Boolean = false,
        val phase: SimpleModePhase = SimpleModePhase.INACTIVE,
        val permissionSubPhase: PermissionSubPhase = PermissionSubPhase.INACTIVE,
        val currentStepIndex: Int = 0,
        val completedCount: Int = 0,
        val step: SimpleStepState? = null,
        val done: Pair<Int, Int>? = null,
        val showAppInfoDialog: Boolean = false,
        val showOverlayDialog: Boolean = false,
        val showAccessibilityDialog: Boolean = false,
        val showRestrictedDialog: Boolean = false,
        val showLocationDialog: Boolean = false,
        val showPermissionFallbackDialog: Boolean = false,
        val stuckPhase: PermissionSubPhase? = null,
        val showRestrictedSettingsScreen: Boolean = false,
        val restrictedSettingsShown: Boolean = false,
        val accessibilityAttempts: Int = 0,
        val overlayAttempts: Int = 0,
        val appInfoAttempts: Int = 0,
        val showBatteryDialog: Boolean = false,
        val failedStepIds: List<String> = emptyList(),
        /**
         * Шаги, которые переключились, но состояние не прочиталось (`state_unreadable`):
         * отдельное ведро отчёта — не провал и не успех. Пользователь видит «отработал,
         * проверить не удалось»; в откат шаг попадёт по факту тапа (D2).
         */
        val unconfirmedStepIds: List<String> = emptyList(),
        val skippedStepIds: List<String> = emptyList(),
        /** Реально переключённые notif_*-шаги: список для экрана результатов (Аддендум C3). */
        val notifToggledStepIds: List<String> = emptyList(),
        /**
         * Шаги, где тумблер УЖЕ был в целевом состоянии (reason=already_off/already_done):
         * ничего не меняли, но состояние проверено — пользователь должен видеть разницу
         * между «выключено сейчас» и «было выключено».
         */
        val alreadyOffStepIds: List<String> = emptyList(),
        /**
         * Прогон прерван пользователем ПОСЛЕ подтверждения отмены (S4): результат
         * частичный — `partial=true` в логах и состоянии.
         */
        val partialRun: Boolean = false
    )

    val isActive: Boolean get() = state.active
    val failedStepIds: List<String> get() = state.failedStepIds
    val unconfirmedStepIds: List<String> get() = state.unconfirmedStepIds
    val skippedStepIds: List<String> get() = state.skippedStepIds

    private fun checkAccessibility(): Boolean {
        val component = ComponentName(context, AdbEnablerService::class.java).flattenToString()
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
    }

    private fun checkOverlay(): Boolean = Settings.canDrawOverlays(context)

    private var state: SimpleModeState = SimpleModeState()
    /** Снимок состояния контроллера (тесты отчёта: какие шаги проверены как «уже выключено»). */
    internal val snapshot: SimpleModeState get() = state
    private var isAccessibilityEnabled: Boolean = checkAccessibility()
    private var isOverlayGranted: Boolean = checkOverlay()
    private var stepAttempt: Int = 1
    private var stepsStarted: Boolean = false

    /** Прозрачность уведомлений (дефолт ON): фильтр notif_* в PlanBuilder. */
    @Volatile
    private var notifTransparency: Boolean = true

    private var autoFlowJob: Job? = null
    /** Сырой журнал провалов ДО фильтрации: пользовательское ведро — `failedStepIds` в состоянии. */
    private val failedIds: MutableList<String> = mutableListOf()
    /** Сырой журнал «не подтверждено»: шаг мимо ведра провалов, но и не успех. */
    private val unconfirmedIds: MutableList<String> = mutableListOf()
    private val skippedIds: MutableList<String> = mutableListOf()
    private val notifToggledIds: MutableList<String> = mutableListOf()
    private val alreadyOffIds: MutableList<String> = mutableListOf()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var restrictedLocation: RestrictedLocation = RestrictedLocation.UNKNOWN

    private val needsRestrictedUnlock: Boolean by lazy {
        permissionFlow.isSideloadedOnAndroid13Plus()
    }

    // ═══════════════════════════════════════════════════════════════
    // ИСПРАВЛЕНИЕ (beta11): Защита от повтора диалога батареи
    // ═══════════════════════════════════════════════════════════════
    /**
     * Флаг "уже показывали диалог батареи".
     * Если true — больше не показываем диалог, сразу переходим к STEPS.
     * Это решает проблему MIUI, когда isIgnoringBatteryOptimizations
     * остаётся false даже после включения "нет ограничений".
     */
    private var batteryDialogAlreadyShown: Boolean = false

    /**
     * Разрешение оверлея пропало во время прогона и не восстановилось: прогон уже
     * остановлен одним итогом, повторные сигналы игнорируются (see
     * [onOverlayPermissionLost]).
     */
    private var overlayLost: Boolean = false

    fun setState(update: SimpleModeState.() -> SimpleModeState) {
        state = state.update()
        onStateChanged(state)
    }

    /**
     * Пользователь подтвердил отмену прогона в оверлее (S4): состояние помечается
     * частичным — «выполнено частично», а не «прервано без следа». Раньше нажатие
     * единственной кнопки отмены останавливало прогон мгновенно и случайно
     * (прогон rmuh2vb1r, шаг 27/28).
     */
    fun markPartialRun() {
        AppLog.i(TAG, "run: partial=true (отмена подтверждена пользователем)")
        setState { copy(partialRun = true) }
    }

    /**
     * P0 (прогон rmuu7xcch): разрешение «Поверх других окон» пропало во время прогона и
     * не восстановилось. Продолжать шаги нельзя — окна автоматизации нет, каждый
     * следующий шаг упал бы `overlay_not_attached` (17 шагов каскадом), а настройки
     * применялись бы «слепой» автоматизацией. Прогон закрывается одним честным итогом;
     * экран результата поднимает MainActivity (оверлея нет).
     */
    fun onOverlayPermissionLost() {
        if (!state.active) {
            AppLog.w(TAG, "onOverlayPermissionLost: controller inactive, ignoring")
            return
        }
        if (overlayLost) {
            AppLog.w(TAG, "onOverlayPermissionLost: already stopped, ignoring duplicate")
            return
        }
        overlayLost = true
        autoFlowJob?.cancel()
        releaseWakeLock()
        val total = SimplePlan.all().size
        val executed = (state.currentStepIndex + 1).coerceAtMost(total)
        val notRun = (total - executed).coerceAtLeast(0)
        AppLog.e(
            TAG,
            "overlay permission lost: run stopped at $executed/$total " +
                "(${state.step?.step?.id}), not_executed=$notRun"
        )
        setState {
            copy(
                phase = SimpleModePhase.DONE,
                permissionSubPhase = PermissionSubPhase.DONE,
                step = null,
                partialRun = true,
                done = Pair(completedCount, (total - skippedIds.size).coerceAtLeast(0))
            )
        }
        // Фазу завершает обычный путь закрытия результата (reset -> endPhase + hide):
        // инвариант «один show на фазу» не ломаем.
        publishResult()
    }

    /**
     * Экран результата: один и тот же итог для штатного финиша и для аварийной
     * остановки (потеря разрешения оверлея) — иначе ветки разъезжаются при правке.
     */
    private fun publishResult() {
        // «Не подтверждено» — отдельное ведро: шаг отработал, но проверить его нельзя.
        // В провалы он не попадает, иначе пользователь видит его дважды и не понимает,
        // что именно повторять (D1b, прогон rmvgvhbzu).
        val reportedFailed = failedIds.filterNot { it in unconfirmedIds }
        OverlayController.showResult(
            context,
            state.completedCount,
            state.done?.second ?: 0,
            reportedFailed.size,
            skippedIds.size,
            notifToggledIds.map { stepTitle(it) },
            alreadyOffIds.map { stepTitle(it) },
            // Провалившиеся шаги — списком: счётчик без имён не показывает, что осталось
            // включённым (F6, прогон rmvb6atul).
            failedSteps = reportedFailed.map { stepTitle(it) },
            unconfirmedSteps = unconfirmedIds.map { stepTitle(it) }
        )
    }

    fun destroy() {
        if (state.active && state.phase == SimpleModePhase.STEPS) {
            AppLog.i(TAG, "destroy() skipped while STEPS are actively running")
            return
        }
        autoFlowJob?.cancel()
        // hide() — обязанность вызывающего (MainViewModel.onCleared), не дублируем
        releaseWakeLock()  // НОВОЕ (beta11): гарантированное освобождение
        scope.cancel()
    }

    fun updatePermissionStatuses(accEnabled: Boolean, overlayGranted: Boolean) {
        val accJustEnabled: Boolean = !isAccessibilityEnabled && accEnabled
        val overlayJustEnabled: Boolean = !isOverlayGranted && overlayGranted
        isAccessibilityEnabled = accEnabled
        isOverlayGranted = overlayGranted

        if (!state.active) return
        if (state.phase != SimpleModePhase.PERMISSIONS) return

        if (accJustEnabled || overlayJustEnabled) {
            AppLog.i(
                TAG,
                "Permission changed (acc=$accJustEnabled, overlay=$overlayJustEnabled) — advancing"
            )
            advance()
        }
    }

    fun onResumeAfterPermissionReturn() {
        if (!state.active || state.phase != SimpleModePhase.PERMISSIONS) return
        AppLog.i(TAG, "onResumeAfterPermissionReturn: subPhase=${state.permissionSubPhase}")
        scope.launch {
            delay(300.milliseconds)
            advance()
        }
    }

    fun refresh() {
        if (state.active && state.phase == SimpleModePhase.PERMISSIONS) {
            AppLog.d(TAG, "refresh() — re-checking current sub-phase")
            advance()
        }
    }

    fun start() {
        AppLog.i(TAG, "Starting simple mode, needsRestrictedUnlock=$needsRestrictedUnlock")
        isAccessibilityEnabled = checkAccessibility()
        isOverlayGranted = checkOverlay()
        failedIds.clear()
        unconfirmedIds.clear()
        skippedIds.clear()
        notifToggledIds.clear()
        alreadyOffIds.clear()
        stepAttempt = 1
        stepsStarted = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        batteryDialogAlreadyShown = false  // НОВОЕ (beta11): сброс флага
        overlayLost = false

        state = SimpleModeState(
            active = true,
            phase = SimpleModePhase.PERMISSIONS,
            permissionSubPhase = PermissionSubPhase.OVERLAY
        )
        onStateChanged(state)
        advance()
    }

    // ═══════════════════════════════════════════════════════════════
    // Диалоги разрешений
    // ═══════════════════════════════════════════════════════════════

    fun onAppInfoDialogAgreed() {
        setState { copy(showAppInfoDialog = false, appInfoAttempts = appInfoAttempts + 1) }
        ChainFlags.waitingAccessibilityReturn = true
        permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
    }

    fun onAppInfoDialogCancelled() = reset()

    fun onAppInfoReturnWithoutSuccess() {
        if (state.appInfoAttempts >= 1 && restrictedLocation == RestrictedLocation.UNKNOWN) {
            setState { copy(showLocationDialog = true) }
        }
    }

    fun onLocationChosen(location: RestrictedLocation) {
        restrictedLocation = location
        setState { copy(showLocationDialog = false) }
        if (location == RestrictedLocation.ABSENT) {
            setState { copy(permissionSubPhase = PermissionSubPhase.OVERLAY) }
            advance()
        } else {
            permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
        }
    }

    fun onLocationDialogCancelled() = reset()

    fun onDialogAgreed() {
        when (state.permissionSubPhase) {
            PermissionSubPhase.ACCESSIBILITY -> {
                setState {
                    copy(
                        showAccessibilityDialog = false,
                        accessibilityAttempts = accessibilityAttempts + 1
                    )
                }
                ChainFlags.waitingAccessibilityReturn = true
                permissionFlow.openAccessibilityWithPointer()
            }

            PermissionSubPhase.OVERLAY -> {
                setState { copy(showOverlayDialog = false, overlayAttempts = overlayAttempts + 1) }
                permissionFlow.openOverlayWithPointer()
            }

            else -> setState {
                copy(
                    showAccessibilityDialog = false, showOverlayDialog = false,
                    showRestrictedDialog = false, showAppInfoDialog = false
                )
            }
        }
    }

    fun onDialogCancelled() = reset()

    fun onRestrictedDialogAgreed() {
        setState {
            copy(
                showRestrictedDialog = false, showAccessibilityDialog = false,
                permissionSubPhase = PermissionSubPhase.APP_INFO,
                restrictedSettingsShown = true, accessibilityAttempts = 0
            )
        }
        permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
    }

    fun onRestrictedDialogCancelled() = reset()

    fun onRestrictedScreenOpenSettings() {
        setState {
            copy(appInfoAttempts = appInfoAttempts + 1)
        }
        permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
    }

    fun onRestrictedScreenDone() {
        setState {
            copy(
                showRestrictedSettingsScreen = false,
                restrictedSettingsShown = true,
                permissionSubPhase = PermissionSubPhase.ACCESSIBILITY
            )
        }
        advance()
    }

    fun onRestrictedScreenCancelled() = reset()

    // ═══════════════════════════════════════════════════════════════
    // Батарея (ИСПРАВЛЕНО beta11)
    // ═══════════════════════════════════════════════════════════════

    fun onBatteryDialogAgreed() {
        AppLog.i(TAG, "Battery dialog agreed")
        batteryDialogAlreadyShown = true  // НОВОЕ: запоминаем что показывали
        setState { copy(showBatteryDialog = false) }
        permissionFlow.openBatteryOptimizationWithPointer()
    }

    fun onBatteryDialogSkipped() {
        AppLog.i(TAG, "Battery dialog skipped — advancing to STEPS")
        batteryDialogAlreadyShown = true
        if (state.phase == SimpleModePhase.STEPS || state.phase == SimpleModePhase.DONE) {
            AppLog.w(TAG, "onBatteryDialogSkipped: already in STEPS/DONE, ignoring")
            return
        }
        if (!goSteps()) return
        nextStep(autoStart = true)
    }

    fun onBatteryReturn(ignoring: Boolean) {
        AppLog.i(
            TAG,
            "onBatteryReturn: isIgnoringBatteryOptimizations=$ignoring, alreadyShown=$batteryDialogAlreadyShown"
        )

        // СТРОГАЯ ЗАЩИТА: если мы уже в фазе шагов, повторный вызов (гонка onResume)
        // НЕ должен перезапускать цепочку или отменять текущий шаг.
        if (state.phase == SimpleModePhase.STEPS || state.phase == SimpleModePhase.DONE) {
            AppLog.i(TAG, "onBatteryReturn: already in STEPS/DONE, ignoring duplicate")
            return
        }

        if (ignoring) {
            if (state.phase != SimpleModePhase.STEPS) {
                // Фазу переводит сам goSteps: гейт предшага обязан проверяться ДО неё,
                // иначе закрытый гейт оставил бы режим в STEPS без плана и без шага.
                if (!goSteps()) return
                nextStep(autoStart = true)
            }
        } else if (batteryDialogAlreadyShown) {
            // ИСПРАВЛЕНО (beta11): если уже показывали диалог и пользователь вернулся
            // БЕЗ включения — НЕ показываем повторно, сразу идём к STEPS.
            // Это решает проблему MIUI, где isIgnoring всегда false.
            AppLog.i(TAG, "onBatteryReturn: already shown dialog, advancing to STEPS")
            if (!goSteps()) return
            nextStep(autoStart = true)
        }
    }

    fun reshowBatteryDialog() {
        // КРИТИЧНО: если шаги уже идут — НЕ вызывать nextStep повторно
        // (иначе отменяется текущий шаг JobCancellationException, как в логе msa→step2)
        if (state.phase == SimpleModePhase.STEPS || state.phase == SimpleModePhase.DONE) {
            AppLog.i(TAG, "reshowBatteryDialog: already in STEPS/DONE, ignoring")
            return
        }
        if (batteryDialogAlreadyShown) {
            AppLog.i(TAG, "reshowBatteryDialog: already shown, advancing to STEPS once")
            if (!goSteps()) return
            nextStep(autoStart = true)
            return
        }
        AppLog.i(TAG, "reshowBatteryDialog: user returned without disabling")
        if (state.permissionSubPhase == PermissionSubPhase.BATTERY_OPTIMIZATION) {
            setState { copy(showBatteryDialog = true) }
        }
    }

    fun continueToSteps() {
        AppLog.i(TAG, "continueToSteps: user confirmed, starting steps")
        if (state.phase == SimpleModePhase.STEPS || state.phase == SimpleModePhase.DONE) {
            AppLog.w(TAG, "continueToSteps: already in STEPS/DONE, ignoring")
            return
        }
        // Гейт предшага закрыт — выходим до nextStep: шаг без решения пользователя не начинается.
        if (!goSteps()) return
        nextStep(autoStart = true)
    }

    /**
     * Гейт предшага: `true` — шаги начинать можно.
     *
     * Предикат назначается ViewModel обязательным параметром конструктора, поэтому
     * «неназначенного» гейта не существует. Единственная точка проверки — [goSteps]:
     * в шаги ведут пять дверей ([advance] по разрешениям, [continueToSteps], решения
     * по батарее в [onBatteryDialogSkipped]/[onBatteryReturn]/[reshowBatteryDialog]),
     * и своё условие в каждой разошлось бы при первой же правке.
     */
    internal fun preStepsGateOpen(): Boolean {
        val open = preStepsGate()
        if (!open) AppLog.i(TAG, "preStepsGateOpen: pre-step pending, steps not started")
        return open
    }

    /**
     * Единственная точка входа в шаги.
     *
     * @return false — шаги НЕ стартовали (гейт предшага закрыт или префлайт не прошёл):
     * вызывающий обязан выйти, не вызывая [nextStep].
     */
    private fun goSteps(): Boolean {
        // Гейт — до любых побочных эффектов: снятия pending-флага, префлайта, сборки плана.
        if (!preStepsGateOpen()) {
            // Вопрос батареи к этому моменту уже решён — диалог не должен висеть поверх предшага.
            if (state.showBatteryDialog) setState { copy(showBatteryDialog = false) }
            return false
        }
        autoFlowJob?.cancel()
        // Не возобновлять Simple Mode после смерти процесса посреди шагов
        scope.launch {
            runCatching {
                com.xiaohypercleaner.XiaoHyperApp.instance.preferencesManager
                    .setPendingSimpleMode(false)
            }
        }
        // Префлайт перед шагами: разрешения перечитываются «здесь и сейчас», а не берутся
        // из кэша старта (за фазу разрешений кэш успевает устареть). appops
        // SYSTEM_ALERT_WINDOW читается публичным AppOpsManager (OverlayPermissionProbe);
        // MIUI-ops 10017/10020/10021 сторонним приложением не читаются — их проверяет
        // adb-рецепт (docs/diag/handoff_active.md). Прогон rmuu7xcch: без такой проверки
        // разрешение пропало на 11-м шаге и 17 шагов упали каскадом.
        val overlayPreflight = OverlayPermissionProbe.isGranted(context)
        isOverlayGranted = overlayPreflight
        isAccessibilityEnabled = checkAccessibility()
        val batteryPreflight = permissionFlow.isIgnoringBatteryOptimizations()
        AppLog.i(
            TAG,
            "preflight: overlay=$overlayPreflight (${OverlayPermissionProbe.describe(context)}) " +
                "accessibility=$isAccessibilityEnabled battery=$batteryPreflight"
        )
        if (!overlayPreflight) {
            // Шаги без окна автоматизации — это «слепая» автоматизация: возвращаемся к
            // диалогу разрешения, а не стартуем цепочку.
            AppLog.w(TAG, "preflight: overlay permission missing — back to permission phase")
            OverlayController.markPermissionLost()
            if (state.overlayAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                setState {
                    copy(
                        phase = SimpleModePhase.PERMISSIONS,
                        permissionSubPhase = PermissionSubPhase.OVERLAY,
                        showPermissionFallbackDialog = true,
                        showOverlayDialog = false,
                        stuckPhase = PermissionSubPhase.OVERLAY
                    )
                }
            } else {
                setState {
                    copy(
                        phase = SimpleModePhase.PERMISSIONS,
                        permissionSubPhase = PermissionSubPhase.OVERLAY,
                        showOverlayDialog = true
                    )
                }
            }
            return false
        }
        OverlayController.clearPermissionLost()

        // Префильтр плана: только установленные пакеты, plan-time home-skip,
        // фильтр notif_* по настройке прозрачности уведомлений.
        val profile = RomProfile.detect(context)
        SimplePlan.set(PlanBuilder.build(context, profile, notifTransparency))
        setState {
            copy(
                phase = SimpleModePhase.STEPS,
                permissionSubPhase = PermissionSubPhase.DONE,
                showBatteryDialog = false
            )
        }
        return true
    }

    /** Прозрачность уведомлений (Аддендум C4): OFF исключает notif_* из плана. */
    fun setNotifTransparency(enabled: Boolean) {
        notifTransparency = enabled
    }

    /** Локализованное имя notif_*-шага для экрана результатов. */
    private fun stepTitle(id: String): String = localizedStepTitle(id)

    // ═══════════════════════════════════════════════════════════════
    // Fallback
    // ═══════════════════════════════════════════════════════════════

    fun onFallbackRetry() {
        val phaseToRetry: PermissionSubPhase? = state.stuckPhase
        setState {
            copy(
                showPermissionFallbackDialog = false, stuckPhase = null,
                accessibilityAttempts = if (phaseToRetry == PermissionSubPhase.ACCESSIBILITY) 0 else accessibilityAttempts,
                overlayAttempts = if (phaseToRetry == PermissionSubPhase.OVERLAY) 0 else overlayAttempts,
                appInfoAttempts = if (phaseToRetry == PermissionSubPhase.APP_INFO) 0 else appInfoAttempts
            )
        }
        advance()
    }

    fun onFallbackOpenSettings() {
        setState { copy(showPermissionFallbackDialog = false) }
        when (state.stuckPhase) {
            PermissionSubPhase.ACCESSIBILITY -> permissionFlow.openAccessibilityWithPointer()
            PermissionSubPhase.OVERLAY -> permissionFlow.openOverlayWithPointer()
            PermissionSubPhase.APP_INFO, PermissionSubPhase.RESTRICTED_SETTINGS ->
                permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)

            else -> AppLog.w(TAG, "No fallback settings for phase ${state.stuckPhase}")
        }
    }

    fun onFallbackCancelled() = reset()

    // ═══════════════════════════════════════════════════════════════
    // Шаги
    // ═══════════════════════════════════════════════════════════════

    fun startCurrentStep(force: Boolean = false) {
        val current: SimpleStepState = state.step ?: return
        if (current.status == SimpleStepState.Status.WORKING && !force) {
            AppLog.w(TAG, "startCurrentStep: already WORKING, ignoring double-tap")
            return
        }
        stepAttempt = current.attempt.coerceAtLeast(1)
        launchStep()
    }

    fun retryStep() {
        stepAttempt = 1
        launchStep()
    }

    private fun launchStep() {
        autoFlowJob?.cancel()
        setState {
            copy(
                step = step?.copy(
                    status = SimpleStepState.Status.WORKING,
                    attempt = stepAttempt
                )
            )
        }
        val intent: Intent = Intent(context, AdbEnablerService::class.java).apply {
            action = AdbEnablerService.ACTION_SIMPLE_STEP
            putExtra("step_index", state.currentStepIndex)
            putExtra(AdbEnablerService.EXTRA_STEP_ID, state.step?.step?.id)
        }
        context.startService(intent)
    }

    fun onStepResult(success: Boolean, attempt: Int, finalFailure: Boolean = false) {
        AppLog.i(
            TAG,
            "onStepResult: success=$success, attempt=$attempt, step=${state.currentStepIndex}, finalFailure=$finalFailure"
        )
        val step: SimpleStepState = state.step ?: return
        if (!state.active) {
            AppLog.w(TAG, "onStepResult: controller inactive, ignoring")
            return
        }

        // Гарантия индекса: если результат пришёл от предыдущего шага (гонка), игнорируем
        if (step.stepIndex != state.currentStepIndex) {
            AppLog.w(
                TAG,
                "onStepResult: stale result ignored (stepIndex=${step.stepIndex} != current=${state.currentStepIndex})"
            )
            return
        }

        if (success) {
            val newCount: Int = state.completedCount + 1
            setState {
                copy(
                    completedCount = newCount,
                    step = step.copy(
                        status = SimpleStepState.Status.SUCCESS,
                        completedCount = newCount, attempt = attempt
                    )
                )
            }
            scheduleAdvance()
            return
        }

        if (finalFailure) {
            AppLog.e(TAG, "onStepResult: FINAL failure for step ${state.currentStepIndex}")
            if (!failedIds.contains(step.step.id)) failedIds.add(step.step.id)
            setState {
                copy(
                    // Ведро провалов исключает неподтверждённые. Решение принимается по
                    // состоянию, а не по порядку вызовов: noteUnconfirmed ставит тот же фильтр.
                    failedStepIds = failedIds.filterNot { it in unconfirmedIds },
                    step = step.copy(status = SimpleStepState.Status.FAILED, attempt = attempt)
                )
            }
            scheduleAdvance()
            return
        }

        setState { copy(step = step.copy(status = SimpleStepState.Status.IDLE, attempt = attempt)) }
    }

    fun onStepSkipped(stepId: String, kind: String = SimpleRunner.SkipKind.NOT_ON_DEVICE.name) {
        AppLog.i(TAG, "onStepSkipped: step=$stepId kind=$kind, index=${state.currentStepIndex}")
        // Пропуском в отчёте остаётся одно ведро — «нет на устройстве»; kind приходит из
        // раннера транспортом (SkipKind не трогаем) и виден только в логе.
        if (!skippedIds.contains(stepId)) skippedIds.add(stepId)
        setState { copy(skippedStepIds = skippedIds.toList()) }
        scheduleAdvance()
    }

    /**
     * Регистрирует фактически переключённый notif_*-шаг: такие шаги попадают
     * списком на экран результатов (уведомление отключено у этих приложений).
     */
    fun noteNotifToggled(stepId: String) {
        if (!stepId.startsWith("notif_")) return
        if (notifToggledIds.contains(stepId)) return
        notifToggledIds.add(stepId)
        AppLog.i(TAG, "notif toggled: step=$stepId total=${notifToggledIds.size}")
        setState { copy(notifToggledStepIds = notifToggledIds.toList()) }
    }

    /**
     * Регистрирует шаг, тумблер которого УЖЕ был в целевом состоянии: робот ничего
     * не менял, но факт проверен в снапшоте отката. Раздел «уже выключено» на экране
     * результатов — чтобы пользователь видел состояние, а не догадывался.
     */
    fun noteAlreadyOff(stepId: String) {
        if (alreadyOffIds.contains(stepId)) return
        alreadyOffIds.add(stepId)
        AppLog.i(TAG, "already off: step=$stepId total=${alreadyOffIds.size}")
        setState { copy(alreadyOffStepIds = alreadyOffIds.toList()) }
    }

    /**
     * Регистрирует шаг, который переключился, но состояние прочитано не было
     * (`state_unreadable`). Это не провал и не успех: отдельное ведро отчёта, чтобы
     * пользователь видел «отработал, проверить не удалось», а не «не получилось».
     *
     * `failedStepIds` пересчитывается тем же фильтром, что и в [onStepResult]: инвариант
     * «шаг ровно в одном ведре» не зависит от порядка вызовов.
     */
    fun noteUnconfirmed(stepId: String) {
        if (unconfirmedIds.contains(stepId)) return
        unconfirmedIds.add(stepId)
        AppLog.i(TAG, "unconfirmed: step=$stepId total=${unconfirmedIds.size}")
        setState {
            copy(
                unconfirmedStepIds = unconfirmedIds.toList(),
                failedStepIds = failedIds.filterNot { it in unconfirmedIds }
            )
        }
    }

    private fun scheduleAdvance() {
        autoFlowJob?.cancel()
        autoFlowJob = scope.launch {
            delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
            // Даём сервису доп. время освободить мьютекс после завершения шага
            delay(150)
            if (state.active) {
                AppLog.i(TAG, "auto-advance -> nextStep")
                nextStep(autoStart = true)
            } else {
                AppLog.w(TAG, "auto-advance skipped: state inactive")
            }
        }
    }

    fun nextStep(autoStart: Boolean = false) {
        if (state.phase == SimpleModePhase.DONE) return

        val steps: List<SimpleSteps.Step> = SimplePlan.all().map { it.step }
        val nextIndex: Int = if (stepsStarted) state.currentStepIndex + 1 else 0
        stepsStarted = true

        if (nextIndex >= steps.size) {
            AppLog.i(TAG, "All simple steps completed")
            val finalCompleted: Int = state.completedCount
            val applicable: Int = (steps.size - skippedIds.size).coerceAtLeast(0)

            releaseWakeLock()

            setState {
                copy(
                    phase = SimpleModePhase.DONE, permissionSubPhase = PermissionSubPhase.DONE,
                    step = null, done = Pair(finalCompleted, applicable)
                )
            }
            publishResult()
            return
        }

        if (nextIndex == 0) {
            OverlayController.startAutomation(context, steps.size)
        }

        val nextStepObj: SimpleSteps.Step = steps[nextIndex]
        val currentCompletedCount: Int = state.completedCount
        setState {
            copy(
                currentStepIndex = nextIndex,
                step = SimpleStepState(
                    step = nextStepObj, status = SimpleStepState.Status.IDLE,
                    attempt = 1, completedCount = currentCompletedCount,
                    stepIndex = nextIndex, totalSteps = steps.size
                )
            )
        }
        if (autoStart) {
            // Пауза перед запуском следующего шага, чтобы предыдущий SimpleRunner
            // успел освободить stepMutex и закончить работу без JobCancellationException
            scope.launch {
                delay(150)
                if (state.active && state.step?.stepIndex == nextIndex) {
                    startCurrentStep()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // advance (permission-фазы)
    // ═══════════════════════════════════════════════════════════════

    private fun advance() {
        if (!state.active) return
        if (state.phase != SimpleModePhase.PERMISSIONS) return

        when {
            // 1. Разрешение «Поверх других окон»
            !isOverlayGranted -> {
                if (state.overlayAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    setState {
                        copy(
                            showPermissionFallbackDialog = true,
                            showOverlayDialog = false,
                            stuckPhase = PermissionSubPhase.OVERLAY
                        )
                    }
                    return
                }
                setState {
                    copy(
                        permissionSubPhase = PermissionSubPhase.OVERLAY,
                        showOverlayDialog = true,
                        showRestrictedSettingsScreen = false,
                        showAccessibilityDialog = false,
                        showBatteryDialog = false
                    )
                }
            }

            // 2. Ограниченные настройки (Android 13+) — оверлей уже выдан, pointer работает
            needsRestrictedUnlock && !state.restrictedSettingsShown -> {
                setState {
                    copy(
                        permissionSubPhase = PermissionSubPhase.RESTRICTED_SETTINGS,
                        showRestrictedSettingsScreen = true,
                        showOverlayDialog = false,
                        showAccessibilityDialog = false,
                        showBatteryDialog = false
                    )
                }
            }

            // 3. Специальные возможности (AdbEnablerService)
            !isAccessibilityEnabled -> {
                if (state.accessibilityAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    setState {
                        copy(
                            showPermissionFallbackDialog = true,
                            showAccessibilityDialog = false,
                            stuckPhase = PermissionSubPhase.ACCESSIBILITY
                        )
                    }
                    return
                }
                setState {
                    copy(
                        permissionSubPhase = PermissionSubPhase.ACCESSIBILITY,
                        showAccessibilityDialog = true,
                        showOverlayDialog = false,
                        showRestrictedSettingsScreen = false,
                        showBatteryDialog = false
                    )
                }
            }

            // 4. Оптимизация батареи (показываем один раз)
            !permissionFlow.isIgnoringBatteryOptimizations() && !batteryDialogAlreadyShown -> {
                setState {
                    copy(
                        permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION,
                        showBatteryDialog = true,
                        showOverlayDialog = false,
                        showRestrictedSettingsScreen = false,
                        showAccessibilityDialog = false
                    )
                }
            }

            // 5. Все разрешения предоставлены — переходим к автоматизации
            // (батарея: либо выдана, либо диалог уже показали один раз)
            else -> {
                if (state.phase == SimpleModePhase.STEPS) {
                    AppLog.i(TAG, "advance: already STEPS, skip re-entry")
                    return
                }
                if (!goSteps()) return
                nextStep(autoStart = true)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Wake Lock management (beta11)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Освобождает wake lock через AdbEnablerService.
     * Вызывается при:
     * - Завершении всех шагов (nextStep -> DONE)
     * - Сбросе контроллера (reset/destroy)
     * - Отмене оптимизации пользователем
     */
    private fun releaseWakeLock() {
        try {
            context.startService(
                Intent(context, AdbEnablerService::class.java).apply {
                    action = AdbEnablerService.ACTION_RELEASE_WAKE
                }
            )
            AppLog.i(TAG, "releaseWakeLock: sent ACTION_RELEASE_WAKE")
        } catch (e: Exception) {
            AppLog.w(TAG, "releaseWakeLock failed: ${e.message}")
        }
    }

    /**
     * Полная отмена Simple Mode: UI + контроллер + runner.
     */
    fun cancelAndReset() {
        AppLog.i(TAG, "cancelAndReset")
        reset()
    }

    /**
     * Сбрасывает контроллер в начальное состояние.
     */
    private fun reset() {
        AppLog.i(TAG, "Resetting simple mode controller")
        autoFlowJob?.cancel()
        OverlayController.endPhase()
        OverlayController.hide(context)
        releaseWakeLock()  // НОВОЕ (beta11): освобождаем wake lock
        failedIds.clear()
        unconfirmedIds.clear()
        skippedIds.clear()
        notifToggledIds.clear()
        alreadyOffIds.clear()
        stepAttempt = 1
        stepsStarted = false
        SimplePlan.reset()
        restrictedLocation = RestrictedLocation.UNKNOWN
        batteryDialogAlreadyShown = false  // НОВОЕ (beta11): сброс флага
        overlayLost = false
        state = SimpleModeState()
        onStateChanged(state)
    }
}
/**
 * Локализованное имя шага: семантический каталог → legacy-заголовок → идентификатор.
 * Одна реализация на прогресс прогона и отчёт отката (списки невосстановленных).
 */
internal fun localizedStepTitle(id: String): String {
    val legacy = SimpleSteps.ALL.firstOrNull { it.id == id }
    val fallback = when (Locale.getDefault().language) {
        "ru" -> legacy?.titleRu ?: id
        else -> legacy?.titleEn ?: legacy?.titleRu ?: id
    }
    return SemanticCatalog.title(id, fallback)
}
