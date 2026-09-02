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
import com.xiaohypercleaner.util.AppLog

private const val TAG = "OptimizationLevelDialog"

/**
 * Акцентный цвет для Pro-режима (teal).
 */
private val TealAccent: Color = Color(0xFF26C6DA)

/**
 * Диалог выбора уровня оптимизации (Simple/Pro).
 *
 * Показывается после нажатия кнопки "Оптимизировать" на главном экране.
 * Содержит:
 * - Две карточки режимов с иконками и описаниями
 * - Кнопку "Продолжить" (disabled до выбора режима)
 * - Анимированного робокота, умывающегося лапкой (как настоящий кот)
 *
 * @param onModeSelected Callback при подтверждении выбора режима
 * @param onDismiss Callback при отмене (закрытие диалога)
 */
@Composable
fun OptimizationLevelDialog(
    onModeSelected: (OptimizationMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode: OptimizationMode? by remember { mutableStateOf(null) }

    AppLog.d(TAG, "OptimizationLevelDialog shown")

    Dialog(onDismissRequest = {
        AppLog.i(TAG, "Dialog dismissed by system")
        onDismiss()
    }) {
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
                // ═══════════════════════════════════════════════════════════════
                // Заголовок
                // ═══════════════════════════════════════════════════════════════

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

                // ═══════════════════════════════════════════════════════════════
                // Карточки режимов
                // ═══════════════════════════════════════════════════════════════

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
                    onClick = {
                        AppLog.i(TAG, "Simple mode selected")
                        selectedMode = OptimizationMode.SIMPLE
                    }
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
                    onClick = {
                        AppLog.i(TAG, "Pro mode selected")
                        selectedMode = OptimizationMode.PRO
                    }
                )
                Spacer(Modifier.height(20.dp))

                // ═══════════════════════════════════════════════════════════════
                // Кнопки действий
                // ═══════════════════════════════════════════════════════════════

                Button(
                    onClick = {
                        val mode: OptimizationMode = selectedMode ?: return@Button
                        AppLog.i(TAG, "Continue clicked, mode=$mode")
                        onModeSelected(mode)
                    },
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
                    onClick = {
                        AppLog.i(TAG, "Cancel clicked")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.height(8.dp))

                // ═══════════════════════════════════════════════════════════════
                // Робокот умывается лапкой (как настоящий кот)
                // ═══════════════════════════════════════════════════════════════

                RoboCatWashing(modifier = Modifier.size(160.dp, 160.dp))
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

/**
 * Карточка режима оптимизации с иконкой, заголовком и описанием.
 *
 * @param icon Composable иконки (обычно `Icon` с `ImageVector`)
 * @param tint Акцентный цвет карточки (используется для border и фона при выборе)
 * @param title Заголовок режима
 * @param description Описание режима
 * @param selected Выбрана ли карточка
 * @param onClick Callback при нажатии на карточку
 */
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

/**
 * Анимированный робокот, умывающийся лапкой — как настоящий кот.
 *
 * Использует те же цвета и пропорции, что и официальный drawable
 * `ic_robot_companion.xml`, но с дополнительной анимацией:
 * правая лапка движется по дуге от тела к щеке и обратно,
 * имитируя кошачье умывание.
 *
 * Анимация через `rememberInfiniteTransition` + `animateFloat`:
 * - `wash` от 0 до 1 (с `RepeatMode.Reverse` — плавное движение туда-обратно)
 * - Лапка движется по квадратичной кривой Безье от исходной точки к щеке
 *
 * @param modifier Modifier для настройки размера (рекомендуется 160x160 dp)
 */
@Composable
private fun RoboCatWashing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "robocat_wash")
    val wash: Float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wash_motion"
    )

    Canvas(modifier = modifier) {
        val d: Float = density
        fun px(v: Float): Float = v * d

        // ── Цвета из официального ic_robot_companion.xml ──
        val bodyColor: Color = Color(0xFFB0BEC5)      // Светло-серый металлический (тело, голова)
        val limbColor: Color = Color(0xFF90A4AE)      // Чуть темнее (лапки, ушки)
        val darkColor: Color =
            Color(0xFF546E7A)      // Тёмные детали (антенна, винтики, грудная панель)
        val screenColor: Color = Color(0xFF263238)    // Очень тёмный (экран-лицо)
        val glowColor: Color = Color(0xFF4FC3F7)      // Голубое свечение (глаза, индикаторы)
        val glowBright: Color = Color(0xFFE1F5FE)     // Яркие блики в глазах
        val white: Color = Color(0xFFFFFFFF)          // Белые блики
        val antennaRed: Color = Color(0xFFFF5252)     // Красный кончик антенны
        val blush: Color = Color(0xFFFF80AB)          // Розовый румянец на щеках

        // ═══════════════════════════════════════════════════════════════
        // Хвостик-антенна сзади (за телом)
        // ═══════════════════════════════════════════════════════════════
        val tail = Path().apply {
            moveTo(px(115f), px(110f))
            quadraticTo(px(135f), px(105f), px(140f), px(92f))
        }
        // ИСПРАВЛЕНО: убран несуществующий параметр `path` в Stroke и `toPath()` у Color.
        // Цвет stroke задаётся через параметр `color` в drawPath().
        // Используем именованные параметры для современности и читаемости.
        drawPath(
            path = tail,
            color = darkColor,
            style = Stroke(width = px(3f), cap = StrokeCap.Round)
        )
        // Шарик на хвосте (голубой)
        drawCircle(glowColor, px(4f), Offset(px(140f), px(92f)))

        // ═══════════════════════════════════════════════════════════════
        // Тело (металлическое, округлое)
        // ═══════════════════════════════════════════════════════════════
        val body = Path().apply {
            moveTo(px(42f), px(98f))
            quadraticTo(px(42f), px(85f), px(55f), px(83f))
            lineTo(px(105f), px(83f))
            quadraticTo(px(118f), px(85f), px(118f), px(98f))
            lineTo(px(118f), px(135f))
            quadraticTo(px(118f), px(148f), px(105f), px(148f))
            lineTo(px(55f), px(148f))
            quadraticTo(px(42f), px(148f), px(42f), px(135f))
            close()
        }
        drawPath(body, bodyColor)

        // Тёмная грудная панель
        val chest = Path().apply {
            moveTo(px(62f), px(98f))
            quadraticTo(px(62f), px(90f), px(72f), px(90f))
            lineTo(px(88f), px(90f))
            quadraticTo(px(98f), px(90f), px(98f), px(98f))
            lineTo(px(98f), px(130f))
            quadraticTo(px(98f), px(138f), px(88f), px(138f))
            lineTo(px(72f), px(138f))
            quadraticTo(px(62f), px(138f), px(62f), px(130f))
            close()
        }
        drawPath(chest, darkColor)

        // Светящийся индикатор на груди
        drawCircle(glowColor, px(5f), Offset(px(80f), px(114f)))
        drawCircle(glowBright, px(2f), Offset(px(80f), px(114f)))

        // ═══════════════════════════════════════════════════════════════
        // Статичные лапки (ножки)
        // ═══════════════════════════════════════════════════════════════
        // Левая ножка
        val legL = Path().apply {
            moveTo(px(46f), px(128f))
            quadraticTo(px(43f), px(128f), px(43f), px(136f))
            lineTo(px(43f), px(148f))
            quadraticTo(px(43f), px(153f), px(49f), px(153f))
            lineTo(px(61f), px(153f))
            quadraticTo(px(67f), px(153f), px(67f), px(148f))
            lineTo(px(67f), px(136f))
            quadraticTo(px(67f), px(128f), px(61f), px(128f))
            close()
        }
        drawPath(legL, limbColor)
        // Подушечки на левой ножке
        drawCircle(glowColor, px(2f), Offset(px(51f), px(146f)))
        drawCircle(glowColor, px(2f), Offset(px(58f), px(146f)))

        // Правая ножка
        val legR = Path().apply {
            moveTo(px(93f), px(128f))
            quadraticTo(px(87f), px(128f), px(87f), px(136f))
            lineTo(px(87f), px(148f))
            quadraticTo(px(87f), px(153f), px(93f), px(153f))
            lineTo(px(105f), px(153f))
            quadraticTo(px(111f), px(153f), px(111f), px(148f))
            lineTo(px(111f), px(136f))
            quadraticTo(px(111f), px(128f), px(105f), px(128f))
            close()
        }
        drawPath(legR, limbColor)
        // Подушечки на правой ножке
        drawCircle(glowColor, px(2f), Offset(px(96f), px(146f)))
        drawCircle(glowColor, px(2f), Offset(px(103f), px(146f)))

        // ═══════════════════════════════════════════════════════════════
        // Голова (большая, круглая, металлическая)
        // ═══════════════════════════════════════════════════════════════
        val head = Path().apply {
            moveTo(px(30f), px(52f))
            quadraticTo(px(30f), px(15f), px(80f), px(15f))
            quadraticTo(px(130f), px(15f), px(130f), px(52f))
            quadraticTo(px(130f), px(88f), px(80f), px(88f))
            quadraticTo(px(30f), px(88f), px(30f), px(52f))
            close()
        }
        drawPath(head, bodyColor)

        // Левое кошачье ушко (металлическое)
        val earL = Path().apply {
            moveTo(px(45f), px(22f))
            lineTo(px(36f), px(0f))
            lineTo(px(62f), px(18f))
            close()
        }
        drawPath(earL, limbColor)
        // Правое кошачье ушко
        val earR = Path().apply {
            moveTo(px(115f), px(22f))
            lineTo(px(124f), px(0f))
            lineTo(px(98f), px(18f))
            close()
        }
        drawPath(earR, limbColor)

        // Светящаяся внутренняя часть ушек
        val earInL = Path().apply {
            moveTo(px(47f), px(20f))
            lineTo(px(42f), px(7f))
            lineTo(px(57f), px(17f))
            close()
        }
        val earInR = Path().apply {
            moveTo(px(113f), px(20f))
            lineTo(px(118f), px(7f))
            lineTo(px(103f), px(17f))
            close()
        }
        drawPath(earInL, glowColor)
        drawPath(earInR, glowColor)

        // Антенна на голове
        drawLine(
            darkColor,
            Offset(px(80f), px(15f)),
            Offset(px(80f), px(5f)),
            px(2.5f),
            cap = StrokeCap.Round
        )
        drawCircle(antennaRed, px(3f), Offset(px(80f), px(5f)))

        // Экран-лицо (тёмный, скруглённый)
        val screen = Path().apply {
            moveTo(px(42f), px(42f))
            quadraticTo(px(42f), px(28f), px(80f), px(28f))
            quadraticTo(px(118f), px(28f), px(118f), px(42f))
            lineTo(px(118f), px(66f))
            quadraticTo(px(118f), px(78f), px(80f), px(78f))
            quadraticTo(px(42f), px(78f), px(42f), px(66f))
            close()
        }
        drawPath(screen, screenColor)

        // Левый глаз (светящийся)
        drawCircle(glowColor, px(9f), Offset(px(58f), px(52f)))
        drawCircle(glowBright, px(4f), Offset(px(58f), px(52f)))
        drawCircle(white, px(1.5f), Offset(px(60f), px(49f)))

        // Правый глаз (светящийся)
        drawCircle(glowColor, px(9f), Offset(px(102f), px(52f)))
        drawCircle(glowBright, px(4f), Offset(px(102f), px(52f)))
        drawCircle(white, px(1.5f), Offset(px(104f), px(49f)))

        // Милый ротик (дуга)
        val mouth = Path().apply {
            moveTo(px(72f), px(64f))
            quadraticTo(px(80f), px(70f), px(88f), px(64f))
        }
        // ИСПРАВЛЕНО: убран несуществующий параметр `path` в Stroke и `toPath()` у Color.
        // Цвет stroke задаётся через параметр `color` в drawPath().
        drawPath(
            path = mouth,
            color = glowColor,
            style = Stroke(width = px(2f), cap = StrokeCap.Round)
        )

        // Румянец-индикаторы на щеках
        drawOval(
            color = blush.copy(alpha = 0.8f),
            topLeft = Offset(px(45f), px(59.5f)),
            size = Size(px(6f), px(5f))
        )
        drawOval(
            color = blush.copy(alpha = 0.8f),
            topLeft = Offset(px(109f), px(59.5f)),
            size = Size(px(6f), px(5f))
        )

        // Боковые винтики на голове
        drawCircle(darkColor, px(2.5f), Offset(px(36f), px(52f)))
        drawCircle(darkColor, px(2.5f), Offset(px(124f), px(52f)))

        // ═══════════════════════════════════════════════════════════════
        // АНИМАЦИЯ: правая лапка умывает щёку (как кот)
        //
        // Лапка движется по дуге Безье:
        //   Старт: (104, 118) — у правого бока тела
        //   Контрольная точка: (130, 90) — выгиб наружу
        //   Финиш: (118, 62) — правая щека
        //
        // `wash` меняется 0 → 1 → 0 (Reverse), создавая плавное движение
        // туда-обратно. Лапка "умывает" щёку 2 раза в секунду.
        // ═══════════════════════════════════════════════════════════════
        val pawStart = Offset(px(104f), px(118f))       // У тела
        val pawControl = Offset(px(130f), px(88f))       // Выгиб наружу
        val pawEnd = Offset(px(118f), px(62f))           // Правая щека

        // Квадратичная интерполяция Безье: P = (1-t)²·P0 + 2(1-t)t·P1 + t²·P2
        val t: Float = wash
        val invT: Float = 1f - t
        val pawX: Float = invT * invT * pawStart.x +
                2f * invT * t * pawControl.x +
                t * t * pawEnd.x
        val pawY: Float = invT * invT * pawStart.y +
                2f * invT * t * pawControl.y +
                t * t * pawEnd.y

        val pawCenter = Offset(pawX, pawY)

        // "Рука" — толстая линия от плеча до лапки
        val shoulder = Offset(px(108f), px(100f))
        drawLine(
            limbColor,
            shoulder,
            pawCenter,
            px(10f),
            cap = StrokeCap.Round
        )

        // Сама лапка (круглая)
        drawCircle(limbColor, px(10f), pawCenter)
        // Подушечки на умывающей лапке (видны когда лапка у щеки)
        drawCircle(glowColor, px(2.5f), pawCenter + Offset(px(-3f), px(2f)))
        drawCircle(glowColor, px(2.5f), pawCenter + Offset(px(3f), px(2f)))
        drawCircle(glowColor, px(2.5f), pawCenter + Offset(px(0f), px(-3f)))
    }
}