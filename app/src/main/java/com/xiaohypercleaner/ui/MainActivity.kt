package com.xiaohypercleaner.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.ui.components.InfoDialog
import com.xiaohypercleaner.ui.components.MenuDialog
import com.xiaohypercleaner.ui.components.OptimizationLevelDialog
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
                if (::vm.isInitialized) {
                    vm.onShizukuPermissionResult(granted)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "onCreate started")

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        val prefs = (application as XiaoHyperApp).preferencesManager

        setContent {
            val isDarkFromPrefs by prefs.isDarkTheme.collectAsState(initial = false)
            val hasManuallyChosen by prefs.hasManuallyChosenTheme.collectAsState(initial = false)
            val isSystemDark = isSystemInDarkTheme()
            val isDark = if (hasManuallyChosen) isDarkFromPrefs else isSystemDark

            val scope = rememberCoroutineScope()
            var showOnboarding by remember { mutableStateOf(false) }
            var onboardingChecked by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val completed = prefs.hasCompletedOnboarding.first()
                AppLog.i(TAG, "hasCompletedOnboarding=$completed")
                showOnboarding = !completed
                onboardingChecked = true
            }

            if (!onboardingChecked) {
                return@setContent
            }

            if (showOnboarding) {
                XiaoHyperCleanerTheme(darkTheme = isDark) {
                    OnboardingScreen(
                        isDark = isDark,
                        onFinish = {
                            AppLog.i(TAG, "onboarding finished")
                            showOnboarding = false
                            scope.launch {
                                prefs.setHasCompletedOnboarding(true)
                            }
                        }
                    )
                }
            } else {
                vm = viewModel()
                val state by vm.state.collectAsState()
                val lifecycle = LocalLifecycleOwner.current.lifecycle

                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        AppLog.i(TAG, "lifecycle RESUMED — refreshing statuses")
                        vm.refreshStatuses()
                    }
                }

                XiaoHyperCleanerTheme(darkTheme = isDark) {
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
        if (::vm.isInitialized) {
            vm.checkRestrictedSettingsOnResume()
            if (!vm.state.value.isWorking) {
                stopService(Intent(this, OverlayService::class.java))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppLog.i(TAG, "onPause")
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
    val context = LocalContext.current
    val view = LocalView.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    AppLog.i("MainUI", "MainContent composed: state=$state")

    // ===== ПРОСТОЙ/ПРОДВИНУТЫЙ РЕЖИМ: выбор уровня =====
    if (state.showLevelDialog) {
        OptimizationLevelDialog(
            onModeSelected = { mode -> vm.onLevelChosen(mode) },
            onDismiss = { /* закрываем диалог без отмены — просто скрываем */ }
        )
    }

    // Диалог подтверждения выбора режима
    if (state.showLevelConfirm) {
        val modeName = when (state.selectedLevel) {
            com.xiaohypercleaner.data.OptimizationMode.SIMPLE -> "Простой"
            com.xiaohypercleaner.data.OptimizationMode.PRO -> "Продвинутый"
            null -> "Простой"
        }
        InfoDialog(
            title = "Подтверждение режима",
            text = "Вы выбрали $modeName режим оптимизации. Продолжить?",
            confirmText = "Да",
            onConfirm = { vm.confirmLevelStart() },
            onDismiss = { vm.cancelLevelConfirm() }
        )
    }

    // Экран текущего шага простой оптимизации
    if (state.simpleStep != null) {
        val configuration = LocalConfiguration.current
        val isEnglish = configuration.locales.get(0)?.language != "ru"

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

    // Финальный экран после всех шагов простой оптимизации
    if (state.simpleDone != null) {
        SimpleDoneDialog(
            completedCount = state.simpleDone.first,
            totalCount = state.simpleDone.second,
            // ✅ ИСПРАВЛЕНО: передаём реальные failedSteps из ViewModel
            // вместо emptyList() — чтобы экран показывал реальную пользу
            failedSteps = vm.getFailedStepIds(),
            onRate = { openRateApp(context) },
            onDonate = { openWebView(context, "https://yoomoney.ru/to/410011379195150", "ЮMoney") },
            onClose = { vm.closeSimpleMode() }
        )
    }

    // ===== SHIZUKU диалоги =====
    if (state.showShizukuDialog) {
        ShizukuGuideDialog(
            status = state.shizukuStatus,
            onInstall = { vm.shizukuDialogInstall() },
            onOpenApp = { vm.shizukuDialogOpenApp() },
            onSources = { vm.openShizukuSources() },
            onDismiss = { vm.shizukuDialogLater() }
        )
    }

    // Выбор альтернативного источника, если Google Play не дал установить
    if (state.showShizukuSources) {
        ShizukuSourcesDialog(
            onSource = { source -> vm.installFromSource(source) },
            onClose = { vm.closeShizukuSources() }
        )
    }

    // Мастер пошагового запуска Shizuku
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

    // ===== ПРОДВИНУТЫЙ РЕЖИМ: разрешения =====
    if (state.showRestrictedDialog) {
        val isAndroid14Plus = Build.VERSION.SDK_INT >= 34
        val hintRestricted = stringResource(R.string.hint_restricted)
        InfoDialog(
            title = if (isAndroid14Plus) stringResource(R.string.forbidden_dialog_title)
            else stringResource(R.string.restricted_dialog_title),
            text = if (isAndroid14Plus) stringResource(R.string.forbidden_dialog_text)
            else stringResource(R.string.restricted_dialog_text),
            confirmText = if (isAndroid14Plus) stringResource(R.string.forbidden_dialog_open)
            else stringResource(R.string.restricted_dialog_open),
            onConfirm = {
                AppLog.i("MainUI", "restricted dialog: open settings clicked")
                vm.restrictedDialogAgreed()
                vm.markAppInfoOpened()
                openAppInfoSettings(context)
                showHintOverlay(context, hintRestricted)
            },
            onDismiss = {
                AppLog.i("MainUI", "restricted dialog: cancelled")
                vm.restrictedDialogCancelled()
            }
        )
    }

    if (state.showAccessibilityDialog) {
        val hintAccessibility = stringResource(R.string.hint_accessibility)
        InfoDialog(
            title = stringResource(R.string.accessibility_explanation_title),
            text = stringResource(R.string.accessibility_explanation_text),
            confirmText = stringResource(R.string.agree_and_open),
            onConfirm = {
                AppLog.i("MainUI", "accessibility dialog: agreed")
                vm.dialogAgreed()
                com.xiaohypercleaner.service.ChainFlags.waitingAccessibilityReturn = true
                vm.markAccessibilityOpened()
                openAccessibilitySettings(context)
                showHintOverlay(context, hintAccessibility)
            },
            onDismiss = {
                AppLog.i("MainUI", "accessibility dialog: cancelled")
                vm.dialogCancelled()
            }
        )
    }

    if (state.showOverlayDialog) {
        val hintOverlay = stringResource(R.string.hint_overlay)
        InfoDialog(
            title = stringResource(R.string.overlay_permission_title),
            text = stringResource(R.string.overlay_permission_text),
            confirmText = stringResource(R.string.allow),
            onConfirm = {
                AppLog.i("MainUI", "overlay dialog: agreed")
                vm.dialogAgreed()
                openOverlaySettings(context)
                showHintOverlay(context, hintOverlay)
            },
            onDismiss = {
                AppLog.i("MainUI", "overlay dialog: cancelled")
                vm.dialogCancelled()
            }
        )
    }

    if (state.showOptionsDialog) {
        OptionsDialog(
            dnsFilterEnabled = state.dnsFilterEnabled,
            aggressiveMode = state.aggressiveMode,
            onDnsToggle = { enabled -> vm.toggleDnsFilter(enabled) },
            onAggressiveToggle = { enabled -> vm.toggleAggressiveMode(enabled) },
            onConfirm = {
                AppLog.i("MainUI", "options dialog: confirmed")
                vm.optionsDialogConfirmed()
            },
            onCancel = {
                AppLog.i("MainUI", "options dialog: cancelled")
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
                AppLog.i("MainUI", "DNS warning: accepted")
                vm.dnsWarningAccepted()
            },
            onDismiss = {
                AppLog.i("MainUI", "DNS warning: declined")
                vm.dnsWarningDeclined()
            }
        )
    }

    if (state.showDevModeDialog) {
        DevModeDialog(
            onOpenDeviceInfo = {
                AppLog.i("MainUI", "dev mode dialog: open device info")
                openDeviceInfoSettings(context)
            },
            onRetry = {
                AppLog.i("MainUI", "dev mode dialog: retry clicked")
                vm.devModeDialogRetry()
            },
            onCancel = {
                AppLog.i("MainUI", "dev mode dialog: cancelled")
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
                AppLog.i("MainUI", "restore dialog: confirmed")
                confirmRestore = false
                vm.restoreOptimization()
            },
            onDismiss = {
                AppLog.i("MainUI", "restore dialog: cancelled")
                confirmRestore = false
            }
        )
    }

    if (state.showRebootDialog) {
        InfoDialog(
            title = stringResource(R.string.reboot_dialog_title),
            text = stringResource(R.string.reboot_dialog_text),
            confirmText = stringResource(R.string.reboot_confirm),
            onConfirm = {
                AppLog.i("MainUI", "reboot dialog: confirmed")
                vm.confirmReboot()
            },
            onDismiss = {
                AppLog.i("MainUI", "reboot dialog: dismissed")
                vm.dismissRebootDialog()
            }
        )
    }

    if (state.rebootFailed) {
        InfoDialog(
            title = stringResource(R.string.reboot_dialog_title),
            text = stringResource(R.string.reboot_failed_text),
            onDismiss = {
                AppLog.i("MainUI", "reboot failed dialog: dismissed")
                vm.dismissRebootFailed()
            }
        )
    }

    if (state.restoreFailed) {
        InfoDialog(
            title = stringResource(R.string.restore_dialog_title),
            text = stringResource(R.string.restore_failed_text),
            onDismiss = {
                AppLog.i("MainUI", "restore failed dialog: dismissed")
                vm.dismissRestoreFailed()
            }
        )
    }

    if (state.showFinalDialog) {
        InfoDialog(
            title = stringResource(R.string.final_dialog_title),
            text = if (state.finalReport.isNotEmpty()) state.finalReport
            else if (state.optimizationSuccess) stringResource(R.string.final_dialog_success_text)
            else stringResource(R.string.final_dialog_failed_text),
            confirmText = if (state.optimizationSuccess) stringResource(R.string.final_dialog_rate)
            else stringResource(R.string.final_dialog_send_log),
            onConfirm = {
                AppLog.i("MainUI", "final dialog: confirmed, success=${state.optimizationSuccess}")
                vm.dismissFinalDialog()
                if (state.optimizationSuccess) openRateApp(context) else shareLog(context)
            },
            onDismiss = {
                AppLog.i("MainUI", "final dialog: dismissed")
                vm.dismissFinalDialog()
            }
        )
    }

    if (menuOpen) {
        // ✅ ПОЛУЧАЕМ URL В COMPOSABLE-КОНТЕКСТЕ (до передачи в лямбду)
        val privacyUrl = stringResource(R.string.privacy_policy_url)

        MenuDialog(
            isDark = isDark,
            onDarkChange = onDarkChange,
            onClose = {
                AppLog.i("MainUI", "menu: closed")
                menuOpen = false
            },
            onRate = {
                AppLog.i("MainUI", "menu: rate clicked")
                openRateApp(context)
            },
            onYooMoney = {
                AppLog.i("MainUI", "menu: yoomoney clicked")
                openWebView(context, "https://yoomoney.ru/to/410011379195150", "ЮMoney")
            },
            onCloudTips = {
                AppLog.i("MainUI", "menu: cloudtips clicked")
                openWebView(context, "https://pay.cloudtips.ru/p/90614cff", "CloudTips")
            },
            onShareLog = {
                AppLog.i("MainUI", "menu: share log clicked")
                shareLog(context)
            },
            onPrivacyPolicyClick = {
                AppLog.i("MainUI", "menu: privacy policy clicked")
                openUrl(context, privacyUrl)  // ✅ используем уже полученную строку
            }
        )
    }

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
                    AppLog.i("MainUI", "menu button clicked")
                    menuOpen = true
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
            ) {
                InfoCard()
            }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(600, delayMillis = 150)) + slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(600, delayMillis = 150)
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
                        AppLog.i("MainUI", "restore button clicked")
                        confirmRestore = true
                    },
                    onReboot = {
                        AppLog.i("MainUI", "reboot button clicked")
                        vm.requestReboot()
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
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

    val title = stringResource(R.string.shizuku_dialog_title)
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
                    title,
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
                    onClick = {
                        if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) {
                            onInstall()
                        } else {
                            primaryAction()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) {
                            stringResource(R.string.shizuku_dialog_install)
                        } else {
                            primaryText
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Только если Shizuku не установлен — предлагаем альтернативные магазины
                if (status == ShizukuExecutor.Status.NOT_INSTALLED) {
                    androidx.compose.material3.TextButton(
                        onClick = onSources,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.shizuku_card_other_sources))
                    }
                }

                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.shizuku_dialog_later))
                }
            }
        }
    }
}

