package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.clickable
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
import com.xiaohypercleaner.service.InstallerWatcherStatus
import com.xiaohypercleaner.ui.WindowScanStatus
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
 * @param isNotifTransparency Прозрачность уведомлений (notif_*-шаги в плане)
 * @param onNotifTransparencyChange Callback при переключении прозрачности уведомлений
 * @param diagLevelFull Диагностика в release переведена в FULL
 * @param onVersionTap Callback при тапе по номеру версии (скрытый вход в FULL)
 * @param onClose Callback при закрытии меню
 * @param onRate Callback при нажатии "Оценить приложение"
 * @param onSupportPage Callback при нажатии "Поддержать проект"
 * @param onShareLog Callback при нажатии "Поделиться логом"
 * @param onPrivacyPolicyClick Callback при нажатии "Политика конфиденциальности"
 */
@Composable
fun MenuDialog(
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onNotifTransparencyChange: (Boolean) -> Unit,
    onVersionTap: () -> Unit,
    onClose: () -> Unit,
    onRate: () -> Unit,
    onSupportPage: () -> Unit,
    onShareLog: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    /** R2.1: скан окон с переключателями (read-only, без оверлея). */
    onWindowScan: () -> Unit,
    isNotifTransparency: Boolean = true,
    diagLevelFull: Boolean = false,
    /** Статус скана: текст собирается здесь из строк resources (хардкода нет). */
    windowScan: WindowScanStatus = WindowScanStatus(),
    /** Итог ловца установщика: Idle — молчание. */
    installerWatcher: InstallerWatcherStatus = InstallerWatcherStatus.Idle
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

                // Прозрачность notif_*: OFF исключает шаги отключения уведомлений из плана.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.menu_notif_transparency),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(R.string.menu_notif_transparency_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isNotifTransparency,
                        onCheckedChange = { checked: Boolean ->
                            AppLog.i(TAG, "Notification transparency toggled: $checked")
                            onNotifTransparencyChange(checked)
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))

                // ═══════════════════════════════════════════════════════════════
                // Действия
                // ═══════════════════════════════════════════════════════════════

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Window scan clicked")
                        onWindowScan()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.menu_window_scan))
                }

                // Статус скана: отдельный текст для «не начинался», «прерван фазой» и «готово».
                if (windowScan.running) {
                    Text(
                        stringResource(R.string.scan_status_running),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (windowScan.skippedReason != null) {
                    Text(
                        stringResource(
                            if (windowScan.aborted) R.string.scan_status_aborted
                            else R.string.scan_status_skipped
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (windowScan.done) {
                    Text(
                        stringResource(
                            R.string.scan_status_done,
                            windowScan.screens,
                            windowScan.candidates
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Статус ловца установщика: Idle — молчание, текстов не показываем.
                when (installerWatcher) {
                    InstallerWatcherStatus.Running -> Text(
                        stringResource(R.string.watcher_status_running),
                        style = MaterialTheme.typography.bodySmall
                    )

                    InstallerWatcherStatus.RecommendationsOff -> Text(
                        stringResource(R.string.watcher_status_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    InstallerWatcherStatus.Skipped -> Text(
                        stringResource(R.string.watcher_status_skipped),
                        style = MaterialTheme.typography.bodySmall
                    )

                    InstallerWatcherStatus.Idle -> Unit
                }

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
                        AppLog.i(TAG, "Support page clicked")
                        onSupportPage()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.support_page))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        AppLog.i(TAG, "Version tapped (hidden diagnostics entry)")
                        onVersionTap()
                    }
                )

                if (diagLevelFull) {
                    Text(
                        stringResource(R.string.menu_diag_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

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