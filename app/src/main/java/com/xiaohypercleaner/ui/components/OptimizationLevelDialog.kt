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

enum class OptimizationLevel { SIMPLE, ADVANCED, EXTREME }

@Composable
fun OptimizationLevelDialog(
    onChoose: (OptimizationLevel) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { onChoose(OptimizationLevel.SIMPLE) }) {
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
                    stringResource(R.string.level_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.level_dialog_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { onChoose(OptimizationLevel.SIMPLE) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.level_simple_title),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.level_simple_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { onChoose(OptimizationLevel.ADVANCED) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.level_advanced_title))
                        Text(
                            stringResource(R.string.level_advanced_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { onChoose(OptimizationLevel.EXTREME) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.level_extreme_title))
                        Text(
                            stringResource(R.string.level_extreme_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Подтверждение выбранного уровня. Даёт пользователю время прочитать
 * что будет происходить, и осознанно нажать «Начать».
 */
@Composable
fun LevelConfirmDialog(
    level: OptimizationLevel,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val (titleRes, textRes) = when (level) {
        OptimizationLevel.SIMPLE -> Pair(
            R.string.level_confirm_simple_title,
            R.string.level_confirm_simple_text
        )

        OptimizationLevel.ADVANCED -> Pair(
            R.string.level_confirm_advanced_title,
            R.string.level_confirm_advanced_text
        )

        OptimizationLevel.EXTREME -> Pair(
            R.string.level_confirm_extreme_title,
            R.string.level_confirm_extreme_text
        )
    }

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
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(textRes),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.level_confirm_start)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}