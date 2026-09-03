package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
 * ИСПРАВЛЕНИЯ (beta11):
 * - Защита от повтора диалога батареи (MIUI кэширует isIgnoringBatteryOptimizations)
 * - Освобождение Wake Lock при отмене и завершении оптимизации
 */
class SimpleModeController(
    private val context: Context,
    private val permissionFlow: PermissionFlowManager,
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
        val skippedStepIds: List<String> = emptyList()
    )

    val isActive: Boolean get() = state.active
    val failedStepIds: List<String> get() = state.failedStepIds
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
    private var isAccessibilityEnabled: Boolean = checkAccessibility()
    private var isOverlayGranted: Boolean = checkOverlay()
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

    fun setState(update: SimpleModeState.() -> SimpleModeState) {
        state = state.update()
        onStateChanged(state)
    }

    fun destroy() {
        if (state.active && state.phase == SimpleModePhase.STEPS) {
            AppLog.i(TAG, "destroy() skipped while STEPS are actively running")
            return
        }
        autoFlowJob?.cancel()
        OverlayController.hide(context)
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
            permissionFlow.hideOverlay()
            advance()
        }
    }

    fun onResumeAfterPermissionReturn() {
        if (!state.active || state.phase != SimpleModePhase.PERMISSIONS) return
        AppLog.i(TAG, "onResumeAfterPermissionReturn: subPhase=${state.permissionSubPhase}")
        permissionFlow.hideOverlay()
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
        skippedIds.clear()
        stepAttempt = 1
        stepsStarted = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        batteryDialogAlreadyShown = false  // НОВОЕ (beta11): сброс флага
        OverlayController.hide(context)

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
        goSteps()
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
                setState {
                    copy(
                        phase = SimpleModePhase.STEPS,
                        permissionSubPhase = PermissionSubPhase.DONE,
                        showBatteryDialog = false
                    )
                }
                goSteps()
                nextStep(autoStart = true)
            }
        } else if (batteryDialogAlreadyShown) {
            // ИСПРАВЛЕНО (beta11): если уже показывали диалог и пользователь вернулся
            // БЕЗ включения — НЕ показываем повторно, сразу идём к STEPS.
            // Это решает проблему MIUI, где isIgnoring всегда false.
            AppLog.i(TAG, "onBatteryReturn: already shown dialog, advancing to STEPS")
            goSteps()
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
            goSteps()
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
        goSteps()
        nextStep(autoStart = true)
    }

    private fun goSteps() {
        autoFlowJob?.cancel()
        // Не возобновлять Simple Mode после смерти процесса посреди шагов
        scope.launch {
            runCatching {
                com.xiaohypercleaner.XiaoHyperApp.instance.preferencesManager
                    .setPendingSimpleMode(false)
            }
        }
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
                    failedStepIds = failedIds.toList(),
                    step = step.copy(status = SimpleStepState.Status.FAILED, attempt = attempt)
                )
            }
            scheduleAdvance()
            return
        }

        setState { copy(step = step.copy(status = SimpleStepState.Status.IDLE, attempt = attempt)) }
    }

    fun onStepSkipped(stepId: String) {
        AppLog.i(TAG, "onStepSkipped: step=$stepId, index=${state.currentStepIndex}")
        if (!skippedIds.contains(stepId)) skippedIds.add(stepId)
        setState { copy(skippedStepIds = skippedIds.toList()) }
        scheduleAdvance()
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

        val steps: List<SimpleSteps.Step> = SimpleSteps.ALL
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
            OverlayController.showResult(
                context, finalCompleted, applicable, failedIds.size, skippedIds.size
            )
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
                goSteps()
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
        OverlayController.hide(context)
        releaseWakeLock()  // НОВОЕ (beta11): освобождаем wake lock
        permissionFlow.hideOverlay()
        failedIds.clear()
        skippedIds.clear()
        stepAttempt = 1
        stepsStarted = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        batteryDialogAlreadyShown = false  // НОВОЕ (beta11): сброс флага
        state = SimpleModeState()
        onStateChanged(state)
    }
}