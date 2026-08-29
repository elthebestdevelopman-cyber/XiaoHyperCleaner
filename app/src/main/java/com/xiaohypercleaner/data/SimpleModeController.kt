package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Архитектура:
 * - PERMISSIONS фаза: запрос разрешений через PermissionFlowManager
 * - STEPS фаза: выполнение шагов через AdbEnablerService → SimpleRunner
 * - DONE фаза: показ результатов через OverlayController
 *
 * УЛУЧШЕНИЯ:
 * 1. Явные типы для всех переменных
 * 2. Полная документация для ключевых методов
 * 3. Улучшенное логирование для диагностики
 * 4. Защита от race condition в nextStep()
 */
class SimpleModeController(
    private val context: Context,
    private val permissionFlow: PermissionFlowManager,
    private val onStateChanged: (SimpleModeState) -> Unit
) {
    companion object {
        private const val TAG = "SimpleModeController"
    }

    /**
     * Состояние Simple Mode.
     * Используется в ViewModel для управления UI.
     */
    data class SimpleModeState(
        /** Активен ли Simple Mode */
        val active: Boolean = false,

        /** Текущая фаза (PERMISSIONS, STEPS, DONE) */
        val phase: SimpleModePhase = SimpleModePhase.INACTIVE,

        /** Подфаза запроса разрешений */
        val permissionSubPhase: PermissionSubPhase = PermissionSubPhase.INACTIVE,

        /** Индекс текущего шага в SimpleSteps.ALL */
        val currentStepIndex: Int = 0,

        /** Количество успешно завершённых шагов */
        val completedCount: Int = 0,

        /** Состояние текущего шага */
        val step: SimpleStepState? = null,

        /** Финальный результат (completed, total) */
        val done: Pair<Int, Int>? = null,

        /** Показывать диалог App Info */
        val showAppInfoDialog: Boolean = false,

        /** Показывать диалог Overlay */
        val showOverlayDialog: Boolean = false,

        /** Показывать диалог Accessibility */
        val showAccessibilityDialog: Boolean = false,

        /** Показывать диалог Restricted Settings */
        val showRestrictedDialog: Boolean = false,

        /** Показывать диалог выбора местоположения кнопки */
        val showLocationDialog: Boolean = false,

        /** Показывать fallback диалог */
        val showPermissionFallbackDialog: Boolean = false,

        /** Застрявшая фаза (для retry) */
        val stuckPhase: PermissionSubPhase? = null,

        /** Показывать экран Restricted Settings */
        val showRestrictedSettingsScreen: Boolean = false,

        /** Был ли показан экран Restricted Settings */
        val restrictedSettingsShown: Boolean = false,

        /** Количество попыток включения Accessibility */
        val accessibilityAttempts: Int = 0,

        /** Количество попыток получения Overlay */
        val overlayAttempts: Int = 0,

        /** Количество попыток открытия App Info */
        val appInfoAttempts: Int = 0,

        /** Показывать диалог Battery Optimization */
        val showBatteryDialog: Boolean = false,

        /** ID шагов, которые не удалось выполнить */
        val failedStepIds: List<String> = emptyList(),

        /** ID шагов, которые были пропущены (приложение не установлено) */
        val skippedStepIds: List<String> = emptyList()
    )

    val isActive: Boolean get() = state.active
    val failedStepIds: List<String> get() = state.failedStepIds
    val skippedStepIds: List<String> get() = state.skippedStepIds

    private var state: SimpleModeState = SimpleModeState()
    private var isAccessibilityEnabled: Boolean = false
    private var isOverlayGranted: Boolean = false
    private var stepAttempt: Int = 1
    private var stepsStarted: Boolean = false
    private var autoFlowJob: Job? = null
    private val failedIds: MutableList<String> = mutableListOf()
    private val skippedIds: MutableList<String> = mutableListOf()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var restrictedLocation: RestrictedLocation = RestrictedLocation.UNKNOWN

    private val needsRestrictedUnlock: Boolean by lazy {
        permissionFlow.isSideloadedOnAndroid13Plus()
    }

    /**
     * Обновляет состояние и уведомляет ViewModel.
     * Использует DSL-паттерн для удобного обновления.
     */
    fun setState(update: SimpleModeState.() -> SimpleModeState) {
        state = state.update()
        onStateChanged(state)
    }

    /**
     * Освобождает ресурсы при уничтожении контроллера.
     */
    fun destroy() {
        autoFlowJob?.cancel()
        OverlayController.hide(context)
        scope.cancel()
    }

    /**
     * Обновляет статусы разрешений.
     * Вызывается из ViewModel при возврате из настроек.
     */
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
            permissionFlow.hideOverlay()
            advance()
        }
    }

    /**
     * Вызывается при возврате из настроек разрешений.
     */
    fun onResumeAfterPermissionReturn() {
        if (!state.active || state.phase != SimpleModePhase.PERMISSIONS) return
        AppLog.i(TAG, "onResumeAfterPermissionReturn: subPhase=${state.permissionSubPhase}")
        permissionFlow.hideOverlay()
        scope.launch {
            delay(300.milliseconds)
            advance()
        }
    }

    /**
     * Перепроверяет текущую подфазу.
     */
    fun refresh() {
        if (state.active && state.phase == SimpleModePhase.PERMISSIONS) {
            AppLog.d(TAG, "refresh() — re-checking current sub-phase")
            advance()
        }
    }

    /**
     * Запускает Simple Mode.
     */
    fun start() {
        AppLog.i(TAG, "Starting simple mode, needsRestrictedUnlock=$needsRestrictedUnlock")
        failedIds.clear()
        skippedIds.clear()
        stepAttempt = 1
        stepsStarted = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        OverlayController.hide(context)

        state = SimpleModeState(
            active = true,
            phase = SimpleModePhase.PERMISSIONS,
            permissionSubPhase = when {
                needsRestrictedUnlock -> PermissionSubPhase.RESTRICTED_SETTINGS
                else -> PermissionSubPhase.OVERLAY
            }
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
            copy(showRestrictedSettingsScreen = false, appInfoAttempts = appInfoAttempts + 1)
        }
        permissionFlow.openAppInfoSettings()
    }

    fun onRestrictedScreenDone() {
        setState {
            copy(
                showRestrictedSettingsScreen = false, restrictedSettingsShown = true,
                permissionSubPhase = PermissionSubPhase.OVERLAY
            )
        }
        advance()
    }

    fun onRestrictedScreenCancelled() = reset()

    // ═══════════════════════════════════════════════════════════════
    // Батарея
    // ═══════════════════════════════════════════════════════════════

    fun onBatteryDialogAgreed() {
        AppLog.i(TAG, "Battery dialog agreed")
        setState { copy(showBatteryDialog = false) }
        permissionFlow.openBatteryOptimizationWithPointer()
    }

    fun onBatteryDialogSkipped() {
        AppLog.i(TAG, "Battery dialog skipped — advancing to STEPS")
        goSteps()
        nextStep(autoStart = true)
    }

    fun onBatteryReturn(ignoring: Boolean) {
        AppLog.i(TAG, "onBatteryReturn: isIgnoringBatteryOptimizations=$ignoring")
        if (ignoring) {
            if (state.phase != SimpleModePhase.STEPS) {
                setState {
                    copy(
                        phase = SimpleModePhase.STEPS,
                        permissionSubPhase = PermissionSubPhase.DONE,
                        showBatteryDialog = false
                    )
                }
            }
        }
    }

    fun reshowBatteryDialog() {
        AppLog.i(TAG, "reshowBatteryDialog: user returned without disabling")
        if (state.permissionSubPhase == PermissionSubPhase.BATTERY_OPTIMIZATION) {
            setState { copy(showBatteryDialog = true) }
        }
    }

    fun continueToSteps() {
        AppLog.i(TAG, "continueToSteps: user confirmed, starting steps")
        goSteps()
        nextStep(autoStart = true)
    }

    private fun goSteps() {
        autoFlowJob?.cancel()
        setState {
            copy(
                phase = SimpleModePhase.STEPS,
                permissionSubPhase = PermissionSubPhase.DONE,
                showBatteryDialog = false
            )
        }
    }

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

    /**
     * Запускает текущий шаг.
     *
     * @param force Если true, запускает даже если шаг уже WORKING
     */
    fun startCurrentStep(force: Boolean = false) {
        val current: SimpleStepState = state.step ?: return
        if (current.status == SimpleStepState.Status.WORKING && !force) {
            AppLog.w(TAG, "startCurrentStep: already WORKING, ignoring double-tap")
            return
        }
        stepAttempt = current.attempt.coerceAtLeast(1)
        launchStep()
    }

    /**
     * Повторяет текущий шаг с первой попытки.
     */
    fun retryStep() {
        stepAttempt = 1
        launchStep()
    }

    /**
     * Запускает выполнение шага через AdbEnablerService.
     */
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
        }
        context.startService(intent)
    }

    /**
     * Обрабатывает результат выполнения шага.
     *
     * @param success true, если шаг выполнен успешно
     * @param attempt Номер попытки
     * @param finalFailure true, если это финальная неудача (все попытки исчерпаны)
     */
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
                    failedStepIds = failedIds.toList(),
                    step = step.copy(status = SimpleStepState.Status.FAILED, attempt = attempt)
                )
            }
            scheduleAdvance()
            return
        }

        setState { copy(step = step.copy(status = SimpleStepState.Status.IDLE, attempt = attempt)) }
    }

    /**
     * Обрабатывает пропуск шага (приложение не установлено).
     */
    fun onStepSkipped(stepId: String) {
        AppLog.i(TAG, "onStepSkipped: step=$stepId, index=${state.currentStepIndex}")
        if (!skippedIds.contains(stepId)) skippedIds.add(stepId)
        setState { copy(skippedStepIds = skippedIds.toList()) }
        scheduleAdvance()
    }

    /**
     * Планирует автоматическое продвижение к следующему шагу.
     * Использует AUTO_ADVANCE_DELAY_MS для задержки.
     */
    private fun scheduleAdvance() {
        autoFlowJob?.cancel()
        autoFlowJob = scope.launch {
            delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
            if (state.active) {
                AppLog.i(TAG, "auto-advance -> nextStep")
                nextStep(autoStart = true)
            } else {
                AppLog.w(TAG, "auto-advance skipped: state inactive")
            }
        }
    }

    /**
     * Переходит к следующему шагу.
     *
     * ВАЖНО: сохраняет completedCount в локальную переменную перед setState,
     * потому что вложенный SimpleStepState(...) скрывал это поле от компилятора.
     *
     * @param autoStart Если true, автоматически запускает шаг
     */
    fun nextStep(autoStart: Boolean = false) {
        if (state.phase == SimpleModePhase.DONE) return

        val steps: List<SimpleSteps.Step> = SimpleSteps.ALL
        val nextIndex: Int = if (stepsStarted) state.currentStepIndex + 1 else 0
        stepsStarted = true

        if (nextIndex >= steps.size) {
            AppLog.i(TAG, "All simple steps completed")
            val finalCompleted: Int = state.completedCount
            setState {
                copy(
                    phase = SimpleModePhase.DONE, permissionSubPhase = PermissionSubPhase.DONE,
                    step = null, done = Pair(finalCompleted, steps.size)
                )
            }
            OverlayController.showResult(
                context, finalCompleted, steps.size, failedIds.size, skippedIds.size
            )
            return
        }

        if (nextIndex == 0) {
            OverlayController.startAutomation(context, steps.size)
        }

        val nextStepObj: SimpleSteps.Step = steps[nextIndex]
        // КЛЮЧЕВОЙ ФИКС: сохраняем completedCount ПЕРЕД setState
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
        if (autoStart) startCurrentStep()
    }

    // ═══════════════════════════════════════════════════════════════
    // advance (permission-фазы)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Продвигает машину состояний к следующей подфазе.
     */
    private fun advance() {
        if (!state.active) return
        if (state.phase != SimpleModePhase.PERMISSIONS) return

        when (state.permissionSubPhase) {
            PermissionSubPhase.INACTIVE -> return

            PermissionSubPhase.RESTRICTED_SETTINGS -> {
                if (state.showRestrictedSettingsScreen) return
                setState { copy(showRestrictedSettingsScreen = true) }
            }

            PermissionSubPhase.APP_INFO -> {
                if (isAccessibilityEnabled) {
                    setState { copy(permissionSubPhase = PermissionSubPhase.OVERLAY) }
                    advance(); return
                }
                if (state.appInfoAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    setState {
                        copy(
                            showPermissionFallbackDialog = true, showAppInfoDialog = false,
                            stuckPhase = PermissionSubPhase.APP_INFO
                        )
                    }
                    return
                }
                if (state.showAppInfoDialog || state.showLocationDialog) return
                setState { copy(showAppInfoDialog = true) }
            }

            PermissionSubPhase.OVERLAY -> {
                if (isOverlayGranted) {
                    setState { copy(permissionSubPhase = PermissionSubPhase.ACCESSIBILITY) }
                    advance(); return
                }
                if (state.overlayAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    setState {
                        copy(
                            showPermissionFallbackDialog = true, showOverlayDialog = false,
                            stuckPhase = PermissionSubPhase.OVERLAY
                        )
                    }
                    return
                }
                if (state.showOverlayDialog) return
                setState { copy(showOverlayDialog = true) }
            }

            PermissionSubPhase.ACCESSIBILITY -> {
                if (isAccessibilityEnabled) {
                    AppLog.i(TAG, "Accessibility enabled — switching to BATTERY_OPTIMIZATION")
                    setState {
                        copy(
                            permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION,
                            showBatteryDialog = true
                        )
                    }
                    return
                }
                if (state.accessibilityAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    setState {
                        copy(
                            showPermissionFallbackDialog = true,
                            showAccessibilityDialog = false, showRestrictedDialog = false,
                            stuckPhase = PermissionSubPhase.ACCESSIBILITY
                        )
                    }
                    return
                }
                if (state.showAccessibilityDialog) return
                setState { copy(showAccessibilityDialog = true) }
            }

            PermissionSubPhase.BATTERY_OPTIMIZATION -> {
                if (permissionFlow.isIgnoringBatteryOptimizations()) {
                    AppLog.i(TAG, "Battery already ignored — waiting for user confirmation")
                    setState {
                        copy(
                            phase = SimpleModePhase.STEPS,
                            permissionSubPhase = PermissionSubPhase.DONE,
                            showBatteryDialog = false
                        )
                    }
                    return
                }
                return
            }

            PermissionSubPhase.DONE -> {}
        }
    }

    /**
     * Сбрасывает контроллер в начальное состояние.
     */
    private fun reset() {
        AppLog.i(TAG, "Resetting simple mode controller")
        autoFlowJob?.cancel()
        OverlayController.hide(context)
        permissionFlow.hideOverlay()
        failedIds.clear()
        skippedIds.clear()
        stepAttempt = 1
        stepsStarted = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        state = SimpleModeState()
        onStateChanged(state)
    }
}