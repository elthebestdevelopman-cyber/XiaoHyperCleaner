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
import com.xiaohypercleaner.ui.vm.ProFlowController
import com.xiaohypercleaner.ui.vm.ShizukuUiController
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationNotifier
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
 * Тонкий фасад главного экрана.
 *
 * Делегаты:
 *  - [SimpleModeController] — машина состояний Simple Mode (data-слой)
 *  - [ShizukuUiController]  — Shizuku-диалоги, мастер, источники
 *  - [ProFlowController]    — PRO-цепочка: редиректы, цепочка, откат, перезагрузка
 *
 * ИСПРАВЛЕНО в этой версии:
 *  1. SimpleStepBridge.onResult / onSkipped регистрируются ОДИН РАЗ в init
 *     (раньше onSkipped перерегистрировался внутри onResult — критическая ошибка)
 *  2. OverlayController.setOnCancel / setOnResultClose регистрируются ОДИН РАЗ
 *  3. Используется AdbEnablerService.instance вместо несуществующего поля adbEnablerService
 *  4. closeSimpleMode() вызывает OverlayController.hide(app) — убирает оверлей робокота
 *  5. Убраны дубликаты регистраций
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainVM"
        const val SHIZUKU_PERMISSION_CODE = 9001
    }

    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private val permissionFlow = PermissionFlowManager(app)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private fun update(transform: (MainUiState) -> MainUiState) = _state.update(transform)

    // ── Делегаты ──────────────────────────────────────────────────────
    val simpleController = SimpleModeController(app, permissionFlow) { mergeSimpleState(it) }

    private val shizuku = ShizukuUiController(app, ::update)

    private val proFlow = ProFlowController(
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

    // ── Локальные флаги Simple Mode / шагов ──────────────────────────
    private var simpleModeActive = false
    private var stepAttempt = 1
    private var autoFlowJob: Job? = null
    private val failedSimpleStepIds = mutableListOf<String>()

    init {
        AppLog.i(TAG, "init started")

        // ═══════════════════════════════════════════════════════════════
        // ИСПРАВЛЕНО: все регистрации Bridge/Overlay — ОДИН РАЗ, в init
        // ═══════════════════════════════════════════════════════════════

        // Результат шага (success/failure) — вызывается из AdbEnablerService
        SimpleStepBridge.onResult = { success ->
            AppLog.i(TAG, "SimpleStepBridge result: $success")
            onSimpleStepResult(success)
        }

        // Пропуск шага (приложение не установлено) — НЕ ошибка, идём дальше
        SimpleStepBridge.onSkipped = { stepId ->
            AppLog.i(TAG, "SimpleStepBridge skipped (app not installed): $stepId")
            simpleController.onStepSkipped(stepId)
        }

        // Кнопка "Отменить" в оверлее робокота
        OverlayController.setOnCancel {
            AppLog.i(TAG, "automation cancelled by user via overlay")
            // Останавливаем runner через singleton-инстанс сервиса
            AdbEnablerService.instance?.cancelRunner()
            OverlayController.hide(app)
            closeSimpleMode()
        }

        // Закрытие финального оверлея с результатами
        OverlayController.setOnResultClose {
            AppLog.i(TAG, "result overlay closed by user")
            OverlayController.hide(app)
            closeSimpleMode()
        }

        // ═══════════════════════════════════════════════════════════════
        // Подписки на Flow из PreferencesManager и OptimizationNotifier
        // ═══════════════════════════════════════════════════════════════

        viewModelScope.launch {
            prefs.isHiddenSettingsApplied.collect { applied ->
                AppLog.i(TAG, "isHiddenSettingsApplied changed to $applied")
                update { it.copy(isOptimized = applied) }
            }
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
                        stopOverlayService()
                        update { it.copy(showDevModeDialog = true) }
                    }

                    is OptimizationNotifier.Result.Idle -> {}
                }
            }
        }
        AppLog.i(TAG, "init completed")
    }

    override fun onCleared() {
        OverlayController.hide(app)
        simpleController.destroy()
        autoFlowJob?.cancel()
        super.onCleared()
    }

    // ═══════════════════════════════════════════════════════════════
    // Слияние состояния Simple Mode
    // ═══════════════════════════════════════════════════════════════

    private fun mergeSimpleState(s: SimpleModeController.SimpleModeState) {
        val running =
            s.active || s.step != null || s.done != null || s.phase != SimpleModePhase.INACTIVE

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

        simpleModeActive = running
    }

    // ═══════════════════════════════════════════════════════════════
    // Обновление статусов (оркестрация Simple + PRO)
    // ═══════════════════════════════════════════════════════════════

    fun refreshStatuses() {
        AppLog.i(TAG, "refreshStatuses called")

        val component = ComponentName(app, AdbEnablerService::class.java).flattenToString()
        val acc = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
        val overlay = Settings.canDrawOverlays(app)

        val prevState = _state.value

        AppLog.i(
            TAG,
            "refreshStatuses: accessibility=$acc (was ${prevState.previousAccessibility}), overlay=$overlay (was ${prevState.previousOverlay}), simpleModeActive=$simpleModeActive"
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

        // Simple Mode имеет приоритет; PRO-ветка ниже не выполняется
        if (simpleModeActive) {
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

    fun onBatteryOptimizationReturn() {
        AppLog.i(TAG, "onBatteryOptimizationReturn")
        simpleController.onBatteryReturn()
    }

    // ═══════════════════════════════════════════════════════════════
    // Выбор уровня
    // ═══════════════════════════════════════════════════════════════

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

        update { it.copy(showLevelDialog = true) }
    }

    fun onLevelChosen(level: OptimizationMode) {
        AppLog.i(TAG, "onLevelChosen: $level")
        update {
            it.copy(showLevelDialog = false, showLevelConfirm = true, selectedLevel = level)
        }
    }

    fun confirmLevelStart() {
        val level = _state.value.selectedLevel ?: OptimizationMode.SIMPLE
        AppLog.i(TAG, "confirmLevelStart: $level")
        update { it.copy(showLevelConfirm = false, selectedLevel = null) }

        when (level) {
            OptimizationMode.SIMPLE -> startSimpleMode()
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
        simpleModeActive = true
        failedSimpleStepIds.clear()
        stepAttempt = 1
        simpleController.start()
    }

    private fun startAdvancedFlow() {
        val status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "startAdvancedFlow: shizuku status=$status")

        if (status != ShizukuExecutor.Status.AVAILABLE) {
            shizuku.showDialog(status)
        } else {
            shizuku.showOptionsDialog()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Шаги Simple Mode (результаты, ретраи)
    // ═══════════════════════════════════════════════════════════════

    fun onSimpleStepResult(success: Boolean) {
        AppLog.i(TAG, "onSimpleStepResult: success=$success, attempt=$stepAttempt")
        val step = _state.value.simpleStep ?: return

        if (success) {
            simpleController.onStepResult(true, attempt = stepAttempt)
            autoFlowJob = viewModelScope.launch {
                delay(AUTO_ADVANCE_DELAY_MS.milliseconds)
                advanceToNextStep(autoStart = true)
            }
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
            simpleController.onStepResult(
                success = false,
                attempt = stepAttempt,
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

    /**
     * ИСПРАВЛЕНО: теперь при закрытии Simple Mode:
     *  1. Останавливается авто-поток
     *  2. Убирается оверлей робокота (OverlayController.hide)
     *  3. Сбрасывается состояние UI
     */
    fun closeSimpleMode() {
        AppLog.i(TAG, "closeSimpleMode")
        autoFlowJob?.cancel()
        simpleModeActive = false

        // НОВОЕ: убираем оверлей робокота
        OverlayController.hide(app)

        update {
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
                showLocationDialog = false,
                showPermissionFallbackDialog = false,
                stuckPhase = null,
                showRestrictedSettingsScreen = false,
                showBatteryDialog = false
            )
        }
    }

    fun getFailedStepIds(): List<String> = failedSimpleStepIds.toList()

    // ═══════════════════════════════════════════════════════════════
    // Simple Mode: обёртки UI-действий (делегирование в контроллер)
    // ═══════════════════════════════════════════════════════════════

    fun startCurrentSimpleStep() = simpleController.startCurrentStep()
    fun nextSimpleStep() = onSimpleStepResult(true)

    fun skipSimpleStep() {
        val stepId = _state.value.simpleStep?.step?.id ?: "unknown"
        if (!failedSimpleStepIds.contains(stepId)) failedSimpleStepIds.add(stepId)
        onSimpleStepResult(false)
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
        simpleModeActive = false
        simpleController.onFallbackCancelled()
    }

    // ═══════════════════════════════════════════════════════════════
    // Диалоги разрешений (Simple + PRO)
    // ═══════════════════════════════════════════════════════════════

    fun dialogAgreed() {
        AppLog.i(TAG, "dialogAgreed, simpleModeActive=$simpleModeActive")

        if (simpleModeActive) {
            val currentState = _state.value
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
        AppLog.i(TAG, "dialogCancelled, simpleModeActive=$simpleModeActive")

        if (simpleModeActive) {
            simpleModeActive = false
            simpleController.onDialogCancelled()
            return
        }
        proFlow.dialogCancelled()
    }

    fun restrictedDialogAgreed() {
        AppLog.i(TAG, "restrictedDialogAgreed")
        if (simpleModeActive) {
            simpleController.onRestrictedDialogAgreed()
            return
        }
        proFlow.restrictedDialogAgreed()
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled")
        if (simpleModeActive) {
            simpleModeActive = false
            simpleController.onRestrictedDialogCancelled()
            return
        }
        proFlow.restrictedDialogCancelled()
    }

    // ═══════════════════════════════════════════════════════════════
    // Опции PRO (DNS / aggressive)
    // ═══════════════════════════════════════════════════════════════

    fun optionsDialogConfirmed() {
        AppLog.i(
            TAG,
            "optionsDialogConfirmed, dnsFilter=${_state.value.dnsFilterEnabled}, aggressive=${_state.value.aggressiveMode}"
        )
        update { it.copy(showOptionsDialog = false) }

        if (_state.value.dnsFilterEnabled) {
            viewModelScope.launch {
                val seen = prefs.hasSeenDnsWarning.first()
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

    // ═══════════════════════════════════════════════════════════════
    // Shizuku: обёртки (делегирование в ShizukuUiController)
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // PRO: откат / перезагрузка / dev-mode (делегирование в ProFlowController)
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    // Открытие системных экранов + подсказки (используются делегатами)
    // ═══════════════════════════════════════════════════════════════

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
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${app.packageName}".toUri()
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
}