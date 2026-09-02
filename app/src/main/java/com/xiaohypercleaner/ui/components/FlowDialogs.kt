package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.ShizukuHelper

private const val TAG = "FlowDialogs"

// ═══════════════════════════════════════════════════════════════
// ОБЩИЕ ПОМОЩНИКИ — сокращают бойлерплейт всех диалогов
// ═══════════════════════════════════════════════════════════════

/**
 * Стандартный диалог флоу: Surface + скроллируемая Column.
 *
 * Базовый контейнер для всех диалогов потока разрешений.
 * Обеспечивает единообразный вид и поведение.
 *
 * @param onDismiss Callback при закрытии диалога
 * @param content Контент диалога (ColumnScope для доступа к Column-методам)
 */
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

/**
 * Заголовок диалога с центрированием.
 *
 * @param text Текст заголовка
 */
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

/**
 * Кнопка на всю ширину с фиксированной высотой.
 *
 * @param text Текст кнопки
 * @param onClick Callback при нажатии
 */
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

/**
 * Текстовая кнопка на всю ширину.
 *
 * @param text Текст кнопки
 * @param onClick Callback при нажатии
 */
@Composable
fun ColumnScope.FullWidthTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
    Spacer(Modifier.height(8.dp))
}

/**
 * Строка с переключателем (для OptionsDialog).
 *
 * @param title Заголовок опции
 * @param subtitle Описание опции
 * @param subtitleColor Цвет описания
 * @param checked Текущее состояние переключателя
 * @param onCheckedChange Callback при изменении состояния
 */
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
 * Fallback-диалог после исчерпания попыток запроса разрешений.
 *
 * Показывается, когда пользователь несколько раз не смог выполнить действие
 * (например, включить Accessibility Service).
 *
 * Кнопки рендерятся внутри text-слота AlertDialog (вертикально, во всю ширину),
 * чтобы текст гарантированно влезал на любой экран.
 *
 * @param title Заголовок диалога
 * @param text Описание проблемы
 * @param onRetry Callback при нажатии "Повторить"
 * @param onOpenSettings Callback при нажатии "Открыть настройки"
 * @param onCancel Callback при нажатии "Отмена" (полный сброс)
 */
