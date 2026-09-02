package com.xiaohypercleaner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Типографика приложения (шрифтовая система).
 *
 * Использует системный шрифт (FontFamily.Default) для максимальной
 * совместимости с MIUI/HyperOS и поддержки кириллицы.
 *
 * УЛУЧШЕНИЯ:
 * 1. Добавлены все стили, используемые в компонентах проекта
 * 2. Логическая группировка по назначению
 * 3. Документация для каждого стиля
 */
val Typography = Typography(
    // ═══════════════════════════════════════════════════════════════
    // Заголовки (экраны и секции)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Крупный заголовок экрана (Онбординг, заголовки диалогов).
     * Используется в: OnboardingScreen, OptimizationLevelDialog.
     */
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    /**
     * Крупный заголовок карточек и экранов.
     * Используется в: InfoCard, OptimizationCard, Theme.kt.
     */
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),

    /**
     * Средний заголовок секций и диалогов.
     * Используется в: Диалоги, заголовки карточек.
     */
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),

    /**
     * Малый заголовок подзаголовков.
     * Используется в: InfoCard ("Что мы делаем"), диалоги.
     */
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),

    // ═══════════════════════════════════════════════════════════════
    // Основной текст
    // ═══════════════════════════════════════════════════════════════

    /**
     * Крупный основной текст (онбординг, описания).
     * Используется в: OnboardingScreen.
     */
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),

    /**
     * Стандартный основной текст.
     * Используется в: Везде — самый используемый стиль.
     */
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),

    /**
     * Малый основной текст (описания, подписи, детали).
     * Используется в: Диалоги, InfoCard, RestrictedSettingsScreen.
     */
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ═══════════════════════════════════════════════════════════════
    // Метки и технические тексты
    // ═══════════════════════════════════════════════════════════════

    /**
     * Средняя метка (счётчики шагов, статусы).
     * Используется в: SimpleStepScreen ("Шаг 3 из 26").
     */
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp
    ),

    /**
     * Малая метка (версия приложения, технические тексты).
     * Используется в: Меню, карточки.
     */
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),

    /**
     * Крупная метка для кнопок.
     * Используется в: Material3 Button по умолчанию.
     */
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)