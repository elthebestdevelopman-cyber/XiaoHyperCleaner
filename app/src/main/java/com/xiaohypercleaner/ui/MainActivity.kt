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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.ui.theme.Blue500
import com.xiaohypercleaner.ui.theme.DarkGradientEnd
import com.xiaohypercleaner.ui.theme.DarkGradientStart
import com.xiaohypercleaner.ui.theme.GradientEnd
import com.xiaohypercleaner.ui.theme.GradientStart
import com.xiaohypercleaner.ui.theme.XiaoHyperCleanerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = (application as XiaoHyperApp).preferencesManager
            val isDark by prefs.isDarkTheme.collectAsState(initial = false)
            val scope = rememberCoroutineScope()
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsState()

            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    vm.refreshStatuses()
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

    if (state.showAccessibilityDialog) {
        AccessibilityDialog(
            onAgree = {
                vm.dialogAgreed()
                openAccessibilitySettings(context)
            },
            onClose = { vm.dialogCancelled() }
        )
    }

    if (state.showOverlayDialog) {
        OverlayPermissionDialog(
            onAllow = {
                vm.dialogAgreed()
                openOverlaySettings(context)
            },
            onCancel = { vm.dialogCancelled() }
        )
    }

    if (confirmRestore) {
        RestoreConfirmDialog(
            onConfirm = {
                confirmRestore = false
                vm.restoreOptimization()
            },
            onCancel = { confirmRestore = false }
        )
    }

    if (state.showRebootDialog) {
        RebootConfirmDialog(
            onConfirm = vm::confirmReboot,
            onCancel = vm::dismissRebootDialog
        )
    }

    if (state.rebootFailed) {
        RebootFailedDialog(onClose = vm::dismissRebootFailed)
    }

    if (state.restoreFailed) {
        RestoreFailedDialog(onClose = vm::dismissRestoreFailed)
    }

    if (menuOpen) {
        MenuDialog(
            isDark = isDark,
            onDarkChange = onDarkChange,
            onClose = { menuOpen = false },
            onRate = { openRateApp(context) },
            onYooMoney = { openUrl(context, "https://yoomoney.ru/to/410011379195150") },
            onCloudTips = { openUrl(context, "https://pay.cloudtips.ru/p/90614cff") }
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
                IconButton(onClick = { menuOpen = true }) {
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
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        vm.startFlow()
                    },
                    onRestore = { confirmRestore = true },
                    onReboot = vm::requestReboot
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

@Composable
private fun RestoreConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.restore_dialog_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = { Text(stringResource(R.string.restore_dialog_text)) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RestoreFailedDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        AlertDialog(
            onDismissRequest = onClose,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.restore_dialog_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = { Text(stringResource(R.string.restore_failed_text)) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun RebootConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(stringResource(R.string.reboot_dialog_title), fontWeight = FontWeight.SemiBold)
            },
            text = { Text(stringResource(R.string.reboot_dialog_text)) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.reboot_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RebootFailedDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        AlertDialog(
            onDismissRequest = onClose,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(stringResource(R.string.reboot_dialog_title), fontWeight = FontWeight.SemiBold)
            },
            text = { Text(stringResource(R.string.reboot_failed_text)) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun OverlayPermissionDialog(onAllow: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.overlay_permission_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = { Text(stringResource(R.string.overlay_permission_text)) },
            confirmButton = {
                TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun AccessibilityDialog(onAgree: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        AlertDialog(
            onDismissRequest = onClose,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.accessibility_explanation_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = { Text(stringResource(R.string.accessibility_explanation_text)) },
            confirmButton = {
                TextButton(onClick = onAgree) { Text(stringResource(R.string.agree_and_open)) }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun MenuDialog(
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onRate: () -> Unit,
    onYooMoney: () -> Unit,
    onCloudTips: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.about_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_version),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_text),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_author),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onRate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.rate_app)) }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.menu_dark_theme),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isDark,
                        onCheckedChange = onDarkChange,
                        modifier = Modifier.scale(0.8f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.support_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onYooMoney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.support_yoomoney)) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCloudTips,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.support_cloudtips)) }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}

private fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

private fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(context, AdbEnablerService::class.java)
    val flattened = component.flattenToString()

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
        return
    } catch (_: Exception) {
    }

    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (_: Exception) {
    }
}

private fun openRateApp(context: Context) {
    val pkg = context.packageName
    val schemes = listOf(
        "rustore://application/$pkg",
        "mimarket://details?id=$pkg",
        "market://details?id=$pkg"
    )
    for (s in schemes) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s)))
            return
        } catch (_: Exception) {
        }
    }
    openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
}