package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.service.AdbEnablerService
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
    val showAccessibilityDialog: Boolean = false,
    val showOverlayDialog: Boolean = false,
    val showRebootDialog: Boolean = false,
    val rebootFailed: Boolean = false,
    val restoreFailed: Boolean = false,
    val optimizationFailed: Boolean = false,
    val showManualInstructions: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private var flowActive = false
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
            app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
        val overlay = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(app)
        _state.update { it.copy(isAccessibilityEnabled = acc, isOverlayGranted = overlay) }
        if (flowActive) advance()
    }

    fun startFlow() {
        if (_state.value.isWorking) return
        flowActive = true
        advance()
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

    fun dismissOptimizationFailed() {
        _state.update { it.copy(optimizationFailed = false) }
    }

    fun showManualInstructions() {
        _state.update { it.copy(showManualInstructions = true, optimizationFailed = false) }
    }

    fun dismissManualInstructions() {
        _state.update { it.copy(showManualInstructions = false) }
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
            val ok = app.deps.newEngine().reboot()
            _state.update { it.copy(isWorking = false, rebootFailed = !ok) }
        }
    }

    private fun startChain() {
        val intent = Intent(app, AdbEnablerService::class.java)
        intent.action = AdbEnablerService.ACTION_START_CHAIN
        try {
            app.startService(intent)
        } catch (_: Exception) {
            runLocal()
        }
    }

    private fun runLocal() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, progress = 0f, optimizationFailed = false) }
            val engine = app.deps.newEngine()
            val ok = engine.optimize(callbacks())
            if (ok) {
                prefs.setHiddenSettingsApplied(true)
                _state.update { it.copy(isWorking = false, isOptimized = true) }
            } else {
                _state.update { it.copy(isWorking = false, optimizationFailed = true) }
            }
        }
    }

    fun restoreOptimization() {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, progress = 0f) }
            val ok = app.deps.newEngine().restore(callbacks())
            if (ok) {
                prefs.setHiddenSettingsApplied(false)
                _state.update { it.copy(isWorking = false, isOptimized = false) }
            } else {
                _state.update { it.copy(isWorking = false, restoreFailed = true) }
            }
        }
    }

    private fun callbacks() = OptimizationEngine.Callbacks(
        onProgress = { p -> _state.update { it.copy(progress = p) } }
    )
}