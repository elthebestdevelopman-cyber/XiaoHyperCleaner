package com.xiaohypercleaner.data

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Режим оптимизации
 */
enum class OptimizationMode {
    /**
     * Простой режим — для всех пользователей.
     * Работает через Accessibility Service, автоматически выполняет настройки
     */
    SIMPLE,

    /**
     * Продвинутый режим — для опытных пользователей.
     * Использует Shizuku или Wireless ADB для глубокой настройки
     */
    PRO;

    fun toPreferenceKey() = stringPreferencesKey("optimization_mode")

    companion object {
        fun fromString(value: String?): OptimizationMode {
            return try {
                valueOf(value ?: "SIMPLE")
            } catch (e: IllegalArgumentException) {
                SIMPLE
            }
        }
    }
}
