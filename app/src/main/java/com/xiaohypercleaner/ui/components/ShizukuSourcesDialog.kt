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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.ShizukuHelper

/**
 * Диалог выбора источника установки Shizuku.
 * Показывает только те магазины, которые реально установлены на устройстве,
 * плюс универсальные веб-источники (GitHub, APKPure).
 */
@Composable
fun ShizukuSourcesDialog(
    onSource: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

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

                Button(
                    onClick = { onSource("play") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.shizuku_sources_play)) }
                Spacer(Modifier.height(8.dp))

                if (ShizukuHelper.hasAurora(context)) {
                    OutlinedButton(
                        onClick = { onSource("aurora") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.shizuku_sources_aurora)) }
                    Spacer(Modifier.height(8.dp))
                }

                if (ShizukuHelper.hasGetApps(context)) {
                    OutlinedButton(
                        onClick = { onSource("getapps") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.shizuku_sources_getapps)) }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = { onSource("github") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.shizuku_sources_github)) }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { onSource("apkpure") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.shizuku_sources_apkpure)) }
                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}