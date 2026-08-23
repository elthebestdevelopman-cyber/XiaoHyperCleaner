package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.util.ShizukuHelper
import androidx.compose.ui.platform.LocalContext

// ═══════════════════════════════════════════════════════════════
// ОБЩИЕ ПОМОЩНИКИ — сокращают бойлерплейт всех диалогов
// ═══════════════════════════════════════════════════════════════

/** Стандартный диалог флоу: Surface + скроллируемая Column */
@Composable
fun FlowDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                content = content
            )
        }
    }
}

/** Заголовок диалога */
@Composable
fun ColumnScope.DialogTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
}

/** Кнопка на всю ширину */
@Composable
fun ColumnScope.FullWidthButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text) }
    Spacer(Modifier.height(8.dp))
}

/** Текстовая кнопка на всю ширину */
@Composable
fun ColumnScope.FullWidthTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
    Spacer(Modifier.height(8.dp))
}

/** Строка с переключателем (для OptionsDialog) */
@Composable
private fun ColumnScope.ToggleRow(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(16.dp))
}

// ═══════════════════════════════════════════════════════════════
// ДИАЛОГИ ФЛОУ
// ═══════════════════════════════════════════════════════════════

/**
 * Fallback-диалог после исчерпания попыток.
 * Кнопки рендерятся внутри text-слота AlertDialog (вертикально, во всю ширину),
 * чтобы текст гарантированно влезал на любой экран.
 */
@Composable
fun PermissionFallbackDialog(
    title: String,
    text: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
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
                        textAlign = TextAlign.Center, maxLines = 2, softWrap = true
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
                        textAlign = TextAlign.Center, maxLines = 2, softWrap = true
                    )
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.fallback_cancel),
                        textAlign = TextAlign.Center, maxLines = 2, softWrap = true
                    )
                }
            }
        },
        confirmButton = { /* кнопки внутри text */ },
        dismissButton = { /* кнопки внутри text */ },
        shape = RoundedCornerShape(20.dp)
    )
}

/** Диалог установки/запуска Shizuku */
@Composable
fun ShizukuGuideDialog(
    status: ShizukuExecutor.Status,
    onInstall: () -> Unit,
    onOpenApp: () -> Unit,
    onSources: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isInstalled = remember { ShizukuHelper.isInstalled(context) }

    // AVAILABLE отсекаем сразу — диалог не показывается
    val data = when (status) {
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
    val (text, primaryText, primaryAction) = data

    FlowDialog(onDismiss) {
        DialogTitle(stringResource(R.string.shizuku_dialog_title))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        FullWidthButton(
            text = if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) primaryText
            else primaryText,
            onClick = {
                if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) onInstall()
                else primaryAction()
            }
        )
        if (status == ShizukuExecutor.Status.NOT_INSTALLED) {
            FullWidthTextButton(stringResource(R.string.shizuku_card_other_sources), onSources)
        }
        FullWidthTextButton(stringResource(R.string.shizuku_dialog_later), onDismiss)
    }
}

/** Выбор расположения пункта «Ограниченные настройки» */
@Composable
fun LocationChoiceDialog(
    onLocation: (RestrictedLocation) -> Unit,
    onDismiss: () -> Unit
) {
    FlowDialog(onDismiss) {
        DialogTitle(stringResource(R.string.pointer_restricted_not_found_title))
        Text(
            stringResource(R.string.pointer_restricted_location_question),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        FullWidthButton(stringResource(R.string.pointer_restricted_menu_option)) {
            onLocation(RestrictedLocation.TOP_MENU)
        }
        FullWidthButton(stringResource(R.string.pointer_restricted_bottom_option)) {
            onLocation(RestrictedLocation.BOTTOM_LIST)
        }
        FullWidthTextButton(stringResource(R.string.pointer_restricted_absent_option)) {
            onLocation(RestrictedLocation.ABSENT)
        }
    }
}

/** Опции перед оптимизацией: DNS-фильтр + расширенный режим */
@Composable
fun OptionsDialog(
    dnsFilterEnabled: Boolean,
    aggressiveMode: Boolean,
    onDnsToggle: (Boolean) -> Unit,
    onAggressiveToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    FlowDialog(onCancel) {
        DialogTitle(stringResource(R.string.options_dialog_title))
        Text(
            stringResource(R.string.options_dialog_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            title = stringResource(R.string.dns_option_title),
            subtitle = stringResource(R.string.dns_option_description),
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checked = dnsFilterEnabled,
            onCheckedChange = onDnsToggle
        )
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            title = stringResource(R.string.aggressive_option_title),
            subtitle = stringResource(R.string.aggressive_option_description),
            subtitleColor = MaterialTheme.colorScheme.error,
            checked = aggressiveMode,
            onCheckedChange = onAggressiveToggle
        )
        FullWidthButton(stringResource(R.string.options_dialog_start), onConfirm)
        FullWidthTextButton(stringResource(R.string.cancel), onCancel)
    }
}

/** Диалог «нужен режим разработчика» */
@Composable
fun DevModeDialog(
    onOpenDeviceInfo: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    FlowDialog(onCancel) {
        DialogTitle(stringResource(R.string.dev_mode_dialog_title))
        Text(
            stringResource(R.string.dev_mode_dialog_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        FullWidthButton(stringResource(R.string.dev_mode_dialog_open_about), onOpenDeviceInfo)
        FullWidthButton(stringResource(R.string.dev_mode_dialog_retry), onRetry)
        FullWidthTextButton(stringResource(R.string.cancel), onCancel)
    }
}