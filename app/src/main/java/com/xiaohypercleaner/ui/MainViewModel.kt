package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.OptimizationOptions
import com.xiaohypercleaner.data.OptimizationReport
import com.xiaohypercleaner.service.AdbEnablerService
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
    val accessibilityAttempts: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private var flowActive = false
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

                    is OptimizationNotifier.Result.Idle -> {}
                }
            }
        }

        AppLog.i(TAG, "init completed")
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
        AppLog.i(TAG, "refreshStatuses: accessibility=$acc, overlay=$overlay")
        _state.update { it.copy(isAccessibilityEnabled = acc, isOverlayGranted = overlay) }
        if (flowActive) advance()
    }

    fun checkRestrictedSettingsOnResume() {
        AppLog.i(TAG, "checkRestrictedSettingsOnResume called")

        // Если мы пытались включить accessibility, но не получилось — показываем restricted dialog
        if (_state.value.accessibilityAttempts > 0 && !_state.value.isAccessibilityEnabled) {
            AppLog.i(
                TAG,
                "checkRestrictedSettingsOnResume: accessibility not enabled after ${_state.value.accessibilityAttempts} attempts"
            )
            if (needsRestrictedSettings()) {
                AppLog.i(TAG, "checkRestrictedSettingsOnResume: showing restricted dialog")
                _state.update { it.copy(showRestrictedDialog = true) }
            }
        }
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
                _state.update {
                    it.copy(
                        showOverlayDialog = false,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false,
                        accessibilityAttempts = 0
                    )
                }
                startChain()
            }
        }
    }

    fun dialogAgreed() {
        AppLog.i(TAG, "dialogAgreed")
        _state.update {
            it.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                accessibilityAttempts = _state.value.accessibilityAttempts + 1
            )
        }
    }

    fun dialogCancelled() {
        AppLog.i(TAG, "dialogCancelled")
        flowActive = false
        _state.update {
            it.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false
            )
        }
    }

    fun restrictedDialogAgreed() {
        AppLog.i(TAG, "restrictedDialogAgreed — opening app info settings")
        _state.update { it.copy(showRestrictedDialog = false) }
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled")
        flowActive = false
        _state.update {
            it.copy(
                showRestrictedDialog = false,
                accessibilityAttempts = 0
            )
        }
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
            AppLog.i(TAG, "startChain: pending flag set, opening accessibility settings")
            openAccessibilitySettingsAutomatically()
        }
    }

    private fun runLocal() {
        AppLog.i(TAG, "runLocal (fallback)")
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, progress = 0f) }
            val deps = XiaoHyperApp.testDeps ?: app.deps
            val engine = deps.newEngine()
            val options = OptimizationOptions(dnsFilter = _state.value.dnsFilterEnabled)
            val report = engine.optimize(options, callbacks())
            if (report.success) {
                prefs.setHiddenSettingsApplied(true)
                OptimizationNotifier.setSuccess(buildReportSummary(report))
            } else {
                OptimizationNotifier.setFailure(report.failedActions, buildReportSummary(report))
            }
            _state.update { it.copy(isWorking = false, isOptimized = report.success) }
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

    private fun needsRestrictedSettings(): Boolean {
        return Build.VERSION.SDK_INT >= 33 && !isFromKnownStore()
    }

    private fun isFromKnownStore(): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= 30) {
                app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getInstallerPackageName(app.packageName)
            }
            val result = installer in KNOWN_STORES
            AppLog.i(TAG, "isFromKnownStore: installer=$installer, result=$result")
            result
        } catch (e: Exception) {
            AppLog.w(TAG, "isFromKnownStore: exception: ${e.message}")
            false
        }
    }

    private fun openAccessibilitySettingsAutomatically() {
        AppLog.i(TAG, "openAccessibilitySettingsAutomatically: showing accessibility dialog")
        _state.update { it.copy(showAccessibilityDialog = true) }
    }

    companion object {
        private const val TAG = "MainVM"

        private val KNOWN_STORES = listOf(
            "com.android.vending",
            "com.xiaomi.mimarket",
            "ru.ozon.app.android",
            "com.retailstore.android",
            "com.huawei.appmarket",
            "com.samsung.android.app.galaxystore"
        )
    }
}