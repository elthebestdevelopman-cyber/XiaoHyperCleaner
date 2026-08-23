package com.xiaohypercleaner.ui.vm

import android.app.Application
import android.content.Intent
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Делегат PRO-цепочки (Shizuku/ADB): переходы разрешений, авто-редиректы,
 * запуск цепочки, откат, перезагрузка, диалоги dev-mode.
 * Снимает с MainViewModel ~250 строк.
 *
 * Теги логов оставлены "MainVM", чтобы logcat-фильтры продолжали работать.
 */
class ProFlowController(
    private val app: Application,
    private val prefs: PreferencesManager,
    private val getState: () -> MainUiState,
    private val update: ((MainUiState) -> MainUiState) -> Unit,
    private val openAccessibilityWithHint: () -> Unit,
    private val openAppInfoWithHint: () -> Unit,
    private val openAccessibilitySettings: () -> Unit,
    private val openOverlaySettings: () -> Unit,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MainVM"
    }

    private enum class Redirect { NONE, ACCESSIBILITY, APP_INFO }

    private var flowActive = false
    private var lastRedirect = Redirect.NONE
    private var restrictedFlowStarted = false

    fun proceedToChain() {
        AppLog.i(TAG, "proceedToChain")
        flowActive = true
        advance()
    }

    /**
     * PRO-ветка refreshStatuses: авто-продолжение цепочки и редиректы
     * при отказе в accessibility/overlay. Возвращает управление вызывающему —
     * Simple Mode обрабатывается ДО этого вызова и сюда не доходит.
     */
    fun handleRefresh(acc: Boolean, overlay: Boolean, prevState: MainUiState) {
        if (!flowActive) return

        val accessibilityJustChanged = !prevState.previousAccessibility && acc
        val overlayJustChanged = !prevState.previousOverlay && overlay

        when {
            accessibilityJustChanged -> {
                AppLog.i(TAG, "refreshStatuses: accessibility just enabled, continuing chain")
                resetRedirectFlow()
                update { it.copy(accessibilityAttempts = 0) }
                advance()
            }

            overlayJustChanged -> {
                AppLog.i(TAG, "refreshStatuses: overlay just enabled, continuing chain")
                update { it.copy(overlayAttempts = 0) }
                advance()
            }

            !acc && prevState.accessibilityAttempts > 0 -> {
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
                        update {
                            it.copy(
                                showRestrictedDialog = true,
                                showAccessibilityDialog = false,
                                showOverlayDialog = false,
                                restrictedSettingsShown = true
                            )
                        }
                    }
                }
            }

            !overlay && prevState.overlayAttempts > 0 -> {
                AppLog.i(
                    TAG,
                    "refreshStatuses: overlay not enabled after attempt, showing dialog again"
                )
                update {
                    it.copy(
                        showOverlayDialog = true,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            else -> advance()
        }
    }

    /** PRO-ветка dialogAgreed */
    fun dialogAgreed() {
        val s = getState()
        if (s.showAccessibilityDialog) {
            update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    accessibilityAttempts = it.accessibilityAttempts + 1
                )
            }
            ChainFlags.waitingAccessibilityReturn = true
            lastRedirect = Redirect.ACCESSIBILITY
            openAccessibilitySettings()
        } else if (s.showOverlayDialog) {
            update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    overlayAttempts = it.overlayAttempts + 1
                )
            }
            openOverlaySettings()
        } else {
            update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false
                )
            }
        }
    }

    /** PRO-ветка dialogCancelled */
    fun dialogCancelled() {
        flowActive = false
        resetRedirectFlow()
        update {
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
        update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                restrictedSettingsShown = true
            )
        }
        lastRedirect = Redirect.APP_INFO
        openAppInfoWithHint()
    }

    fun restrictedDialogCancelled() {
        flowActive = false
        resetRedirectFlow()
        update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                accessibilityAttempts = 0,
                restrictedSettingsShown = false
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Запуск цепочки
    // ═══════════════════════════════════════════════════════════════

    private fun advance() {
        val s = getState()
        AppLog.i(TAG, "advance: acc=${s.isAccessibilityEnabled}, overlay=${s.isOverlayGranted}")

        when {
            !s.isAccessibilityEnabled -> {
                AppLog.i(TAG, "advance: showing accessibility dialog")
                update {
                    it.copy(
                        showAccessibilityDialog = true,
                        showOverlayDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            !s.isOverlayGranted -> {
                AppLog.i(TAG, "advance: showing overlay dialog")
                update {
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
                update {
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

    private fun startChain() {
        AppLog.i(
            TAG,
            "startChain: setting pending flag, dnsFilter=${getState().dnsFilterEnabled}, aggressive=${getState().aggressiveMode}"
        )

        scope.launch {
            prefs.setPendingOptimization(true)
            prefs.setDnsFilterEnabled(getState().dnsFilterEnabled)
            prefs.setAggressiveMode(getState().aggressiveMode)
            AppLog.i(TAG, "startChain: pending flag set")

            if (getState().isAccessibilityEnabled) {
                AppLog.i(
                    TAG,
                    "startChain: accessibility already enabled, starting service directly"
                )
                val intent = Intent(app, AdbEnablerService::class.java)
                intent.action = AdbEnablerService.ACTION_START_CHAIN
                app.startService(intent)
            } else {
                AppLog.i(TAG, "startChain: opening accessibility settings")
                ChainFlags.waitingAccessibilityReturn = true
                update { it.copy(showAccessibilityDialog = true) }
            }
        }
    }

    private fun resetRedirectFlow() {
        lastRedirect = Redirect.NONE
        restrictedFlowStarted = false
    }

    // ═══════════════════════════════════════════════════════════════
    // Откат / перезагрузка / dev-mode
    // ═══════════════════════════════════════════════════════════════

    fun restoreOptimization() {
        if (getState().isWorking) return

        scope.launch {
            try {
                update { it.copy(isWorking = true, progress = 0f) }
                val deps = XiaoHyperApp.testDeps ?: (app as XiaoHyperApp).deps
                val ok = deps.newEngine().restore(
                    OptimizationEngine.Callbacks(
                    onProgress = { p -> update { it.copy(progress = p) } }
                ))

                if (ok) {
                    prefs.setHiddenSettingsApplied(false)
                    update { it.copy(isWorking = false, isOptimized = false) }
                } else {
                    update { it.copy(isWorking = false, restoreFailed = true) }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "restore failed: ${e.message}", e)
                update { it.copy(isWorking = false, restoreFailed = true) }
            }
        }
    }

    fun confirmReboot() {
        update { it.copy(showRebootDialog = false, isWorking = true) }

        scope.launch {
            try {
                val deps = XiaoHyperApp.testDeps ?: (app as XiaoHyperApp).deps
                val ok = deps.newEngine().reboot()
                update { it.copy(isWorking = false, rebootFailed = !ok) }
            } catch (e: Exception) {
                AppLog.e(TAG, "reboot failed: ${e.message}", e)
                update { it.copy(isWorking = false, rebootFailed = true) }
            }
        }
    }

    fun requestReboot() = update { it.copy(showRebootDialog = true) }

    fun dismissRebootDialog() = update { it.copy(showRebootDialog = false) }

    fun dismissRebootFailed() = update { it.copy(rebootFailed = false) }

    fun dismissRestoreFailed() = update { it.copy(restoreFailed = false) }

    fun devModeDialogRetry() {
        AppLog.i(TAG, "devModeDialog: retry — resuming chain (service will restart overlay)")
        update { it.copy(showDevModeDialog = false) }
        val intent = Intent(app, AdbEnablerService::class.java)
        intent.action = AdbEnablerService.ACTION_RETRY_DEV
        app.startService(intent)
    }

    fun devModeDialogCancel() {
        AppLog.i(TAG, "devModeDialog: cancel — stopping chain")
        update { it.copy(showDevModeDialog = false) }
        OverlayController.triggerCancel()
    }
}