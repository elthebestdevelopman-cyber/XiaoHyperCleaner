package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

/**
 * Диалог явного согласия на использование AccessibilityService.
 *
 * КРИТИЧНО ДЛЯ GOOGLE PLAY:
 * Соответствует Accessibility Services API Policy — показывает явный чекбокс
 * и объясняет, что служба НЕ обходит приватность.
 *
 * Без этого диалога приложение будет отклонено при публикации в Google Play.
 *
 * УЛУЧШЕНИЯ:
 * 1. Явные типы для state переменных
 * 2. Импорт Dialog (вместо полного пути)
 * 3. Логирование действий пользователя
 * 4. Полная документация
 *
 * @param onConfirm Callback при подтверждении согласия (чекбокс должен быть установлен)
 * @param onDismiss Callback при отмене (нажатие кнопки "Отмена" или клик вне диалога)
 */
@Composable
fun AccessibilityConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var consentGiven: Boolean by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
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
                    stringResource(R.string.consent_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.consent_dialog_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))

                // Явный чекбокс согласия (требование Google Play)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = consentGiven,
                        onCheckedChange = { checked: Boolean ->
                            consentGiven = checked
                            AppLog.i("AccessibilityConsent", "Checkbox changed: $checked")
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.consent_checkbox_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        AppLog.i("AccessibilityConsent", "User confirmed consent")
                        onConfirm()
                    },
                    enabled = consentGiven,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.consent_confirm_button))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        AppLog.i("AccessibilityConsent", "User cancelled consent dialog")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.consent_cancel_button))
                }
            }
        }
    }
}