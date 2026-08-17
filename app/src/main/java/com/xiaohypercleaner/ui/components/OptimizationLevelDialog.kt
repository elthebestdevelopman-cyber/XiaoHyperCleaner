package com.xiaohypercleaner.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.VectorBuilder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaohypercleaner.data.OptimizationMode

// --- ВЕКТОРНЫЕ ИКОНКИ (Рисуем кодом, файлы не нужны) ---

val IcRocket: ImageVector
    get() {
        if (_icRocket != null) return _icRocket!!
        _icRocket = VectorBuilder(
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null,
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 2f)
                curveTo(12f, 2f, 8f, 6f, 8f, 12f)
                curveTo(8f, 16f, 10f, 19f, 12f, 22f)
                curveTo(14f, 19f, 16f, 16f, 16f, 12f)
                curveTo(16f, 6f, 12f, 2f, 12f, 2f)
                close()
                moveTo(8f, 12f)
                curveTo(5f, 12f, 3f, 14f, 3f, 17f)
                curveTo(3f, 19f, 5f, 20f, 8f, 20f)
                lineTo(8f, 12f)
                close()
                moveTo(16f, 12f)
                curveTo(19f, 12f, 21f, 14f, 21f, 17f)
                curveTo(21f, 19f, 19f, 20f, 16f, 20f)
                lineTo(16f, 12f)
                close()
                moveTo(12f, 15f)
                circleToRelative(1.5f, 1.5f, 0f, 1f, 1f, -1f, -1.5f, 0f, 0f, -1f, -1f, 1.5f, 0f)
            }
        }.build()
        return _icRocket!!
    }
private var _icRocket: ImageVector? = null

val IcChip: ImageVector
    get() {
        if (_icChip != null) return _icChip!!
        _icChip = VectorBuilder(
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                fillAlpha = 1.0f,
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(6f, 2f)
                lineTo(6f, 6f)
                moveTo(10f, 2f)
                lineTo(10f, 6f)
                moveTo(14f, 2f)
                lineTo(14f, 6f)
                moveTo(18f, 2f)
                lineTo(18f, 6f)
                
                moveTo(6f, 18f)
                lineTo(6f, 22f)
                moveTo(10f, 18f)
                lineTo(10f, 22f)
                moveTo(14f, 18f)
                lineTo(14f, 22f)
                moveTo(18f, 18f)
                lineTo(18f, 22f)
                
                moveTo(2f, 6f)
                lineTo(6f, 6f)
                moveTo(2f, 10f)
                lineTo(6f, 10f)
                moveTo(2f, 14f)
                lineTo(6f, 14f)
                moveTo(2f, 18f)
                lineTo(6f, 18f)
                
                moveTo(18f, 6f)
                lineTo(22f, 6f)
                moveTo(18f, 10f)
                lineTo(22f, 10f)
                moveTo(18f, 14f)
                lineTo(22f, 14f)
                moveTo(18f, 18f)
                lineTo(22f, 18f)
                
                moveTo(8f, 8f)
                lineTo(16f, 8f)
                lineTo(16f, 16f)
                lineTo(8f, 16f)
                lineTo(8f, 8f)
                close()
                
                moveTo(12f, 12f)
                circleToRelative(1.5f, 1.5f, 0f, 1f, 0f, -3f, 1.5f, 0f, 0f, 0f, 3f)
            }
            
            // Внутренний квадрат с заливкой для красоты
            path(
                fill = SolidColor(Color(0x40FFFFFF)),
                stroke = null,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(9f, 9f)
                lineTo(15f, 9f)
                lineTo(15f, 15f)
                lineTo(9f, 15f)
                lineTo(9f, 9f)
                close()
            }
        }.build()
        return _icChip!!
    }
private var _icChip: ImageVector? = null

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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1E1E2E),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Выберите режим силы",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Мы подберем оптимальный баланс безопасности и мощности",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                ModeCard(
                    mode = OptimizationMode.SIMPLE,
                    title = "Лёгкий старт",
                    description = "Безопасно для всех. Уберет рекламу и спам, не трогая важные функции.",
                    icon = IcRocket,
                    gradient = Brush.linearGradient(
                        colors = listOf(Color(0xFF4facfe), Color(0xFF00f2fe))
                    ),
                    isSelected = currentMode == OptimizationMode.SIMPLE,
                    onClick = { onModeSelected(OptimizationMode.SIMPLE) },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                ModeCard(
                    mode = OptimizationMode.PRO,
                    title = "Глубокая очистка",
                    description = "Для опытных. Максимальная оптимизация системы с умными проверками.",
                    icon = IcChip,
                    gradient = Brush.linearGradient(
                        colors = listOf(Color(0xFFf093fb), Color(0xFFf5576c))
                    ),
                    isSelected = currentMode == OptimizationMode.PRO,
                    onClick = { onModeSelected(OptimizationMode.PRO) },
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Назад", color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: OptimizationMode,
    title: String,
    description: String,
    icon: ImageVector,
    gradient: Brush,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 150)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = if (isSelected) 16.dp else 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = if (isSelected) gradient.colors[0] else Color.Black.copy(alpha = 0.2f),
                spotColor = if (isSelected) gradient.colors[0] else Color.Black.copy(alpha = 0.2f)
            )
            .background(
                color = Color(0xFF2A2A3A),
                shape = RoundedCornerShape(20.dp)
            )
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isPressed = true
                onClick()
                isPressed = false
            }
            .padding(20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        brush = gradient,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) gradient.colors[0] else Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = Color(0xFFB0B0C0),
                    lineHeight = 18.sp
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = androidx.compose.material.icons.filled.CheckCircle,
                    contentDescription = "Выбрано",
                    tint = gradient.colors[0],
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        if (isPressed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = 300f
                        )
                    )
            )
        }
    }
}