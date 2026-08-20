package com.xiaohypercleaner.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.animation.doOnEnd
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.service.SystemAutomationService
import com.xiaohypercleaner.ui.components.AccessibilityConsentDialog
import com.xiaohypercleaner.ui.components.InfoDialog
import com.xiaohypercleaner.ui.components.MenuDialog
import com.xiaohypercleaner.ui.components.OptimizationLevelDialog
import com.xiaohypercleaner.ui.components.RestrictedSettingsDialog
import com.xiaohypercleaner.ui.components.ShizukuSetupWizard
import com.xiaohypercleaner.ui.components.ShizukuSourcesDialog
import com.xiaohypercleaner.ui.components.SimpleDoneDialog
import com.xiaohypercleaner.ui.components.SimpleStepScreen
import com.xiaohypercleaner.ui.theme.Blue500
import com.xiaohypercleaner.ui.theme.DarkGradientEnd
import com.xiaohypercleaner.ui.theme.DarkGradientStart
import com.xiaohypercleaner.ui.theme.GradientEnd
import com.xiaohypercleaner.ui.theme.GradientStart
import com.xiaohypercleaner.ui.theme.XiaoHyperCleanerTheme
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.ShizukuHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Главный экран приложения.
 *
 * Структура:
 *  - [MainActivity] — lifecycle + splash + onResume с обработкой возврата из TestActivity
 *  - [MainContent]  — корневой Composable с фоном, InfoCard и OptimizationCard
 *  - [MainDialogsHost] — хост ВСЕХ 17 диалогов (один Composable вместо inline-цепочки)
 *  - UI-карточки: InfoCard / OptimizationCard и их sub-views (Ready/Working/Done)
 *  - Утилиты для внешних intent'ов (openUrl / shareLog / openRateApp ...)
 *
 * Логирование: все клики пользователя и ключевые переходы помечены префиксом `MainUI`
 * (или `MainAct` для lifecycle). По ним легко отлаживать сценарий в logcat.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainAct"
    }

    private lateinit var vm: MainViewModel

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == MainViewModel.SHIZUKU_PERMISSION_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                AppLog.i(
                    TAG,
                    "shizukuPermissionListener: requestCode=$requestCode, granted=$granted"
                )
                if (::vm.isInitialized) vm.onShizukuPermissionResult(granted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var keepSplashVisible = true
        splashScreen.setKeepOnScreenCondition { keepSplashVisible }

        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "onCreate started")

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        val prefs = (application as XiaoHyperApp).preferencesManager

        android.os.Handler(mainLooper).postDelayed({
            keepSplashVisible = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                splashScreen.setOnExitAnimationListener { sv ->
                    val fadeOut = ObjectAnimator.ofFloat(sv.view, View.ALPHA, 1f, 0f).apply {
                        interpolator = AccelerateInterpolator(); duration = 400L
                    }
                    val scaleOutX =
                        ObjectAnimator.ofFloat(sv.iconView, View.SCALE_X, 1f, 0.85f).apply {
                            interpolator = AccelerateInterpolator(); duration = 400L
                        }
                    val scaleOutY =
                        ObjectAnimator.ofFloat(sv.iconView, View.SCALE_Y, 1f, 0.85f).apply {
                            interpolator = AccelerateInterpolator(); duration = 400L
                        }
                    fadeOut.doOnEnd { sv.remove() }
                    fadeOut.start(); scaleOutX.start(); scaleOutY.start()
                }
            }
        }, 1200L)

        setContent {
            val isDarkFromPrefs by prefs.isDarkTheme.collectAsState(initial = false)
            val hasManuallyChosen by prefs.hasManuallyChosenTheme.collectAsState(initial = false)
            val isDark = if (hasManuallyChosen) isDarkFromPrefs else isSystemInDarkTheme()

            val scope = rememberCoroutineScope()
            var showOnboarding by remember { mutableStateOf(false) }
            var onboardingChecked by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val completed = prefs.hasCompletedOnboarding.first()
                AppLog.i(TAG, "hasCompletedOnboarding=$completed")
                showOnboarding = !completed
                onboardingChecked = true
            }
            if (!onboardingChecked) return@setContent

            XiaoHyperCleanerTheme(darkTheme = isDark) {
                if (showOnboarding) {
                    OnboardingScreen(
                        isDark = isDark,
                        onFinish = {
                            AppLog.i(TAG, "onboarding finished")
                            showOnboarding = false
                            scope.launch { prefs.setHasCompletedOnboarding(true) }
                        }
                    )
                } else {
                    vm = viewModel()
                    val state by vm.state.collectAsState()
                    val lifecycle = LocalLifecycleOwner.current.lifecycle

                    LaunchedEffect(lifecycle) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            vm.refreshStatuses()
                        }
                    }
                    MainContent(
                        state = state,
                        isDark = isDark,
                        onDarkChange = { enabled ->
                            scope.launch {
                                prefs.setDarkTheme(enabled)
                                prefs.setHasManuallyChosenTheme(true)
                            }
                        },
                        vm = vm
                    )
                }
            }
        }
        AppLog.i(TAG, "onCreate completed")
    }

    override fun onResume() {
        super.onResume()
        AppLog.i(TAG, "onResume")
        if (!::vm.isInitialized) return

        vm.checkRestrictedSettingsOnResume()
        val currentState = vm.state.value

        // Останавливаем оверлей только если Simple Mode не активен —
        // иначе стрелки-подсказки будут исчезать при каждом возврате.
        if (!currentState.isWorking && !currentState.simpleModeActive) {
            stopService(Intent(this, OverlayService::class.java))
        }

        // Обработка возврата из TestActivity (фаза TEST_CLICK).
        if (currentState.simpleModeActive &&
            currentState.permissionSubPhase == PermissionSubPhase.TEST_CLICK
        ) {
            val finished = !SystemAutomationService.awaitingTestClick.get()
            if (finished) {
                val testSuccess = TestActivity.lastTestResult
                AppLog.i(TAG, "onResume: TEST_CLICK return, success=$testSuccess")
                vm.onTestActivityReturn(testSuccess)
            } else {
                AppLog.d(TAG, "onResume: TestActivity is still running, ignoring resume")
            }
        }

        // Обработка возврата из настроек Battery Optimization
        if (currentState.simpleModeActive &&
            currentState.permissionSubPhase == PermissionSubPhase.BATTERY_OPTIMIZATION
        ) {
            AppLog.i(TAG, "onResume: BATTERY_OPTIMIZATION return")
            vm.onBatteryOptimizationReturn()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════════════
// КОРНЕВОЙ COMPOSABLE
// ═══════════════════════════════════════════════════════════════

@Composable
private fun MainContent(
    state: MainUiState,
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    vm: MainViewModel
) {
    val view = LocalView.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    MainDialogsHost(
        state,
        vm,
        confirmRestore,
        { confirmRestore = it },
        menuOpen,
        { menuOpen = it },
        isDark,
        onDarkChange
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = if (isDark) listOf(DarkGradientStart, DarkGradientEnd)
                    else listOf(GradientStart, GradientEnd)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = {
                    AppLog.i("MainUI", "menu button clicked"); menuOpen = true
                }) {
                    Text("⋮", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(600)) + slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(600)
                )
            ) { InfoCard() }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(600, delayMillis = 150)) + slideInVertically(
                    initialOffsetY = { it / 4 }, animationSpec = tween(600, delayMillis = 150)
                )
            ) {
                OptimizationCard(
                    state = state,
                    onOptimize = {
                        AppLog.i("MainUI", "optimize button clicked")
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        vm.startFlow()
                    },
                    onRestore = {
                        AppLog.i("MainUI", "restore button clicked"); confirmRestore = true
                    },
                    onReboot = { AppLog.i("MainUI", "reboot button clicked"); vm.requestReboot() }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ХОСТ ВСЕХ ДИАЛОГОВ
// ═══════════════════════════════════════════════════════════════

@Composable
private fun MainDialogsHost(
    state: MainUiState,
    vm: MainViewModel,
    confirmRestore: Boolean,
    onConfirmRestoreChange: (Boolean) -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val isAndroid14Plus = Build.VERSION.SDK_INT >= 34

    if (state.showLevelDialog) {
        OptimizationLevelDialog(
            onModeSelected = { vm.onLevelChosen(it) },
            onDismiss = { vm.closeSimpleMode() }
        )
    }
    if (state.showLocationDialog) {
        LocationChoiceDialog(
            onLocation = { vm.onLocationChosen(it) },
            onDismiss = { vm.onLocationDialogCancelled() }
        )
    }
    if (state.showLevelConfirm) {
        val level = state.selectedLevel
        val isSimple = level != OptimizationMode.PRO
        InfoDialog(
            title = stringResource(if (isSimple) R.string.level_confirm_simple_title else R.string.level_confirm_advanced_title),
            text = stringResource(if (isSimple) R.string.level_confirm_simple_text else R.string.level_confirm_advanced_text),
            confirmText = stringResource(R.string.level_confirm_start),
            onConfirm = {
                AppLog.i(
                    "MainUI",
                    "level confirm: start clicked, level=$level"
                ); vm.confirmLevelStart()
            },
            onDismiss = { AppLog.i("MainUI", "level confirm: cancelled"); vm.cancelLevelConfirm() }
        )
    }
    if (state.simpleStep != null) {
        val isEnglish = LocalConfiguration.current.locales.get(0)?.language != "ru"
        SimpleStepScreen(
            state = state.simpleStep,
            isEnglish = isEnglish,
            onStart = { vm.startCurrentSimpleStep() },
            onNext = { vm.nextSimpleStep() },
            onSkip = { vm.skipSimpleStep() },
            onRetry = { vm.retrySimpleStep() },
            onCancel = { vm.closeSimpleMode() }
        )
    }
    if (state.simpleDone != null) {
        SimpleDoneDialog(
            completedCount = state.simpleDone.first,
            totalCount = state.simpleDone.second,
            failedSteps = vm.getFailedStepIds(),
            onRate = { openRateApp(context) },
            onDonate = { openWebView(context, "https://yoomoney.ru/to/410011379195150", "ЮMoney") },
            onClose = { vm.closeSimpleMode() }
        )
    }
    if (state.showShizukuDialog) {
        ShizukuGuideDialog(
            status = state.shizukuStatus,
            onInstall = { vm.shizukuDialogInstall() },
            onOpenApp = { vm.shizukuDialogOpenApp() },
            onSources = { vm.openShizukuSources() },
            onDismiss = { vm.shizukuDialogLater() }
        )
    }
    if (state.showShizukuSources) {
        ShizukuSourcesDialog(
            onSource = { vm.installFromSource(it) },
            onClose = { vm.closeShizukuSources() })
    }
    if (state.showShizukuWizard) {
        ShizukuSetupWizard(
            checkMessage = state.shizukuCheckMessage,
            onOpenAbout = { openDeviceInfoSettings(context) },
            onOpenDevOptions = { openDevOptionsSettings(context) },
            onOpenShizuku = { ShizukuHelper.openShizukuApp(context) },
            onRequestPermission = { vm.requestShizukuPermission() },
            onCheck = { vm.wizardCheck() },
            onSkip = { vm.wizardSkip() },
            onClose = { vm.closeShizukuWizard() }
        )
    }
    if (state.showRestrictedDialog) {
        InfoDialog(
            title = stringResource(if (isAndroid14Plus) R.string.forbidden_dialog_title else R.string.restricted_dialog_title),
            text = stringResource(if (isAndroid14Plus) R.string.forbidden_dialog_text else R.string.restricted_dialog_text),
            confirmText = stringResource(if (isAndroid14Plus) R.string.forbidden_dialog_open else R.string.restricted_dialog_open),
            onConfirm = {
                AppLog.i(
                    "MainUI",
                    "restricted dialog: open settings clicked"
                ); vm.restrictedDialogAgreed()
            },
            onDismiss = {
                AppLog.i(
                    "MainUI",
                    "restricted dialog: cancelled"
                ); vm.restrictedDialogCancelled()
            }
        )
    }
    if (state.showAppInfoDialog) {
        InfoDialog(
            title = stringResource(R.string.pointer_restricted_blocked),
            text = stringResource(R.string.pointer_restricted_explain),
            confirmText = stringResource(if (isAndroid14Plus) R.string.forbidden_dialog_open else R.string.restricted_dialog_open),
            onConfirm = { AppLog.i("MainUI", "appInfo dialog: agreed"); vm.appInfoDialogAgreed() },
            onDismiss = {
                AppLog.i(
                    "MainUI",
                    "appInfo dialog: cancelled"
                ); vm.appInfoDialogCancelled()
            }
        )
    }
    if (state.showRestrictedSettingsScreen) {
        RestrictedSettingsDialog(
            onOpenSettings = {
                AppLog.i(
                    "MainUI",
                    "restricted screen: open settings clicked"
                ); vm.onRestrictedScreenOpenSettings()
            },
            onDone = {
                AppLog.i(
                    "MainUI",
                    "restricted screen: done clicked"
                ); vm.onRestrictedScreenDone()
            },
            onCancel = {
                AppLog.i(
                    "MainUI",
                    "restricted screen: cancelled"
                ); vm.onRestrictedScreenCancelled()
            }
        )
    }
    if (state.showTestClickFailedDialog) {
        AlertDialog(
            onDismissRequest = {
                AppLog.i(
                    "MainUI",
                    "test click failed dialog: dismissed"
                ); vm.onTestClickSkip()
            },
            title = {
                Text(
                    stringResource(R.string.test_click_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.test_click_failed_text),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppLog.i(
                            "MainUI",
                            "test click failed: retry clicked"
                        ); vm.onTestClickRetry()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.test_click_retry)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLog.i(
                        "MainUI",
                        "test click failed: skip clicked"
                    ); vm.onTestClickSkip()
                }) { Text(stringResource(R.string.test_click_skip)) }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
    if (state.showBatteryDialog) {
        AlertDialog(
            onDismissRequest = {
                AppLog.i(
                    "MainUI",
                    "battery dialog: dismissed by scrim"
                ); vm.onBatteryDialogSkipped()
            },
            title = {
                Text(
                    stringResource(R.string.battery_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.battery_dialog_text),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppLog.i(
                            "MainUI",
                            "battery dialog: agreed"
                        ); vm.onBatteryDialogAgreed()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.battery_dialog_open)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLog.i(
                        "MainUI",
                        "battery dialog: skipped"
                    ); vm.onBatteryDialogSkipped()
                }) { Text(stringResource(R.string.battery_dialog_skip)) }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
    if (state.showPermissionFallbackDialog) {
        val (title, text) = when (state.stuckPhase) {
            PermissionSubPhase.OVERLAY -> stringResource(R.string.fallback_overlay_title) to stringResource(
                R.string.fallback_overlay_text
            )

            PermissionSubPhase.APP_INFO -> stringResource(R.string.fallback_appinfo_title) to stringResource(
                R.string.fallback_appinfo_text
            )

            else -> stringResource(R.string.fallback_accessibility_title) to stringResource(R.string.fallback_accessibility_text)
        }
        PermissionFallbackDialog(
            title = title, text = text,
            onRetry = {
                AppLog.i(
                    "MainUI",
                    "permission fallback: retry clicked for phase=${state.stuckPhase}"
                ); vm.onPermissionFallbackRetry()
            },
            onOpenSettings = {
                AppLog.i(
                    "MainUI",
                    "permission fallback: open settings clicked for phase=${state.stuckPhase}"
                ); vm.onPermissionFallbackOpenSettings()
            },
            onCancel = {
                AppLog.i(
                    "MainUI",
                    "permission fallback: cancelled (full reset)"
                ); vm.onPermissionFallbackCancelled()
            }
        )
    }
    if (state.showAccessibilityDialog) {
        AccessibilityConsentDialog(
            onConfirm = {
                AppLog.i(
                    "MainUI",
                    "accessibility consent dialog: confirmed with explicit consent"
                ); vm.dialogAgreed()
            },
            onDismiss = {
                AppLog.i(
                    "MainUI",
                    "accessibility consent dialog: dismissed"
                ); vm.dialogCancelled()
            }
        )
    }
    if (state.showOverlayDialog) {
        InfoDialog(
            title = stringResource(R.string.overlay_permission_title),
            text = stringResource(R.string.overlay_permission_text),
            confirmText = stringResource(R.string.allow),
            onConfirm = { AppLog.i("MainUI", "overlay dialog: agreed"); vm.dialogAgreed() },
            onDismiss = { AppLog.i("MainUI", "overlay dialog: cancelled"); vm.dialogCancelled() }
        )
    }
    if (state.showOptionsDialog) {
        OptionsDialog(
            dnsFilterEnabled = state.dnsFilterEnabled,
            aggressiveMode = state.aggressiveMode,
            onDnsToggle = { vm.toggleDnsFilter(it) },
            onAggressiveToggle = { vm.toggleAggressiveMode(it) },
            onConfirm = {
                AppLog.i(
                    "MainUI",
                    "options dialog: confirmed"
                ); vm.optionsDialogConfirmed()
            },
            onCancel = {
                AppLog.i(
                    "MainUI",
                    "options dialog: cancelled"
                ); vm.optionsDialogCancelled()
            }
        )
    }
    if (state.showDnsWarningDialog) {
        InfoDialog(
            title = stringResource(R.string.dns_warning_title),
            text = stringResource(R.string.dns_warning_text),
            confirmText = stringResource(R.string.dns_warning_accept),
            onConfirm = { AppLog.i("MainUI", "DNS warning: accepted"); vm.dnsWarningAccepted() },
            onDismiss = { AppLog.i("MainUI", "DNS warning: declined"); vm.dnsWarningDeclined() }
        )
    }
    if (state.showDevModeDialog) {
        DevModeDialog(
            onOpenDeviceInfo = {
                AppLog.i(
                    "MainUI",
                    "dev mode dialog: open device info"
                ); openDeviceInfoSettings(context)
            },
            onRetry = {
                AppLog.i(
                    "MainUI",
                    "dev mode dialog: retry clicked"
                ); vm.devModeDialogRetry()
            },
            onCancel = {
                AppLog.i(
                    "MainUI",
                    "dev mode dialog: cancelled"
                ); vm.devModeDialogCancel()
            }
        )
    }
    if (confirmRestore) {
        InfoDialog(
            title = stringResource(R.string.restore_dialog_title),
            text = stringResource(R.string.restore_dialog_text),
            confirmText = stringResource(R.string.restore_confirm),
            onConfirm = {
                AppLog.i("MainUI", "restore dialog: confirmed"); onConfirmRestoreChange(
                false
            ); vm.restoreOptimization()
            },
            onDismiss = {
                AppLog.i("MainUI", "restore dialog: cancelled"); onConfirmRestoreChange(
                false
            )
            }
        )
    }
    if (state.showRebootDialog) {
        InfoDialog(
            title = stringResource(R.string.reboot_dialog_title),
            text = stringResource(R.string.reboot_dialog_text),
            confirmText = stringResource(R.string.reboot_confirm),
            onConfirm = { AppLog.i("MainUI", "reboot dialog: confirmed"); vm.confirmReboot() },
            onDismiss = { AppLog.i("MainUI", "reboot dialog: dismissed"); vm.dismissRebootDialog() }
        )
    }
    if (state.rebootFailed) {
        InfoDialog(
            title = stringResource(R.string.reboot_dialog_title),
            text = stringResource(R.string.reboot_failed_text),
            onDismiss = {
                AppLog.i(
                    "MainUI",
                    "reboot failed dialog: dismissed"
                ); vm.dismissRebootFailed()
            }
        )
    }
    if (state.restoreFailed) {
        InfoDialog(
            title = stringResource(R.string.restore_dialog_title),
            text = stringResource(R.string.restore_failed_text),
            onDismiss = {
                AppLog.i(
                    "MainUI",
                    "restore failed dialog: dismissed"
                ); vm.dismissRestoreFailed()
            }
        )
    }
    if (state.showFinalDialog) {
        InfoDialog(
            title = stringResource(R.string.final_dialog_title),
            text = when {
                state.finalReport.isNotEmpty() -> state.finalReport
                state.optimizationSuccess -> stringResource(R.string.final_dialog_success_text)
                else -> stringResource(R.string.final_dialog_failed_text)
            },
            confirmText = stringResource(if (state.optimizationSuccess) R.string.final_dialog_rate else R.string.final_dialog_send_log),
            onConfirm = {
                AppLog.i("MainUI", "final dialog: confirmed, success=${state.optimizationSuccess}")
                vm.dismissFinalDialog()
                if (state.optimizationSuccess) openRateApp(context) else shareLog(context)
            },
            onDismiss = { AppLog.i("MainUI", "final dialog: dismissed"); vm.dismissFinalDialog() }
        )
    }
    if (menuOpen) {
        val privacyUrl = stringResource(R.string.privacy_policy_url)

        MenuDialog(
            isDark = isDark,
            onDarkChange = onDarkChange,
            onClose = { AppLog.i("MainUI", "menu: closed"); onMenuOpenChange(false) },
            onRate = { AppLog.i("MainUI", "menu: rate clicked"); openRateApp(context) },
            onYooMoney = {
                AppLog.i("MainUI", "menu: yoomoney clicked")
                openWebView(context, "https://yoomoney.ru/to/410011379195150", "ЮMoney")
            },
            onCloudTips = {
                AppLog.i("MainUI", "menu: cloudtips clicked")
                openWebView(context, "https://pay.cloudtips.ru/p/90614cff", "CloudTips")
            },
            onShareLog = { AppLog.i("MainUI", "menu: share log clicked"); shareLog(context) },
            onPrivacyPolicyClick = {
                AppLog.i("MainUI", "menu: privacy policy clicked")
                openUrl(context, privacyUrl)   // ← используем готовую строку
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// ВСПОМОГАТЕЛЬНЫЕ ДИАЛОГИ
// ═══════════════════════════════════════════════════════════════

/**
 * PermissionFallbackDialog: вертикальная компоновка кнопок внутри `text`.
 * `confirmButton` и `dismissButton` оставлены пустыми — это обязательно
 * для AlertDialog, но визуально их нет. Реальные кнопки рендерятся внутри
 * `text`-слота, что даёт полный контроль над компоновкой и гарантирует,
 * что текст влезает на любой экран.
 */
@Composable
private fun PermissionFallbackDialog(
    title: String,
    text: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            AppLog.i(
                "MainUI",
                "permission fallback: dismissed by scrim/back"
            ); onCancel()
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.fallback_retry),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        softWrap = true
                    )
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.fallback_open_settings),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        softWrap = true
                    )
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.fallback_cancel),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        softWrap = true
                    )
                }
            }
        },
        confirmButton = { /* пусто — кнопки внутри text */ },
        dismissButton = { /* пусто — кнопки внутри text */ },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ShizukuGuideDialog(
    status: ShizukuExecutor.Status,
    onInstall: () -> Unit,
    onOpenApp: () -> Unit,
    onSources: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isInstalled = remember { ShizukuHelper.isInstalled(context) }

    // ИСПРАВЛЕНО: добавлена ветка else (для AVAILABLE, хотя она отсекается выше — для exhaustive)
    val (text, primaryText, primaryAction) = when (status) {
        ShizukuExecutor.Status.NOT_INSTALLED -> Triple(
            stringResource(R.string.shizuku_dialog_not_installed),
            stringResource(R.string.shizuku_dialog_install),
            onInstall
        )

        ShizukuExecutor.Status.NOT_RUNNING -> Triple(
            stringResource(R.string.shizuku_dialog_not_running),
            stringResource(R.string.shizuku_dialog_howto),
            onOpenApp
        )

        ShizukuExecutor.Status.PERMISSION_REQUIRED -> Triple(
            stringResource(R.string.shizuku_dialog_permission),
            stringResource(R.string.shizuku_dialog_howto),
            onOpenApp
        )

        ShizukuExecutor.Status.AVAILABLE -> return
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    stringResource(R.string.shizuku_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) onInstall() else primaryAction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) stringResource(
                            R.string.shizuku_dialog_install
                        ) else primaryText
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (status == ShizukuExecutor.Status.NOT_INSTALLED) {
                    TextButton(onClick = onSources, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.shizuku_card_other_sources)
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.shizuku_dialog_later)
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationChoiceDialog(onLocation: (RestrictedLocation) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.pointer_restricted_not_found_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pointer_restricted_location_question),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onLocation(RestrictedLocation.TOP_MENU) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.pointer_restricted_menu_option)) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onLocation(RestrictedLocation.BOTTOM_LIST) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.pointer_restricted_bottom_option)) }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onLocation(RestrictedLocation.ABSENT) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.pointer_restricted_absent_option)) }
            }
        }
    }
}

