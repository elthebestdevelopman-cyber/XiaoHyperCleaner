package com.xiaohypercleaner.ui.managers

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.SimpleModeController
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SimpleModeManager(
    private val state: MutableStateFlow<MainUiState>,
    private val simpleController: SimpleModeController,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SimpleModeMgr"
    }

    private var simpleModeActive = false
    private var stepAttempt = 1
    private var autoFlowJob: Job? = null
    private val failedStepIds = mutableListOf<String>()

    fun isActive(): Boolean = simpleModeActive

    fun start() {
        AppLog.i(TAG, "start")
        simpleModeActive = true
        failedStepIds.clear()
        stepAttempt = 1
        simpleController.start()
    }

    fun onStepResult(success: Boolean) {
        AppLog.i(TAG, "onStepResult: success=$success, attempt=$stepAttempt")
        val step = state.value.simpleStep ?: return

        if (success) {
            simpleController.onStepResult(true)
            autoFlowJob = scope.launch {
                delay(AppConstants.AUTO_ADVANCE_DELAY_MS)
                advanceToNextStep(autoStart = true)
            }
            return
        }

        if (stepAttempt < step.maxAttempts) {
            stepAttempt++
            AppLog.w(TAG, "onStepResult: auto-retry $stepAttempt/${step.maxAttempts}")
            autoFlowJob?.cancel()
            autoFlowJob = scope.launch {
                delay(AppConstants.RETRY_DELAY_MS)
                simpleController.startCurrentStep()
            }
        } else {
            AppLog.e(TAG, "onStepResult: all attempts exhausted")
            failedStepIds.add(step.step.id)
            simpleController.onStepResult(false)
        }
    }

    private fun advanceToNextStep(autoStart: Boolean) {
        simpleController.nextStep()
        if (autoStart && state.value.simpleStep != null) {
            stepAttempt = 1
            simpleController.startCurrentStep()
        }
    }

    fun close() {
        AppLog.i(TAG, "close")
        autoFlowJob?.cancel()
        simpleModeActive = false

        state.update { current ->
            current.copy(
                simpleStep = null,
                simpleDone = null,
                simpleModePhase = SimpleModePhase.INACTIVE,
                simpleModeActive = false,
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                showAppInfoDialog = false,
                showLocationDialog = false
            )
        }
    }

    fun mergeState(s: SimpleModeController.SimpleModeState) {
        val running =
            s.active || s.step != null || s.done != null || s.phase != SimpleModePhase.INACTIVE

        state.update { current ->
            current.copy(
                simpleModePhase = s.phase,
                permissionSubPhase = s.permissionSubPhase,
                simpleStep = s.step,
                simpleDone = s.done,
                simpleModeActive = if (!running) false else current.simpleModeActive,
                showAppInfoDialog = if (running) s.showAppInfoDialog else false,
                showOverlayDialog = if (running) s.showOverlayDialog else false,
                showAccessibilityDialog = if (running) s.showAccessibilityDialog else false,
                showRestrictedDialog = if (running) s.showRestrictedDialog else false,
                showLocationDialog = if (running) s.showLocationDialog else false,
                restrictedSettingsShown = if (running) s.restrictedSettingsShown else false,
                accessibilityAttempts = if (running) s.accessibilityAttempts else 0,
                overlayAttempts = if (running) s.overlayAttempts else 0,
                appInfoAttempts = if (running) s.appInfoAttempts else 0
            )
        }

        if (!running && simpleModeActive) {
            simpleModeActive = false
        }
    }

    fun refresh() {
        if (simpleModeActive) {
            simpleController.refresh()
        }
    }

    fun onResumeAfterPermissionReturn() {
        if (simpleModeActive) {
            simpleController.onResumeAfterPermissionReturn()
        }
    }

    fun updatePermissionStatuses(accEnabled: Boolean, overlayGranted: Boolean) {
        simpleController.updatePermissionStatuses(accEnabled, overlayGranted)
    }

    fun onDialogAgreed() {
        simpleController.onDialogAgreed()
    }

    fun onDialogCancelled() {
        simpleModeActive = false
        simpleController.onDialogCancelled()
    }

    fun onRestrictedDialogAgreed() {
        simpleController.onRestrictedDialogAgreed()
    }

    fun onRestrictedDialogCancelled() {
        simpleModeActive = false
        simpleController.onRestrictedDialogCancelled()
    }

    fun onAppInfoDialogAgreed() {
        simpleController.onAppInfoDialogAgreed()
    }

    fun onAppInfoDialogCancelled() {
        simpleModeActive = false
        simpleController.onAppInfoDialogCancelled()
    }

    fun onLocationChosen(location: RestrictedLocation) {
        simpleController.onLocationChosen(location)
    }

    fun onLocationDialogCancelled() {
        simpleModeActive = false
        simpleController.onLocationDialogCancelled()
    }

    fun markStepFailed(stepId: String) {
        if (!failedStepIds.contains(stepId)) {
            failedStepIds.add(stepId)
        }
    }

    fun getFailedStepIds(): List<String> = failedStepIds.toList()

    fun destroy() {
        autoFlowJob?.cancel()
        simpleController.destroy()
    }
}