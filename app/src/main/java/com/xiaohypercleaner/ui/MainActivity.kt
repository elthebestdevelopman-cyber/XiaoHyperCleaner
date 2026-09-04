package com.xiaohypercleaner.ui

import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.ui.components.AccessibilityConsentDialog
import com.xiaohypercleaner.ui.components.DevModeDialog
import com.xiaohypercleaner.ui.components.InfoCard
import com.xiaohypercleaner.ui.components.InfoDialog
import com.xiaohypercleaner.ui.components.LocationChoiceDialog
import com.xiaohypercleaner.ui.components.MenuDialog
import com.xiaohypercleaner.ui.components.OptionsDialog
import com.xiaohypercleaner.ui.components.OptimizationCard
import com.xiaohypercleaner.ui.components.OptimizationLevelDialog
import com.xiaohypercleaner.ui.components.PermissionFallbackDialog
import com.xiaohypercleaner.ui.components.RestrictedSettingsDialog
import com.xiaohypercleaner.ui.components.ShizukuGuideDialog
import com.xiaohypercleaner.ui.components.ShizukuSetupWizard
import com.xiaohypercleaner.ui.components.ShizukuSourcesDialog
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
 * Архитектура:
 * - MVVM: MainViewModel управляет состоянием
 * - Compose: декларативный UI
 * - Splash screen: анимация при старте
 * - Shizuku: listener для запроса разрешений
 *
 * Ключевые фичи:
 * - wasStopped флаг: фильтрация ложных onResume от системных диалогов MIUI
 * - repeatOnLifecycle(RESUMED): автообновление статусов при возврате
 * - Онбординг: показывается один раз при первом запуске
 *
 * УЛУЧШЕНИЯ:
 * 1. Убран stopService() — используем OverlayController.hide() для consistency
 * 2. TAG переименован в "MainActivity"
 * 3. Убраны inline функции (вынесены в UiActions.kt)
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"

        /** Конвертация dp в px для анимаций (не зависит от Context в composable) */
        private fun dpToPx(context: android.content.Context, dp: Int): Int {
            return android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }
    }

    private lateinit var vm: MainViewModel

    /**
     * Флаг реального ухода из приложения.
     *
     * Системный диалог MIUI поверх настроек батареи даёт onPause, но НЕ onStop.
     * Реальный уход в чужое Activity (настройки) — даёт onStop.
     * Обрабатываем возврат из батареи только когда wasStopped = true.
     */
    private var wasStopped: Boolean = false

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == MainViewModel.SHIZUKU_PERMISSION_CODE) {
                val granted: Boolean = grantResult == PackageManager.PERMISSION_GRANTED
                AppLog.i(
                    TAG,
                    "shizukuPermissionListener: requestCode=$requestCode, granted=$granted"
                )
                if (::vm.isInitialized) vm.onShizukuPermissionResult(granted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var keepSplashVisible: Boolean = true
        splashScreen.setKeepOnScreenCondition { keepSplashVisible }

        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "onCreate started")

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        val prefs = (application as XiaoHyperApp).preferencesManager

        Handler(mainLooper).postDelayed({
            keepSplashVisible = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                splashScreen.setOnExitAnimationListener { splashScreenView ->
                    val iconView: View? = splashScreenView.iconView

                    // ═══════════════════════════════════════════════════════════════
                    // ИСПРАВЛЕНО (beta12): АНИМАЦИЯ «РОБОКОТ МАШЕТ ЛАПКОЙ И УЛЕТАЕТ»
                    // ═══════════════════════════════════════════════════════════════

                    // Фаза 1: Махание лапкой (0 — 700 мс)
                    // Робот покачивается из стороны в сторону, как будто машет.
                    // Rotation: 0° → -15° → +15° → -15° → +15° → 0° (3 взмаха)
                    val waveAnimator = if (iconView != null) {
                        ObjectAnimator.ofFloat(
                            iconView,
                            View.ROTATION,
                            0f, -15f, 15f, -15f, 15f, -15f, 15f, 0f
                        ).apply {
                            duration = 700L
                            interpolator = android.view.animation.LinearInterpolator()
                        }
                    } else null

                    // Фаза 2: Улёт (600 — 1000 мс, начинается с задержкой 600мс)
                    // Робот увеличивается, поднимается вверх и растворяется.

                    // Увеличение (scale up) — «отталкивается от земли»
                    val scaleUpAnimator = if (iconView != null) {
                        ObjectAnimator.ofPropertyValuesHolder(
                            iconView,
                            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f),
                            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f)
                        ).apply {
                            duration = 400L
                            startDelay = 600L
                            interpolator = android.view.animation.DecelerateInterpolator()
                        }
                    } else null

                    // Подъём вверх — «улетает»
                    val flyUpAnimator = if (iconView != null) {
                        ObjectAnimator.ofFloat(
                            iconView,
                            View.TRANSLATION_Y,
                            0f,
                            -dpToPx(this@MainActivity, 80).toFloat()
                        ).apply {
                            duration = 400L
                            startDelay = 600L
                            interpolator = AccelerateInterpolator()
                        }
                    } else null

                    // Fade out всего splash-view (исчезновение фона вместе с роботом)
                    val fadeOutAnimator = ObjectAnimator.ofFloat(
                        splashScreenView.view,
                        View.ALPHA,
                        1f, 0f
                    ).apply {
                        duration = 350L
                        startDelay = 650L
                        interpolator = AccelerateInterpolator()
                        // ВАЖНО: удаляем splash-view после завершения всех анимаций
                        doOnEnd { splashScreenView.remove() }
                    }

                    // Запускаем все анимации параллельно (со своими startDelay)
                    waveAnimator?.start()
                    scaleUpAnimator?.start()
                    flyUpAnimator?.start()
                    fadeOutAnimator.start()
                }
            }
        }, 1200L)

        setContent {
            val isDarkFromPrefs by prefs.isDarkTheme.collectAsState(initial = false)
            val hasManuallyChosen by prefs.hasManuallyChosenTheme.collectAsState(initial = false)
            val isDark: Boolean = if (hasManuallyChosen) isDarkFromPrefs else isSystemInDarkTheme()

            val scope = rememberCoroutineScope()
            var showOnboarding by remember { mutableStateOf(false) }
            var onboardingChecked by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val completed: Boolean = prefs.hasCompletedOnboarding.first()
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
                            vm.tryResumePendingSimpleMode()
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

    /**
     * Реальный уход в чужое Activity (настройки батареи).
     * Системный диалог MIUI поверх НЕ вызывает onStop.
     */
    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onResume() {
        super.onResume()
        AppLog.i(TAG, "onResume (wasStopped=$wasStopped)")
        if (!::vm.isInitialized) return

        vm.checkRestrictedSettingsOnResume()
        val currentState: MainUiState = vm.state.value

        // ИСПРАВЛЕНО: используем OverlayController.hide() вместо stopService()
        // для consistency с OverlayService (hide без stopSelf)
        if (!currentState.isWorking && !currentState.simpleModeActive) {
            OverlayController.hide(this)
        }

        // Обрабатываем возврат из настроек батареи в ДВУХ случаях:
        //   1. wasStopped=true  — реальный уход в чужое Activity
        //   2. isIgnoring=true  — пользователь отключил экономию через
        //      диалог MIUI поверх (который не вызывает onStop)
        if (currentState.simpleModeActive &&
            currentState.permissionSubPhase == PermissionSubPhase.BATTERY_OPTIMIZATION
        ) {
            val ignoring: Boolean = vm.permissionFlow_isIgnoringBatteryOptimizations()
            if (wasStopped || ignoring) {
                AppLog.i(
                    TAG,
                    "onResume: BATTERY_OPTIMIZATION return (wasStopped=$wasStopped, ignoring=$ignoring)"
                )
                vm.onBatteryOptimizationReturn()
            }
        }
        wasStopped = false
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
        AppLog.i(TAG, "onDestroy")
    }
}

