package com.xiaohypercleaner.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Режим оптимизации.
 * Чистый enum без зависимостей от Android Framework.
 *
 * Соответствует принципам чистой архитектуры:
 *  - Data-слой НЕ знает о Context и R
 *  - Локализованные строки резолвятся в UI-слое
 *    (см. com.xiaohypercleaner.ui.extensions.OptimizationModeExtensions)
 *
 * Два режима работы приложения:
 *  - SIMPLE: автоматизация через Accessibility Service
 *    (автоматически кликает по настройкам, как робот-помощник)
 *  - PRO: глубокая настройка через Shizuku / Wireless ADB
 *    (надёжнее, но требует предварительной настройки)
 */
enum class OptimizationMode {
    SIMPLE,
    PRO;

    /** Проверяет, простой ли это режим */
    fun isSimple(): Boolean = this == SIMPLE

    /** Проверяет, продвинутый ли это режим */
    fun isPro(): Boolean = this == PRO

    companion object {
        /**
         * Ключ для DataStore.
         * Вынесен в companion object — создаётся один раз при загрузке класса,
         * а не при каждом вызове (оптимизация производительности).
         *
         * Совпадает с PreferenceKey.OptimizationModeKey.name в PreferencesManager.
         */
        val PREFERENCE_KEY: Preferences.Key<String> =
            stringPreferencesKey("optimization_mode")

        /**
         * Парсит строку в enum с защитой от невалидных значений.
         *
         * @param value строковое значение («SIMPLE», «PRO» или null)
         * @return соответствующий enum или SIMPLE по умолчанию
         */
        fun fromString(value: String?): OptimizationMode {
            return try {
                valueOf(value ?: "SIMPLE")
            } catch (e: IllegalArgumentException) {
                SIMPLE
            }
        }
    }
}