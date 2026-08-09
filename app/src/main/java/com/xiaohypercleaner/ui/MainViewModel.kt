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
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.util.OptimizationNotifier
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
    val showRestrictedDialog: Boolean = false,
    val showRebootDialog: Boolean = false,
    val rebootFailed: Boolean = false,
    val restoreFailed: Boolean = false,
    val showFinalDialog: Boolean = false,
    val optimizationSuccess: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private var flowActive = false
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        // Наблюдение за состоянием оптимизации в DataStore
        viewModelScope.launch {
            prefs.isHiddenSettingsApplied.collect { applied ->
                _state.update { it.copy(isOptimized = applied) }
            }
        }

        // Наблюдение за результатом оптимизации из службы
        viewModelScope.launch {
            OptimizationNotifier.result.collect { result ->
                when (result) {
                    is OptimizationNotifier.Result.Success -> {
                        _state.update {
                            it.copy(
                                isWorking = false,
                                isOptimized = true,
                                showFinalDialog = true,
                                optimizationSuccess = true
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
                                optimizationSuccess = false
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

        // При запуске проверяем restricted settings
        checkRestrictedSettingsOnStart()
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
        if (flowActive) advance()
    }

    // Проверка restricted settings при запуске
    private fun checkRestrictedSettingsOnStart() {
        if (needsRestricted() && !restrictedSettingsAllowed()) {
            _state.update { it.copy(showRestrictedDialog = true) }
        }
    }

    // Вызывается из onResume для повторной проверки
    fun checkRestrictedSettingsOnResume() {
        if (_state.value.showRestrictedDialog && restrictedSettingsAllowed()) {
            // Пользователь разрешил restricted settings — закрываем диалог и открываем спец возможности
            _state.update { it.copy(showRestrictedDialog = false) }
            openAccessibilitySettingsAutomatically()
        }
    }

    fun startFlow() {
        if (_state.value.isWorking) return

        // Проверка restricted settings (Android 13+ sideload)
        if (needsRestricted() && !restrictedSettingsAllowed()) {
            _state.update { it.copy(showRestrictedDialog = true) }
            return
        }

        flowActive = true
        advance()
    }

    private fun advance() {
        val s = _state.value
        when {
            !s.isAccessibilityEnabled ->
                _state.update {
                    it.copy(
                        showAccessibilityDialog = true,
                        showOverlayDialog = false,
                        showRestrictedDialog = false
                    )
                }

            !s.isOverlayGranted ->
                _state.update {
                    it.copy(
                        showOverlayDialog = true,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false
                    )
                }

            else -> {
                flowActive = false
                _state.update {
                    it.copy(
                        showOverlayDialog = false,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false
                    )
                }
                startChain()
            }
        }
    }

    fun dialogAgreed() {
        _state.update {
            it.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false
            )
        }
    }

    fun dialogCancelled() {
        flowActive = false
        _state.update {
            it.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false
            )
        }
    }

    fun showRestrictedDialog() {
        _state.update { it.copy(showRestrictedDialog = true) }
    }

    fun dismissRestrictedDialog() {
        _state.update { it.copy(showRestrictedDialog = false) }
    }

    // Автоматический переход в спец возможности после разрешения restricted settings
    private fun openAccessibilitySettingsAutomatically() {
        _state.update { it.copy(showAccessibilityDialog = true) }
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
            _state.update { it.copy(isWorking = true, progress = 0f) }
            val deps = XiaoHyperApp.testDeps ?: app.deps
            val ok = deps.newEngine().optimize(callbacks())
            if (ok) {
                prefs.setHiddenSettingsApplied(true)
                OptimizationNotifier.setSuccess("Local optimization completed")
            } else {
                OptimizationNotifier.setFailure(
                    listOf("local_optimization"),
                    "Local optimization failed"
                )
            }
            _state.update { it.copy(isWorking = false, isOptimized = ok) }
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

    // Проверка restricted settings (Android 13+ sideload)
    private fun needsRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return !isFromKnownStore()
    }

    private fun isFromKnownStore(): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= 30) {
                app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getInstallerPackageName(app.packageName)
            }
            installer in KNOWN_STORES
        } catch (_: Exception) {
            false
        }
    }

    private fun restrictedSettingsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            val appOps = app.getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                "android:restricted_settings",
                android.os.Process.myUid(),
                app.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val KNOWN_STORES = listOf(
            "com.android.vending",      // Google Play
            "com.xiaomi.mimarket",       // Mi Market / GetApps
            "ru.ozon.app.android",       // Ozon
            "com.retailstore.android",   // RuStore
            "com.huawei.appmarket",      // Huawei AppGallery
            "com.samsung.android.app.galaxystore" // Samsung Galaxy Store
        )
    }
}