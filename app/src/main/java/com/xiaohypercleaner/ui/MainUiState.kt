package com.xiaohypercleaner.ui

import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.data.SimpleStepState

/**
 * Всё состояние главного экрана. Вынесено из MainViewModel,
 * чтобы VM оставался тонким фасадом над делегатами.
 */
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
    val showAppInfoDialog: Boolean = false,
    val showLocationDialog: Boolean = false,
    val showPermissionFallbackDialog: Boolean = false,
    val stuckPhase: PermissionSubPhase? = null,
    val showRestrictedSettingsScreen: Boolean = false,
    val showTestClickFailedDialog: Boolean = false,
    val showBatteryDialog: Boolean = false,
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
    val permissionSubPhase: PermissionSubPhase = PermissionSubPhase.INACTIVE,
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
    val appInfoAttempts: Int = 0,
    val previousAccessibility: Boolean = false,
    val previousOverlay: Boolean = false,
    val restrictedSettingsShown: Boolean = false,
    val showDevModeDialog: Boolean = false,
    val simpleModeActive: Boolean = false
)