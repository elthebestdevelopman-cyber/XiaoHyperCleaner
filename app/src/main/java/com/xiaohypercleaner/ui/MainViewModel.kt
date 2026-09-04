package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.AppConstants.AUTO_ADVANCE_DELAY_MS
import com.xiaohypercleaner.AppConstants.RETRY_DELAY_MS
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PermissionFlowManager
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.SimpleModeController
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.service.SimpleStepBridge
import com.xiaohypercleaner.ui.extensions.displayName
import com.xiaohypercleaner.ui.extensions.description
import com.xiaohypercleaner.ui.vm.ProFlowController
import com.xiaohypercleaner.ui.vm.ShizukuUiController
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Главный ViewModel приложения.
 *
 * Архитектура:
 * - Тонкий фасад над делегатами: SimpleModeController, ProFlowController, ShizukuUiController
 * - SimpleStepBridge — мост между AdbEnablerService и ViewModel
 * - OptimizationNotifier — реактивное получение результатов Pro-режима
 *
 * УЛУЧШЕНИЯ:
 * 1. ИСПРАВЛЕН КРИТИЧЕСКИЙ БАГ: showHint() теперь устанавливает ACTION_HINT и EXTRA_HINT
 * 2. Убран дублирующий simpleModeActive — используется только state.simpleModeActive
 * 3. stopOverlayService() заменён на OverlayController.hide() (без stopService)
 * 4. openAccessibilitySettings/openOverlaySettings делегируют PermissionFlowManager
 * 5. Русские логи для consistency
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        const val SHIZUKU_PERMISSION_CODE: Int = 9001
    }

    private val app: XiaoHyperApp = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private val permissionFlow: PermissionFlowManager = PermissionFlowManager(app)

    private val _state: MutableStateFlow<MainUiState> = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private fun update(transform: (MainUiState) -> MainUiState) = _state.update(transform)

    val simpleController: SimpleModeController =
        SimpleModeController(app, permissionFlow) { mergeSimpleState(it) }

    private val shizuku: ShizukuUiController = ShizukuUiController(app, ::update)

    private val proFlow: ProFlowController = ProFlowController(
        app = app,
        prefs = prefs,
        getState = { _state.value },
        update = ::update,
        openAccessibilityWithHint = ::openAccessibilityWithHint,
        openAppInfoWithHint = ::openAppInfoWithHint,
        openAccessibilitySettings = ::openAccessibilitySettings,
        openOverlaySettings = ::openOverlaySettings,
        scope = viewModelScope
    )

    private var stepAttempt: Int = 1
    private var autoFlowJob: Job? = null
    private val failedSimpleStepIds: MutableList<String> = mutableListOf()

    init {
        AppLog.i(TAG, "init started")

        SimpleStepBridge.onResult = { success, reason ->
            AppLog.i(TAG, "SimpleStepBridge result: $success reason=$reason")
            if (success && (reason == "toggled" || reason == "confirmed" || reason == "already_done")) {
                val stepId = _state.value.simpleStep?.step?.id
                if (stepId != null) {
                    viewModelScope.launch {
                        prefs.addSimpleToggledStep(stepId)
                        prefs.setHiddenSettingsApplied(true)
                    }
                }
            }
            onSimpleStepResult(success)
        }

        SimpleStepBridge.onSkipped = { stepId ->
            AppLog.i(TAG, "SimpleStepBridge skipped (app not installed): $stepId")
            stepAttempt = 1
            simpleController.onStepSkipped(stepId)
        }

        OverlayController.setOnCancel {
            AppLog.i(TAG, "automation cancelled by user via overlay")
            AdbEnablerService.instance?.cancelRunner()
            OverlayController.hide(app)
            closeSimpleMode()
        }

        OverlayController.setOnResultClose {
            AppLog.i(TAG, "result overlay closed by user")
            OverlayController.hide(app)
            closeSimpleMode()
        }

        viewModelScope.launch {
            prefs.isHiddenSettingsApplied.collect { applied ->
                AppLog.i(TAG, "isHiddenSettingsApplied changed to $applied")
                update { it.copy(isOptimized = applied) }
            }
        }

        viewModelScope.launch {
            val rootOk = try {
                com.xiaohypercleaner.data.RootExecutor().isAvailable()
            } catch (_: Exception) {
                false
            }
            AppLog.i(TAG, "canAutoReboot (root)=$rootOk")
            update { it.copy(canAutoReboot = rootOk) }
        }

        viewModelScope.launch {
            prefs.dnsFilterEnabled.collect { enabled ->
                AppLog.i(TAG, "dnsFilterEnabled changed to $enabled")
                update { it.copy(dnsFilterEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            OptimizationNotifier.result.collect { result ->
                AppLog.i(TAG, "notifier result: $result")
                when (result) {
                    is OptimizationNotifier.Result.Success -> {
                        update {
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
                        update {
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
                        update { it.copy(isWorking = true) }
                    }

                    is OptimizationNotifier.Result.DevModeRequired -> {
                        AppLog.i(TAG, "notifier: dev mode required, hiding overlay, showing dialog")
                        OverlayController.hide(app)
                        update { it.copy(showDevModeDialog = true) }
                    }

                    is OptimizationNotifier.Result.Idle -> {}
                }
            }
        }
        AppLog.i(TAG, "init completed")
    }

    override fun onCleared() {
        OverlayController.setOnCancel(null)
        OverlayController.setOnResultClose(null)
        OverlayController.hide(app)
        simpleController.destroy()
        autoFlowJob?.cancel()
        super.onCleared()
    }

    private fun mergeSimpleState(s: SimpleModeController.SimpleModeState) {
        val running: Boolean =
            s.active || s.step != null || s.done != null || s.phase != SimpleModePhase.INACTIVE

        if (s.phase == SimpleModePhase.DONE || s.phase == SimpleModePhase.INACTIVE) {
            viewModelScope.launch { prefs.setPendingSimpleMode(false) }
        }

        update { current ->
            current.copy(
                simpleModePhase = s.phase,
                permissionSubPhase = s.permissionSubPhase,
                simpleStep = s.step,
                simpleDone = s.done,
                simpleModeActive = running,
                showAccessibilityDialog = if (running) s.showAccessibilityDialog else false,
                showOverlayDialog = if (running) s.showOverlayDialog else false,
                showRestrictedDialog = if (running) s.showRestrictedDialog else false,
                showAppInfoDialog = if (running) s.showAppInfoDialog else false,
                showLocationDialog = if (running) s.showLocationDialog else false,
                showPermissionFallbackDialog = if (running) s.showPermissionFallbackDialog else false,
                stuckPhase = if (running) s.stuckPhase else null,
                showRestrictedSettingsScreen = if (running) s.showRestrictedSettingsScreen else false,
                showBatteryDialog = if (running) s.showBatteryDialog else false,
                appInfoAttempts = if (running) s.appInfoAttempts else 0,
                restrictedSettingsShown = if (running) s.restrictedSettingsShown else false
            )
        }
    }

    fun refreshStatuses() {
        AppLog.i(TAG, "refreshStatuses called")

        val component: String = ComponentName(app, AdbEnablerService::class.java).flattenToString()
        val acc: Boolean = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
        val overlay: Boolean = Settings.canDrawOverlays(app)

        val prevState: MainUiState = _state.value

        AppLog.i(
            TAG,
            "refreshStatuses: accessibility=$acc (was ${prevState.previousAccessibility}), " +
                    "overlay=$overlay (was ${prevState.previousOverlay}), simpleModeActive=${_state.value.simpleModeActive}"
        )

        update {
            it.copy(
                isAccessibilityEnabled = acc,
                isOverlayGranted = overlay,
                previousAccessibility = acc,
                previousOverlay = overlay
            )
        }

        shizuku.consumePendingSourceSuggestion()
        simpleController.updatePermissionStatuses(acc, overlay)

        if (_state.value.simpleModeActive) {
            if (!ChainFlags.waitingAccessibilityReturn) {
                simpleController.onResumeAfterPermissionReturn()
            } else {
                simpleController.refresh()
            }
            return
        }

        proFlow.handleRefresh(acc, overlay, prevState)
    }

    fun checkRestrictedSettingsOnResume() {
        AppLog.i(TAG, "checkRestrictedSettingsOnResume called (no-op, handled by refreshStatuses)")
    }

    /**
     * ИСПРАВЛЕНО: возвращение из настроек Battery Optimization.
     *
     *  • Отключил экономию (ignoring=true):
     *      — закрываем диалог батареи
     *      — показываем карточку «Продолжить»
     *      — контроллер переводит фазу в STEPS (без автозапуска)
     *
     *  • Вернулся без отключения (ignoring=false):
     *      — осознанно вызываем reshowBatteryDialog()
     *        (диалог батареи показывается снова, можно повторить или пропустить)
     *
     * Раньше контроллер сам вызывал refresh() и advance(), что приводило к
     * «ложному» всплытию диалога при системном диалоге MIUI поверх настроек.
     */
    fun permissionFlow_isIgnoringBatteryOptimizations(): Boolean =
        permissionFlow.isIgnoringBatteryOptimizations()

    fun onBatteryOptimizationReturn() {
        AppLog.i(TAG, "onBatteryOptimizationReturn")
        viewModelScope.launch {
            var ignoring: Boolean = permissionFlow.isIgnoringBatteryOptimizations()
            if (!ignoring) {
                delay(600)
                ignoring = permissionFlow.isIgnoringBatteryOptimizations()
            }
            AppLog.i(TAG, "onBatteryOptimizationReturn: isIgnoring=$ignoring")

            if (ignoring) {
                // Отключил → закрываем диалог батареи, показываем «Продолжить»
                update { it.copy(showBatteryDialog = false, showLevelConfirm = true) }
                simpleController.onBatteryReturn(true)
            } else {
                // Вернулся без отключения → осознанно показываем диалог снова
                simpleController.reshowBatteryDialog()
            }
            refreshStatuses()
        }
    }

    private var eeaNoticeAcknowledged: Boolean = false

    fun startFlow() {
        AppLog.i(TAG, "startFlow called, isWorking=${_state.value.isWorking}")

        if (_state.value.isWorking) return
        if (_state.value.showLevelDialog || _state.value.showLevelConfirm) return

        if (_state.value.simpleModeActive && _state.value.simpleModePhase == SimpleModePhase.DONE) {
            AppLog.i(TAG, "startFlow: previous run finished — resetting")
            closeSimpleMode()
        }
        // Если пользователь отменил оверлей, но флаг залип — сбрасываем и даём начать заново
        if (_state.value.simpleModeActive &&
            _state.value.simpleModePhase == SimpleModePhase.STEPS &&
            !_state.value.isWorking
        ) {
            val runnerBusy = AdbEnablerService.instance?.let {
                com.xiaohypercleaner.service.SimpleRunner.isRunning
            } == true
            if (!runnerBusy) {
                AppLog.w(TAG, "startFlow: stale simpleModeActive without runner — resetting")
                closeSimpleMode()
            }
        }
        if (_state.value.simpleModeActive) {
            AppLog.i(TAG, "startFlow: simple mode already active, ignoring")
            return
        }

        val profile = RomProfile.detect(app)
        if (profile.optimizationScope == com.xiaohypercleaner.data.OptimizationScope.PRE_OPTIMIZED_EEA && !eeaNoticeAcknowledged) {
            update { it.copy(showEeaNoticeDialog = true, eeaRegionName = profile.regionCode) }
            return
        }

        update { it.copy(showLevelDialog = true) }
    }

    fun onEeaNoticeAgreed() {
        eeaNoticeAcknowledged = true
        update { it.copy(showEeaNoticeDialog = false, showLevelDialog = true) }
    }

    fun onEeaNoticeCancelled() {
        update { it.copy(showEeaNoticeDialog = false) }
    }

    fun onLevelChosen(level: OptimizationMode) {
        AppLog.i(TAG, "onLevelChosen: $level")
        update {
            it.copy(showLevelDialog = false, showLevelConfirm = true, selectedLevel = level)
        }
    }

    fun confirmLevelStart(level: OptimizationMode) {
        AppLog.i(TAG, "confirmLevelStart: $level")
        update { it.copy(showLevelConfirm = false) }

        when (level) {
            OptimizationMode.SIMPLE -> {
                if (_state.value.simpleModeActive) {
                    simpleController.continueToSteps()
                } else {
                    startSimpleMode()
                }
            }

            OptimizationMode.PRO -> startAdvancedFlow()
        }
    }

    fun cancelLevelConfirm() {
        AppLog.i(TAG, "cancelLevelConfirm")
        update { it.copy(showLevelConfirm = false, selectedLevel = null) }
    }

    @VisibleForTesting
    internal fun startSimpleMode() {
        AppLog.i(TAG, "startSimpleMode")
        failedSimpleStepIds.clear()
        stepAttempt = 1
        viewModelScope.launch { prefs.setPendingSimpleMode(true) }
        simpleController.start()
    }

    private fun startAdvancedFlow() {
        val status: ShizukuExecutor.Status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "startAdvancedFlow: shizuku status=$status")

        when (status) {
            ShizukuExecutor.Status.AVAILABLE -> shizuku.showOptionsDialog()
            ShizukuExecutor.Status.PERMISSION_REQUIRED -> {
                // Один тап меньше: сразу запрашиваем разрешение
                shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }
            ShizukuExecutor.Status.NOT_RUNNING -> {
                shizuku.showDialog(status)
                // Параллельно открываем беспроводную отладку — главный блокер новичков
                ShizukuHelper.openWirelessDebuggingSettings(app)
            }
            ShizukuExecutor.Status.NOT_INSTALLED -> shizuku.showDialog(status)
        }
    }

    fun onSimpleStepResult(success: Boolean) {
        AppLog.i(TAG, "onSimpleStepResult: success=$success, attempt=$stepAttempt")
        val step = _state.value.simpleStep ?: return

        if (success) {
            val attempt = stepAttempt
            stepAttempt = 1
            simpleController.onStepResult(true, attempt = attempt)
            return
        }

        if (stepAttempt < step.maxAttempts) {
            stepAttempt++
            AppLog.w(TAG, "onSimpleStepResult: auto-retry $stepAttempt/${step.maxAttempts}")
            autoFlowJob?.cancel()
            autoFlowJob = viewModelScope.launch {
                delay(RETRY_DELAY_MS.milliseconds)
                simpleController.startCurrentStep(force = true)
            }
        } else {
            AppLog.e(TAG, "onSimpleStepResult: all attempts exhausted")
            failedSimpleStepIds.add(step.step.id)
            val attempt = stepAttempt
            stepAttempt = 1
            simpleController.onStepResult(
                success = false,
                attempt = attempt,
                finalFailure = true
            )
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
        AdbEnablerService.instance?.cancelRunner()
        OverlayController.hide(app)
        simpleController.cancelAndReset()
        ChainFlags.reset()
        viewModelScope.launch { prefs.setPendingSimpleMode(false) }

        update {
            it.copy(
                simpleStep = null,
                simpleDone = null,
                simpleModePhase = SimpleModePhase.INACTIVE,
                permissionSubPhase = PermissionSubPhase.INACTIVE,
                simpleModeActive = false,
                showLevelDialog = false,
                showLevelConfirm = false,
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                showAppInfoDialog = false,
                showLocationDialog = false,
                showPermissionFallbackDialog = false,
                stuckPhase = null,
                showRestrictedSettingsScreen = false,
                showBatteryDialog = false
            )
        }
    }

    /**
     * После kill процесса при включении Accessibility — продолжить Simple Mode,
     * если разрешения уже выданы.
     */
    fun tryResumePendingSimpleMode() {
        viewModelScope.launch {
            if (!prefs.getPendingSimpleMode()) return@launch
            if (_state.value.simpleModeActive) return@launch

            val component = ComponentName(app, AdbEnablerService::class.java).flattenToString()
            val acc = Settings.Secure.getString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(component) == true
            val overlay = Settings.canDrawOverlays(app)

            if (!acc || !overlay) {
                AppLog.i(TAG, "tryResumePendingSimpleMode: permissions incomplete, keep pending")
                return@launch
            }

            AppLog.i(TAG, "tryResumePendingSimpleMode: restoring Simple Mode after process restart")
            failedSimpleStepIds.clear()
            stepAttempt = 1
            simpleController.start()
        }
    }

    fun getFailedStepIds(): List<String> = failedSimpleStepIds.toList()

    fun startCurrentSimpleStep() = simpleController.startCurrentStep()
    fun nextSimpleStep() = onSimpleStepResult(true)

    fun skipSimpleStep() {
        val stepId: String = _state.value.simpleStep?.step?.id ?: "unknown"
        if (!failedSimpleStepIds.contains(stepId)) failedSimpleStepIds.add(stepId)
        stepAttempt = 1
        simpleController.onStepSkipped(stepId)
    }

    fun retrySimpleStep() = simpleController.retryStep()

    fun appInfoDialogAgreed() = simpleController.onAppInfoDialogAgreed()
    fun appInfoDialogCancelled() = simpleController.onAppInfoDialogCancelled()
    fun onLocationChosen(location: RestrictedLocation) =
        simpleController.onLocationChosen(location)

    fun onLocationDialogCancelled() = simpleController.onLocationDialogCancelled()
    fun onRestrictedScreenOpenSettings() = simpleController.onRestrictedScreenOpenSettings()
    fun onRestrictedScreenDone() = simpleController.onRestrictedScreenDone()
    fun onRestrictedScreenCancelled() = simpleController.onRestrictedScreenCancelled()
    fun onBatteryDialogAgreed() = simpleController.onBatteryDialogAgreed()
    fun onBatteryDialogSkipped() = simpleController.onBatteryDialogSkipped()
    fun onPermissionFallbackRetry() = simpleController.onFallbackRetry()
    fun onPermissionFallbackOpenSettings() = simpleController.onFallbackOpenSettings()

    fun onPermissionFallbackCancelled() {
        simpleController.onFallbackCancelled()
    }

    fun dialogAgreed() {
        AppLog.i(TAG, "dialogAgreed, simpleModeActive=${_state.value.simpleModeActive}")

        if (_state.value.simpleModeActive) {
            val currentState: MainUiState = _state.value
            if (currentState.showAccessibilityDialog) {
                update { it.copy(accessibilityAttempts = it.accessibilityAttempts + 1) }
            } else if (currentState.showOverlayDialog) {
                update { it.copy(overlayAttempts = it.overlayAttempts + 1) }
            }
            simpleController.onDialogAgreed()
            return
        }
        proFlow.dialogAgreed()
    }

    fun dialogCancelled() {
        AppLog.i(TAG, "dialogCancelled, simpleModeActive=${_state.value.simpleModeActive}")

        if (_state.value.simpleModeActive) {
            simpleController.onDialogCancelled()
            return
        }
        proFlow.dialogCancelled()
    }

    fun restrictedDialogAgreed() {
        AppLog.i(TAG, "restrictedDialogAgreed")
        if (_state.value.simpleModeActive) {
            simpleController.onRestrictedDialogAgreed()
            return
        }
        proFlow.restrictedDialogAgreed()
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled")
        if (_state.value.simpleModeActive) {
            simpleController.onRestrictedDialogCancelled()
            return
        }
        proFlow.restrictedDialogCancelled()
    }

    fun optionsDialogConfirmed() {
        AppLog.i(
            TAG,
            "optionsDialogConfirmed, dnsFilter=${_state.value.dnsFilterEnabled}, aggressive=${_state.value.aggressiveMode}"
        )
        update { it.copy(showOptionsDialog = false) }

        if (_state.value.dnsFilterEnabled) {
            viewModelScope.launch {
                val seen: Boolean = prefs.hasSeenDnsWarning.first()
                if (!seen) {
                    AppLog.i(TAG, "showing DNS warning dialog")
                    update { it.copy(showDnsWarningDialog = true) }
                    return@launch
                }
                proFlow.proceedToChain()
            }
        } else {
            proFlow.proceedToChain()
        }
    }

    fun dnsWarningAccepted() {
        AppLog.i(TAG, "dnsWarningAccepted")
        update { it.copy(showDnsWarningDialog = false) }
        viewModelScope.launch { prefs.setHasSeenDnsWarning(true) }
        proFlow.proceedToChain()
    }

    fun dnsWarningDeclined() {
        AppLog.i(TAG, "dnsWarningDeclined — disabling DNS")
        update { it.copy(showDnsWarningDialog = false, dnsFilterEnabled = false) }
        viewModelScope.launch { prefs.setDnsFilterEnabled(false) }
        proFlow.proceedToChain()
    }

    fun optionsDialogCancelled() {
        AppLog.i(TAG, "optionsDialogCancelled")
        update { it.copy(showOptionsDialog = false) }
    }

    fun toggleDnsFilter(enabled: Boolean) {
        AppLog.i(TAG, "toggleDnsFilter: $enabled")
        update { it.copy(dnsFilterEnabled = enabled) }
        viewModelScope.launch { prefs.setDnsFilterEnabled(enabled) }
    }

    fun toggleAggressiveMode(enabled: Boolean) {
        AppLog.i(TAG, "toggleAggressiveMode: $enabled")
        update { it.copy(aggressiveMode = enabled) }
    }

    fun shizukuDialogInstall() = shizuku.dialogInstall()
    fun shizukuDialogOpenApp() = shizuku.dialogOpenApp()
    fun wizardSkip() = shizuku.wizardSkip()
    fun requestShizukuPermission() = shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
    fun onShizukuPermissionResult(granted: Boolean) = shizuku.onPermissionResult(granted)
    fun wizardCheck() = shizuku.wizardCheck()
    fun openShizukuSources() = shizuku.openSources()
    fun closeShizukuSources() = shizuku.closeSources()
    fun installFromSource(source: String) = shizuku.installFromSource(source)
    fun shizukuDialogLater() = shizuku.dialogLater()
    fun closeShizukuWizard() = shizuku.closeWizard()

    fun restoreOptimization() = proFlow.restoreOptimization()
    fun confirmReboot() = proFlow.confirmReboot()
    fun requestReboot() = proFlow.requestReboot()
    fun dismissRebootDialog() = proFlow.dismissRebootDialog()
    fun dismissRebootFailed() = proFlow.dismissRebootFailed()
    fun dismissRestoreFailed() = proFlow.dismissRestoreFailed()
    fun devModeDialogOpenAbout() = AppLog.i(TAG, "devModeDialog: open about phone")
    fun devModeDialogRetry() = proFlow.devModeDialogRetry()
    fun devModeDialogCancel() = proFlow.devModeDialogCancel()
    fun dismissFinalDialog() = update { it.copy(showFinalDialog = false) }

    /**
     * Открывает экран настроек Accessibility с deep link на конкретный сервис.
     * Делегирует PermissionFlowManager для единообразия.
     */
    fun openAccessibilitySettings() {
        AppLog.i(TAG, "openAccessibilitySettings: delegating to PermissionFlowManager")
        permissionFlow.openAccessibilityWithHint()
    }

    /**
     * Открывает экран настроек Overlay с подсказкой.
     * Делегирует PermissionFlowManager для единообразия.
     */
    fun openOverlaySettings() {
        AppLog.i(TAG, "openOverlaySettings: delegating to PermissionFlowManager")
        permissionFlow.openOverlayWithPointer()
    }

    private fun openAppInfoWithHint() {
        AppLog.i(TAG, "auto-redirect: opening app info with hint card")
        permissionFlow.openAppInfoWithSmartPointer(RestrictedLocation.UNKNOWN)
    }

    private fun openAccessibilityWithHint() {
        AppLog.i(TAG, "auto-redirect: opening accessibility services with hint card")
        permissionFlow.openAccessibilityWithHint()
    }

    /**
     * Показывает текстовую подсказку через OverlayService.
     *
     * ИСПРАВЛЕН КРИТИЧЕСКИЙ БАГ:
     * Раньше передавалась строка "hint" вместо OverlayService.EXTRA_HINT,
     * и не устанавливался action = ACTION_HINT. Хинты не показывались.
     */
    private fun showHint(text: String) {
        try {
            val intent = Intent(app, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HINT
                putExtra(OverlayService.EXTRA_HINT, text)
            }
            app.startService(intent)
            AppLog.i(TAG, "showHint: success")
        } catch (e: Exception) {
            AppLog.w(TAG, "showHint failed: ${e.message}")
        }
    }
}