package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

private const val TAG = "ShizukuSetupWizard"

/**
 * Пошаговый wizard настройки Shizuku для Pro-режима.
 *
 * Содержит 5 шагов:
 * 1. Открыть «О телефоне» → 7 раз нажать на номер сборки (активировать режим разработчика)
 * 2. Включить режим разработчика
 * 3. Открыть настройки разработчика → включить беспроводную отладку
 * 4. Открыть Shizuku → запустить через беспроводную отладку
 * 5. Запросить разрешение для XiaoHyperCleaner
 *
 * UX-особенности:
 * - Кнопка "Пропустить" вверху (для большинства пользователей, которые не хотят настраивать Shizuku)
 * - Кнопка "Пропустить" внизу (для тех, кто пролистал весь wizard)
 * - `checkMessage` показывает результат последней проверки статуса
 *
 * УЛУЧШЕНИЯ:
 * 1. TAG и логирование действий пользователя
 * 2. Импорт Dialog (вместо полного пути)
 * 3. Явные типы для всех переменных
 * 4. Проверка `isNotBlank()` вместо `!= null` для checkMessage
 * 5. Полный Javadoc для параметров
 * 6. Секции с комментариями для читаемости
 *
 * @param checkMessage Сообщение результата проверки статуса (null или пустое — не показывается)
 * @param onOpenAbout Callback при нажатии "Открыть «О телефоне»"
 * @param onOpenDevOptions Callback при нажатии "Открыть настройки разработчика"
 * @param onOpenShizuku Callback при нажатии "Открыть Shizuku"
 * @param onRequestPermission Callback при нажатии "Запросить разрешение"
 * @param onCheck Callback при нажатии "Проверить статус"
 * @param onSkip Callback при нажатии "Пропустить" (переход к выбору режима)
 * @param onClose Callback при закрытии wizard (отмена Pro-режима)
 */
@Composable
fun ShizukuSetupWizard(
    checkMessage: String?,
    onOpenAbout: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
    onCheck: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit
) {
    AppLog.d(TAG, "ShizukuSetupWizard shown")

    Dialog(onDismissRequest = {
        AppLog.i(TAG, "Dialog dismissed by system")
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
                    stringResource(R.string.shizuku_wizard_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.shizuku_wizard_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // ═══════════════════════════════════════════════════════════════
                // Главная кнопка — пропустить (для большинства пользователей)
                // ═══════════════════════════════════════════════════════════════

                Button(
                    onClick = {
                        AppLog.i(TAG, "Skip (top) clicked")
                        onSkip()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        stringResource(R.string.shizuku_wizard_skip_top),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.shizuku_wizard_or),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Шаг 1: Активировать режим разработчика
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.shizuku_wizard_step1_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.shizuku_wizard_step1_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Open About clicked")
                        onOpenAbout()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_wizard_open_about))
                }
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Шаг 2: Режим разработчика активирован
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.shizuku_wizard_step2_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.shizuku_wizard_step2_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Шаг 3: Включить беспроводную отладку
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.shizuku_wizard_step3_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.shizuku_wizard_step3_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Open Dev Options clicked")
                        onOpenDevOptions()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_wizard_open_dev))
                }
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Шаг 4: Запустить Shizuku через беспроводную отладку
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.shizuku_wizard_step4_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.shizuku_wizard_step4_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Open Shizuku clicked")
                        onOpenShizuku()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_wizard_open_shizuku))
                }
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Шаг 5: Запросить разрешение для приложения
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.shizuku_wizard_step5_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.shizuku_wizard_step5_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Кнопки проверки и запроса разрешения
                // ═══════════════════════════════════════════════════════════════

                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Check status clicked")
                        onCheck()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_wizard_check))
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Request permission clicked")
                        onRequestPermission()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_wizard_request_permission))
                }

                // ═══════════════════════════════════════════════════════════════
                // Сообщение проверки статуса (если есть)
                // ИСПРАВЛЕНО: isNotBlank() вместо != null для защиты от пустых строк
                // ═══════════════════════════════════════════════════════════════

                if (!checkMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            checkMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // Кнопки навигации (внизу)
                // Дублируем "Пропустить" для тех, кто пролистал весь wizard
                // ═══════════════════════════════════════════════════════════════

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Skip (bottom) clicked")
                        onSkip()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.shizuku_wizard_skip),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Close clicked")
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.shizuku_wizard_close))
                }
            }
        }
    }
}