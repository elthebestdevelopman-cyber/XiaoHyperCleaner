package com.xiaohypercleaner.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaohypercleaner.data.OptimizationMode

@Composable
fun OptimizationLevelDialog(
    currentMode: OptimizationMode,
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
                    text = "Выберите режим",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Режим можно изменить позже в настройках",
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
                        mode = OptimizationMode.SIMPLE,
                        isSelected = currentMode == OptimizationMode.SIMPLE,
                        onClick = { onModeSelected(OptimizationMode.SIMPLE) },
                        iconContent = {
                            SimpleIcon(color = MaterialTheme.colorScheme.primary)
                        },
                        title = "Простой",
                        desc = "Безопасно для всех. Автоматическая очистка без сложных настроек."
                    )

                    ModeCard(
                        mode = OptimizationMode.PRO,
                        isSelected = currentMode == OptimizationMode.PRO,
                        onClick = { onModeSelected(OptimizationMode.PRO) },
                        iconContent = {
                            ProIcon(color = MaterialTheme.colorScheme.secondary)
                        },
                        title = "Продвинутый",
                        desc = "Глубокая оптимизация. Требует дополнительной настройки прав."
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отмена")
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: OptimizationMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit,
    title: String,
    desc: String
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = tween(200)
    )

    Card(
        modifier = Modifier
            .weight(1f)
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
        border = if (isSelected)
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
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
                    imageVector = androidx.compose.material.icons.Icons.Filled.CheckCircle,
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
        imageVector = ImageVector.vectorBuilder(
            width = 24.dp,
            height = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ) {
            path(
                fill = SolidColor(color),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 2.5f)
                curveTo(12f, 2.5f, 17f, 8f, 17f, 13f)
                curveTo(17f, 16f, 15f, 18f, 12f, 21f)
                curveTo(9f, 18f, 7f, 16f, 7f, 13f)
                curveTo(7f, 8f, 12f, 2.5f, 12f, 2.5f)
                close()
                moveTo(12f, 14f)
                circle(12f, 14f, 1.5f)
            }
        }.build(),
        contentDescription = "Simple Mode",
        modifier = Modifier.size(40.dp),
        tint = color
    )
}

@Composable
private fun ProIcon(color: Color) {
    Icon(
        imageVector = ImageVector.vectorBuilder(
            width = 24.dp,
            height = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ) {
            path(
                fill = SolidColor(color),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(9f, 3f)
                lineTo(15f, 3f)
                lineTo(15f, 5f)
                lineTo(17f, 5f)
                lineTo(17f, 3f)
                lineTo(19f, 3f)
                lineTo(19f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 9f)
                lineTo(19f, 9f)
                lineTo(19f, 11f)
                lineTo(21f, 11f)
                lineTo(21f, 15f)
                lineTo(19f, 15f)
                lineTo(19f, 17f)
                lineTo(21f, 17f)
                lineTo(21f, 21f)
                lineTo(17f, 21f)
                lineTo(17f, 19f)
                lineTo(15f, 19f)
                lineTo(15f, 21f)
                lineTo(9f, 21f)
                lineTo(9f, 19f)
                lineTo(7f, 19f)
                lineTo(7f, 21f)
                lineTo(3f, 21f)
                lineTo(3f, 17f)
                lineTo(5f, 17f)
                lineTo(5f, 15f)
                lineTo(3f, 15f)
                lineTo(3f, 11f)
                lineTo(5f, 11f)
                lineTo(5f, 9f)
                lineTo(3f, 9f)
                lineTo(3f, 5f)
                lineTo(5f, 5f)
                lineTo(5f, 3f)
                lineTo(7f, 3f)
                lineTo(7f, 5f)
                lineTo(9f, 5f)
                lineTo(9f, 3f)
                close()
                moveTo(9f, 9f)
                lineTo(9f, 15f)
                lineTo(15f, 15f)
                lineTo(15f, 9f)
                lineTo(9f, 9f)
                close()
            }
        }.build(),
        contentDescription = "Pro Mode",
        modifier = Modifier.size(40.dp),
        tint = color
    )
}
