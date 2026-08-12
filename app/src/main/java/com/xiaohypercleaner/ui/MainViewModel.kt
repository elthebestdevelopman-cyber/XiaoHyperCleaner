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
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.OptimizationOptions
import com.xiaohypercleaner.data.OptimizationReport
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationNotifier
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
    val dnsFilterEnabled: Boolean = false,
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
    private var flowActive = false

    // Машина состояний автопереходов при блокировке службы
    private enum class Redirect { NONE, ACCESSIBILITY, APP_INFO }

    private var lastRedirect = Redirect.NONE
    private var restrictedFlowStarted = false

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        AppLog.i(TAG, "init started")

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

    // ===== Метки для машины автопереходов (вызывает MainActivity) =====

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

    // ===== Автопереходы с карточками =====

    private fun showHint(text: String) {
        try {
            val intent = Intent(app, OverlayService::class.java)
            intent.putExtra("hint", text)
            app.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to show hint: ${e.message}")
        }
    }

    /** Пользователю сказали «нет доступа» — сразу кидаем в настройки приложения с карточкой */
    private fun openAppInfoWithHint() {
        AppLog.i(TAG, "auto-redirect: opening app info with hint card")
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            showHint(app.getString(R.string.hint_restricted))
        } catch (e: Exception) {
            AppLog.w(TAG, "auto-redirect to app info failed: ${e.message}")
            _state.update { it.copy(showRestrictedDialog = true) }
        }
    }

    /** Вернулся из настроек приложения — снова кидаем в установленные службы с карточкой */
    private fun openAccessibilityWithHint() {
        AppLog.i(TAG, "auto-redirect: opening accessibility services with hint card")
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
                AppLog.w(TAG, "auto-redirect to accessibility failed: ${e2.message}")
                _state.update { it.copy(showAccessibilityDialog = true) }
            }
        }
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
            "refreshStatuses: accessibility=$acc (was ${prevState.previousAccessibility}), overlay=$overlay (was ${prevState.previousOverlay}), accAttempts=${prevState.accessibilityAttempts}, overlayAttempts=${prevState.overlayAttempts}, lastRedirect=$lastRedirect"
        )

        _state.update {
            it.copy(
                isAccessibilityEnabled = acc,
                isOverlayGranted = overlay,
                previousAccessibility = acc,
                previousOverlay = overlay
            )
        }

        // Служба включилась — продолжаем цепочку, сбрасываем автопереходы
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

        // ===== Служба НЕ включилась после попытки — машина автопереходов =====
        if (!acc && prevState.accessibilityAttempts > 0 && flowActive) {
            AppLog.i(TAG, "refreshStatuses: accessibility not enabled after attempt")
            when {
                // Пришли из спец.возможностей без включения = получили «нет доступа»
                lastRedirect == Redirect.ACCESSIBILITY && !restrictedFlowStarted -> {
                    AppLog.i(TAG, "refreshStatuses: denied — auto-redirect to app info")
                    restrictedFlowStarted = true
                    lastRedirect = Redirect.APP_INFO
                    openAppInfoWithHint()
                }

                // Пришли из настроек приложения — разрешено или нет, снова в службы
                lastRedirect == Redirect.APP_INFO -> {
                    AppLog.i(
                        TAG,
                        "refreshStatuses: back from app info — auto-redirect to accessibility"
                    )
                    lastRedirect = Redirect.ACCESSIBILITY
                    openAccessibilityWithHint()
                }

                // Предохранитель: объясняем диалогом внутри приложения
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
        if (_state.value.isWorking) return
        _state.update { it.copy(showOptionsDialog = true) }
    }

    fun optionsDialogConfirmed() {
        AppLog.i(TAG, "optionsDialogConfirmed, dnsFilter=${_state.value.dnsFilterEnabled}")
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
        AppLog.i(TAG, "dialogAgreed")
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
        } else if (currentState.showOverlayDialog) {
            _state.update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    overlayAttempts = it.overlayAttempts + 1
                )
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
    }

    fun dialogCancelled() {
        AppLog.i(TAG, "dialogCancelled")
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
                showAccessibilityDialog = false
            )
        }
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled")
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
            val deps = XiaoHyperApp.testDeps ?: app.deps
            val ok = deps.newEngine().reboot()
            _state.update { it.copy(isWorking = false, rebootFailed = !ok) }
        }
    }

    private fun startChain() {
        AppLog.i(
            TAG,
            "startChain: setting pending flag, dnsFilter=${_state.value.dnsFilterEnabled}"
        )
        viewModelScope.launch {
            prefs.setPendingOptimization(true)
            prefs.setDnsFilterEnabled(_state.value.dnsFilterEnabled)
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
            _state.update { it.copy(isWorking = true, progress = 0f) }
            val deps = XiaoHyperApp.testDeps ?: app.deps
            val ok = deps.newEngine().restore(callbacks())
            if (ok) {
                prefs.setHiddenSettingsApplied(false)
                _state.update { it.copy(isWorking = false, isOptimized = false) }
            } else {
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
        return buildString {
            append("✅ Отключено сервисов: ${report.disabledPackages.size}\n")
            append("✅ Применено параметров: ${report.appliedSettings.size}\n")
            if (report.failedActions.isNotEmpty()) {
                append("⚠️ Не удалось: ${report.failedActions.joinToString(", ")}\n")
            }
            append(if (report.success) "✅ Все проверки пройдены" else "❌ Проверка не пройдена")
        }
    }

    private fun openAccessibilitySettingsAutomatically() {
        AppLog.i(TAG, "openAccessibilitySettingsAutomatically: showing accessibility dialog")
        ChainFlagsAutoReturn()
        _state.update { it.copy(showAccessibilityDialog = true) }
    }

    private fun ChainFlagsAutoReturn() {
        com.xiaohypercleaner.service.ChainFlags.waitingAccessibilityReturn = true
    }

    companion object {
        private const val TAG = "MainVM"
    }
}