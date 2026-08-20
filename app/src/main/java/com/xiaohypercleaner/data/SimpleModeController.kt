package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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
        val restrictedSettingsShown: Boolean = false,
        val accessibilityAttempts: Int = 0,
        val overlayAttempts: Int = 0,
        val appInfoAttempts: Int = 0,
        val failedStepIds: List<String> = emptyList()
    )

    val isActive: Boolean get() = state.active
    val failedStepIds: List<String> get() = state.failedStepIds

    private var state = SimpleModeState()
    private var isAccessibilityEnabled = false
    private var isOverlayGranted = false
    private var stepAttempt = 1
    private var autoFlowJob: Job? = null
    private val failedIds = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var restrictedLocation: RestrictedLocation = RestrictedLocation.UNKNOWN

    // Проверку sideload делегируем PermissionFlowManager —
    // единая реализация без дублирования и мёртвых веток SDK_INT
    private val needsRestrictedUnlock: Boolean by lazy {
        permissionFlow.isSideloadedOnAndroid13Plus()
    }

    fun setState(update: SimpleModeState.() -> SimpleModeState) {
        state = state.update()
        onStateChanged(state)
    }

    fun destroy() {
        autoFlowJob?.cancel()
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
        stepAttempt = 1
        restrictedLocation = RestrictedLocation.UNKNOWN
        state = SimpleModeState(
            active = true,
            phase = SimpleModePhase.PERMISSIONS,
            permissionSubPhase = if (needsRestrictedUnlock) {
                PermissionSubPhase.APP_INFO
            } else {
                PermissionSubPhase.OVERLAY
            }
        )
        onStateChanged(state)
        advance()
    }

    fun onAppInfoDialogAgreed() {
        AppLog.i(TAG, "AppInfo dialog agreed, location=$restrictedLocation")
        setState {
            copy(
                showAppInfoDialog = false,
                appInfoAttempts = appInfoAttempts + 1
            )
        }
        ChainFlags.waitingAccessibilityReturn = true
        permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
    }

    fun onAppInfoDialogCancelled() {
        AppLog.i(TAG, "AppInfo dialog cancelled — resetting")
        reset()
    }

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
            AppLog.i(TAG, "User says no restricted item — skipping APP_INFO phase")
            setState { copy(permissionSubPhase = PermissionSubPhase.OVERLAY) }
            advance()
        } else {
            permissionFlow.openAppInfoWithSmartPointer(location)
        }
    }

    fun onLocationDialogCancelled() {
        AppLog.i(TAG, "Location dialog cancelled — resetting")
        reset()
    }

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
                setState {
                    copy(
                        showOverlayDialog = false,
                        overlayAttempts = overlayAttempts + 1
                    )
                }
                permissionFlow.openOverlayWithPointer()
            }

            else -> {
                setState {
                    copy(
                        showAccessibilityDialog = false,
                        showOverlayDialog = false,
                        showRestrictedDialog = false,
                        showAppInfoDialog = false
                    )
                }
            }
        }
    }

    fun onDialogCancelled() {
        AppLog.i(TAG, "Dialog cancelled — resetting simple mode")
        reset()
    }

    fun onRestrictedDialogAgreed() {
        AppLog.i(TAG, "Restricted dialog agreed — going back to APP_INFO")
        setState {
            copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                permissionSubPhase = PermissionSubPhase.APP_INFO,
                restrictedSettingsShown = true,
                accessibilityAttempts = 0
            )
        }
        permissionFlow.openAppInfoWithSmartPointer(restrictedLocation)
    }

    fun onRestrictedDialogCancelled() {
        AppLog.i(TAG, "Restricted dialog cancelled — resetting simple mode")
        reset()
    }

    fun startCurrentStep() {
        val current = state.step ?: return
        if (current.status == SimpleStepState.Status.WORKING) {
            AppLog.w(TAG, "startCurrentStep: already WORKING, ignoring double-tap")
            return
        }
        stepAttempt = 1
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

    fun onStepResult(success: Boolean) {
        AppLog.i(
            TAG,
            "onStepResult: success=$success, attempt=$stepAttempt, step=${state.currentStepIndex}"
        )
        val step = state.step ?: return

        if (success) {
            val newCount = state.completedCount + 1
            setState {
                copy(
                    completedCount = newCount,
                    step = step.copy(
                        status = SimpleStepState.Status.SUCCESS,
                        completedCount = newCount
                    )
                )
            }
            autoFlowJob = scope.launch {
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
                nextStep(autoStart = true)
            }
            return
        }

        if (stepAttempt < step.maxAttempts) {
            stepAttempt++
            AppLog.w(TAG, "onStepResult: auto-retry $stepAttempt/${step.maxAttempts}")
            setState {
                copy(
                    step = step.copy(
                        status = SimpleStepState.Status.WORKING,
                        attempt = stepAttempt
                    )
                )
            }
            autoFlowJob = scope.launch {
                delay(AppConstants.RETRY_DELAY_MS.milliseconds)
                launchStep()
            }
        } else {
            AppLog.e(TAG, "onStepResult: all attempts exhausted for step ${state.currentStepIndex}")
            failedIds.add(step.step.id)
            setState {
                copy(
                    failedStepIds = failedIds.toList(),
                    step = step.copy(status = SimpleStepState.Status.FAILED)
                )
            }
            autoFlowJob = scope.launch {
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS.milliseconds)
                nextStep(autoStart = true)
            }
        }
    }

    fun nextStep(autoStart: Boolean = false) {
        autoFlowJob?.cancel()

        val nextIndex = state.currentStepIndex + 1
        val steps = SimpleSteps.ALL

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
            return
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

        if (autoStart) {
            startCurrentStep()
        }
    }

    private fun advance() {
        if (!state.active) return
        if (state.phase != SimpleModePhase.PERMISSIONS) return

        when (state.permissionSubPhase) {
            PermissionSubPhase.INACTIVE -> return

            PermissionSubPhase.APP_INFO -> {
                if (isAccessibilityEnabled) {
                    AppLog.i(
                        TAG,
                        "Accessibility already enabled after APP_INFO — jumping to OVERLAY"
                    )
                    setState { copy(permissionSubPhase = PermissionSubPhase.OVERLAY) }
                    advance()
                    return
                }

                if (state.appInfoAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    AppLog.w(TAG, "APP_INFO attempts exhausted — asking for location")
                    setState {
                        copy(
                            showLocationDialog = true,
                            showAppInfoDialog = false
                        )
                    }
                    return
                }

                if (state.showAppInfoDialog || state.showLocationDialog) return
                AppLog.d(
                    TAG,
                    "APP_INFO phase — showing dialog (attempt ${state.appInfoAttempts + 1})"
                )
                setState { copy(showAppInfoDialog = true) }
            }

            PermissionSubPhase.OVERLAY -> {
                if (isOverlayGranted) {
                    AppLog.d(TAG, "Overlay already granted — skipping to ACCESSIBILITY")
                    setState { copy(permissionSubPhase = PermissionSubPhase.ACCESSIBILITY) }
                    advance()
                    return
                }
                if (state.showOverlayDialog) return
                AppLog.d(TAG, "OVERLAY phase — showing dialog")
                setState { copy(showOverlayDialog = true) }
            }

            PermissionSubPhase.ACCESSIBILITY -> {
                if (isAccessibilityEnabled) {
                    AppLog.i(TAG, "All permissions granted — switching to STEPS phase")
                    setState {
                        copy(
                            phase = SimpleModePhase.STEPS,
                            permissionSubPhase = PermissionSubPhase.DONE
                        )
                    }
                    nextStep(autoStart = true)
                    return
                }
                if (state.accessibilityAttempts >= AppConstants.MAX_ACCESSIBILITY_ATTEMPTS) {
                    AppLog.w(
                        TAG,
                        "Accessibility stuck after ${AppConstants.MAX_ACCESSIBILITY_ATTEMPTS} attempts — showing restricted dialog"
                    )
                    setState {
                        copy(
                            showRestrictedDialog = true,
                            showAccessibilityDialog = false
                        )
                    }
                    return
                }
                if (state.showAccessibilityDialog) return
                AppLog.d(
                    TAG,
                    "ACCESSIBILITY phase — showing dialog (attempt ${state.accessibilityAttempts + 1})"
                )
                setState { copy(showAccessibilityDialog = true) }
            }

            PermissionSubPhase.DONE -> {}
        }
    }

    private fun reset() {
        AppLog.i(TAG, "Resetting simple mode controller")
        autoFlowJob?.cancel()
        permissionFlow.hideOverlay()
        failedIds.clear()
        stepAttempt = 1
        restrictedLocation = RestrictedLocation.UNKNOWN
        state = SimpleModeState()
        onStateChanged(state)
    }
}