@Composable
private fun OptionsDialog(
    dnsFilterEnabled: Boolean, aggressiveMode: Boolean,
    onDnsToggle: (Boolean) -> Unit, onAggressiveToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit, onCancel: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    stringResource(R.string.options_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.options_dialog_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dns_option_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.dns_option_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = dnsFilterEnabled, onCheckedChange = onDnsToggle)
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.aggressive_option_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.aggressive_option_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = aggressiveMode, onCheckedChange = onAggressiveToggle)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.options_dialog_start)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.cancel)
                    )
                }
            }
        }
    }
}

@Composable
private fun DevModeDialog(onOpenDeviceInfo: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    stringResource(R.string.dev_mode_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.dev_mode_dialog_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onOpenDeviceInfo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.dev_mode_dialog_open_about)) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.dev_mode_dialog_retry)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.cancel)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// UI-КАРТОЧКИ ГЛАВНОГО ЭКРАНА
// ═══════════════════════════════════════════════════════════════

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.app_name),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.app_description_short),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                softWrap = true
            )
            Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.features_title),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start
            )
            Spacer(Modifier.height(12.dp))
            FeatureRow(Icons.Filled.Lock, stringResource(R.string.feature_processes))
            Spacer(Modifier.height(8.dp))
            FeatureRow(Icons.Filled.Build, stringResource(R.string.feature_speed))
            Spacer(Modifier.height(8.dp))
            FeatureRow(Icons.Filled.Star, stringResource(R.string.feature_battery))
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OptimizationCard(
    state: MainUiState,
    onOptimize: () -> Unit, onRestore: () -> Unit, onReboot: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val stageKey = when {
                state.isWorking -> "working"; state.isOptimized -> "done"; else -> "ready"
            }
            AnimatedContent(
                targetState = stageKey,
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(
                        tween(300),
                        initialScale = 0.95f
                    )) togetherWith (fadeOut(tween(200)) + scaleOut(
                        tween(200),
                        targetScale = 0.95f
                    ))
                },
                label = "stage"
            ) { stage ->
                when (stage) {
                    "working" -> WorkingView(state.progress)
                    "done" -> DoneView(onRestore, onReboot)
                    else -> ReadyView(onOptimize)
                }
            }
        }
    }
}

