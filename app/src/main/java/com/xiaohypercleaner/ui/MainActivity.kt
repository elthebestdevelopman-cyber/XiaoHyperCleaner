package com.xiaohypercleaner.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
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
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.ui.components.InfoDialog
import com.xiaohypercleaner.ui.components.MenuDialog
import com.xiaohypercleaner.ui.theme.Blue500
import com.xiaohypercleaner.ui.theme.DarkGradientEnd
import com.xiaohypercleaner.ui.theme.DarkGradientStart
import com.xiaohypercleaner.ui.theme.GradientEnd
import com.xiaohypercleaner.ui.theme.GradientStart
import com.xiaohypercleaner.ui.theme.XiaoHyperCleanerTheme
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainAct"
    }

    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "onCreate started")

        setContent {
            val prefs = (application as XiaoHyperApp).preferencesManager
            val isDark by prefs.isDarkTheme.collectAsState(initial = false)
            val scope = rememberCoroutineScope()
            vm = viewModel()
            val state by vm.state.collectAsState()
            val lifecycle = LocalLifecycleOwner.current.lifecycle

            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    AppLog.i(TAG, "lifecycle RESUMED — refreshing statuses")
                    vm.refreshStatuses()
                    vm.checkRestrictedSettingsOnResume()
                }
            }

            XiaoHyperCleanerTheme(darkTheme = isDark) {
                MainContent(
                    state = state,
                    isDark = isDark,
                    onDarkChange = { enabled -> scope.launch { prefs.setDarkTheme(enabled) } },
                    vm = vm
                )
            }
        }
        AppLog.i(TAG, "onCreate completed")
    }

    override fun onResume() {
        super.onResume()
        AppLog.i(TAG, "onResume")
        if (::vm.isInitialized) {
            vm.checkRestrictedSettingsOnResume()
        }
    }

    override fun onPause() {
        super.onPause()
        AppLog.i(TAG, "onPause")
    }

    override fun onDestroy() {
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

    if (state.showRestrictedDialog) {
        val isAndroid14Plus = Build.VERSION.SDK_INT >= 34
        val title = if (isAndroid14Plus) {
            stringResource(R.string.forbidden_dialog_title)
        } else {
            stringResource(R.string.restricted_dialog_title)
        }
        val text = if (isAndroid14Plus) {
            stringResource(R.string.forbidden_dialog_text)
        } else {
            stringResource(R.string.restricted_dialog_text)
        }
        val buttonText = if (isAndroid14Plus) {
            stringResource(R.string.forbidden_dialog_open)
        } else {
            stringResource(R.string.restricted_dialog_open)
        }

        InfoDialog(
            title = title,
            text = text,
            confirmText = buttonText,
            onConfirm = {
                AppLog.i(
                    "MainUI",
                    "restricted/forbidden dialog: open settings clicked (Android ${Build.VERSION.SDK_INT})"
                )
                vm.dismissRestrictedDialog()
                openAppInfoSettings(context)
            },
            onDismiss = {
                AppLog.i("MainUI", "restricted/forbidden dialog: dismissed")
                vm.dialogCancelled()
            }
        )
    }

    if (state.showAccessibilityDialog) {
        InfoDialog(
            title = stringResource(R.string.accessibility_explanation_title),
            text = stringResource(R.string.accessibility_explanation_text),
            confirmText = stringResource(R.string.agree_and_open),
            onConfirm = {
                AppLog.i("MainUI", "accessibility dialog: agreed")
                vm.dialogAgreed()
                openAccessibilitySettings(context)
            },
            onDismiss = {
                AppLog.i("MainUI", "accessibility dialog: cancelled")
                vm.dialogCancelled()
            }
        )
    }

    if (state.showOverlayDialog) {
        InfoDialog(
            title = stringResource(R.string.overlay_permission_title),
            text = stringResource(R.string.overlay_permission_text),
            confirmText = stringResource(R.string.allow),
            onConfirm = {
                AppLog.i("MainUI", "overlay dialog: agreed")
                vm.dialogAgreed()
                openOverlaySettings(context)
            },
            onDismiss = {
                AppLog.i("MainUI", "overlay dialog: cancelled")
                vm.dialogCancelled()
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
            text = if (state.optimizationSuccess) {
                stringResource(R.string.final_dialog_success_text)
            } else {
                stringResource(R.string.final_dialog_failed_text)
            },
            confirmText = if (state.optimizationSuccess) {
                stringResource(R.string.final_dialog_rate)
            } else {
                stringResource(R.string.final_dialog_send_log)
            },
            onConfirm = {
                AppLog.i("MainUI", "final dialog: confirmed, success=${state.optimizationSuccess}")
                vm.dismissFinalDialog()
                if (state.optimizationSuccess) {
                    openRateApp(context)
                } else {
                    shareLog(context)
                }
            },
            onDismiss = {
                AppLog.i("MainUI", "final dialog: dismissed")
                vm.dismissFinalDialog()
            }
        )
    }

    if (menuOpen) {
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
            FeatureRow(Icons.Filled.PrivacyTip, stringResource(R.string.feature_processes))
            Spacer(Modifier.height(8.dp))
            FeatureRow(Icons.Filled.Settings, stringResource(R.string.feature_speed))
            Spacer(Modifier.height(8.dp))
            FeatureRow(Icons.Filled.VerifiedUser, stringResource(R.string.feature_battery))
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
    Text(
        stringResource(R.string.status_working),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
        color = Blue500
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "${(progress * 100).toInt()}%",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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

private fun openUrl(context: Context, url: String) {
    AppLog.i("OpenUrl", "opening url: $url")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        AppLog.e("OpenUrl", "failed to open url: ${e.message}")
    }
}

private fun openWebView(context: Context, url: String, title: String) {
    AppLog.i("WebView", "opening webView: $url")
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
            AppLog.w(
                "OpenSettings",
                "alternative also failed, fallback to general settings: ${e2.message}"
            )
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
            }
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