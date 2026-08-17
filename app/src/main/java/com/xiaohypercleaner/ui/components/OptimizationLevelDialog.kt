package com.xiaohypercleaner.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val infiniteTransition = rememberInfiniteTransition(label = "robot_float")
    val floatY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float_y"
    )
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFFFAFAFA),
            shadowElevation = 32.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Robocat Mascot - плавающая анимация
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .offset(y = (-floatY).dp),
                    contentAlignment = Alignment.Center
                ) {
                    RobocatLarge(
                        modifier = Modifier.size(120.dp),
                        primaryColor = Color(0xFF4A90E2),
                        secondaryColor = Color(0xFFFF6B6B)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Выберите режим заботы",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF2D3436),
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = "Робокот подскажет, что лучше для вашего телефона",
                    fontSize = 15.sp,
                    color = Color(0xFF636E72),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
                )

                ModeCardModern(
                    mode = OptimizationMode.SIMPLE,
                    title = "Лёгкий",
                    subtitle = "Для всех",
                    description = "Бережно уберёт спам и рекламу. Максимально безопасно — идеально для первого раза.",
                    icon = IcRocket,
                    gradient = Brush.linearGradient(
                        colors = listOf(Color(0xFF4facfe), Color(0xFF00f2fe))
                    ),
                    isSelected = currentMode == OptimizationMode.SIMPLE,
                    onClick = { onModeSelected(OptimizationMode.SIMPLE) },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                ModeCardModern(
                    mode = OptimizationMode.PRO,
                    title = "Продвинутый",
                    subtitle = "Для опытных",
                    description = "Глубокая настройка системы. Потребуется дополнительная подготовка, но результат того стоит.",
                    icon = IcChip,
                    gradient = Brush.linearGradient(
                        colors = listOf(Color(0xFF667eea), Color(0xFF764ba2))
                    ),
                    isSelected = currentMode == OptimizationMode.PRO,
                    onClick = { onModeSelected(OptimizationMode.PRO) },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        "Назад",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF636E72)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCardModern(
    mode: OptimizationMode,
    title: String,
    subtitle: String,
    description: String,
    icon: ImageVector,
    gradient: Brush,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = if (isSelected) 20.dp else 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = if (isSelected) gradient.colors[0].copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.08f),
                spotColor = if (isSelected) gradient.colors[0].copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.08f)
            )
            .background(
                brush = if (isSelected) gradient else Brush.linearGradient(colors = listOf(Color.White, Color.White)),
                shape = RoundedCornerShape(24.dp)
            )
            .border(
                width = 2.dp,
                brush = if (isSelected) gradient else Brush.linearGradient(colors = listOf(Color(0xFFE8E8E8), Color(0xFFF5F5F5))),
                shape = RoundedCornerShape(24.dp)
            )
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isPressed = true
                onClick()
                isPressed = false
            }
            .padding(22.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with glow effect
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = gradient.colors[0].copy(alpha = 0.3f),
                        spotColor = gradient.colors[0].copy(alpha = 0.3f)
                    )
                    .background(
                        brush = gradient,
                        shape = CircleShape
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isSelected) Color.White else Color(0xFF2D3436)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = if (isSelected) Color.White.copy(alpha = 0.25f) else Color(0xFFF0F0F5),
                        contentColor = if (isSelected) Color.White else Color(0xFF636E72)
                    ) {
                        Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = if (isSelected) Color.White.copy(alpha = 0.95f) else Color(0xFF636E72),
                    lineHeight = 20.sp
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = androidx.compose.material.icons.filled.CheckCircle,
                    contentDescription = "Выбрано",
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.2f),
                            spotColor = Color.Black.copy(alpha = 0.2f)
                        )
                )
            }
        }
        
        // Press ripple effect
        if (isPressed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = 400f
                        )
                    )
            )
        }
    }
}

@Composable
private fun RobocatLarge(
    modifier: Modifier = Modifier,
    primaryColor: Color,
    secondaryColor: Color
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.minDimension / 2

        // Antenna ball
        drawCircle(
            color = secondaryColor,
            radius = radius * 0.08f,
            center = Offset(centerX, centerY - radius * 1.15f)
        )
        
        // Antenna stick
        drawLine(
            color = secondaryColor,
            start = Offset(centerX, centerY - radius * 0.85f),
            end = Offset(centerX, centerY - radius * 1.1f),
            strokeWidth = radius * 0.04f
        )

        // Left ear
        val leftEarPath = Path().apply {
            moveTo(centerX - radius * 0.5f, centerY - radius * 0.5f)
            lineTo(centerX - radius * 0.85f, centerY - radius * 0.95f)
            lineTo(centerX - radius * 0.25f, centerY - radius * 0.65f)
            close()
        }
        drawPath(leftEarPath, primaryColor, style = Fill)
        
        // Right ear
        val rightEarPath = Path().apply {
            moveTo(centerX + radius * 0.5f, centerY - radius * 0.5f)
            lineTo(centerX + radius * 0.85f, centerY - radius * 0.95f)
            lineTo(centerX + radius * 0.25f, centerY - radius * 0.65f)
            close()
        }
        drawPath(rightEarPath, primaryColor, style = Fill)

        // Main head
        drawCircle(
            color = primaryColor,
            radius = radius * 0.85f,
            center = Offset(centerX, centerY)
        )

        // Face area (lighter)
        drawCircle(
            color = primaryColor.copy(alpha = 0.85f),
            radius = radius * 0.65f,
            center = Offset(centerX, centerY + radius * 0.1f)
        )

        // Eyes with shine
        drawCircle(
            color = Color(0xFF2D3436),
            radius = radius * 0.13f,
            center = Offset(centerX - radius * 0.28f, centerY + radius * 0.05f)
        )
        drawCircle(
            color = Color(0xFF2D3436),
            radius = radius * 0.13f,
            center = Offset(centerX + radius * 0.28f, centerY + radius * 0.05f)
        )
        // Eye shine
        drawCircle(
            color = Color.White,
            radius = radius * 0.05f,
            center = Offset(centerX - radius * 0.24f, centerY + radius * 0.02f)
        )
        drawCircle(
            color = Color.White,
            radius = radius * 0.05f,
            center = Offset(centerX + radius * 0.32f, centerY + radius * 0.02f)
        )

        // Nose
        drawCircle(
            color = secondaryColor,
            radius = radius * 0.09f,
            center = Offset(centerX, centerY + radius * 0.28f)
        )

        // Smile
        drawArc(
            color = Color(0xFF2D3436),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(centerX - radius * 0.25f, centerY + radius * 0.35f),
            size = androidx.compose.ui.geometry.Size(radius * 0.5f, radius * 0.25f),
            style = Stroke(width = radius * 0.04f, cap = StrokeCap.Round)
        )
        
        // Cheeks
        drawCircle(
            color = secondaryColor.copy(alpha = 0.3f),
            radius = radius * 0.12f,
            center = Offset(centerX - radius * 0.45f, centerY + radius * 0.25f)
        )
        drawCircle(
            color = secondaryColor.copy(alpha = 0.3f),
            radius = radius * 0.12f,
            center = Offset(centerX + radius * 0.45f, centerY + radius * 0.25f)
        )
    }
}