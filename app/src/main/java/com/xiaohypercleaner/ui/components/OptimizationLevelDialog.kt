package com.xiaohypercleaner.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.R

@Composable
fun OptimizationLevelDialog(
    onModeSelected: (OptimizationMode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.level_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.level_dialog_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ModeCard(
                        isSelected = false,
                        onClick = { onModeSelected(OptimizationMode.SIMPLE) },
                        iconContent = {
                            SimpleIcon(color = MaterialTheme.colorScheme.primary)
                        },
                        title = stringResource(R.string.level_simple_title),
                        desc = stringResource(R.string.level_simple_desc),
                        modifier = Modifier.weight(1f)
                    )

                    ModeCard(
                        isSelected = false,
                        onClick = { onModeSelected(OptimizationMode.PRO) },
                        iconContent = {
                            ProIcon(color = MaterialTheme.colorScheme.secondary)
                        },
                        title = stringResource(R.string.level_advanced_title),
                        desc = stringResource(R.string.level_advanced_desc),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    isSelected: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit,
    title: String,
    desc: String,
    modifier: Modifier = Modifier  // ✅ modifier передаётся снаружи из RowScope
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = tween(200)
    )

    Card(
        modifier = modifier  // ✅ используем переданный (с .weight(1f))
            .heightIn(min = 180.dp)
            .scale(scale)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        // ✅ ИСПРАВЛЕНО: используем Modifier.border вместо CardDefaults.outlinedCardBorder()
        // который ведёт себя по-разному в разных версиях Compose
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary
            )
        else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else
                            Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                iconContent()
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SimpleIcon(color: Color) {
    Icon(
        imageVector = Icons.Filled.AutoAwesome,
        contentDescription = "Simple Mode",
        modifier = Modifier.size(40.dp),
        tint = color
    )
}

@Composable
private fun ProIcon(color: Color) {
    Icon(
        imageVector = Icons.Filled.Settings,
        contentDescription = "Pro Mode",
        modifier = Modifier.size(40.dp),
        tint = color
    )
}