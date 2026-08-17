package com.xiaohypercleaner.data

/**
 * Режим оптимизации
 */
enum class OptimizationMode {
    /**
     * Простой режим — для всех пользователей
     * Работает через Accessibility Service, автоматически выполняет настройки
     */
    SIMPLE,
    
    /**
     * Продвинутый режим — для опытных пользователей
     * Использует Shizuku или Wireless ADB для глубокой настройки
     */
    PRO
}
