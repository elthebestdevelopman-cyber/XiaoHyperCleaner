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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.ShizukuHelper

private const val TAG = "ShizukuSourcesDialog"

/**
 * Диалог выбора источника установки Shizuku.
 *
 * Показывает ТОЛЬКО те магазины, которые реально установлены на устройстве,
 * плюс универсальные веб-источники (GitHub, APKPure), которые всегда доступны.
 *
 * Логика показа:
 * - Google Play: только если `ShizukuHelper.hasPlayStore(context)` == true
 * - Aurora Store: только если установлен
 * - GetApps (Xiaomi): только если установлен (есть на всех Xiaomi/Redmi/Poco)
 * - GitHub: всегда (веб-ссылка, работает через браузер)
 * - APKPure: всегда (веб-ссылка, работает через браузер)
 *
 * ИСПРАВЛЕНИЯ:
 * 1. 🔴 Play Store теперь показывается только если установлен (было: всегда)
 *    Это критично для устройств без GMS (Huawei, китайские Xiaomi)
 * 2. Добавлен TAG и логирование действий пользователя
 * 3. Импорт Dialog (вместо полного пути)
 * 4. Явные типы для всех переменных
 * 5. Полный Javadoc с описанием логики
 * 6. Секции с комментариями
 *
 * @param onSource Callback с выбранным источником ("play", "aurora", "getapps", "github", "apkpure")
 * @param onClose Callback при закрытии диалога
 */
@Composable
fun ShizukuSourcesDialog(
    onSource: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // Проверяем наличие магазинов один раз при композиции
    val hasPlay: Boolean = remember { ShizukuHelper.hasPlayStore(context) }
    val hasAurora: Boolean = remember { ShizukuHelper.hasAurora(context) }
    val hasGetApps: Boolean = remember { ShizukuHelper.hasGetApps(context) }

    AppLog.d(
        TAG,
        "ShizukuSourcesDialog shown, play=$hasPlay, aurora=$hasAurora, getapps=$hasGetApps"
    )

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
                    stringResource(R.string.shizuku_sources_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.shizuku_sources_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                // ═══════════════════════════════════════════════════════════════
                // Магазины приложений (только установленные)
                // ═══════════════════════════════════════════════════════════════

                // ИСПРАВЛЕНО: Play Store показывается только если установлен.
                // Раньше кнопка показывалась всегда, что приводило к ошибке
                // на устройствах без GMS (Huawei, китайские Xiaomi).
                if (hasPlay) {
                    Button(
                        onClick = {
                            AppLog.i(TAG, "Play Store source selected")
                            onSource("play")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.shizuku_sources_play))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (hasAurora) {
                    OutlinedButton(
                        onClick = {
                            AppLog.i(TAG, "Aurora Store source selected")
                            onSource("aurora")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.shizuku_sources_aurora))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (hasGetApps) {
                    OutlinedButton(
                        onClick = {
                            AppLog.i(TAG, "GetApps source selected")
                            onSource("getapps")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.shizuku_sources_getapps))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ═══════════════════════════════════════════════════════════════
                // Веб-источники (всегда доступны через браузер)
                // ═══════════════════════════════════════════════════════════════

                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "GitHub source selected")
                        onSource("github")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_sources_github))
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "APKPure source selected")
                        onSource("apkpure")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.shizuku_sources_apkpure))
                }
                Spacer(Modifier.height(8.dp))

                // ═══════════════════════════════════════════════════════════════
                // Кнопка закрытия
                // ═══════════════════════════════════════════════════════════════

                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Close clicked")
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