@Composable
private fun MainContent(
    state: MainUiState,
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    vm: MainViewModel
) {
    val view = LocalView.current
    val context = LocalContext.current
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
        onDarkChange,
        context
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
                    AppLog.i(MainActivity.TAG, "menu button clicked"); menuOpen = true
                }) {
                    Text("⋮", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(600)) + slideInVertically(
                    initialOffsetY = { it / 4 }, animationSpec = tween(600)
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
                        AppLog.i(MainActivity.TAG, "optimize button clicked")
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        vm.startFlow()
                    },
                    onRestore = {
                        AppLog.i(MainActivity.TAG, "restore button clicked"); confirmRestore = true
                    },
                    onReboot = {
                        AppLog.i(
                            MainActivity.TAG,
                            "reboot button clicked"
                        ); vm.requestReboot()
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MainDialogsHost(
    state: MainUiState,
    vm: MainViewModel,
    confirmRestore: Boolean,
    onConfirmRestoreChange: (Boolean) -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    context: android.content.Context
) {
    val isAndroid14Plus: Boolean = Build.VERSION.SDK_INT >= 34

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
        val level: OptimizationMode? = state.selectedLevel
        val isSimple: Boolean = level != OptimizationMode.PRO
        InfoDialog(
            title = stringResource(if (isSimple) R.string.level_confirm_simple_title else R.string.level_confirm_advanced_title),
            text = stringResource(if (isSimple) R.string.level_confirm_simple_text else R.string.level_confirm_advanced_text),
            confirmText = stringResource(R.string.level_confirm_start),
            onConfirm = {
                AppLog.i(MainActivity.TAG, "level confirm: start clicked, level=$level")
                level?.let { vm.confirmLevelStart(it) }  // ✅ передаём level
            },
            onDismiss = {
                AppLog.i(
                    MainActivity.TAG,
                    "level confirm: cancelled"
                ); vm.cancelLevelConfirm()
            }
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
            onClose = { vm.closeShizukuSources() }
        )
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
                AppLog.i(MainActivity.TAG, "restricted dialog: open settings clicked")
                vm.restrictedDialogAgreed()
            },
            onDismiss = {
                AppLog.i(MainActivity.TAG, "restricted dialog: cancelled")
                vm.restrictedDialogCancelled()
            }
        )
    }
    if (state.showAppInfoDialog) {
        InfoDialog(
            title = stringResource(R.string.pointer_restricted_blocked),
            text = stringResource(R.string.pointer_restricted_explain),
            confirmText = stringResource(if (isAndroid14Plus) R.string.forbidden_dialog_open else R.string.restricted_dialog_open),
            onConfirm = {
                AppLog.i(
                    MainActivity.TAG,
                    "appInfo dialog: agreed"
                ); vm.appInfoDialogAgreed()
            },
            onDismiss = {
                AppLog.i(MainActivity.TAG, "appInfo dialog: cancelled")
                vm.appInfoDialogCancelled()
            }
        )
    }
    if (state.showRestrictedSettingsScreen) {
        RestrictedSettingsDialog(
            attempt = state.appInfoAttempts,
            onOpenSettings = {
                AppLog.i(MainActivity.TAG, "restricted screen: open settings clicked")
                vm.onRestrictedScreenOpenSettings()
            },
            onDone = {
                AppLog.i(MainActivity.TAG, "restricted screen: done clicked")
                vm.onRestrictedScreenDone()
            },
            onCancel = {
                AppLog.i(MainActivity.TAG, "restricted screen: cancelled")
                vm.onRestrictedScreenCancelled()
            }
        )
    }
    if (state.showBatteryDialog) {
        InfoDialog(
            title = stringResource(R.string.battery_dialog_title),
            text = stringResource(R.string.battery_dialog_text),
            confirmText = stringResource(R.string.battery_dialog_open),
            onConfirm = {
                AppLog.i(MainActivity.TAG, "battery dialog: agreed")
                vm.onBatteryDialogAgreed()
            },
            onDismiss = {
                AppLog.i(MainActivity.TAG, "battery dialog: skipped")
                vm.onBatteryDialogSkipped()
            }
        )
    }
    if (state.showPermissionFallbackDialog) {
        val (title, text) = when (state.stuckPhase) {
            PermissionSubPhase.OVERLAY ->
                stringResource(R.string.fallback_overlay_title) to stringResource(R.string.fallback_overlay_text)

            PermissionSubPhase.APP_INFO ->
                stringResource(R.string.fallback_appinfo_title) to stringResource(R.string.fallback_appinfo_text)

            else ->
                stringResource(R.string.fallback_accessibility_title) to stringResource(R.string.fallback_accessibility_text)
        }
        PermissionFallbackDialog(
            title = title,
            text = text,
            onRetry = {
                AppLog.i(
                    MainActivity.TAG,
                    "permission fallback: retry clicked for phase=${state.stuckPhase}"
                )
                vm.onPermissionFallbackRetry()
            },
            onOpenSettings = {
                AppLog.i(
                    MainActivity.TAG,
                    "permission fallback: open settings clicked for phase=${state.stuckPhase}"
                )
                vm.onPermissionFallbackOpenSettings()
            },
            onCancel = {
                AppLog.i(MainActivity.TAG, "permission fallback: cancelled (full reset)")
                vm.onPermissionFallbackCancelled()
            }
        )
    }
    if (state.showAccessibilityDialog) {
        AccessibilityConsentDialog(
            onConfirm = {
                AppLog.i(
                    MainActivity.TAG,
                    "accessibility consent dialog: confirmed with explicit consent"
                )
                vm.dialogAgreed()
            },
            onDismiss = {
                AppLog.i(MainActivity.TAG, "accessibility consent dialog: dismissed")
                vm.dialogCancelled()
            }
        )
    }
    if (state.showOverlayDialog) {
        InfoDialog(
            title = stringResource(R.string.overlay_permission_title),
            text = stringResource(R.string.overlay_permission_text),
            confirmText = stringResource(R.string.allow),
            onConfirm = { AppLog.i(MainActivity.TAG, "overlay dialog: agreed"); vm.dialogAgreed() },
            onDismiss = {
                AppLog.i(
                    MainActivity.TAG,
                    "overlay dialog: cancelled"
                ); vm.dialogCancelled()
            }
        )
    }
    if (state.showOptionsDialog) {
        OptionsDialog(
            dnsFilterEnabled = state.dnsFilterEnabled,
            aggressiveMode = state.aggressiveMode,
            onDnsToggle = { vm.toggleDnsFilter(it) },
            onAggressiveToggle = { vm.toggleAggressiveMode(it) },
            onConfirm = {
                AppLog.i(MainActivity.TAG, "options dialog: confirmed")
                vm.optionsDialogConfirmed()
            },
            onCancel = {
                AppLog.i(MainActivity.TAG, "options dialog: cancelled")
                vm.optionsDialogCancelled()
            }
        )
    }
    if (state.showDnsWarningDialog) {
        InfoDialog(
            title = stringResource(R.string.dns_warning_title),
            text = stringResource(R.string.dns_warning_text),
            confirmText = stringResource(R.string.dns_warning_accept),
            onConfirm = {
                AppLog.i(
                    MainActivity.TAG,
                    "DNS warning: accepted"
                ); vm.dnsWarningAccepted()
            },
            onDismiss = {
                AppLog.i(
                    MainActivity.TAG,
                    "DNS warning: declined"
                ); vm.dnsWarningDeclined()
            }
        )
    }
    if (state.showDevModeDialog) {
        DevModeDialog(
            onOpenDeviceInfo = {
                AppLog.i(MainActivity.TAG, "dev mode dialog: open device info")
                openDeviceInfoSettings(context)
            },
            onRetry = {
                AppLog.i(MainActivity.TAG, "dev mode dialog: retry clicked")
                vm.devModeDialogRetry()
            },
            onCancel = {
                AppLog.i(MainActivity.TAG, "dev mode dialog: cancelled")
                vm.devModeDialogCancel()
            }
        )
    }
    if (confirmRestore) {
        InfoDialog(
            title = stringResource(R.string.restore_dialog_title),
            text = stringResource(R.string.restore_dialog_text),
            confirmText = stringResource(R.string.restore_confirm),
            onConfirm = {
                AppLog.i(MainActivity.TAG, "restore dialog: confirmed")
                onConfirmRestoreChange(false); vm.restoreOptimization()
            },
            onDismiss = {
                AppLog.i(MainActivity.TAG, "restore dialog: cancelled")
                onConfirmRestoreChange(false)
            }
        )
    }
    if (state.showRebootDialog) {
        InfoDialog(
            title = stringResource(R.string.reboot_dialog_title),
            text = stringResource(R.string.reboot_dialog_text),
            confirmText = stringResource(R.string.reboot_confirm),
            onConfirm = {
                AppLog.i(
                    MainActivity.TAG,
                    "reboot dialog: confirmed"
                ); vm.confirmReboot()
            },
            onDismiss = {
                AppLog.i(
                    MainActivity.TAG,
                    "reboot dialog: dismissed"
                ); vm.dismissRebootDialog()
            }
        )
    }
    if (state.rebootFailed) {
        InfoDialog(
            title = stringResource(R.string.reboot_dialog_title),
            text = stringResource(R.string.reboot_failed_text),
            onDismiss = {
                AppLog.i(MainActivity.TAG, "reboot failed dialog: dismissed")
                vm.dismissRebootFailed()
            }
        )
    }
    if (state.restoreFailed) {
        InfoDialog(
            title = stringResource(R.string.restore_dialog_title),
            text = stringResource(R.string.restore_failed_text),
            onDismiss = {
                AppLog.i(MainActivity.TAG, "restore failed dialog: dismissed")
                vm.dismissRestoreFailed()
            }
        )
    }
    if (state.showEeaNoticeDialog) {
        InfoDialog(
            title = stringResource(R.string.eea_notice_title),
            text = stringResource(R.string.eea_notice_text, state.eeaRegionName),
            confirmText = stringResource(R.string.eea_notice_continue),
            onConfirm = {
                AppLog.i(MainActivity.TAG, "eea dialog: confirmed continue")
                vm.onEeaNoticeAgreed()
            },
            onDismiss = {
                AppLog.i(MainActivity.TAG, "eea dialog: dismissed")
                vm.onEeaNoticeCancelled()
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
            confirmText = stringResource(
                if (state.optimizationSuccess) R.string.final_dialog_rate else R.string.final_dialog_send_log
            ),
            onConfirm = {
                AppLog.i(
                    MainActivity.TAG,
                    "final dialog: confirmed, success=${state.optimizationSuccess}"
                )
                vm.dismissFinalDialog()
                // ИСПРАВЛЕНО: используем функции из UiActions.kt
                if (state.optimizationSuccess) openRateApp(context) else shareLog(context)
            },
            onDismiss = {
                AppLog.i(
                    MainActivity.TAG,
                    "final dialog: dismissed"
                ); vm.dismissFinalDialog()
            }
        )
    }
    if (menuOpen) {
        val privacyUrl: String = stringResource(R.string.privacy_policy_url)
        MenuDialog(
            isDark = isDark,
            onDarkChange = onDarkChange,
            onClose = { AppLog.i(MainActivity.TAG, "menu: closed"); onMenuOpenChange(false) },
            onRate = { AppLog.i(MainActivity.TAG, "menu: rate clicked"); openRateApp(context) },
            onYooMoney = {
                AppLog.i(MainActivity.TAG, "menu: yoomoney clicked")
                openWebView(context, "https://yoomoney.ru/to/410011379195150", "ЮMoney")
            },
            onCloudTips = {
                AppLog.i(MainActivity.TAG, "menu: cloudtips clicked")
                openWebView(context, "https://pay.cloudtips.ru/p/90614cff", "CloudTips")
            },
            onShareLog = {
                AppLog.i(
                    MainActivity.TAG,
                    "menu: share log clicked"
                ); shareLog(context)
            },
            onPrivacyPolicyClick = {
                AppLog.i(MainActivity.TAG, "menu: privacy policy clicked")
                openUrl(context, privacyUrl)
            }
        )
    }
}