@Composable
private fun OptionsDialog(
    dnsFilterEnabled: Boolean,
    aggressiveMode: Boolean,
    onDnsToggle: (Boolean) -> Unit,
    onAggressiveToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
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
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

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
                    Switch(
                        checked = dnsFilterEnabled,
                        onCheckedChange = onDnsToggle
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

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
                    Switch(
                        checked = aggressiveMode,
                        onCheckedChange = onAggressiveToggle
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.options_dialog_start))
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun DevModeDialog(
    onOpenDeviceInfo: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
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
                ) {
                    Text(stringResource(R.string.dev_mode_dialog_open_about))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.dev_mode_dialog_retry))
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
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
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
    onOptimize: () -> Unit,
    onRestore: () -> Unit,
    onReboot: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val stageKey = when {
                state.isWorking -> "working"
                state.isOptimized -> "done"
                else -> "ready"
            }
            AnimatedContent(
                targetState = stageKey,
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.95f)) togetherWith
                            (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.95f))
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
    val percent = progress.toInt().coerceIn(0, 100)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
            "$percent%",
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

private fun showHintOverlay(context: Context, text: String) {
    AppLog.i("Overlay", "showing hint overlay")
    try {
        val intent = Intent(context, OverlayService::class.java)
        intent.putExtra("hint", text)
        context.startService(intent)
    } catch (e: Exception) {
        AppLog.w("Overlay", "failed to show hint: ${e.message}")
    }
}

