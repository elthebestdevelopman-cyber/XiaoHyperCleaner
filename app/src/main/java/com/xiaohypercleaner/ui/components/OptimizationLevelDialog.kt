package com.xiaohypercleaner.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.ui.theme.Blue500

private val TealAccent = Color(0xFF26C6DA)

@Composable
fun OptimizationLevelDialog(
    onModeSelected: (OptimizationMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf<OptimizationMode?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.level_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))

                ModeCard(
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Blue500,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    tint = Blue500,
                    title = stringResource(R.string.level_simple_title),
                    description = stringResource(R.string.level_simple_desc),
                    selected = selectedMode == OptimizationMode.SIMPLE,
                    onClick = { selectedMode = OptimizationMode.SIMPLE }
                )
                Spacer(Modifier.height(12.dp))
                ModeCard(
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            tint = TealAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    tint = TealAccent,
                    title = stringResource(R.string.level_pro_title),
                    description = stringResource(R.string.level_pro_desc),
                    selected = selectedMode == OptimizationMode.PRO,
                    onClick = { selectedMode = OptimizationMode.PRO }
                )
                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { selectedMode?.let { onModeSelected(it) } },
                    enabled = selectedMode != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.level_dialog_continue))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.height(8.dp))

                RoboCatWashing(modifier = Modifier.size(160.dp, 140.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.robocat_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: @Composable () -> Unit,
    tint: Color,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) tint.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, tint) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(8.dp))
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun RoboCatWashing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "robocat")
    val wash by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wash"
    )

    Canvas(modifier = modifier) {
        val d = density
        fun px(v: Float) = v * d

        val bodyColor = Color(0xFF90A4AE)
        val bellyColor = Color(0xFFCFD8DC)
        val darkColor = Color(0xFF37474F)
        val accent = TealAccent
        val pink = Color(0xFFF48FB1)

        val tail = Path().apply {
            moveTo(px(110f), px(120f))
            quadraticTo(px(140f), px(112f), px(134f), px(88f))
        }
        drawPath(tail, accent, style = Stroke(px(6f), cap = StrokeCap.Round))

        drawOval(
            color = bodyColor,
            topLeft = Offset(px(48f), px(86f)),
            size = Size(px(64f), px(46f))
        )
        drawOval(
            color = bellyColor,
            topLeft = Offset(px(66f), px(96f)),
            size = Size(px(28f), px(32f))
        )
        drawCircle(accent, px(3f), Offset(px(80f), px(106f)))
        drawCircle(pink, px(3f), Offset(px(80f), px(116f)))
        drawCircle(bodyColor, px(8f), Offset(px(60f), px(128f)))

        val earL = Path().apply {
            moveTo(px(54f), px(42f)); lineTo(px(62f), px(16f)); lineTo(px(74f), px(34f)); close()
        }
        val earR = Path().apply {
            moveTo(px(86f), px(34f)); lineTo(px(98f), px(16f)); lineTo(px(106f), px(42f)); close()
        }
        drawPath(earL, bodyColor)
        drawPath(earR, bodyColor)
        val earInL = Path().apply {
            moveTo(px(59f), px(38f)); lineTo(px(63f), px(24f)); lineTo(px(70f), px(34f)); close()
        }
        val earInR = Path().apply {
            moveTo(px(90f), px(34f)); lineTo(px(97f), px(24f)); lineTo(px(101f), px(38f)); close()
        }
        drawPath(earInL, pink)
        drawPath(earInR, pink)

        drawLine(
            darkColor,
            Offset(px(80f), px(32f)),
            Offset(px(80f), px(22f)),
            px(3f),
            cap = StrokeCap.Round
        )
        drawCircle(accent, px(4f), Offset(px(80f), px(18f)))

        drawCircle(bodyColor, px(30f), Offset(px(80f), px(60f)))

        drawArc(
            color = darkColor, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(px(62f), px(56f)), size = Size(px(12f), px(8f)),
            style = Stroke(px(3f), cap = StrokeCap.Round)
        )
        drawArc(
            color = darkColor, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(px(86f), px(56f)), size = Size(px(12f), px(8f)),
            style = Stroke(px(3f), cap = StrokeCap.Round)
        )
        drawArc(
            color = darkColor, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(px(72f), px(66f)), size = Size(px(8f), px(8f)),
            style = Stroke(px(2.5f), cap = StrokeCap.Round)
        )
        drawArc(
            color = darkColor, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(px(80f), px(66f)), size = Size(px(8f), px(8f)),
            style = Stroke(px(2.5f), cap = StrokeCap.Round)
        )
        drawCircle(pink.copy(alpha = 0.6f), px(5f), Offset(px(58f), px(70f)))
        drawCircle(pink.copy(alpha = 0.6f), px(5f), Offset(px(102f), px(70f)))
        drawLine(
            darkColor,
            Offset(px(40f), px(54f)),
            Offset(px(56f), px(58f)),
            px(2f),
            cap = StrokeCap.Round
        )
        drawLine(
            darkColor,
            Offset(px(40f), px(66f)),
            Offset(px(56f), px(64f)),
            px(2f),
            cap = StrokeCap.Round
        )

        val pawCenter = Offset(px(104f), px(62f + wash * 8f))
        drawLine(bodyColor, Offset(px(96f), px(92f)), pawCenter, px(10f), cap = StrokeCap.Round)
        drawCircle(bodyColor, px(9f), pawCenter)

        drawCircle(accent.copy(alpha = 0.55f), px(4f), Offset(px(120f), px(50f - wash * 6f)))
        drawCircle(accent.copy(alpha = 0.35f), px(3f), Offset(px(128f), px(58f - wash * 4f)))
    }
}