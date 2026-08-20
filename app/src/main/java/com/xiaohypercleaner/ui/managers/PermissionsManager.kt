package com.xiaohypercleaner.ui.managers

import com.xiaohypercleaner.data.PermissionFlowManager
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class PermissionsManager(
    private val state: MutableStateFlow<MainUiState>,
    private val permissionFlow: PermissionFlowManager,
    private val dialogManager: DialogManager
) {
    companion object {
        private const val TAG = "PermissionsMgr"
    }

    private enum class Redirect { NONE, ACCESSIBILITY, APP_INFO }

    private var lastRedirect = Redirect.NONE
    private var restrictedFlowStarted = false
    private var flowActive = false

    fun setFlowActive(active: Boolean) {
        flowActive = active
    }

    fun checkAndAdvance(
        accEnabled: Boolean,
        overlayGranted: Boolean,
        accessibilityJustChanged: Boolean,
        overlayJustChanged: Boolean,
        onAdvance: () -> Unit,
        onOpenAppInfo: () -> Unit,
        onOpenAccessibility: () -> Unit
    ) {
        val prevState = state.value

        if (accessibilityJustChanged && flowActive) {
            AppLog.i(TAG, "accessibility just enabled, continuing chain")
            resetRedirectFlow()
            state.update { it.copy(accessibilityAttempts = 0) }
            onAdvance()
            return
        }

        if (overlayJustChanged && flowActive) {
            AppLog.i(TAG, "overlay just enabled, continuing chain")
            state.update { it.copy(overlayAttempts = 0) }
            onAdvance()
            return
        }

        if (!accEnabled && prevState.accessibilityAttempts > 0 && flowActive) {
            AppLog.i(TAG, "accessibility not enabled after attempt")
            when {
                lastRedirect == Redirect.ACCESSIBILITY && !restrictedFlowStarted -> {
                    AppLog.i(TAG, "denied — auto-redirect to app info")
                    restrictedFlowStarted = true
                    lastRedirect = Redirect.APP_INFO
                    onOpenAppInfo()
                }

                lastRedirect == Redirect.APP_INFO -> {
                    AppLog.i(TAG, "back from app info — auto-redirect to accessibility")
                    lastRedirect = Redirect.ACCESSIBILITY
                    onOpenAccessibility()
                }

                else -> {
                    AppLog.i(TAG, "loop breaker — showing restricted dialog")
                    dialogManager.showDialog(DialogType.RESTRICTED)
                    state.update { it.copy(restrictedSettingsShown = true) }
                }
            }
            return
        }

        if (!overlayGranted && prevState.overlayAttempts > 0 && flowActive) {
            AppLog.i(TAG, "overlay not enabled after attempt, showing dialog again")
            dialogManager.showDialog(DialogType.OVERLAY)
            return
        }

        if (flowActive) onAdvance()
    }

    fun advance(
        accEnabled: Boolean,
        overlayGranted: Boolean,
        onStartChain: () -> Unit
    ) {
        AppLog.i(TAG, "advance: acc=$accEnabled, overlay=$overlayGranted")

        when {
            !accEnabled -> {
                AppLog.i(TAG, "showing accessibility dialog")
                dialogManager.hideAllDialogs()
                dialogManager.showDialog(DialogType.ACCESSIBILITY)
            }

            !overlayGranted -> {
                AppLog.i(TAG, "showing overlay dialog")
                dialogManager.hideAllDialogs()
                dialogManager.showDialog(DialogType.OVERLAY)
            }

            else -> {
                AppLog.i(TAG, "all permissions granted, starting chain")
                flowActive = false
                resetRedirectFlow()
                state.update { current ->
                    current.copy(
                        accessibilityAttempts = 0,
                        overlayAttempts = 0,
                        restrictedSettingsShown = false
                    )
                }
                dialogManager.hideAllDialogs()
                onStartChain()
            }
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
}