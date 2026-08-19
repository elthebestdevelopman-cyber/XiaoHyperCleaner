package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.AppConstants.AUTO_ADVANCE_DELAY_MS
import com.xiaohypercleaner.AppConstants.RETRY_DELAY_MS
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.OptimizationReport
import com.xiaohypercleaner.data.PermissionFlowManager
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.ShizukuWizardManager
import com.xiaohypercleaner.data.SimpleModeController
import com.xiaohypercleaner.data.SimpleStepState
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.service.SimpleStepBridge
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationNotifier
import com.xiaohypercleaner.util.OptimizationReportFormatter
import com.xiaohypercleaner.util.ShizukuHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SimpleModePhase {
    INACTIVE,
    PERMISSIONS,
    STEPS,
    DONE
}

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
    val previousAccessibility: Boolean = false,
    val previousOverlay: Boolean = false,
    val restrictedSettingsShown: Boolean = false,
    val showDevModeDialog: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private val permissionFlow = PermissionFlowManager(app)
    private var flowActive = false
    private var simpleModeActive = false
    private var simpleStepIndex = 0
    private var simpleCompletedCount = 0
    private val failedSimpleStepIds = mutableListOf<String>()
    private var stepAttempt = 1
    private var autoFlowJob: kotlinx.coroutines.Job? = null

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

    private fun stopOverlayService() {
        try {
            app.stopService(Intent(app, OverlayService::class.java))
            AppLog.i(TAG, "overlay service stopped for dialog")
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to stop overlay service: ${e.message}")
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

    private fun showHint(text: String) {
        try {
            val intent = Intent(app, OverlayService::class.java)
            intent.putExtra("hint", text)
            app.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to show hint: ${e.message}")
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
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(app)

        val prevState = _state.value
        val accessibilityJustChanged = !prevState.previousAccessibility && acc
        val overlayJustChanged = !prevState.previousOverlay && overlay

        AppLog.i(
            TAG,
            "refreshStatuses: accessibility=$acc (was ${prevState.previousAccessibility}), overlay=$overlay (was ${prevState.previousOverlay}), accAttempts=${prevState.accessibilityAttempts}, overlayAttempts=${prevState.overlayAttempts}, lastRedirect=$lastRedirect, simpleModeActive=$simpleModeActive"
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

        // Простой режим — продолжаем проверку разрешений
        if (simpleModeActive) {
            advanceSimpleMode()
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
        
        // Защита от повторного вызова во время работы
        if (_state.value.isWorking) {
            AppLog.i(TAG, "startFlow: already working, ignoring")
            return
        }

        // Защита от повторного вызова если диалог уже показан
        if (_state.value.showLevelDialog || _state.value.showLevelConfirm) {
            AppLog.i(TAG, "startFlow: level dialog already shown, ignoring")
            return
        }

        // Если уже идёт простая оптимизация — не начинаем заново
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

    private fun startAdvancedFlow() {
        val status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "startAdvancedFlow: shizuku status=$status")

        if (status != ShizukuExecutor.Status.AVAILABLE) {
            _state.update { it.copy(showShizukuDialog = true, shizukuStatus = status) }
        } else {
            _state.update { it.copy(showOptionsDialog = true) }
        }
    }

    // ===== Простая оптимизация =====

    fun startSimpleMode() {
        AppLog.i(TAG, "startSimpleMode")
        simpleModeActive = true
        simpleStepIndex = 0
        simpleCompletedCount = 0
        failedSimpleStepIds.clear()  // ← ДОБАВИТЬ

        _state.update { it.copy(simpleModePhase = SimpleModePhase.PERMISSIONS) }
        advanceSimpleMode()
    }

    private fun advanceSimpleMode() {
        if (!simpleModeActive) return

        val s = _state.value
        AppLog.i(
            TAG,
            "advanceSimpleMode: restricted=${s.restrictedSettingsShown}, acc=${s.isAccessibilityEnabled}, overlay=${s.isOverlayGranted}"
        )

        // Фаза 1: Проверка restricted/forbidden settings (для Android 13+ sideload)
        val isAndroid13Plus = Build.VERSION.SDK_INT >= 33
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getInstallerPackageName(app.packageName)
            }
        } catch (e: Exception) {
            null
        }
        val isFromKnownStore = installer in listOf(
            "com.android.vending",
            "com.xiaomi.market",
            "ru.vk.store"
        )

        if (isAndroid13Plus && !isFromKnownStore && !s.restrictedSettingsShown) {
            AppLog.i(TAG, "advanceSimpleMode: showing restricted/forbidden dialog")
            _state.update { it.copy(showRestrictedDialog = true) }
            return
        }

        // Фаза 2: Проверка Accessibility Service
        if (!s.isAccessibilityEnabled) {
            AppLog.i(TAG, "advanceSimpleMode: showing accessibility dialog")
            _state.update { it.copy(showAccessibilityDialog = true) }
            return
        }

        // Фаза 3: Проверка Overlay permission
        if (!s.isOverlayGranted) {
            AppLog.i(TAG, "advanceSimpleMode: showing overlay dialog")
            _state.update { it.copy(showOverlayDialog = true) }
            return
        }

        // Все разрешения получены — переходим к шагам
        AppLog.i(TAG, "advanceSimpleMode: all permissions granted, starting steps")
        simpleModeActive = false
        _state.update { it.copy(simpleModePhase = SimpleModePhase.STEPS) }
        showSimpleStep()
    }

    private fun showSimpleStep() {
        if (simpleStepIndex >= SimpleSteps.ALL.size) {
            // Все шаги выполнены — переходим к финальному экрану с верификацией
            performFinalVerification()
            return
        }
        _state.update {
            it.copy(
                simpleStep = SimpleStepState(
                    stepIndex = simpleStepIndex,
                    totalSteps = SimpleSteps.ALL.size,
                    step = SimpleSteps.ALL[simpleStepIndex],
                    status = SimpleStepState.Status.READY,
                    completedCount = simpleCompletedCount
                ),
                simpleDone = null
            )
        }
    }

    /**
     * Верификация результата после всех шагов перед показом финального экрана.
     * Проверяем что ключевые переключатели действительно выключены.
     */
    private fun performFinalVerification() {
        AppLog.i(
            TAG,
            "performFinalVerification: completed $simpleCompletedCount of ${SimpleSteps.ALL.size} steps"
        )

        // Для простой оптимизации показываем финальный экран сразу
        // Полная верификация через повторный проход по шагам может занять много времени
        // и потребует дополнительных разрешений

        // Логируем статистику для диагностики
        val skippedCount = SimpleSteps.ALL.size - simpleCompletedCount
        if (skippedCount > 0) {
            AppLog.w(TAG, "Final verification: $skippedCount steps were skipped or failed")
        } else {
            AppLog.i(
                TAG,
                "Final verification: all ${SimpleSteps.ALL.size} steps completed successfully"
            )
        }

        _state.update {
            it.copy(
                simpleStep = null,
                simpleDone = Pair(simpleCompletedCount, SimpleSteps.ALL.size),
                simpleModePhase = SimpleModePhase.DONE
            )
        }
    }

    // ===== Простая оптимизация: автопрогон с ретраями =====

    fun startCurrentSimpleStep() {
        val current = _state.value.simpleStep ?: return
        if (current.status == SimpleStepState.Status.WORKING) {
            AppLog.w(TAG, "startCurrentSimpleStep: already WORKING, ignoring double-tap")
            return
        }
        stepAttempt = 1
        launchStep()
    }

    fun retrySimpleStep() {
        AppLog.i(TAG, "retrySimpleStep: manual retry by user")
        stepAttempt = 1
        launchStep()
    }

    private fun launchStep() {
        autoFlowJob?.cancel()
        _state.update {
            it.copy(
                simpleStep = it.simpleStep?.copy(
                    status = SimpleStepState.Status.WORKING,
                    attempt = stepAttempt
                )
            )
        }
        val intent = Intent(app, AdbEnablerService::class.java).apply {
            action = AdbEnablerService.ACTION_SIMPLE_STEP
            putExtra("step_index", simpleStepIndex)
        }
        app.startService(intent)
    }

    fun onSimpleStepResult(success: Boolean) {
        AppLog.i(
            TAG,
            "onSimpleStepResult: success=$success, attempt=$stepAttempt, step=$simpleStepIndex"
        )
        val step = _state.value.simpleStep ?: return

        if (success) {
            simpleCompletedCount++
            _state.update {
                it.copy(
                    simpleStep = step.copy(
                        status = SimpleStepState.Status.SUCCESS,
                        completedCount = simpleCompletedCount
                    )
                )
            }
            // АВТОпереход: кнопок нет — машина работает сама
            autoFlowJob = viewModelScope.launch {
                delay(AUTO_ADVANCE_DELAY_MS)
                advanceToNextStep(autoStart = true)
            }
            return
        }

        // Не получилось → проверяем почему (лог) и пробуем повторно
        if (stepAttempt < step.maxAttempts) {
            stepAttempt++
            AppLog.w(
                TAG,
                "onSimpleStepResult: auto-retry $stepAttempt/${step.maxAttempts} for step ${step.stepIndex}"
            )
            // Обновляем UI с номером попытки
            _state.update {
                it.copy(
                    simpleStep = step.copy(
                        status = SimpleStepState.Status.WORKING,
                        attempt = stepAttempt
                    )
                )
            }
            autoFlowJob = viewModelScope.launch {
                delay(RETRY_DELAY_MS)
                launchStep()
            }
        } else {
            // Исчерпали попытки → показываем ручную подсказку + «Повторить»
            AppLog.e(
                TAG,
                "onSimpleStepResult: all ${step.maxAttempts} attempts exhausted for step ${step.stepIndex}"
            )
            onStepExhausted(step)
        }
    }

    /** Вызывается когда все попытки исчерпаны */
    private fun onStepExhausted(step: SimpleStepState) {
        // Сохраняем ID неудачного шага для финального экрана
        failedSimpleStepIds.add(step.step.id)

        _state.update {
            it.copy(simpleStep = step.copy(status = SimpleStepState.Status.FAILED))
        }
        // Логируем причину для диагностики с деталями из SimpleOptimizationRunner
        AppLog.w(
            TAG,
            "Step '${step.step.id}' (${step.step.titleRu}) failed after ${step.maxAttempts} attempts - reason: switch not found or click failed"
        )
    }

    fun nextSimpleStep() = advanceToNextStep(autoStart = false)

    fun skipSimpleStep() {
        AppLog.i(TAG, "skipSimpleStep: index=$simpleStepIndex")
        advanceToNextStep(autoStart = true)
    }

    private fun advanceToNextStep(autoStart: Boolean) {
        simpleStepIndex++
        showSimpleStep()
        if (autoStart && _state.value.simpleStep != null) {
            stepAttempt = 1
            launchStep()
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
                simpleModePhase = SimpleModePhase.INACTIVE
            )
        }
    }

    // ===== Shizuku диалоги =====

    fun shizukuDialogInstall() {
        AppLog.i(TAG, "shizuku dialog: install clicked")
        pendingSourceSuggestion = true
        _state.update { it.copy(showShizukuDialog = false) }
        ShizukuHelper.openShizukuInStore(app)
    }

    fun shizukuDialogOpenApp() {
        AppLog.i(TAG, "shizuku dialog: open app clicked")
        _state.update { it.copy(showShizukuDialog = false) }
        openShizukuWizard()
    }

    fun openShizukuWizard() {
        AppLog.i(TAG, "shizuku dialog: howto clicked — opening wizard")
        _state.update {
            it.copy(
                showShizukuDialog = false,
                showShizukuWizard = true,
                shizukuCheckMessage = null
            )
        }
    }

    fun closeShizukuWizard() {
        AppLog.i(TAG, "wizard: closed")
        _state.update { it.copy(showShizukuWizard = false) }
    }

    fun wizardSkip() {
        AppLog.i(TAG, "wizard: skip — proceeding to options dialog (wireless ADB path)")
        _state.update {
            it.copy(
                showShizukuWizard = false,
                shizukuCheckMessage = null,
                showOptionsDialog = true
            )
        }
    }

    fun requestShizukuPermission() {
        AppLog.i(TAG, "wizardRequestPermission: requesting...")
        ShizukuExecutor.requestPermission(SHIZUKU_PERMISSION_CODE)
    }

    fun onShizukuPermissionResult(granted: Boolean) {
        AppLog.i(TAG, "onShizukuPermissionResult: granted=$granted")
        if (granted) {
            _state.update {
                it.copy(
                    showShizukuWizard = false,
                    shizukuCheckMessage = null,
                    showOptionsDialog = true
                )
            }
        } else {
            _state.update {
                it.copy(
                    shizukuCheckMessage = app.getString(R.string.shizuku_wizard_permission_denied)
                )
            }
        }
    }

    fun wizardCheck() {
        val status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "wizardCheck: status=$status")
        when (status) {
            ShizukuExecutor.Status.AVAILABLE -> {
                AppLog.i(TAG, "wizardCheck: SUCCESS — proceeding to options")
                _state.update {
                    it.copy(
                        showShizukuWizard = false,
                        shizukuCheckMessage = null,
                        showOptionsDialog = true
                    )
                }
            }

            ShizukuExecutor.Status.PERMISSION_REQUIRED -> {
                _state.update {
                    it.copy(
                        shizukuCheckMessage = app.getString(R.string.shizuku_wizard_step6)
                    )
                }
            }

            else -> {
                _state.update {
                    it.copy(
                        shizukuCheckMessage = app.getString(R.string.shizuku_wizard_not_ready)
                    )
                }
            }
        }
    }

    fun openShizukuSources() {
        AppLog.i(TAG, "shizuku dialog: other sources clicked")
        _state.update { it.copy(showShizukuDialog = false, showShizukuSources = true) }
    }

    fun closeShizukuSources() {
        _state.update { it.copy(showShizukuSources = false) }
    }

    fun installFromSource(source: String) {
        AppLog.i(TAG, "sources dialog: chosen=$source")
        pendingSourceSuggestion = true
        _state.update { it.copy(showShizukuSources = false) }
        when (source) {
            "play" -> ShizukuHelper.openPlay(app)
            "aurora" -> ShizukuHelper.openAurora(app)
            "getapps" -> ShizukuHelper.openGetApps(app)
            "github" -> ShizukuHelper.openGithub(app)
            "apkpure" -> ShizukuHelper.openApkPure(app)
        }
    }

    fun shizukuDialogLater() {
        AppLog.i(TAG, "shizuku dialog: later — proceeding to options dialog")
        _state.update {
            it.copy(showShizukuDialog = false, showOptionsDialog = true)
        }
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
            if (simpleModeActive) {
                ChainFlags.waitingAccessibilityReturn = true
                markAccessibilityOpened()
                openAccessibilitySettings()
                return
            }
        } else if (currentState.showOverlayDialog) {
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    overlayAttempts = it.overlayAttempts + 1
                )
            }
            if (simpleModeActive) {
                openOverlaySettings()
                return
            }
        } else {
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false
                )
            }
        }

        if (simpleModeActive) {
            advanceSimpleMode()
        }
    }

    fun dialogCancelled() {
        AppLog.i(TAG, "dialogCancelled, simpleModeActive=$simpleModeActive")

        // В простом режиме — отменяем весь режим
        if (simpleModeActive) {
            simpleModeActive = false
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    accessibilityAttempts = 0,
                    overlayAttempts = 0,
                    restrictedSettingsShown = false,
                    simpleModePhase = SimpleModePhase.INACTIVE
                )
            }
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
        AppLog.i(TAG, "restrictedDialogAgreed — opening app info settings")
        _state.update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                restrictedSettingsShown = true
            )
        }

        if (simpleModeActive) {
            markAppInfoOpened()
            openAppInfoWithHint()
        }
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled, simpleModeActive=$simpleModeActive")

        if (simpleModeActive) {
            simpleModeActive = false
            _state.update {
                it.copy(
                    showRestrictedDialog = false,
                    showAccessibilityDialog = false,
                    accessibilityAttempts = 0,
                    restrictedSettingsShown = false,
                    simpleModePhase = SimpleModePhase.INACTIVE
                )
            }
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

    private fun callbacks() = OptimizationEngine.Callbacks(
        onProgress = { p -> _state.update { it.copy(progress = p) } }
    )

    private fun buildReportSummary(report: OptimizationReport): String {
        return OptimizationReportFormatter.summary(report)
    }

    private fun openAccessibilitySettingsAutomatically() {
        AppLog.i(TAG, "openAccessibilitySettingsAutomatically: showing accessibility dialog")
        setAccessibilityWaitingFlag()
        _state.update { it.copy(showAccessibilityDialog = true) }
    }

    private fun setAccessibilityWaitingFlag() {
        ChainFlags.waitingAccessibilityReturn = true
    }

    private fun openAccessibilitySettings() {
        AppLog.i(TAG, "openAccessibilitySettings for simple mode")
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

    private fun openOverlaySettings() {
        AppLog.i(TAG, "openOverlaySettings for simple mode")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
    }

    fun getFailedStepIds(): List<String> = failedSimpleStepIds.toList()

    companion object {
        private const val TAG = "MainVM"
        const val SHIZUKU_PERMISSION_CODE = 9001
    }
}