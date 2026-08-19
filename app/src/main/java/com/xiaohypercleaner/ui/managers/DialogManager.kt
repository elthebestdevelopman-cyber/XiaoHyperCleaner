package com.xiaohypercleaner.ui.managers

import com.xiaohypercleaner.ui.MainUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class DialogType {
    ACCESSIBILITY,
    OVERLAY,
    RESTRICTED,
    APP_INFO
}

class DialogManager(
    private val state: MutableStateFlow<MainUiState>
) {
    fun handleDialogResult(type: DialogType, agreed: Boolean) {
        when (type) {
            DialogType.ACCESSIBILITY -> handleAccessibilityDialog(agreed)
            DialogType.OVERLAY -> handleOverlayDialog(agreed)
            DialogType.RESTRICTED -> handleRestrictedDialog(agreed)
            DialogType.APP_INFO -> handleAppInfoDialog(agreed)
        }
    }

    private fun handleAccessibilityDialog(agreed: Boolean) {
        state.update { current ->
            if (agreed) {
                current.copy(
                    showAccessibilityDialog = false,
                    accessibilityAttempts = current.accessibilityAttempts + 1
                )
            } else {
                current.copy(showAccessibilityDialog = false)
            }
        }
    }

    private fun handleOverlayDialog(agreed: Boolean) {
        state.update { current ->
            if (agreed) {
                current.copy(
                    showOverlayDialog = false,
                    overlayAttempts = current.overlayAttempts + 1
                )
            } else {
                current.copy(showOverlayDialog = false)
            }
        }
    }

    private fun handleRestrictedDialog(agreed: Boolean) {
        state.update { current ->
            if (agreed) {
                current.copy(
                    showRestrictedDialog = false,
                    restrictedSettingsShown = true
                )
            } else {
                current.copy(showRestrictedDialog = false)
            }
        }
    }

    private fun handleAppInfoDialog(agreed: Boolean) {
        state.update { current ->
            current.copy(showAppInfoDialog = false)
        }
    }

    fun showDialog(type: DialogType) {
        state.update { current ->
            when (type) {
                DialogType.ACCESSIBILITY -> current.copy(showAccessibilityDialog = true)
                DialogType.OVERLAY -> current.copy(showOverlayDialog = true)
                DialogType.RESTRICTED -> current.copy(showRestrictedDialog = true)
                DialogType.APP_INFO -> current.copy(showAppInfoDialog = true)
            }
        }
    }

    fun hideDialog(type: DialogType) {
        state.update { current ->
            when (type) {
                DialogType.ACCESSIBILITY -> current.copy(showAccessibilityDialog = false)
                DialogType.OVERLAY -> current.copy(showOverlayDialog = false)
                DialogType.RESTRICTED -> current.copy(showRestrictedDialog = false)
                DialogType.APP_INFO -> current.copy(showAppInfoDialog = false)
            }
        }
    }

    fun hideAllDialogs() {
        state.update { current ->
            current.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                showAppInfoDialog = false
            )
        }
    }
}