private fun openUrl(context: Context, url: String) {
    AppLog.i("OpenUrl", "opening url: $url")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        AppLog.e("OpenUrl", "failed to open url: ${e.message}")
    }
}

private fun openWebView(context: Context, url: String, title: String) {
    AppLog.i("WebView", "opening WebView: $url")
    try {
        val intent = Intent(context, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            putExtra(WebViewActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        AppLog.w("WebView", "WebView failed, fallback to browser: ${e.message}")
        openUrl(context, url)
    }
}

private fun shareLog(context: Context) {
    AppLog.i("ShareLog", "shareLog requested")
    try {
        AppLog.i("ShareLog", "preparing log file for sharing")

        val logFile = AppLog.getLogFile()
        if (logFile == null) {
            AppLog.w("ShareLog", "log file is null (beta logging not initialized)")
            return
        }
        AppLog.i(
            "ShareLog",
            "log file: ${logFile.absolutePath}, exists=${logFile.exists()}, size=${logFile.length()}"
        )

        if (!logFile.exists() || logFile.length() == 0L) {
            AppLog.w("ShareLog", "log file is empty or not exists")
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )
        AppLog.i("ShareLog", "FileProvider uri: $uri")

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "XiaoHyperCleaner log ${System.currentTimeMillis()}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share log"))
        AppLog.i("ShareLog", "share intent sent successfully")
    } catch (e: Exception) {
        AppLog.e("ShareLog", "shareLog failed", e)
    }
}

