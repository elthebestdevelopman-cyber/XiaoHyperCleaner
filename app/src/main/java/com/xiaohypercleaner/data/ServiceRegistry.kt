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
        "com.miui.analytics",
        "com.xiaomi.ab",
        "com.miui.msa.global",
        "com.miui.msa.core",
        "com.miui.systemAdSolution",
        "com.xiaomi.discover",
        "com.miui.bugreport"
    )

    /**
     * Рекламные сервисы и ассистенты.
     */
    val AD_SERVICES_PACKAGES = listOf(
        "com.xiaomi.ad",
        "com.miui.ad",
        "com.miui.personalassistant",
        "com.miui.smartassistant"
    )

    /**
     * Дополнительные системные сервисы (опционально).
     */
    val OPTIONAL_PACKAGES = listOf(
        "com.miui.daemon",
        "com.miui.yellowpage",
        "com.miui.miservice"
    )

    val ALL_PACKAGES = ANALYTICS_PACKAGES + AD_SERVICES_PACKAGES + OPTIONAL_PACKAGES

    /**
     * Системные параметры (через "settings put").
     */
    val SYSTEM_SETTINGS = mapOf(
        "global low_power" to "1",
        "global always_finish_activities" to "0",
        "global window_animation_scale" to "0.5",
        "global transition_animation_scale" to "0.5",
        "global animator_duration_scale" to "0.5"
    )

    /**
     * Системные свойства (через "setprop").
     * Отдельный список, потому что setprop не имеет аналога "get" через settings.
     */
    val SYSTEM_PROPERTIES = mapOf(
        "persist.sys.timezone" to "Asia/Singapore"
    )

    /**
     * Системные свойства для отката.
     */
    val SYSTEM_PROPERTIES_RESTORE = mapOf(
        "persist.sys.timezone" to "Europe/Moscow"
    )

    /**
     * Скрытые ключи MIUI для отключения рекламы (через "settings put").
     */
    val HIDDEN_KEYS_DISABLE = mapOf(
        "secure miui_region" to "DE",
        "secure miui_ad_filtering_enabled" to "0",
        "global ad_control_enabled" to "0",
        "secure miui_ad_bg_thread_enabled" to "0",
        "system show_commercial_content" to "0",
        "secure limit_ad_tracking" to "1"
    )

    val HIDDEN_KEYS_RESTORE = mapOf(
        "secure miui_region" to "RU",
        "secure miui_ad_filtering_enabled" to "1",
        "global ad_control_enabled" to "1",
        "secure miui_ad_bg_thread_enabled" to "1",
        "system show_commercial_content" to "1",
        "secure limit_ad_tracking" to "0"
    )

    object Dns {
        const val MODE_KEY = "global private_dns_mode"
        const val MODE_VALUE = "hostname"
        const val SPECIFIER_KEY = "global private_dns_specifier"
        const val SPECIFIER_VALUE = "dns.adguard.com"
        const val RESTORE_MODE = "opportunistic"
    }
}