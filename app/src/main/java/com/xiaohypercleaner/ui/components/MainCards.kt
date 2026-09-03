package com.xiaohypercleaner.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaohypercleaner.R
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.ui.theme.Blue500

/**
 * Стадии карточки оптимизации для анимации.
 * Используется вместо String для type safety.
 */
private enum class OptimizationStage {
    /** Готов к оптимизации — показываем кнопку "Оптимизировать" */
    READY,

    /** Оптимизация выполняется — показываем прогресс */
    WORKING,

    /** Оптимизация завершена — показываем кнопки восстановления */
    DONE
}

/**
 * Информационная карточка: название + описание + список возможностей.
 *
 * Показывается вверху главного экрана. Содержит:
 * - Название приложения
 * - Краткое описание
 * - Список из 3 возможностей (процессы, скорость, батарея)
 */
@Composable
fun InfoCard() {
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
            FeatureRow(Icons.Filled.Lock, stringResource(R.string.feature_processes))
            Spacer(Modifier.height(8.dp))
            FeatureRow(Icons.Filled.Build, stringResource(R.string.feature_speed))
            Spacer(Modifier.height(8.dp))
            FeatureRow(Icons.Filled.Star, stringResource(R.string.feature_battery))
        }
    }
}

/**
 * Строка с иконкой и текстом для списка возможностей.
 *
 * @param icon Иконка слева от текста
 * @param text Текст возможности
 */
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
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Карточка оптимизации с анимацией состояний.
 *
 * Переключается между тремя стадиями:
 * - READY: кнопка "Оптимизировать"
 * - WORKING: прогресс-бар с процентами
 * - DONE: кнопки "Восстановить" и "Перезагрузить"
 *
 * @param state Текущее состояние UI
 * @param onOptimize Callback при нажатии "Оптимизировать"
 * @param onRestore Callback при нажатии "Восстановить"
 * @param onReboot Callback при нажатии "Перезагрузить"
 */
@Composable
fun OptimizationCard(
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
            val stage: OptimizationStage = when {
                state.isWorking -> OptimizationStage.WORKING
                state.isOptimized -> OptimizationStage.DONE
                else -> OptimizationStage.READY
            }

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.95f)) togetherWith
                            (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.95f))
                },
                label = "OptimizationStage"
            ) { currentStage: OptimizationStage ->
                when (currentStage) {
                    OptimizationStage.WORKING -> WorkingView(state.progress)
                    OptimizationStage.DONE -> DoneView(
                        onRestore = onRestore,
                        onReboot = onReboot,
                        canAutoReboot = state.canAutoReboot
                    )
                    OptimizationStage.READY -> ReadyView(onOptimize)
                }
            }
        }
    }
}

/**
 * Вид для стадии READY: кнопка "Оптимизировать".
 *
 * @param onClick Callback при нажатии кнопки
 */
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

/**
 * Вид для стадии WORKING: прогресс-бар с процентами.
 *
 * @param progress Прогресс в процентах (0-100)
 */
@Composable
private fun WorkingView(progress: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.status_working),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))

        LinearProgressIndicator(
            progress = { (progress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = Blue500
        )
        Spacer(Modifier.height(8.dp))

        Text(
            "${progress.toInt().coerceIn(0, 100)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Вид для стадии DONE: «Восстановить» + перезагрузка только при root,
 * иначе текстовая подсказка перезагрузить вручную.
 */
@Composable
private fun DoneView(
    onRestore: () -> Unit,
    onReboot: () -> Unit,
    canAutoReboot: Boolean
) {
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
    ) {
        Text(stringResource(R.string.btn_restore))
    }
    Spacer(Modifier.height(12.dp))

    if (canAutoReboot) {
        OutlinedButton(
            onClick = onReboot,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.reboot_now))
        }
    } else {
        Text(
            stringResource(R.string.reboot_manual_hint),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
