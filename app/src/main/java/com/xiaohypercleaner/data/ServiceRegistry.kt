package com.xiaohypercleaner.data

/**
 * Централизованный реестр пакетов и настроек для оптимизации Xiaomi/Redmi/Poco.
 *
 * Структура:
 * 1. AD_SERVICES_PACKAGES — системные сервисы для оптимизации (отключение снижает нагрузку)
 * 2. ANALYTICS_PACKAGES — сервисы телеметрии и сбора статистики
 * 3. HIDDEN_KEYS_DISABLE — безопасные настройки, не ломающие функциональность
 * 4. SYSTEM_SETTINGS — параметры производительности
 * 5. REGIONAL_KEYS — опционально, только при включенном aggressive mode
 *
 * Все изменения обратимы через Transaction-паттерн в OptimizationEngine.
 * Откат читает сохранённые оригинальные значения (не хардкодит).
 */
object ServiceRegistry {

    // ===== 1. СИСТЕМНЫЕ СЕРВИСЫ: оптимизация фоновых процессов =====
    val AD_SERVICES_PACKAGES = listOf(
        "com.miui.msa.global",          // Системный сервис
        "com.miui.msa.core",            // Ядро сервиса
        "com.miui.systemAdSolution",    // Системный сервис настроек
        "com.xiaomi.ad",                // Системный SDK
        "com.miui.ad",                  // Сервис MIUI
        "com.miui.personalassistant",   // Персональный ассистент
        "com.miui.smartassistant",      // Smart Assistant
        "com.xiaomi.discover",          // Сервис обнаружения контента
        "com.miui.yellowpage",          // Справочник
        "com.miui.hybrid",              // Гибридные приложения
        "com.xiaomi.joyose"             // Игровой сервис
    )

    // ===== 2. АНАЛИТИКА: сервисы телеметрии =====
    val ANALYTICS_PACKAGES = listOf(
        "com.miui.analytics",           // Аналитика MIUI
        "com.xiaomi.ab",                // A/B тесты
        "com.miui.bugreport"            // Отчёты об ошибках
    )

    // ===== 3. БЕЗОПАСНЫЕ НАСТРОЙКИ: не ломают функциональность =====
    val HIDDEN_KEYS_DISABLE = mapOf(
        "secure limit_ad_tracking" to "1",      // Ограничивает трекинг
        "secure user_experience_program" to "0", // Отключает программу улучшения UX
        "secure upload_log_pref" to "0",         // Отключает автозагрузку логов
        "secure show_recommendations" to "0"     // Отключает рекомендации
    )

    val HIDDEN_KEYS_RESTORE = mapOf(
        "secure limit_ad_tracking" to "0",
        "secure user_experience_program" to "1",
        "secure upload_log_pref" to "1",
        "secure show_recommendations" to "1"
    )

    // ===== 4. ПРОИЗВОДИТЕЛЬНОСТЬ =====
    val SYSTEM_SETTINGS = mapOf(
        "global low_power" to "1",
        "global always_finish_activities" to "0",
        "global window_animation_scale" to "0.5",
        "global transition_animation_scale" to "0.5",
        "global animator_duration_scale" to "0.5"
    )

    // ===== 5. ОПЦИОНАЛЬНО: Регион (только в aggressive mode) =====
    // ВАЖНО: OptimizationEngine сохраняет оригинальный регион в transaction.originalRegion
    // и восстанавливает его при rollback — не хардкодит RU
    val REGIONAL_KEYS = listOf("secure miui_region")

    // ===== DNS =====
    object Dns {
        const val MODE_KEY = "global private_dns_mode"
        const val MODE_VALUE = "hostname"
        const val SPECIFIER_KEY = "global private_dns_specifier"
        const val SPECIFIER_VALUE = "dns.adguard.com"
        const val RESTORE_MODE = "opportunistic"
    }

    // ===== Deprecated: для обратной совместимости =====
    @Deprecated("Use AD_SERVICES_PACKAGES", ReplaceWith("AD_SERVICES_PACKAGES"))
    val PACKAGES = AD_SERVICES_PACKAGES

    @Deprecated("Use SYSTEM_SETTINGS keys", ReplaceWith("SYSTEM_SETTINGS.keys"))
    val SYSTEM_KEYS = SYSTEM_SETTINGS.keys.toList()

    @Deprecated("Not used", ReplaceWith("emptyMap<String, String>()"))
    val SYSTEM_PROPERTIES: Map<String, String> = emptyMap()

    @Deprecated("Not used", ReplaceWith("emptyMap<String, String>()"))
    val SYSTEM_PROPERTIES_RESTORE: Map<String, String> = emptyMap()
}