private fun openAppInfoSettings(context: Context) {
    AppLog.i("OpenSettings", "opening app info settings for restricted/forbidden settings")
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
        AppLog.i("OpenSettings", "app info settings opened successfully")
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "app info failed, trying alternative: ${e.message}")
        try {
            val intent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i("OpenSettings", "alternative app info opened")
        } catch (e2: Exception) {
            AppLog.w("OpenSettings", "alternative also failed, fallback: ${e2.message}")
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
            }
        }
    }
}

private fun openDeviceInfoSettings(context: Context) {
    AppLog.i("OpenSettings", "opening device info settings (to enable developer mode)")
    try {
        context.startActivity(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        AppLog.i("OpenSettings", "device info settings opened")
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "device info failed: ${e.message}")
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }
}

private fun openDevOptionsSettings(context: Context) {
    AppLog.i("OpenSettings", "opening developer options")
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "dev options failed: ${e.message}")
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }
}

private fun openOverlaySettings(context: Context) {
    AppLog.i("OpenSettings", "opening overlay settings")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.w("OpenSettings", "overlay settings failed: ${e.message}")
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    AppLog.i("OpenSettings", "opening accessibility settings")
    val component = ComponentName(context, AdbEnablerService::class.java)
    val flattened = component.flattenToString()
    AppLog.i("OpenSettings", "component flattened: $flattened")

    val deep = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    val args = Bundle()
    args.putString("componentName", flattened)
    deep.putExtra(
        ":settings:show_fragment",
        "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
    )
    deep.putExtra(":settings:show_fragment_args", args)
    try {
        context.startActivity(deep)
        AppLog.i("OpenSettings", "deep link to accessibility opened")
        return
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "deep link failed: ${e.message}")
    }
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        AppLog.i("OpenSettings", "fallback to general accessibility settings")
    } catch (e: Exception) {
        AppLog.e("OpenSettings", "all accessibility open attempts failed", e)
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
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s)))
            AppLog.i("OpenRate", "opened via scheme: $s")
            return
        } catch (e: Exception) {
            AppLog.w("OpenRate", "scheme $s failed: ${e.message}")
        }
    }
    AppLog.i("OpenRate", "fallback to web")
    openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
}