@Composable
fun PermissionFallbackDialog(
    title: String,
    text: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AppLog.d(TAG, "PermissionFallbackDialog shown")

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        AppLog.i(TAG, "PermissionFallbackDialog: retry clicked")
                        onRetry()
                    },
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
                    onClick = {
                        AppLog.i(TAG, "PermissionFallbackDialog: open settings clicked")
                        onOpenSettings()
                    },
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
                TextButton(
                    onClick = {
                        AppLog.i(TAG, "PermissionFallbackDialog: cancel clicked")
                        onCancel()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
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

/**
 * Модель данных для ShizukuGuideDialog.
 *
 * @param text Описание текущего статуса
 * @param primaryText Текст основной кнопки
 * @param primaryAction Callback основной кнопки
 */
private data class ShizukuDialogData(
    val text: String,
    val primaryText: String,
    val primaryAction: () -> Unit
)

/**
 * Диалог установки/запуска Shizuku для Pro-режима.
 *
 * Показывается, когда Shizuku не установлен, не запущен или требует разрешения.
 * Автоматически определяет статус и показывает соответствующие инструкции.
 *
 * @param status Текущий статус Shizuku
 * @param onInstall Callback при нажатии "Установить"
 * @param onOpenApp Callback при нажатии "Открыть Shizuku"
 * @param onSources Callback при нажатии "Другие источники"
 * @param onDismiss Callback при нажатии "Позже"
 */
@Composable
fun ShizukuGuideDialog(
    status: ShizukuExecutor.Status,
    onInstall: () -> Unit,
    onOpenApp: () -> Unit,
    onSources: () -> Unit,
    onDismiss: () -> Unit
) {
    AppLog.d(TAG, "ShizukuGuideDialog shown, status=$status")

    val context = LocalContext.current
    val isInstalled: Boolean = remember { ShizukuHelper.isInstalled(context) }

    // AVAILABLE отсекаем сразу — диалог не показывается
    if (status == ShizukuExecutor.Status.AVAILABLE) {
        AppLog.i(TAG, "ShizukuGuideDialog: status AVAILABLE, skipping")
        return
    }

    val dialogData: ShizukuDialogData = when (status) {
        ShizukuExecutor.Status.NOT_INSTALLED -> ShizukuDialogData(
            text = stringResource(R.string.shizuku_dialog_not_installed),
            primaryText = stringResource(R.string.shizuku_dialog_install),
            primaryAction = onInstall
        )

        ShizukuExecutor.Status.NOT_RUNNING -> ShizukuDialogData(
            text = stringResource(R.string.shizuku_dialog_not_running),
            primaryText = stringResource(R.string.shizuku_dialog_howto),
            primaryAction = onOpenApp
        )

        ShizukuExecutor.Status.PERMISSION_REQUIRED -> ShizukuDialogData(
            text = stringResource(R.string.shizuku_dialog_permission),
            primaryText = stringResource(R.string.shizuku_dialog_howto),
            primaryAction = onOpenApp
        )

        ShizukuExecutor.Status.AVAILABLE -> return // Уже обработано выше
    }

    FlowDialog(onDismiss) {
        DialogTitle(stringResource(R.string.shizuku_dialog_title))
        Text(
            dialogData.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        FullWidthButton(
            text = if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) {
                stringResource(R.string.shizuku_dialog_install)
            } else {
                dialogData.primaryText
            },
            onClick = {
                AppLog.i(TAG, "ShizukuGuideDialog: primary button clicked, status=$status")
                if (status == ShizukuExecutor.Status.NOT_INSTALLED || !isInstalled) {
                    onInstall()
                } else {
                    dialogData.primaryAction()
                }
            }
        )

        if (status == ShizukuExecutor.Status.NOT_INSTALLED) {
            FullWidthTextButton(
                text = stringResource(R.string.shizuku_card_other_sources),
                onClick = {
                    AppLog.i(TAG, "ShizukuGuideDialog: other sources clicked")
                    onSources()
                }
            )
        }

        FullWidthTextButton(
            text = stringResource(R.string.shizuku_dialog_later),
            onClick = {
                AppLog.i(TAG, "ShizukuGuideDialog: later clicked")
                onDismiss()
            }
        )
    }
}

/**
 * Диалог выбора расположения пункта «Ограниченные настройки» в App Info.
 *
 * Показывается, когда приложение не может автоматически определить,
 * где находится кнопка разблокировки restricted settings.
 *
 * @param onLocation Callback с выбранным расположением
 * @param onDismiss Callback при отмене
 */
@Composable
fun LocationChoiceDialog(
    onLocation: (RestrictedLocation) -> Unit,
    onDismiss: () -> Unit
) {
    AppLog.d(TAG, "LocationChoiceDialog shown")

    FlowDialog(onDismiss) {
        DialogTitle(stringResource(R.string.pointer_restricted_not_found_title))
        Text(
            stringResource(R.string.pointer_restricted_location_question),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        FullWidthButton(stringResource(R.string.pointer_restricted_menu_option)) {
            AppLog.i(TAG, "LocationChoiceDialog: TOP_MENU selected")
            onLocation(RestrictedLocation.TOP_MENU)
        }

        FullWidthButton(stringResource(R.string.pointer_restricted_bottom_option)) {
            AppLog.i(TAG, "LocationChoiceDialog: BOTTOM_LIST selected")
            onLocation(RestrictedLocation.BOTTOM_LIST)
        }

        FullWidthTextButton(stringResource(R.string.pointer_restricted_absent_option)) {
            AppLog.i(TAG, "LocationChoiceDialog: ABSENT selected")
            onLocation(RestrictedLocation.ABSENT)
        }
    }
}

/**
 * Диалог опций перед оптимизацией: DNS-фильтр + расширенный режим.
 *
 * Показывается перед запуском Pro-режима для выбора дополнительных опций.
 *
 * @param dnsFilterEnabled Состояние переключателя DNS-фильтра
 * @param aggressiveMode Состояние переключателя расширенного режима
 * @param onDnsToggle Callback при изменении DNS-фильтра
 * @param onAggressiveToggle Callback при изменении расширенного режима
 * @param onConfirm Callback при подтверждении и запуске оптимизации
 * @param onCancel Callback при отмене
 */
@Composable
fun OptionsDialog(
    dnsFilterEnabled: Boolean,
    aggressiveMode: Boolean,
    onDnsToggle: (Boolean) -> Unit,
    onAggressiveToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AppLog.d(TAG, "OptionsDialog shown, dns=$dnsFilterEnabled, aggressive=$aggressiveMode")

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
            onCheckedChange = { checked: Boolean ->
                AppLog.i(TAG, "OptionsDialog: DNS filter toggled to $checked")
                onDnsToggle(checked)
            }
        )

        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        ToggleRow(
            title = stringResource(R.string.aggressive_option_title),
            subtitle = stringResource(R.string.aggressive_option_description),
            subtitleColor = MaterialTheme.colorScheme.error,
            checked = aggressiveMode,
            onCheckedChange = { checked: Boolean ->
                AppLog.i(TAG, "OptionsDialog: aggressive mode toggled to $checked")
                onAggressiveToggle(checked)
            }
        )

        FullWidthButton(
            text = stringResource(R.string.options_dialog_start),
            onClick = {
                AppLog.i(TAG, "OptionsDialog: confirmed")
                onConfirm()
            }
        )

        FullWidthTextButton(
            text = stringResource(R.string.cancel),
            onClick = {
                AppLog.i(TAG, "OptionsDialog: cancelled")
                onCancel()
            }
        )
    }
}

/**
 * Диалог «нужен режим разработчика» для Pro-режима.
 *
 * Показывается, когда для работы Pro-режима требуется включить режим разработчика,
 * но он не активирован.
 *
 * @param onOpenDeviceInfo Callback при нажатии "Открыть «О телефоне»"
 * @param onRetry Callback при нажатии "Повторить проверку"
 * @param onCancel Callback при отмене
 */
@Composable
fun DevModeDialog(
    onOpenDeviceInfo: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    AppLog.d(TAG, "DevModeDialog shown")

    FlowDialog(onCancel) {
        DialogTitle(stringResource(R.string.dev_mode_dialog_title))
        Text(
            stringResource(R.string.dev_mode_dialog_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        FullWidthButton(
            text = stringResource(R.string.dev_mode_dialog_open_about),
            onClick = {
                AppLog.i(TAG, "DevModeDialog: open device info clicked")
                onOpenDeviceInfo()
            }
        )

        FullWidthButton(
            text = stringResource(R.string.dev_mode_dialog_retry),
            onClick = {
                AppLog.i(TAG, "DevModeDialog: retry clicked")
                onRetry()
            }
        )

        FullWidthTextButton(
            text = stringResource(R.string.cancel),
            onClick = {
                AppLog.i(TAG, "DevModeDialog: cancelled")
                onCancel()
            }
        )
    }
}