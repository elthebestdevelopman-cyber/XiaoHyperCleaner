package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.R
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
 * Машина состояний Simple Mode.
 *
 * Цепочка фаз (TEST_CLICK УДАЛЁН — TestActivity больше не используется):
 *   RESTRICTED_SETTINGS (если sideload) → OVERLAY → ACCESSIBILITY →
 *   BATTERY_OPTIMIZATION → STEPS → DONE
 *
 * ИСПРАВЛЕНО в этой версии:
 *  1. Флаг stepsStarted: первый вход в STEPS начинает с index=0 (шаг msa не теряется)
 *  2. onStepSkipped() — пропуск шага (приложение не установлено) без ретраев
 *  3. skippedIds — список пропущенных шагов для финального экрана
 *  4. OverlayController.startAutomation/showResult/hide — оверлей с робокотом
 *  5. Убрана self-cancellation в nextStep() — отмена теперь на стороне вызывающего
 *  6. onStepResult явно отменяет autoFlowJob перед запуском нового
 *  7. startCurrentStep защищён от двойного запуска через проверку WORKING
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
        val skippedStepIds: List<String> = emptyList()  // НОВОЕ
    )

    val isActive: Boolean get() = state.active
    val failedStepIds: List<String> get() = state.failedStepIds
    val skippedStepIds: List<String> get() = state.skippedStepIds  // НОВОЕ

    private var state = SimpleModeState()
    private var isAccessibilityEnabled = false
    private var isOverlayGranted = false
    private var stepAttempt = 1
    private var stepsStarted = false  // НОВОЕ: первый вход в STEPS начинает с index=0
    private var autoFlowJob: Job? = null
    private val failedIds = mutableListOf<String>()
    private val skippedIds = mutableListOf<String>()  // НОВОЕ
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var restrictedLocation: RestrictedLocation = RestrictedLocation.UNKNOWN

    private val needsRestrictedUnlock: Boolean by lazy {
        permissionFlow.isSideloadedOnAndroid13Plus()
    }

    fun setState(update: SimpleModeState.() -> SimpleModeState) {
        state = state.update()
        onStateChanged(state)
    }

    fun destroy() {
        autoFlowJob?.cancel()
        OverlayController.hide(context)
        scope.cancel()
    }

    fun updatePermissionStatuses(accEnabled: Boolean, overlayGranted: Boolean) {
        val accJustEnabled = !isAccessibilityEnabled && accEnabled
        val overlayJustEnabled = !isOverlayGranted && overlayGranted

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
        failedIds.clear()
        skippedIds.clear()
        stepAttempt = 1
        stepsStarted = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        OverlayController.hide(context)  // Чистим оверлей на старте
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

    fun onAppInfoDialogAgreed() {
        AppLog.i(TAG, "AppInfo dialog agreed, location=$restrictedLocation")
        setState { copy(showAppInfoDialog = false, appInfoAttempts = appInfoAttempts + 1) }
        ChainFlags.waitingAccessibilityReturn = true
        permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
    }

    fun onAppInfoDialogCancelled() = reset()

    fun onAppInfoReturnWithoutSuccess() {
        if (state.appInfoAttempts >= 1 && restrictedLocation == RestrictedLocation.UNKNOWN) {
            AppLog.i(TAG, "User returned without success — asking for location")
            setState { copy(showLocationDialog = true) }
        }
    }

    fun onLocationChosen(location: RestrictedLocation) {
        AppLog.i(TAG, "Location chosen: $location")
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
        AppLog.i(TAG, "Dialog agreed, subPhase=${state.permissionSubPhase}")
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

            else -> {
                setState {
                    copy(
                        showAccessibilityDialog = false, showOverlayDialog = false,
                        showRestrictedDialog = false, showAppInfoDialog = false
                    )
                }
            }
        }
    }

    fun onDialogCancelled() = reset()

    fun onRestrictedDialogAgreed() {
        AppLog.i(TAG, "Restricted dialog agreed — going back to APP_INFO")
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
        AppLog.i(
            TAG,
            "Restricted screen: open settings clicked, attempt=${state.appInfoAttempts + 1}"
        )
        setState {
            copy(
                showRestrictedSettingsScreen = false,
                appInfoAttempts = appInfoAttempts + 1   // НОВОЕ: считаем попытки для адаптивной подсказки
            )
        }
        permissionFlow.openAppInfoSettings()
    }

    fun onRestrictedScreenDone() {
        AppLog.i(TAG, "Restricted screen: done clicked, advancing to OVERLAY")
        setState {
            copy(
                showRestrictedSettingsScreen = false,
                restrictedSettingsShown = true,
                permissionSubPhase = PermissionSubPhase.OVERLAY
            )
        }
        advance()
    }

    fun onRestrictedScreenCancelled() = reset()

    fun onBatteryDialogAgreed() {
        AppLog.i(TAG, "Battery dialog agreed")
        setState { copy(showBatteryDialog = false) }
        permissionFlow.openBatteryOptimizationWithPointer()
    }

    fun onBatteryDialogSkipped() {
        AppLog.i(TAG, "Battery dialog skipped — advancing to STEPS")
        autoFlowJob?.cancel()
        setState {
            copy(
                showBatteryDialog = false,
                phase = SimpleModePhase.STEPS,
                permissionSubPhase = PermissionSubPhase.DONE
            )
        }
        nextStep(autoStart = true)
    }

    fun onBatteryReturn() {
        val ignoring = permissionFlow.isIgnoringBatteryOptimizations()
        AppLog.i(TAG, "onBatteryReturn: isIgnoringBatteryOptimizations=$ignoring")
        if (ignoring) {
            autoFlowJob?.cancel()
            setState {
                copy(phase = SimpleModePhase.STEPS, permissionSubPhase = PermissionSubPhase.DONE)
            }
            nextStep(autoStart = true)
        } else {
            setState { copy(showBatteryDialog = true) }
        }
    }

    fun onFallbackRetry() {
        AppLog.i(TAG, "Fallback retry for phase ${state.stuckPhase}")
        val phaseToRetry = state.stuckPhase
        setState {
            copy(
                showPermissionFallbackDialog = false,
                stuckPhase = null,
                accessibilityAttempts = if (phaseToRetry == PermissionSubPhase.ACCESSIBILITY) 0 else accessibilityAttempts,
                overlayAttempts = if (phaseToRetry == PermissionSubPhase.OVERLAY) 0 else overlayAttempts,
                appInfoAttempts = if (phaseToRetry == PermissionSubPhase.APP_INFO) 0 else appInfoAttempts
            )
        }
        advance()
    }

    fun onFallbackOpenSettings() {
        AppLog.i(TAG, "Fallback open settings for phase ${state.stuckPhase}")
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
        val current = state.step ?: return
        if (current.status == SimpleStepState.Status.WORKING && !force) {
            AppLog.w(TAG, "startCurrentStep: already WORKING, ignoring double-tap")
            return
        }
        stepAttempt = current.attempt.coerceAtLeast(1)
        launchStep()
    }

    fun retryStep() {
        AppLog.i(TAG, "retryStep: manual retry by user")
        stepAttempt = 1
        launchStep()
    }

    private fun launchStep() {
        autoFlowJob?.cancel()
        setState {
            copy(step = step?.copy(status = SimpleStepState.Status.WORKING, attempt = stepAttempt))
        }
        val intent = Intent(context, AdbEnablerService::class.java).apply {
            action = AdbEnablerService.ACTION_SIMPLE_STEP
            putExtra("step_index", state.currentStepIndex)
        }
        context.startService(intent)
    }

    fun onStepResult(success: Boolean, attempt: Int, finalFailure: Boolean = false) {
        AppLog.i(
            TAG,
            "onStepResult: success=$success, attempt=$attempt, step=${state.currentStepIndex}"
        )
        val step = state.step ?: return

        if (!state.active) {
            AppLog.w(TAG, "onStepResult: controller is inactive, ignoring")
            return
        }

        if (success) {
            val newCount = state.completedCount + 1
            setState {
                copy(
                    completedCount = newCount,
                    step = step.copy(
                        status = SimpleStepState.Status.SUCCESS,
                        completedCount = newCount,
                        attempt = attempt
                    )
                )
            }
            autoFlowJob?.cancel()
            autoFlowJob = scope.launch {
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
                if (state.active) nextStep(autoStart = true)
            }
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
            autoFlowJob?.cancel()
            autoFlowJob = scope.launch {
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
                if (state.active) nextStep(autoStart = true)
            }
            return
        }

        AppLog.w(TAG, "onStepResult: intermediate failure, ViewModel will retry")
        setState { copy(step = step.copy(status = SimpleStepState.Status.IDLE, attempt = attempt)) }
    }

    /**
     * НОВОЕ: шаг пропущен (приложение не установлено).
     * Не считается ошибкой — просто идём к следующему шагу без ретраев.
     */
    fun onStepSkipped(stepId: String) {
        AppLog.i(TAG, "onStepSkipped: step=$stepId, index=${state.currentStepIndex}")
        if (!skippedIds.contains(stepId)) skippedIds.add(stepId)
        setState { copy(skippedStepIds = skippedIds.toList()) }
        autoFlowJob?.cancel()
        autoFlowJob = scope.launch {
            delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
            if (state.active) nextStep(autoStart = true)
        }
    }

    /**
     * ИСПРАВЛЕНО:
     *  1. При первом входе в STEPS (stepsStarted=false) начинаем с index=0,
     *     иначе шаг msa (index=0) молча пропускался.
     *  2. При первом запуске показываем оверлей автоматизации (робокот).
     *  3. При достижении конца — показываем финальный оверлей с результатами.
     */
    fun nextStep(autoStart: Boolean = false) {
        val steps = SimpleSteps.ALL

        // НОВОЕ: первый вход начинает с index=0, последующие — с +1
        val nextIndex = if (stepsStarted) state.currentStepIndex + 1 else 0
        stepsStarted = true

        if (nextIndex >= steps.size) {
            AppLog.i(TAG, "All simple steps completed")
            setState {
                copy(
                    phase = SimpleModePhase.DONE,
                    permissionSubPhase = PermissionSubPhase.DONE,
                    step = null,
                    done = Pair(completedCount, steps.size)
                )
            }
            // НОВОЕ: показываем финальный оверлей с довольным котом
            OverlayController.showResult(
                context,
                completed = state.completedCount,   // ✅
                total = steps.size,
                failed = failedIds.size,
                skipped = skippedIds.size
            )
            return
        }

        // НОВОЕ: при переходе к первому шагу показываем оверлей автоматизации
        if (nextIndex == 0) {
            OverlayController.startAutomation(context, steps.size)
        }

        val nextStepObj = steps[nextIndex]
        setState {
            copy(
                currentStepIndex = nextIndex,
                step = SimpleStepState(
                    step = nextStepObj,
                    status = SimpleStepState.Status.IDLE,
                    attempt = 1,
                    completedCount = completedCount,
                    stepIndex = nextIndex,
                    totalSteps = steps.size
                )
            )
        }
        if (autoStart) startCurrentStep()
    }

    private fun advance() {
        if (!state.active) return
        if (state.phase != SimpleModePhase.PERMISSIONS) return

        when (state.permissionSubPhase) {
            PermissionSubPhase.INACTIVE -> return

            PermissionSubPhase.RESTRICTED_SETTINGS -> {
                if (state.showRestrictedSettingsScreen) return
                AppLog.d(TAG, "RESTRICTED_SETTINGS phase — showing instruction screen")
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
                    setState { copy(permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION) }
                    advance()
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
                    AppLog.i(TAG, "Battery optimization already ignored — switching to STEPS")
                    autoFlowJob?.cancel()
                    setState {
                        copy(
                            phase = SimpleModePhase.STEPS,
                            permissionSubPhase = PermissionSubPhase.DONE
                        )
                    }
                    nextStep(autoStart = true)
                    return
                }
                if (state.showBatteryDialog) return
                AppLog.d(TAG, "BATTERY_OPTIMIZATION phase — showing dialog")
                setState { copy(showBatteryDialog = true) }
            }

            PermissionSubPhase.DONE -> {}
        }
    }

    private fun reset() {
        AppLog.i(TAG, "Resetting simple mode controller")
        autoFlowJob?.cancel()
        OverlayController.hide(context)  // НОВОЕ: убираем оверлей при сбросе
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