package com.xiaohypercleaner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Светлая цветовая схема Material3.
 *
 * Использует:
 * - `Blue500` как primary (кнопки, акценты)
 * - `Teal200` как secondary (дополнительные акценты)
 * - Чистый белый для background и surface
 */
private val LightColorScheme = lightColorScheme(
    primary = Blue500,
    onPrimary = White,
    secondary = Teal200,
    onSecondary = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black
)

/**
 * Тёмная цветовая схема Material3.
 *
 * Использует:
 * - `Blue500` как primary (сохраняем брендинг)
 * - `Teal200` как secondary
 * - `DarkBackground` (#121212) для фона
 * - `DarkSurface` (#1E1E1E) для карточек
 */
private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    onPrimary = White,
    secondary = Teal200,
    onSecondary = Color.Black,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White
)

/**
 * Главная тема приложения XiaoHyperCleaner.
 *
 * Архитектура:
 * 1. Если Android 12+ (API 31+) и `dynamicColor = true` → Material You (цвета обоев)
 * 2. Иначе → кастомная схема (`LightColorScheme` / `DarkColorScheme`)
 *
 * Использование в `MainActivity`:
 * ```kotlin
 * val isDark = if (hasManuallyChosen) isDarkFromPrefs else isSystemInDarkTheme()
 * XiaoHyperCleanerTheme(darkTheme = isDark) { ... }
 * ```
 *
 * УЛУЧШЕНИЯ:
 * 1. Добавлена поддержка Material You (dynamic color) для Android 12+
 * 2. Явные типы для `colorScheme`
 * 3. Добавлены `onSecondary`, `onBackground`, `onSurface` для полной палитры
 * 4. Полный JavaDoc с описанием архитектуры
 *
 * @param darkTheme Использовать тёмную тему (управляется из `MainActivity`)
 * @param dynamicColor Использовать Material You на Android 12+ (по умолчанию false)
 * @param content Composable контент приложения
 */
@Composable
fun XiaoHyperCleanerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,  // false по умолчанию, чтобы сохранить брендинг
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Material You (Android 12+, API 31+)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // Кастомная тёмная тема
        darkTheme -> DarkColorScheme

        // Кастомная светлая тема (по умолчанию)
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}