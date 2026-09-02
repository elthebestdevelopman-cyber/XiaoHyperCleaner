package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.BuildConfig
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

private const val TAG = "MenuDialog"

/**
 * Диалог меню приложения (⋮ в правом верхнем углу).
 *
 * Содержит:
 * - Настройки (тёмная тема)
 * - Действия (оценить, поддержать, поделиться логом, политика конфиденциальности)
 * - Информация о приложении (версия, автор, описание, Shizuku attribution)
 *
 * УЛУЧШЕНИЯ:
 * 1. Импортированы Dialog и RoundedCornerShape
 * 2. Добавлен TAG и логирование действий
 * 3. Полный JavaDoc для параметров
 * 4. Явные типы для consistency
 * 5. Логическая группировка элементов
 *
 * @param isDark Текущее состояние тёмной темы
 * @param onDarkChange Callback при переключении тёмной темы
 * @param onClose Callback при закрытии меню
 * @param onRate Callback при нажатии "Оценить приложение"
 * @param onYooMoney Callback при нажатии "Поддержать через ЮMoney"
 * @param onCloudTips Callback при нажатии "Поддержать через CloudTips"
 * @param onShareLog Callback при нажатии "Поделиться логом"
 * @param onPrivacyPolicyClick Callback при нажатии "Политика конфиденциальности"
 */
@Composable
fun MenuDialog(
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onRate: () -> Unit,
    onYooMoney: () -> Unit,
    onCloudTips: () -> Unit,
    onShareLog: () -> Unit,
    onPrivacyPolicyClick: () -> Unit
) {
    AppLog.d(TAG, "MenuDialog shown")

    Dialog(onDismissRequest = {
        AppLog.i(TAG, "MenuDialog dismissed by system")
        onClose()
    }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ═══════════════════════════════════════════════════════════════
                // Заголовок
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.menu_about),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                // ═══════════════════════════════════════════════════════════════
                // Настройки
                // ═══════════════════════════════════════════════════════════════

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.menu_dark_theme),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isDark,
                        onCheckedChange = { checked: Boolean ->
                            AppLog.i(TAG, "Dark theme toggled: $checked")
                            onDarkChange(checked)
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))

                // ═══════════════════════════════════════════════════════════════
                // Действия
                // ═══════════════════════════════════════════════════════════════

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Rate app clicked")
                        onRate()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rate_app))
                }

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "YooMoney support clicked")
                        onYooMoney()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.support_yoomoney))
                }

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "CloudTips support clicked")
                        onCloudTips()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.support_cloudtips))
                }

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Share log clicked")
                        onShareLog()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.log_share))
                }

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Privacy policy clicked")
                        onPrivacyPolicyClick()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.menu_privacy_policy))
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // О приложении
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))

                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    stringResource(R.string.about_author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.shizuku_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Close menu clicked")
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}