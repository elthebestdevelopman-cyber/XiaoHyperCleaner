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
import androidx.compose.material3.LinearProgressIndicator
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
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.SimpleSteps

/**
 * Состояние простого шага.
 */
data class SimpleStepState(
    val stepIndex: Int,
    val totalSteps: Int,
    val step: SimpleSteps.Step,
    val status: Status,
    val completedCount: Int
) {
    enum class Status { READY, WORKING, SUCCESS, FAILED }
}

/**
 * Экран одного шага простой оптимизации.
 */
@Composable
fun SimpleStepScreen(
    state: SimpleStepState,
    isEnglish: Boolean,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
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
                // Прогресс
                Text(
                    stringResource(R.string.simple_step_of, state.stepIndex + 1, state.totalSteps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (state.completedCount.toFloat() / state.totalSteps) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))

                // Заголовок и описание
                Text(
                    if (isEnglish) state.step.titleEn else state.step.titleRu,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isEnglish) state.step.descEn else state.step.descRu,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))

                // Действия в зависимости от статуса
                when (state.status) {
                    SimpleStepState.Status.READY -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.simple_step_next)) }
                    }

                    SimpleStepState.Status.WORKING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.simple_step_working),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    SimpleStepState.Status.SUCCESS -> {
                        Text(
                            "✅ " + stringResource(R.string.simple_step_success),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onNext,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.simple_step_next)) }
                    }

                    SimpleStepState.Status.FAILED -> {
                        Text(
                            "⚠️ " + stringResource(R.string.simple_step_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onNext,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.simple_step_next)) }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.simple_step_skip)) }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

/**
 * Финальный экран после всех шагов.
 */
@Composable
fun SimpleDoneDialog(
    completedCount: Int,
    totalCount: Int,
    onRate: () -> Unit,
    onDonate: () -> Unit,
    onClose: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)) {
                Text(
                    "🎉 " + stringResource(R.string.simple_done_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.simple_done_steps_done, completedCount, totalCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.simple_done_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onRate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.simple_done_rate)) }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDonate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.simple_done_donate)) }
                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.simple_done_close))
                }
            }
        }
    }
}