package com.xiaohypercleaner.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

private const val TAG = "RestrictedSettingsScreen"

/**
 * Цвет подсветки пульсирующих элементов в макете.
 * Голубой Material Design 400.
 */
private val MockHighlight: Color = Color(0xFF64B5F6)

/**
 * Цвет фона макета окна настроек (тёмная тема).
 */
private val MockBackground: Color = Color(0xFF23262B)

/**
 * Цвет фона выпадающего меню в макете.
 */
private val MockMenuBackground: Color = Color(0xFF2E3238)

/**
 * Цвет неактивного переключателя в макете.
 */
private val MockSwitchOff: Color = Color(0xFF55585E)

/**
 * Экран-подсказка для разблокировки ограниченных настроек (Restricted Settings).
 *
 * Показывается, когда пользователь несколько раз не смог найти кнопку
 * разблокировки restricted settings в App Info.
 *
 * Логика адаптации:
 * - attempt <= 2 → показываем макет с переключателем в списке (как на большинстве Xiaomi)
 * - attempt > 2 → показываем макет с меню ⋮ (адаптация после неудачи)
 *
 * Визуальный макет содержит пульсирующий элемент, привлекающий внимание
 * без необходимости читать длинные инструкции.
 *
 * УЛУЧШЕНИЯ:
 * 1. TAG и логирование действий пользователя
 * 2. `pulseAlpha()` переименован в `rememberPulseAlpha()` (стандартный паттерн Compose)
 * 3. Явные типы для всех переменных
 * 4. Полный JavaDoc для всех composable
 * 5. Цвета вынесены в константы
 * 6. ИСПРАВЛЕНО (Lint Correctness): высота окна берётся из
 *    LocalWindowInfo.current.containerSize вместо LocalConfiguration.screenHeightDp —
 *    реальный размер контейнера с учётом вырезов, панелей и multi-window
 *
 * @param attempt Номер текущей попытки (для адаптации макета)
 * @param onOpenSettings Callback при нажатии "Открыть настройки"
 * @param onDone Callback при нажатии "Готово" (пользователь утверждает, что сделал)
 * @param onCancel Callback при отмене (сброс потока разрешений)
 */
@Composable
fun RestrictedSettingsDialog(
    attempt: Int,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    AppLog.d(TAG, "RestrictedSettingsDialog shown, attempt=$attempt")

    // ИСПРАВЛЕНО: реальный размер окна через LocalWindowInfo (вместо Configuration)
    val density = LocalDensity.current
    val containerHeightDp: Dp = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val maxHeight: Dp = containerHeightDp - 48.dp
    val showMenuVariant: Boolean = attempt > 2

    Dialog(onDismissRequest = {
        AppLog.i(TAG, "Dialog dismissed by system")
        onCancel()
    }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .padding(20.dp)
            ) {
                // ═══════════════════════════════════════════════════════════════
                // Заголовок — всегда виден
                // ═══════════════════════════════════════════════════════════════

                Text(
                    stringResource(R.string.restricted_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))

                // ═══════════════════════════════════════════════════════════════
                // Скроллируемая часть: текст + визуальный макет
                // ═══════════════════════════════════════════════════════════════

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.restricted_guide_simple),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    Text(
                        stringResource(
                            if (showMenuVariant) R.string.restricted_guide_look_menu
                            else R.string.restricted_guide_look_main
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))

                    // ВИЗУАЛЬНЫЙ МАКЕТ — показываем, куда нажимать
                    if (showMenuVariant) {
                        MenuMock()
                    } else {
                        ListMock()
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.restricted_guide_confirm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showMenuVariant) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.restricted_guide_not_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // Кнопки — всегда видны
                // ═══════════════════════════════════════════════════════════════

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        AppLog.i(TAG, "Open settings clicked")
                        onOpenSettings()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.restricted_screen_open),
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        AppLog.i(TAG, "Done clicked")
                        onDone()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.restricted_screen_done))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        AppLog.i(TAG, "Cancel clicked")
                        onCancel()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

/**
 * Запоминает и возвращает пульсирующую альфу для анимации подсветки.
 *
 * Значение меняется от 0.35 до 1.0 и обратно с периодом ~1.4 секунды.
 * Используется для привлечения внимания к целевым элементам в макете.
 *
 * @return Текущее значение альфы (0.35 — 1.0)
 */
@Composable
private fun rememberPulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha: Float by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    return alpha
}

/**
 * Макет: строка списка настроек с пульсирующим переключателем.
 *
 * Показывается при attempt <= 2, когда предполагается, что кнопка
 * разблокировки находится в основном списке настроек.
 *
 * Содержит:
 * - Шапку "окна настроек" с кнопкой "назад"
 * - Обычную строку (для контекста)
 * - Целевую строку с пульсирующей подсветкой
 * - Стрелку-подсказку "Включите здесь"
 */
@Composable
private fun ListMock() {
    val pulse: Float = rememberPulseAlpha()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MockBackground, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Шапка «окна настроек»
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "XiaoHyperCleaner",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(12.dp))

        // Обычная строка (для контекста)
        MockRow("Уведомления", enabled = false, pulse = 0f, highlight = MockHighlight)
        Spacer(Modifier.height(8.dp))

        // НУЖНАЯ строка — пульсирует
        MockRow(
            title = stringResource(R.string.restricted_row_name),
            enabled = true,
            pulse = pulse,
            highlight = MockHighlight
        )
        Spacer(Modifier.height(6.dp))

        // Стрелка-подсказка
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                "👆 " + stringResource(R.string.restricted_guide_turn_on),
                color = MockHighlight.copy(alpha = 0.4f + 0.6f * pulse),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Макет: верхняя панель с ⋮ и выпадающим меню.
 *
 * Показывается при attempt > 2, когда переключатель в списке не найден
 * и предполагается, что кнопка находится в меню ⋮ (правый верхний угол).
 *
 * Содержит:
 * - Шапку с пульсирующей ⋮
 * - Выпадающее меню с пульсирующим целевым пунктом
 * - Стрелку-подсказку "Нажмите здесь"
 */
@Composable
private fun MenuMock() {
    val pulse: Float = rememberPulseAlpha()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MockBackground, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Шапка с пульсирующей ⋮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                "XiaoHyperCleaner",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            // Пульсирующая ⋮
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .border(
                        2.dp,
                        MockHighlight.copy(alpha = pulse),
                        CircleShape
                    )
            ) {
                Text("⋮", color = Color.White, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        // Выпадающее меню с нужным пунктом
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MockMenuBackground, RoundedCornerShape(12.dp))
                .padding(6.dp)
        ) {
            Text(
                stringResource(R.string.restricted_row_name),
                color = MockHighlight.copy(alpha = 0.4f + 0.6f * pulse),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MockHighlight.copy(alpha = 0.15f * pulse),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                "👆 " + stringResource(R.string.restricted_guide_tap_here),
                color = MockHighlight.copy(alpha = 0.4f + 0.6f * pulse),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Одна строка макета с переключателем.
 *
 * @param title Текст строки
 * @param enabled Активен ли переключатель (пульсирует ли строка)
 * @param pulse Текущее значение пульсации (0.0 — 1.0)
 * @param highlight Цвет подсветки
 */
@Composable
private fun MockRow(
    title: String,
    enabled: Boolean,
    pulse: Float,
    highlight: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.border(
                        2.dp,
                        highlight.copy(alpha = pulse),
                        RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )

        // Переключатель
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(22.dp)
                .background(
                    if (enabled) highlight else MockSwitchOff,
                    RoundedCornerShape(11.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(2.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}