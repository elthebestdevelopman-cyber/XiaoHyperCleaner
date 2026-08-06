package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.AdbClient
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
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val showAccessibilityDialog: Boolean = false,
    val showOverlayDialog: Boolean = false,
    val restoreFailed: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private val engine = OptimizationEngine(AdbClient())
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
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
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
            _state.update { it.copy(isWorking = true) }
            val ok = engine.optimize()
            if (ok) prefs.setHiddenSettingsApplied(true)
            _state.update { it.copy(isWorking = false, isOptimized = ok) }
        }
    }

    fun restoreOptimization() {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            val ok = engine.restore()
            if (ok) {
                prefs.setHiddenSettingsApplied(false)
                _state.update { it.copy(isWorking = false, isOptimized = false) }
            } else {
                _state.update { it.copy(isWorking = false, restoreFailed = true) }
            }
        }
    }

    fun rebootDevice() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
        } catch (_: Exception) {
        }
    }
}