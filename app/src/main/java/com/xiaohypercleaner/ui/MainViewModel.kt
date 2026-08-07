package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isOptimized: Boolean = false,
    val isWorking: Boolean = false,
    val progress: Float = 0f,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val showRestrictedDialog: Boolean = false,
    val showAccessibilityDialog: Boolean = false,
    val showOverlayDialog: Boolean = false,
    val showRebootDialog: Boolean = false,
    val rebootFailed: Boolean = false,
    val restoreFailed: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private var flowActive = false
    private var restrictedDone = false
    private var awaitingRestrictedReturn = false
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.isHiddenSettingsApplied.collect { applied ->
                _state.update { it.copy(isOptimized = applied) }
            }
        }
    }

    fun refreshStatuses() {
        val component = ComponentName(app, AdbEnablerService::class.java).flattenToString()
        val acc = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
        val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(app)
        _state.update { it.copy(isAccessibilityEnabled = acc, isOverlayGranted = overlay) }
        if (awaitingRestrictedReturn) {
            awaitingRestrictedReturn = false
            restrictedDone = true
            AppLog.i("Flow", "returned from restricted settings")
        }
        if (flowActive) advance()
    }

    fun startFlow() {
        if (_state.value.isWorking) return
        flowActive = true
        AppLog.i("Flow", "start, restrictedNeeded=${needsRestricted()}")
        if (needsRestricted() && !restrictedDone) {
            _state.update { it.copy(showRestrictedDialog = true) }
        } else {
            advance()
        }
    }

    fun openRestrictedSettings() {
        _state.update { it.copy(showRestrictedDialog = false) }
        awaitingRestrictedReturn = true
    }

    fun skipRestricted() {
        restrictedDone = true
        _state.update { it.copy(showRestrictedDialog = false) }
        if (flowActive) advance()
    }

    private fun advance() {
        val s = _state.value
        when {
            !s.isAccessibilityEnabled ->
                _state.update { it.copy(showAccessibilityDialog = true, showOverlayDialog = false) }

            !s.isOverlayGranted ->
                _state.update { it.copy(showOverlayDialog = true, showAccessibilityDialog = false) }

            else -> {
                flowActive = false
                _state.update {
                    it.copy(
                        showOverlayDialog = false,
                        showAccessibilityDialog = false
                    )
                }
                startChain()
            }
        }
    }

    fun dialogAgreed() {
        _state.update { it.copy(showAccessibilityDialog = false, showOverlayDialog = false) }
    }

    fun dialogCancelled() {
        flowActive = false
        _state.update { it.copy(showAccessibilityDialog = false, showOverlayDialog = false) }
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
            AppLog.i("Flow", "reboot requested")
            val ok = app.deps.newEngine().reboot()
            _state.update { it.copy(isWorking = false, rebootFailed = !ok) }
        }
    }

    private fun needsRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getInstallerPackageName(app.packageName)
            }
            installer !in KNOWN_STORES
        } catch (_: Exception) {
            true
        }
    }

    private fun startChain() {
        val intent = Intent(app, AdbEnablerService::class.java)
        intent.action = AdbEnablerService.ACTION_START_CHAIN
        try {
            app.startService(intent)
        } catch (e: Exception) {
            AppLog.e("Flow", "startService failed: ${e.message}")
            runLocal()
        }
    }

    private fun runLocal() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, progress = 0f) }
            val ok = app.deps.newEngine().optimize(callbacks())
            AppLog.i("Flow", "local optimize result=$ok")
            if (ok) prefs.setHiddenSettingsApplied(true)
            _state.update { it.copy(isWorking = false, isOptimized = ok) }
        }
    }

    fun restoreOptimization() {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, progress = 0f) }
            val ok = app.deps.newEngine().restore(callbacks())
            AppLog.i("Flow", "restore result=$ok")
            if (ok) {
                prefs.setHiddenSettingsApplied(false)
                _state.update { it.copy(isWorking = false, isOptimized = false) }
            } else {
                _state.update { it.copy(isWorking = false, restoreFailed = true) }
            }
        }
    }

    private fun callbacks() = OptimizationEngine.Callbacks(
        onProgress = { p -> _state.update { it.copy(progress = p) } },
        onError = { msg -> AppLog.e("Engine", msg) }
    )

    private companion object {
        val KNOWN_STORES = setOf(
            "com.android.vending", "com.xiaomi.market", "ru.vk.store",
            "com.huawei.appmarket", "com.sec.android.app.samsungapps",
            "com.oppo.market", "com.vivo.market", "com.amazon.venezia"
        )
    }
}