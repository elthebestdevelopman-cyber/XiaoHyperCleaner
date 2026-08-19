package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.AppConstants.AUTO_ADVANCE_DELAY_MS
import com.xiaohypercleaner.AppConstants.RETRY_DELAY_MS
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PermissionFlowManager
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.ShizukuWizardManager
import com.xiaohypercleaner.data.SimpleModeController
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.data.SimpleStepState
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.service.SimpleStepBridge
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationNotifier
import com.xiaohypercleaner.util.ShizukuHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isOptimized: Boolean = false,
    val isWorking: Boolean = false,
    val progress: Float = 0f,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val showAccessibilityDialog: Boolean = false,
    val showOverlayDialog: Boolean = false,
    val showOptionsDialog: Boolean = false,
    val showDnsWarningDialog: Boolean = false,
    val showRestrictedDialog: Boolean = false,
    val showAppInfoDialog: Boolean = false,
    val showLocationDialog: Boolean = false,
    val dnsFilterEnabled: Boolean = false,
    val aggressiveMode: Boolean = false,
    val showShizukuDialog: Boolean = false,
    val shizukuStatus: ShizukuExecutor.Status = ShizukuExecutor.Status.NOT_INSTALLED,
    val showShizukuSources: Boolean = false,
    val showShizukuWizard: Boolean = false,
    val shizukuCheckMessage: String? = null,
    val showLevelDialog: Boolean = false,
    val showLevelConfirm: Boolean = false,
    val selectedLevel: OptimizationMode? = null,
    val simpleModePhase: SimpleModePhase = SimpleModePhase.INACTIVE,
    val permissionSubPhase: PermissionSubPhase = PermissionSubPhase.INACTIVE,
    val simpleStep: SimpleStepState? = null,
    val simpleDone: Pair<Int, Int>? = null,
    val showRebootDialog: Boolean = false,
    val rebootFailed: Boolean = false,
    val restoreFailed: Boolean = false,
    val showFinalDialog: Boolean = false,
    val optimizationSuccess: Boolean = false,
    val finalReport: String = "",
    val accessibilityAttempts: Int = 0,
    val overlayAttempts: Int = 0,
    val appInfoAttempts: Int = 0,
    val previousAccessibility: Boolean = false,
    val previousOverlay: Boolean = false,
    val restrictedSettingsShown: Boolean = false,
    val showDevModeDialog: Boolean = false,
    val simpleModeActive: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private val permissionFlow = PermissionFlowManager(app)

    private val simpleController =
        SimpleModeController(app, permissionFlow) { mergeSimpleState(it) }
    private val shizukuManager = ShizukuWizardManager(app) { mergeShizukuState(it) }

    private var flowActive = false
    private var simpleModeActive = false
    private val failedSimpleStepIds = mutableListOf<String>()
    private var stepAttempt = 1
    private var autoFlowJob: Job? = null
    private var pendingSourceSuggestion = false

    private enum class Redirect { NONE, ACCESSIBILITY, APP_INFO }

    private var lastRedirect = Redirect.NONE
    private var restrictedFlowStarted = false

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        AppLog.i(TAG, "init started")

        SimpleStepBridge.onResult = { success ->
            AppLog.i(TAG, "SimpleStepBridge result: $success")
            onSimpleStepResult(success)
        }

        viewModelScope.launch {
            prefs.isHiddenSettingsApplied.collect { applied ->
                AppLog.i(TAG, "isHiddenSettingsApplied changed to $applied")
                _state.update { it.copy(isOptimized = applied) }
            }
        }

        viewModelScope.launch {
            prefs.dnsFilterEnabled.collect { enabled ->
                AppLog.i(TAG, "dnsFilterEnabled changed to $enabled")
                _state.update { it.copy(dnsFilterEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            OptimizationNotifier.result.collect { result ->
                AppLog.i(TAG, "notifier result: $result")
                when (result) {
                    is OptimizationNotifier.Result.Success -> {
                        _state.update {
                            it.copy(
                                isWorking = false,
                                isOptimized = true,
                                showFinalDialog = true,
                                optimizationSuccess = true,
                                finalReport = result.details
                            )
                        }
                        OptimizationNotifier.reset()
                    }

                    is OptimizationNotifier.Result.Failure -> {
                        _state.update {
                            it.copy(
                                isWorking = false,
                                isOptimized = false,
                                showFinalDialog = true,
                                optimizationSuccess = false,
                                finalReport = result.details
                            )
                        }
                        OptimizationNotifier.reset()
                    }

                    is OptimizationNotifier.Result.Running -> {
                        _state.update { it.copy(isWorking = true) }
                    }

                    is OptimizationNotifier.Result.DevModeRequired -> {
                        AppLog.i(TAG, "notifier: dev mode required, hiding overlay, showing dialog")
                        stopOverlayService()
                        _state.update { it.copy(showDevModeDialog = true) }
                    }

                    is OptimizationNotifier.Result.Idle -> {}
                }
            }
        }
        AppLog.i(TAG, "init completed")
    }

    override fun onCleared() {
        // super.onCleared() не вызываем — базовый метод пустой (Lint warning fix)
        simpleController.destroy()
        autoFlowJob?.cancel()
    }

    private fun mergeSimpleState(s: SimpleModeController.SimpleModeState) {
        val running =
            s.active || s.step != null || s.done != null || s.phase != SimpleModePhase.INACTIVE

        _state.update { current ->
            current.copy(
                simpleModePhase = s.phase,
                permissionSubPhase = s.permissionSubPhase,
                simpleStep = s.step,
                simpleDone = s.done,
                simpleModeActive = if (!running) false else current.simpleModeActive,
                showAccessibilityDialog = if (running) s.showAccessibilityDialog else false,
                showOverlayDialog = if (running) s.showOverlayDialog else false,
                showRestrictedDialog = if (running) s.showRestrictedDialog else false,
                showAppInfoDialog = if (running) s.showAppInfoDialog else false,
                showLocationDialog = if (running) s.showLocationDialog else false,
                appInfoAttempts = if (running) s.appInfoAttempts else 0,
                restrictedSettingsShown = if (running) s.restrictedSettingsShown else false
            )
        }

        if (!running && simpleModeActive) {
            simpleModeActive = false
        }
    }

    private fun mergeShizukuState(s: ShizukuWizardManager.ShizukuWizardState) {
        _state.update {
            it.copy(
                showShizukuDialog = s.showShizukuDialog,
                showShizukuWizard = s.showShizukuWizard,
                showShizukuSources = s.showShizukuSources,
                shizukuStatus = s.shizukuStatus,
                shizukuCheckMessage = s.shizukuCheckMessage
            )
        }
    }

    private fun stopOverlayService() {
        try {
            app.stopService(Intent(app, OverlayService::class.java))
            AppLog.i(TAG, "overlay service stopped for dialog")
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to stop overlay service: ${e.message}")
        }
    }

    private fun showHint(text: String) {
        try {
            val intent = Intent(app, OverlayService::class.java)
            intent.putExtra("hint", text)
            app.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to show hint: ${e.message}")
        }
    }

    fun openAccessibilitySettings() {
        AppLog.i(TAG, "openAccessibilitySettings")
        val component = ComponentName(app, AdbEnablerService::class.java).flattenToString()
        val deep = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val args = android.os.Bundle()
        args.putString("componentName", component)
        deep.putExtra(
            ":settings:show_fragment",
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
        )
        deep.putExtra(":settings:show_fragment_args", args)

        try {
            deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(deep)
            showHint(app.getString(R.string.hint_accessibility))
        } catch (e: Exception) {
            try {
                app.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                showHint(app.getString(R.string.hint_accessibility))
            } catch (e2: Exception) {
                AppLog.w(TAG, "openAccessibilitySettings failed: ${e2.message}")
            }
        }
    }

    fun openOverlaySettings() {
        AppLog.i(TAG, "openOverlaySettings")
        // minSdk=28, проверка на Build.VERSION_CODES.M (API 23) избыточна
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${app.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            showHint(app.getString(R.string.hint_overlay))
        } catch (e: Exception) {
            AppLog.w(TAG, "openOverlaySettings failed: ${e.message}")
        }
    }

    private fun openAppInfoWithHint() {
        AppLog.i(TAG, "auto-redirect: opening app info with hint card")
        permissionFlow.openAppInfoSettings()
        showHint(app.getString(R.string.hint_restricted))
    }

    private fun openAccessibilityWithHint() {
        AppLog.i(TAG, "auto-redirect: opening accessibility services with hint card")
        permissionFlow.openAccessibilityWithHint()
        showHint(app.getString(R.string.hint_accessibility))
    }

    fun refreshStatuses() {
        AppLog.i(TAG, "refreshStatuses called")

        val component = ComponentName(app, AdbEnablerService::class.java).flattenToString()
        val acc = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
        val overlay = Settings.canDrawOverlays(app)

        val prevState = _state.value
        val accessibilityJustChanged = !prevState.previousAccessibility && acc
        val overlayJustChanged = !prevState.previousOverlay && overlay

        AppLog.i(
            TAG,
            "refreshStatuses: accessibility=$acc (was ${prevState.previousAccessibility}), overlay=$overlay (was ${prevState.previousOverlay}), simpleModeActive=$simpleModeActive"
        )

        _state.update {
            it.copy(
                isAccessibilityEnabled = acc,
                isOverlayGranted = overlay,
                previousAccessibility = acc,
                previousOverlay = overlay
            )
        }

        if (pendingSourceSuggestion) {
            pendingSourceSuggestion = false
            val st = ShizukuExecutor.checkStatus(app)
            AppLog.i(TAG, "refreshStatuses: pendingSourceSuggestion, shizuku=$st")
            if (st == ShizukuExecutor.Status.NOT_INSTALLED) {
                _state.update { it.copy(showShizukuSources = true) }
            }
        }

        simpleController.updatePermissionStatuses(acc, overlay)

        if (simpleModeActive) {
            if (!ChainFlags.waitingAccessibilityReturn) {
                simpleController.onResumeAfterPermissionReturn()
            } else {
                simpleController.refresh()
            }
            return
        }

        if (accessibilityJustChanged && flowActive) {
            AppLog.i(TAG, "refreshStatuses: accessibility just enabled, continuing chain")
            resetRedirectFlow()
            _state.update { it.copy(accessibilityAttempts = 0) }
            advance()
            return
        }

        if (overlayJustChanged && flowActive) {
            AppLog.i(TAG, "refreshStatuses: overlay just enabled, continuing chain")
            _state.update { it.copy(overlayAttempts = 0) }
            advance()
            return
        }

        if (!acc && prevState.accessibilityAttempts > 0 && flowActive) {
            AppLog.i(TAG, "refreshStatuses: accessibility not enabled after attempt")
            when {
                lastRedirect == Redirect.ACCESSIBILITY && !restrictedFlowStarted -> {
                    AppLog.i(TAG, "refreshStatuses: denied — auto-redirect to app info")
                    restrictedFlowStarted = true
                    lastRedirect = Redirect.APP_INFO
                    openAppInfoWithHint()
                }

                lastRedirect == Redirect.APP_INFO -> {
                    AppLog.i(
                        TAG,
                        "refreshStatuses: back from app info — auto-redirect to accessibility"
                    )
                    lastRedirect = Redirect.ACCESSIBILITY
                    openAccessibilityWithHint()
                }

                else -> {
                    AppLog.i(TAG, "refreshStatuses: loop breaker — showing restricted dialog")
                    _state.update {
                        it.copy(
                            showRestrictedDialog = true,
                            showAccessibilityDialog = false,
                            showOverlayDialog = false,
                            restrictedSettingsShown = true
                        )
                    }
                }
            }
            return
        }

        if (!overlay && prevState.overlayAttempts > 0 && flowActive) {
            AppLog.i(
                TAG,
                "refreshStatuses: overlay not enabled after attempt, showing dialog again"
            )
            _state.update {
                it.copy(
                    showOverlayDialog = true,
                    showAccessibilityDialog = false,
                    showRestrictedDialog = false
                )
            }
            return
        }

        if (flowActive) advance()
    }

    fun checkRestrictedSettingsOnResume() {
        AppLog.i(TAG, "checkRestrictedSettingsOnResume called (no-op, handled by refreshStatuses)")
    }

    fun startFlow() {
        AppLog.i(TAG, "startFlow called, isWorking=${_state.value.isWorking}")

        if (_state.value.isWorking) {
            AppLog.i(TAG, "startFlow: already working, ignoring")
            return
        }

        if (_state.value.showLevelDialog || _state.value.showLevelConfirm) {
            AppLog.i(TAG, "startFlow: level dialog already shown, ignoring")
            return
        }

        if (_state.value.simpleStep != null || _state.value.simpleDone != null || simpleModeActive) {
            AppLog.i(TAG, "startFlow: simple mode already active, ignoring")
            return
        }

        _state.update { it.copy(showLevelDialog = true) }
    }

    fun onLevelChosen(level: OptimizationMode) {
        AppLog.i(TAG, "onLevelChosen: $level")
        _state.update {
            it.copy(
                showLevelDialog = false,
                showLevelConfirm = true,
                selectedLevel = level
            )
        }
    }

    fun confirmLevelStart() {
        val level = _state.value.selectedLevel ?: OptimizationMode.SIMPLE
        AppLog.i(TAG, "confirmLevelStart: $level")
        _state.update { it.copy(showLevelConfirm = false, selectedLevel = null) }

        when (level) {
            OptimizationMode.SIMPLE -> startSimpleMode()
            OptimizationMode.PRO -> startAdvancedFlow()
        }
    }

    fun cancelLevelConfirm() {
        AppLog.i(TAG, "cancelLevelConfirm")
        _state.update { it.copy(showLevelConfirm = false, selectedLevel = null) }
    }

    @VisibleForTesting
    internal fun startSimpleMode() {
        AppLog.i(TAG, "startSimpleMode")
        simpleModeActive = true
        failedSimpleStepIds.clear()
        stepAttempt = 1
        simpleController.start()
    }

    fun onSimpleStepResult(success: Boolean) {
        AppLog.i(TAG, "onSimpleStepResult: success=$success, attempt=$stepAttempt")
        val step = _state.value.simpleStep ?: return

        if (success) {
            simpleController.onStepResult(true)
            autoFlowJob = viewModelScope.launch {
                delay(AUTO_ADVANCE_DELAY_MS)
                advanceToNextStep(autoStart = true)
            }
            return
        }

        if (stepAttempt < step.maxAttempts) {
            stepAttempt++
            AppLog.w(TAG, "onSimpleStepResult: auto-retry $stepAttempt/${step.maxAttempts}")
            autoFlowJob?.cancel()
            autoFlowJob = viewModelScope.launch {
                delay(RETRY_DELAY_MS)
                simpleController.startCurrentStep()
            }
        } else {
            AppLog.e(TAG, "onSimpleStepResult: all attempts exhausted")
            failedSimpleStepIds.add(step.step.id)
            simpleController.onStepResult(false)
        }
    }

    private fun advanceToNextStep(autoStart: Boolean) {
        simpleController.nextStep()
        if (autoStart && _state.value.simpleStep != null) {
            stepAttempt = 1
            simpleController.startCurrentStep()
        }
    }

    fun closeSimpleMode() {
        AppLog.i(TAG, "closeSimpleMode")
        autoFlowJob?.cancel()
        simpleModeActive = false

        _state.update {
            it.copy(
                simpleStep = null,
                simpleDone = null,
                simpleModePhase = SimpleModePhase.INACTIVE,
                permissionSubPhase = PermissionSubPhase.INACTIVE,
                simpleModeActive = false,
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                showAppInfoDialog = false,
                showLocationDialog = false
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SIMPLE MODE UI CONTROLS (обёртки для MainActivity)
    // ═══════════════════════════════════════════════════════════════
    fun startCurrentSimpleStep() {
        simpleController.startCurrentStep()
    }

    fun nextSimpleStep() {
        onSimpleStepResult(true)
    }

    fun skipSimpleStep() {
        val stepId = _state.value.simpleStep?.step?.id ?: "unknown"
        if (!failedSimpleStepIds.contains(stepId)) {
            failedSimpleStepIds.add(stepId)
        }
        onSimpleStepResult(false)
    }

    fun retrySimpleStep() {
        simpleController.retryStep()
    }

    fun simpleController_onAppInfoDialogAgreed() {
        simpleController.onAppInfoDialogAgreed()
    }

    fun simpleController_onAppInfoDialogCancelled() {
        simpleController.onAppInfoDialogCancelled()
    }

    fun onLocationChosen(location: RestrictedLocation) {
        simpleController.onLocationChosen(location)
    }

    fun onLocationDialogCancelled() {
        simpleController.onLocationDialogCancelled()
    }

    private fun startAdvancedFlow() {
        val status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "startAdvancedFlow: shizuku status=$status")

        if (status != ShizukuExecutor.Status.AVAILABLE) {
            shizukuManager.showDialog(status)
        } else {
            _state.update { it.copy(showOptionsDialog = true) }
        }
    }

    fun shizukuDialogInstall() {
        pendingSourceSuggestion = true
        shizukuManager.onInstallClicked()
    }

    fun shizukuDialogOpenApp() {
        shizukuManager.onOpenAppClicked()
    }

    fun wizardSkip() {
        shizukuManager.onWizardSkip()
        _state.update { it.copy(showOptionsDialog = true) }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission(SHIZUKU_PERMISSION_CODE)
    }

    fun onShizukuPermissionResult(granted: Boolean) {
        shizukuManager.onPermissionResult(granted)
        if (granted) {
            _state.update { it.copy(showOptionsDialog = true) }
        }
    }

    fun wizardCheck() {
        shizukuManager.checkStatus()
        if (ShizukuExecutor.checkStatus(app) == ShizukuExecutor.Status.AVAILABLE) {
            _state.update { it.copy(showOptionsDialog = true) }
        }
    }

    fun openShizukuSources() {
        shizukuManager.onOpenSources()
    }

    fun closeShizukuSources() {
        shizukuManager.closeSources()
    }

    fun installFromSource(source: String) {
        pendingSourceSuggestion = true
        shizukuManager.installFromSource(source)
    }

    fun shizukuDialogLater() {
        shizukuManager.onLater()
        _state.update { it.copy(showOptionsDialog = true) }
    }

    fun closeShizukuWizard() {
        shizukuManager.closeWizard()
    }

    fun optionsDialogConfirmed() {
        AppLog.i(
            TAG,
            "optionsDialogConfirmed, dnsFilter=${_state.value.dnsFilterEnabled}, aggressive=${_state.value.aggressiveMode}"
        )
        _state.update { it.copy(showOptionsDialog = false) }

        if (_state.value.dnsFilterEnabled) {
            viewModelScope.launch {
                val seen = prefs.hasSeenDnsWarning.first()
                if (!seen) {
                    AppLog.i(TAG, "showing DNS warning dialog")
                    _state.update { it.copy(showDnsWarningDialog = true) }
                    return@launch
                }
                proceedToChain()
            }
        } else {
            proceedToChain()
        }
    }

    fun dnsWarningAccepted() {
        AppLog.i(TAG, "dnsWarningAccepted")
        _state.update { it.copy(showDnsWarningDialog = false) }
        viewModelScope.launch {
            prefs.setHasSeenDnsWarning(true)
        }
        proceedToChain()
    }

    fun dnsWarningDeclined() {
        AppLog.i(TAG, "dnsWarningDeclined — disabling DNS")
        _state.update { it.copy(showDnsWarningDialog = false, dnsFilterEnabled = false) }
        viewModelScope.launch {
            prefs.setDnsFilterEnabled(false)
        }
        proceedToChain()
    }

    fun optionsDialogCancelled() {
        AppLog.i(TAG, "optionsDialogCancelled")
        _state.update { it.copy(showOptionsDialog = false) }
    }

    fun toggleDnsFilter(enabled: Boolean) {
        AppLog.i(TAG, "toggleDnsFilter: $enabled")
        _state.update { it.copy(dnsFilterEnabled = enabled) }
        viewModelScope.launch {
            prefs.setDnsFilterEnabled(enabled)
        }
    }

    fun toggleAggressiveMode(enabled: Boolean) {
        AppLog.i(TAG, "toggleAggressiveMode: $enabled")
        _state.update { it.copy(aggressiveMode = enabled) }
    }

    private fun proceedToChain() {
        AppLog.i(TAG, "proceedToChain")
        flowActive = true
        advance()
    }

    private fun advance() {
        val s = _state.value
        AppLog.i(TAG, "advance: acc=${s.isAccessibilityEnabled}, overlay=${s.isOverlayGranted}")

        when {
            !s.isAccessibilityEnabled -> {
                AppLog.i(TAG, "advance: showing accessibility dialog")
                _state.update {
                    it.copy(
                        showAccessibilityDialog = true,
                        showOverlayDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            !s.isOverlayGranted -> {
                AppLog.i(TAG, "advance: showing overlay dialog")
                _state.update {
                    it.copy(
                        showOverlayDialog = true,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            else -> {
                AppLog.i(TAG, "advance: all permissions granted, starting chain")
                flowActive = false
                resetRedirectFlow()
                _state.update {
                    it.copy(
                        showOverlayDialog = false,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false,
                        accessibilityAttempts = 0,
                        overlayAttempts = 0,
                        restrictedSettingsShown = false
                    )
                }
                startChain()
            }
        }
    }

    fun dialogAgreed() {
        AppLog.i(TAG, "dialogAgreed, simpleModeActive=$simpleModeActive")

        if (simpleModeActive) {
            val currentState = _state.value
            if (currentState.showAccessibilityDialog) {
                _state.update { it.copy(accessibilityAttempts = it.accessibilityAttempts + 1) }
            } else if (currentState.showOverlayDialog) {
                _state.update { it.copy(overlayAttempts = it.overlayAttempts + 1) }
            }
            simpleController.onDialogAgreed()
            return
        }

        val currentState = _state.value
        if (currentState.showAccessibilityDialog) {
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    accessibilityAttempts = it.accessibilityAttempts + 1
                )
            }
            ChainFlags.waitingAccessibilityReturn = true
            markAccessibilityOpened()
            openAccessibilitySettings()
        } else if (currentState.showOverlayDialog) {
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    overlayAttempts = it.overlayAttempts + 1
                )
            }
            openOverlaySettings()
        } else {
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false
                )
            }
        }
    }

    fun dialogCancelled() {
        AppLog.i(TAG, "dialogCancelled, simpleModeActive=$simpleModeActive")

        if (simpleModeActive) {
            simpleModeActive = false
            simpleController.onDialogCancelled()
            return
        }

        flowActive = false
        resetRedirectFlow()
        _state.update {
            it.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                accessibilityAttempts = 0,
                overlayAttempts = 0,
                restrictedSettingsShown = false
            )
        }
    }

    fun restrictedDialogAgreed() {
        AppLog.i(TAG, "restrictedDialogAgreed")

        if (simpleModeActive) {
            simpleController.onRestrictedDialogAgreed()
            return
        }

        _state.update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                restrictedSettingsShown = true
            )
        }
        markAppInfoOpened()
        openAppInfoWithHint()
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled")

        if (simpleModeActive) {
            simpleModeActive = false
            simpleController.onRestrictedDialogCancelled()
            return
        }

        flowActive = false
        resetRedirectFlow()
        _state.update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                accessibilityAttempts = 0,
                restrictedSettingsShown = false
            )
        }
    }

    fun markAccessibilityOpened() {
        lastRedirect = Redirect.ACCESSIBILITY
        AppLog.i(TAG, "markAccessibilityOpened")
    }

    fun markAppInfoOpened() {
        lastRedirect = Redirect.APP_INFO
        AppLog.i(TAG, "markAppInfoOpened")
    }

    private fun resetRedirectFlow() {
        lastRedirect = Redirect.NONE
        restrictedFlowStarted = false
    }

    private fun startChain() {
        AppLog.i(
            TAG,
            "startChain: setting pending flag, dnsFilter=${_state.value.dnsFilterEnabled}, aggressive=${_state.value.aggressiveMode}"
        )

        viewModelScope.launch {
            prefs.setPendingOptimization(true)
            prefs.setDnsFilterEnabled(_state.value.dnsFilterEnabled)
            prefs.setAggressiveMode(_state.value.aggressiveMode)
            AppLog.i(TAG, "startChain: pending flag set")

            if (_state.value.isAccessibilityEnabled) {
                AppLog.i(
                    TAG,
                    "startChain: accessibility already enabled, starting service directly"
                )
                val intent = Intent(app, AdbEnablerService::class.java)
                intent.action = AdbEnablerService.ACTION_START_CHAIN
                app.startService(intent)
            } else {
                AppLog.i(TAG, "startChain: opening accessibility settings")
                openAccessibilitySettingsAutomatically()
            }
        }
    }

    private fun openAccessibilitySettingsAutomatically() {
        AppLog.i(TAG, "openAccessibilitySettingsAutomatically: showing accessibility dialog")
        setAccessibilityWaitingFlag()
        _state.update { it.copy(showAccessibilityDialog = true) }
    }

    private fun setAccessibilityWaitingFlag() {
        ChainFlags.waitingAccessibilityReturn = true
    }

    fun restoreOptimization() {
        if (_state.value.isWorking) return

        viewModelScope.launch {
            try {
                _state.update { it.copy(isWorking = true, progress = 0f) }
                val deps = XiaoHyperApp.testDeps ?: app.deps
                val ok = deps.newEngine().restore(callbacks())

                if (ok) {
                    prefs.setHiddenSettingsApplied(false)
                    _state.update { it.copy(isWorking = false, isOptimized = false) }
                } else {
                    _state.update { it.copy(isWorking = false, restoreFailed = true) }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "restore failed: ${e.message}", e)
                _state.update { it.copy(isWorking = false, restoreFailed = true) }
            }
        }
    }

    fun dismissFinalDialog() {
        _state.update { it.copy(showFinalDialog = false) }
    }

    fun devModeDialogOpenAbout() {
        AppLog.i(TAG, "devModeDialog: open about phone")
    }

    fun devModeDialogRetry() {
        AppLog.i(TAG, "devModeDialog: retry — resuming chain (service will restart overlay)")
        _state.update { it.copy(showDevModeDialog = false) }
        val intent = Intent(app, AdbEnablerService::class.java)
        intent.action = AdbEnablerService.ACTION_RETRY_DEV
        app.startService(intent)
    }

    fun devModeDialogCancel() {
        AppLog.i(TAG, "devModeDialog: cancel — stopping chain")
        _state.update { it.copy(showDevModeDialog = false) }
        OverlayController.triggerCancel()
    }

    fun dismissRestoreFailed() {
        _state.update { it.copy(restoreFailed = false) }
    }

    fun requestReboot() {
        _state.update { it.copy(showRebootDialog = true) }
    }

    fun dismissRebootDialog() {
        _state.update { it.copy(showRebootDialog = false) }
    }

    fun dismissRebootFailed() {
        _state.update { it.copy(rebootFailed = false) }
    }

    fun confirmReboot() {
        _state.update { it.copy(showRebootDialog = false, isWorking = true) }

        viewModelScope.launch {
            try {
                val deps = XiaoHyperApp.testDeps ?: app.deps
                val ok = deps.newEngine().reboot()
                _state.update { it.copy(isWorking = false, rebootFailed = !ok) }
            } catch (e: Exception) {
                AppLog.e(TAG, "reboot failed: ${e.message}", e)
                _state.update { it.copy(isWorking = false, rebootFailed = true) }
            }
        }
    }

    fun getFailedStepIds(): List<String> = failedSimpleStepIds.toList()

    private fun callbacks() = OptimizationEngine.Callbacks(
        onProgress = { p -> _state.update { it.copy(progress = p) } }
    )

    companion object {
        private const val TAG = "MainVM"
        const val SHIZUKU_PERMISSION_CODE = 9001
    }
}