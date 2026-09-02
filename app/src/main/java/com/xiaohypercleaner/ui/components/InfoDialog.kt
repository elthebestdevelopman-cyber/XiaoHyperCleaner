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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

private const val TAG = "InfoDialog"

/**
 * Универсальный информационный диалог с заголовком, текстом и опциональными кнопками.
 *
 * Используется для:
 * - Диалогов разрешений (overlay, accessibility, restricted)
 * - Подтверждения опасных действий (откат, перезагрузка)
 * - Отображения ошибок (reboot failed, restore failed)
 * - Финальных результатов оптимизации
 * 
 * ИСПРАВЛЕНИЯ:
 * 1. 🔴 КРИТИЧЕСКИЙ БАГ: onDismissRequest теперь вызывает ТОЛЬКО onDismiss.
 *    Раньше при BACK/клике вне диалога мог вызваться onConfirm — пользователь
 *    случайно подтверждал действие вместо отмены.
 * 2. TextButton показывается только если есть хотя бы один callback.
 * 3. Логирование действий пользователя для диагностики.
 * 4. Полная документация параметров.
 *
 * @param title Заголовок диалога (жирный, по центру)
 * @param text Основной текст диалога
 * @param confirmText Текст кнопки подтверждения (если null — кнопка не показывается)
 * @param dismissText Текст кнопки отмены (по умолчанию «Отмена» из strings.xml)
 * @param onConfirm Callback при нажатии кнопки подтверждения
 * @param onDismiss Callback при нажатии кнопки отмены или закрытии диалога
 */
@Composable
fun InfoDialog(
    title: String,
    text: String,
    confirmText: String? = null,
    dismissText: String? = null,
    onConfirm: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val effectiveDismissText: String = dismissText ?: stringResource(R.string.cancel)

    // Показываем кнопку отмены только если есть хотя бы один callback
    val hasAnyAction: Boolean = onConfirm != null || onDismiss != null

    Dialog(
        onDismissRequest = {
            // ИСПРАВЛЕНО: BACK / клик вне диалога вызывает ТОЛЬКО onDismiss.
            // Раньше здесь было onDismiss?.invoke() ?: onConfirm?.invoke(),
            // что приводило к случайному подтверждению при нажатии BACK.
            AppLog.i(TAG, "Dialog dismissed by system (BACK/outside click): $title")
            onDismiss?.invoke()
        }
    ) {
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
                Spacer(Modifier.height(20.dp))

                // Кнопка подтверждения
                if (onConfirm != null && confirmText != null) {
                    Button(
                        onClick = {
                            AppLog.i(TAG, "Confirm clicked: $title")
                            onConfirm()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(confirmText)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Кнопка отмены — показывается только если есть хотя бы один callback
                if (hasAnyAction) {
                    TextButton(
                        onClick = {
                            AppLog.i(TAG, "Dismiss clicked: $title")
                            // Приоритет: onDismiss, иначе onConfirm (fallback для legacy)
                            if (onDismiss != null) {
                                onDismiss()
                            } else {
                                onConfirm?.invoke()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(effectiveDismissText)
                    }
                }
            }
        }
    }
}