@Composable
private fun ReadyView(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Blue500)
    ) {
        Text(
            stringResource(R.string.btn_optimize),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun WorkingView(progress: Float) {
    val normalized = (progress / 100f).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.status_working),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { normalized },
            modifier = Modifier.fillMaxWidth(),
            color = Blue500
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${progress.toInt().coerceIn(0, 100)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DoneView(onRestore: () -> Unit, onReboot: () -> Unit) {
    Text(
        stringResource(R.string.status_done),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.status_done_description),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onRestore,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text(stringResource(R.string.btn_restore)) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onReboot,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text(stringResource(R.string.reboot_now)) }
}

// ═══════════════════════════════════════════════════════════════
// УТИЛИТЫ ДЛЯ ЗАПУСКА ВНЕШНИХ INTENT'ОВ
// ═══════════════════════════════════════════════════════════════

private fun openUrl(context: Context, url: String) {
    AppLog.i("OpenUrl", "opening url: $url")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: Exception) {
        AppLog.e("OpenUrl", "failed to open url: ${e.message}")
    }
}

/**
 * ИСПРАВЛЕНО: убран `?:` после `launchSafely`, который пытался вызвать
 * Composable (`openUrl`) вне @Composable-контекста. Теперь обычный try/catch.
 */
private fun openWebView(context: Context, url: String, title: String) {
    AppLog.i("WebView", "opening webView: $url")
    try {
        context.startActivity(
            Intent(context, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, url)
                .putExtra(WebViewActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        AppLog.w("WebView", "WebView failed, fallback to browser: ${e.message}")
        openUrl(context, url)
    }
}

private fun shareLog(context: Context) {
    AppLog.i("ShareLog", "shareLog requested")
    try {
        val logFile = AppLog.getLogFile()
        if (logFile == null || !logFile.exists() || logFile.length() == 0L) {
            AppLog.w("ShareLog", "log file is null/empty/missing"); return
        }
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "XiaoHyperCleaner log ${System.currentTimeMillis()}"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share log"
            )
        )
        AppLog.i("ShareLog", "share intent sent successfully")
    } catch (e: Exception) {
        AppLog.e("ShareLog", "shareLog failed", e)
    }
}

private fun openDeviceInfoSettings(context: Context) {
    AppLog.i("OpenSettings", "opening device info settings")
    try {
        context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "device info failed: ${e.message}")
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

private fun openDevOptionsSettings(context: Context) {
    AppLog.i("OpenSettings", "opening developer options")
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        )
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "dev options failed: ${e.message}")
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

private fun openRateApp(context: Context) {
    AppLog.i("OpenRate", "opening rate app")
    val pkg = context.packageName
    val schemes = listOf(
        "rustore://application/$pkg",
        "mimarket://details?id=$pkg",
        "market://details?id=$pkg"
    )
    for (s in schemes) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, s.toUri()))
            AppLog.i("OpenRate", "opened via scheme: $s"); return
        } catch (_: Exception) {
        }
    }
    openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
}