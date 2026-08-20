package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.SystemAutomationService
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
        val showPermissionFallbackDialog: Boolean = false,
        val stuckPhase: PermissionSubPhase? = null,
        val showRestrictedSettingsScreen: Boolean = false,
        val restrictedSettingsShown: Boolean = false,
        val accessibilityAttempts: Int = 0,
        val overlayAttempts: Int = 0,
        val appInfoAttempts: Int = 0,
        val showTestClickFailedDialog: Boolean = false,
        val showBatteryDialog: Boolean = false,
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
    private var testActivityLaunched = false

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
        testActivityLaunched = false
        restrictedLocation = RestrictedLocation.UNKNOWN
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

    fun onDialogCancelled() {
        AppLog.i(TAG, "Dialog cancelled — resetting simple mode")
        reset()
    }

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

    fun onRestrictedDialogCancelled() {
        reset()
    }

    fun onRestrictedScreenOpenSettings() {
        AppLog.i(TAG, "Restricted screen: open settings clicked")
        setState { copy(showRestrictedSettingsScreen = false) }
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

    fun onRestrictedScreenCancelled() {
        AppLog.i(TAG, "Restricted screen cancelled — resetting")
        reset()
    }

    fun onTestClickSuccess() {
        AppLog.i(TAG, "TEST_CLICK success — advancing to BATTERY_OPTIMIZATION")
        testActivityLaunched = false
        setState { copy(permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION) }
        advance()
    }

    fun onTestClickFailed() {
        AppLog.w(TAG, "TEST_CLICK failed — showing dialog")
        testActivityLaunched = false
        setState {
            copy(
                showTestClickFailedDialog = true,
                permissionSubPhase = PermissionSubPhase.TEST_CLICK
            )
        }
    }

    fun onTestClickRetry() {
        AppLog.i(TAG, "TEST_CLICK retry")
        setState { copy(showTestClickFailedDialog = false) }
        launchTestActivity()
    }

    fun onTestClickSkip() {
        AppLog.i(TAG, "TEST_CLICK skipped — advancing to BATTERY_OPTIMIZATION")
        testActivityLaunched = false
        setState {
            copy(
                showTestClickFailedDialog = false,
                permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION
            )
        }
        advance()
    }

    fun onBatteryDialogAgreed() {
        AppLog.i(TAG, "Battery dialog agreed")
        setState { copy(showBatteryDialog = false) }
        permissionFlow.openBatteryOptimizationWithPointer()
    }

    fun onBatteryDialogSkipped() {
        AppLog.i(TAG, "Battery dialog skipped — advancing to STEPS")
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
            setState {
                copy(
                    phase = SimpleModePhase.STEPS,
                    permissionSubPhase = PermissionSubPhase.DONE
                )
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

    fun onFallbackCancelled() {
        AppLog.i(TAG, "Fallback cancelled — resetting simple mode")
        reset()
    }

    // ═══════════════════════════════════════════════════════════════
    // ИСПРАВЛЕНО: добавлен параметр force для обхода защиты от двойного тапа.
    // Авто-ретрай (из MainViewModel) использует force=true.
    // Пользовательский тап использует force=false (по умолчанию).
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
            copy(
                step = step?.copy(
                    status = SimpleStepState.Status.WORKING,
                    attempt = stepAttempt
                )
            )
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

        // ═══════════════════════════════════════════════════════════════
        // ИСПРАВЛЕНО: убран дублирующий ретрай внутри SimpleModeController.
        // Теперь ретрай планирует ТОЛЬКО MainViewModel через startCurrentStep(force=true).
        // Это убирает race condition и двойные запуски.
        // ═══════════════════════════════════════════════════════════════
        if (stepAttempt < step.maxAttempts) {
            AppLog.w(TAG, "onStepResult: will auto-retry (handled by MainViewModel)")
            setState {
                copy(step = step.copy(status = SimpleStepState.Status.IDLE, attempt = stepAttempt))
            }
            // НЕ планируем ретрай здесь — это делает MainViewModel.onSimpleStepResult
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
                    AppLog.i(TAG, "Accessibility enabled — switching to TEST_CLICK")
                    setState { copy(permissionSubPhase = PermissionSubPhase.TEST_CLICK) }
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

            PermissionSubPhase.TEST_CLICK -> {
                if (!isSystemAutomationServiceEnabled()) {
                    AppLog.w(TAG, "TEST_CLICK: SystemAutomationService not enabled, skipping")
                    testActivityLaunched = false
                    setState { copy(permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION) }
                    advance()
                    return
                }
                if (testActivityLaunched) {
                    AppLog.d(TAG, "TEST_CLICK: TestActivity already launched, waiting for result")
                    return
                }
                testActivityLaunched = true
                launchTestActivity()
            }

            PermissionSubPhase.BATTERY_OPTIMIZATION -> {
                if (permissionFlow.isIgnoringBatteryOptimizations()) {
                    AppLog.i(TAG, "Battery optimization already ignored — switching to STEPS")
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

    private fun isSystemAutomationServiceEnabled(): Boolean {
        return try {
            val component =
                ComponentName(context, SystemAutomationService::class.java).flattenToString()
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(component) == true
        } catch (e: Exception) {
            AppLog.w(TAG, "isSystemAutomationServiceEnabled failed: ${e.message}")
            false
        }
    }

    private fun launchTestActivity() {
        AppLog.i(TAG, "Launching TestActivity for TEST_CLICK verification")
        try {
            val intent = Intent(context, com.xiaohypercleaner.ui.TestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to launch TestActivity: ${e.message}")
            testActivityLaunched = false
            setState { copy(permissionSubPhase = PermissionSubPhase.BATTERY_OPTIMIZATION) }
            advance()
        }
    }

    private fun reset() {
        AppLog.i(TAG, "Resetting simple mode controller")
        autoFlowJob?.cancel()
        permissionFlow.hideOverlay()
        failedIds.clear()
        stepAttempt = 1
        testActivityLaunched = false
        restrictedLocation = RestrictedLocation.UNKNOWN
        state = SimpleModeState()
        onStateChanged(state)
    }
}