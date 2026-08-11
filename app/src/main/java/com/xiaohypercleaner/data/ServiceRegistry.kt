package com.xiaohypercleaner.data

/**
 * Централизованный реестр системных сервисов и настроек для оптимизации.
 * Используется OptimizationEngine для применения и отката изменений.
 */
object ServiceRegistry {

    /**
     * Системные сервисы аналитики и отслеживания.
     * Источник: документация 4PDA, раздел "Безопасное отключение рекламы в MIUI/HyperOS"
     */
    val ANALYTICS_PACKAGES = listOf(
        "com.miui.analytics",        // Основная аналитика MIUI
        "com.xiaomi.ab",             // Xiaomi analytics backend
        "com.miui.msa.global",       // MIUI system analytics (global)
        "com.miui.msa.core",         // MIUI system analytics (core)
        "com.miui.systemAdSolution", // Системная реклама
        "com.xiaomi.discover",       // Xiaomi discover service
        "com.miui.bugreport"         // Bug report service
    )

    /**
     * Рекламные сервисы и ассистенты.
     */
    val AD_SERVICES_PACKAGES = listOf(
        "com.xiaomi.ad",             // Xiaomi ads
        "com.miui.ad",               // MIUI ads
        "com.miui.personalassistant", // Персональный ассистент
        "com.miui.smartassistant"    // Смарт-ассистент
    )

    /**
     * Дополнительные системные сервисы (опционально).
     * Могут быть отключены пользователем через настройки.
     */
    val OPTIONAL_PACKAGES = listOf(
        "com.miui.daemon",           // MIUI daemon
        "com.miui.yellowpage",       // Yellow pages
        "com.miui.miservice"         // MI service
    )

    /**
     * Все пакеты для отключения.
     */
    val ALL_PACKAGES = ANALYTICS_PACKAGES + AD_SERVICES_PACKAGES + OPTIONAL_PACKAGES

    /**
     * Системные параметры для оптимизации (ключ → значение).
     * Применяются через "settings put <namespace> <key> <value>"
     */
    val SYSTEM_SETTINGS = mapOf(
        "global low_power" to "1",
        "global always_finish_activities" to "0",
        "global window_animation_scale" to "0.5",
        "global transition_animation_scale" to "0.5",
        "global animator_duration_scale" to "0.5"
    )

    /**
     * Скрытые ключи MIUI для отключения рекламы.
     * Применяются через "settings put <namespace> <key> <value>"
     */
    val HIDDEN_KEYS_DISABLE = mapOf(
        "secure miui_region" to "DE",
        "secure miui_ad_filtering_enabled" to "0",
        "global ad_control_enabled" to "0",
        "secure miui_ad_bg_thread_enabled" to "0",
        "system show_commercial_content" to "0",
        "secure limit_ad_tracking" to "1"
    )

    /**
     * Значения для восстановления скрытых ключей.
     */
    val HIDDEN_KEYS_RESTORE = mapOf(
        "secure miui_region" to "RU",
        "secure miui_ad_filtering_enabled" to "1",
        "global ad_control_enabled" to "1",
        "secure miui_ad_bg_thread_enabled" to "1",
        "system show_commercial_content" to "1",
        "secure limit_ad_tracking" to "0"
    )

    /**
     * DNS настройки для AdGuard.
     */
    object Dns {
        const val MODE_KEY = "global private_dns_mode"
        const val MODE_VALUE = "hostname"
        const val SPECIFIER_KEY = "global private_dns_specifier"
        const val SPECIFIER_VALUE = "dns.adguard.com"
        const val RESTORE_MODE = "opportunistic"
    }
}