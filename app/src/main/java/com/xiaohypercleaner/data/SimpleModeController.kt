package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.R
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.ui.SimpleModePhase
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        val currentStepIndex: Int = 0,
        val completedCount: Int = 0,
        val step: SimpleStepState? = null,
        val done: Pair<Int, Int>? = null,
        val showAccessibilityDialog: Boolean = false,
        val showOverlayDialog: Boolean = false,
        val showRestrictedDialog: Boolean = false,
        val restrictedSettingsShown: Boolean = false,
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

        if (accJustEnabled || overlayJustEnabled) {
            AppLog.i(
                TAG,
                "Permission granted (acc=$accJustEnabled, overlay=$overlayJustEnabled) — advancing"
            )
            advance()
        }
    }

    fun refresh() {
        if (state.active) {
            AppLog.d(TAG, "refresh() called while active — attempting advance")
            advance()
        }
    }

    fun start() {
        AppLog.i(TAG, "Starting simple mode")
        failedIds.clear()
        stepAttempt = 1
        state = SimpleModeState(active = true, phase = SimpleModePhase.PERMISSIONS)
        onStateChanged(state)
        advance()
    }

    fun onDialogAgreed() {
        AppLog.i(TAG, "Dialog agreed")

        if (state.showAccessibilityDialog) {
            setState {
                copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false
                )
            }
            ChainFlags.waitingAccessibilityReturn = true
            permissionFlow.openAccessibilityWithHint()
            return
        }

        if (state.showOverlayDialog) {
            setState {
                copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false
                )
            }
            permissionFlow.openOverlaySettings()
            showHint(context.getString(R.string.hint_overlay))
            return
        }
    }

    fun onDialogCancelled() {
        AppLog.i(TAG, "Dialog cancelled — resetting simple mode")
        reset()
    }

    fun onRestrictedDialogAgreed() {
        AppLog.i(TAG, "Restricted dialog agreed — opening app info")
        setState {
            copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                restrictedSettingsShown = true
            )
        }
        openAppInfoWithHint()
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
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS)
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
                delay(AppConstants.RETRY_DELAY_MS)
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
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS)
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

        when (state.phase) {
            SimpleModePhase.PERMISSIONS -> checkPermissionsAndAdvance()
            SimpleModePhase.STEPS -> {
                if (state.step == null && autoFlowJob?.isActive != true) {
                    nextStep(autoStart = true)
                }
            }

            SimpleModePhase.DONE -> {}
            SimpleModePhase.INACTIVE -> {}
        }
    }

    private fun checkPermissionsAndAdvance() {
        if (!isAccessibilityEnabled) {
            AppLog.d(TAG, "Accessibility not enabled — showing dialog")
            setState { copy(showAccessibilityDialog = true) }
            return
        }

        if (!isOverlayGranted) {
            AppLog.d(TAG, "Overlay not granted — showing dialog")
            setState { copy(showOverlayDialog = true) }
            return
        }

        AppLog.i(TAG, "All permissions granted — switching to STEPS phase")
        setState { copy(phase = SimpleModePhase.STEPS) }
        nextStep(autoStart = true)
    }

    private fun reset() {
        AppLog.i(TAG, "Resetting simple mode controller")
        autoFlowJob?.cancel()
        failedIds.clear()
        stepAttempt = 1
        state = SimpleModeState()
        onStateChanged(state)
    }

    private fun openAppInfoWithHint() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            showHint(context.getString(R.string.hint_restricted))
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open app info", e)
        }
    }

    private fun showHint(text: String) {
        AppLog.d(TAG, "Hint requested: $text")
    }
}