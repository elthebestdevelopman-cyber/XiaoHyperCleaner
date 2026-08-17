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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.SimpleStepState
import com.xiaohypercleaner.data.SimpleSteps

@Composable
fun SimpleStepScreen(
    state: SimpleStepState,
    isEnglish: Boolean,
    onStart: () -> Unit,
    onRetry: () -> Unit,
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
                Text(
                    stringResource(R.string.simple_step_of, state.stepIndex + 1, state.totalSteps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.completedCount.toFloat() / state.totalSteps },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Иконка риска если есть
                    when (state.step.riskLevel) {
                        SimpleSteps.RiskLevel.CONDITIONAL -> {
                            Text("⚠️", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        SimpleSteps.RiskLevel.HIGH -> {
                            Text("🔴", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        SimpleSteps.RiskLevel.SAFE -> {
                            // Без иконки
                        }
                    }
                    
                    Text(
                        if (isEnglish) state.step.titleEn else state.step.titleRu,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isEnglish) state.step.descEn else state.step.descRu,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Блок предупреждения для CONDITIONAL/HIGH
                val warningText = if (isEnglish) state.step.warningEn else state.step.warningRu
                if (warningText != null && state.step.riskLevel != SimpleSteps.RiskLevel.SAFE) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ) {
                        Text(
                            text = warningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                Spacer(Modifier.height(20.dp))

                when (state.status) {
                    SimpleStepState.Status.READY -> {
                        // Единственная кнопка за весь прогон — дальше всё само
                        Button(
                            onClick = onStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.simple_step_start)) }
                        
                        // Кнопка "Пропустить" доступна сразу
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier.fillMaxWidth()
                        ) { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚠️ ")
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.simple_step_skip))
                            }
                        }
                    }

                    SimpleStepState.Status.WORKING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            buildString {
                                append(stringResource(R.string.simple_step_working))
                                if (state.attempt > 1) {
                                    append(" · ")
                                    append(
                                        stringResource(
                                            R.string.simple_step_attempt,
                                            state.attempt, state.maxAttempts
                                        )
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    SimpleStepState.Status.SUCCESS -> {
                        // Кнопки нет — автопереход через ~0.7 сек
                        Text(
                            "✅ " + stringResource(R.string.simple_step_auto_next),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    SimpleStepState.Status.FAILED -> {
                        Text(
                            "⚠️ " + stringResource(R.string.simple_step_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isEnglish) state.step.manualHintEn else state.step.manualHintRu,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(stringResource(R.string.simple_step_retry)) }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier.fillMaxWidth()
                        ) { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚠️ ")
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.simple_step_skip))
                            }
                        }
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

@Composable
fun SimpleDoneDialog(
    completedCount: Int,
    totalCount: Int,
    failedSteps: List<String> = emptyList(),
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
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

                // Честные преимущества без лжи
                Text(
                    stringResource(R.string.simple_done_benefits),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                
                val showAdsBenefit = completedCount >= 3 || failedSteps.none { it.contains("msa") || it.contains("ads") }
                val showTelemetryBenefit = completedCount >= 5 || failedSteps.none { it.contains("ux") || it.contains("analytics") }
                val showSpamBenefit = completedCount >= 4 || failedSteps.none { it.contains("getapps") || it.contains("music") || it.contains("themes") }
                val showStableBenefit = completedCount >= 6
                
                if (showAdsBenefit) {
                    Text(
                        stringResource(R.string.simple_done_benefit_ads),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (showTelemetryBenefit) {
                    Text(
                        stringResource(R.string.simple_done_benefit_telemetry),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (showSpamBenefit) {
                    Text(
                        stringResource(R.string.simple_done_benefit_spam),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (showStableBenefit) {
                    Text(
                        stringResource(R.string.simple_done_benefit_stable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

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