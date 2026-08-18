package com.xiaohypercleaner.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xiaohypercleaner.BuildConfig
import com.xiaohypercleaner.R

@Composable
fun MenuDialog(
    isDark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onRate: () -> Unit,
    onYooMoney: () -> Unit,
    onCloudTips: () -> Unit,
    onShareLog: () -> Unit,
    onPrivacyPolicyClick: () -> Unit  // ← ДОБАВЛЕНО
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
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
                    stringResource(R.string.menu_about),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                // Тёмная тема
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.menu_dark_theme),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = isDark, onCheckedChange = onDarkChange)
                }
                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onRate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rate_app))
                }
                TextButton(onClick = onYooMoney, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.support_yoomoney))
                }
                TextButton(onClick = onCloudTips, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.support_cloudtips))
                }
                TextButton(onClick = onShareLog, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.log_share))
                }
                TextButton(
                    onClick = onPrivacyPolicyClick,
                    modifier = Modifier.fillMaxWidth()  // ← ДОБАВЛЕНО для консистентности
                ) {
                    Text(stringResource(R.string.menu_privacy_policy))
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // О приложении
                Text(
                    stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.about_author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.shizuku_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}