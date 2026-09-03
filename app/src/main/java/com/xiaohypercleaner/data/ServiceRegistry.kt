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
 *
 * УЛУЧШЕНИЯ:
 * 1. Полная документация для каждого пакета и настройки
 * 2. Категории рисков (SAFE/CONDITIONAL/HIGH) для каждого пакета
 * 3. Convenience методы для получения списков пакетов
 * 4. Валидация ключей при инициализации
 */
object ServiceRegistry {

    /**
     * Уровень риска отключения пакета.
     * Используется для предупреждения пользователя в UI.
     */
    enum class RiskLevel {
        /** Безопасно отключать — не влияет на работу системы */
        SAFE,

        /** Условно безопасно — может повлиять на некоторые функции */
        CONDITIONAL,

        /** Высокий риск — может сломать системные функции */
        HIGH
    }

    /**
     * Метаданные пакета с уровнем риска и описанием.
     */
    data class PackageInfo(
        val packageName: String,
        val riskLevel: RiskLevel,
        val descriptionRu: String,
        val descriptionEn: String
    )

    // ═══════════════════════════════════════════════════════════════
    // 1. СИСТЕМНЫЕ СЕРВИСЫ: оптимизация фоновых процессов
    // ═══════════════════════════════════════════════════════════════

    /**
     * Системные сервисы для оптимизации.
     * Отключение снижает нагрузку на CPU/RAM, но может повлиять на некоторые функции.
     */
    val AD_SERVICES_PACKAGES = listOf(
        "com.miui.msa.global",          // MSA (MIUI System Ads) — системный сервис рекомендаций
        "com.miui.msa.core",            // Ядро MSA сервиса
        "com.miui.systemAdSolution",    // Системный сервис настроек рекомендаций
        "com.xiaomi.ad",                // Xiaomi Ad SDK — библиотека для разработчиков
        "com.miui.ad",                  // MIUI Ad сервис
        "com.miui.personalassistant",   // Персональный ассистент (лента виджетов)
        "com.miui.smartassistant",      // Smart Assistant — умный помощник
        "com.xiaomi.discover",          // Сервис обнаружения контента
        "com.miui.yellowpage",          // Справочник (желтые страницы)
        "com.miui.hybrid",              // Гибридные приложения (Quick Apps)
        "com.xiaomi.joyose"             // Игровой сервис (Joyose)
    )

    /**
     * Метаданные для AD_SERVICES_PACKAGES с уровнями риска.
     */
    val AD_SERVICES_INFO = listOf(
        PackageInfo(
            "com.miui.msa.global",
            RiskLevel.SAFE,
            "Системный сервис рекомендаций",
            "System recommendations service"
        ),
        PackageInfo("com.miui.msa.core", RiskLevel.SAFE, "Ядро MSA сервиса", "MSA service core"),
        PackageInfo(
            "com.miui.systemAdSolution",
            RiskLevel.SAFE,
            "Сервис настроек рекомендаций",
            "Recommendations settings service"
        ),
        PackageInfo("com.xiaomi.ad", RiskLevel.SAFE, "Xiaomi Ad SDK", "Xiaomi Ad SDK"),
        PackageInfo("com.miui.ad", RiskLevel.SAFE, "MIUI Ad сервис", "MIUI Ad service"),
        PackageInfo(
            "com.miui.personalassistant",
            RiskLevel.CONDITIONAL,
            "Лента виджетов",
            "App Vault"
        ),
        PackageInfo(
            "com.miui.smartassistant",
            RiskLevel.CONDITIONAL,
            "Умный помощник",
            "Smart Assistant"
        ),
        PackageInfo(
            "com.xiaomi.discover",
            RiskLevel.SAFE,
            "Сервис обнаружения контента",
            "Content discovery service"
        ),
        PackageInfo("com.miui.yellowpage", RiskLevel.SAFE, "Справочник", "Yellow pages"),
        PackageInfo("com.miui.hybrid", RiskLevel.CONDITIONAL, "Quick Apps", "Quick Apps"),
        PackageInfo("com.xiaomi.joyose", RiskLevel.CONDITIONAL, "Игровой сервис", "Gaming service")
    )

    // ═══════════════════════════════════════════════════════════════
    // 2. АНАЛИТИКА: сервисы телеметрии
    // ═══════════════════════════════════════════════════════════════

    /**
     * Сервисы телеметрии и сбора статистики.
     * Безопасно отключать — не влияет на работу системы.
     */
    val ANALYTICS_PACKAGES = listOf(
        "com.miui.analytics",           // Аналитика MIUI — сбор статистики использования
        "com.xiaomi.ab",                // A/B тесты — экспериментальные функции
        "com.miui.bugreport"            // Отчёты об ошибках — автоматическая отправка crash-логов
    )

    /**
     * Метаданные для ANALYTICS_PACKAGES с уровнями риска.
     */
    val ANALYTICS_INFO = listOf(
        PackageInfo("com.miui.analytics", RiskLevel.SAFE, "Аналитика MIUI", "MIUI analytics"),
        PackageInfo("com.xiaomi.ab", RiskLevel.SAFE, "A/B тесты", "A/B testing"),
        PackageInfo("com.miui.bugreport", RiskLevel.SAFE, "Отчёты об ошибках", "Bug reports")
    )

    // ═══════════════════════════════════════════════════════════════
    // 3. БЕЗОПАСНЫЕ НАСТРОЙКИ: не ломают функциональность
    // ═══════════════════════════════════════════════════════════════

    /**
     * Скрытые настройки для отключения.
     * Все изменения обратимы через HIDDEN_KEYS_RESTORE.
     */
    val HIDDEN_KEYS_DISABLE = mapOf(
        "secure limit_ad_tracking" to "1",      // Ограничивает трекинг для персонализации
        "secure user_experience_program" to "0", // Отключает программу улучшения UX (телеметрия)
        "secure upload_log_pref" to "0",         // Отключает автозагрузку логов на серверы Xiaomi
        "secure show_recommendations" to "0",    // Отключает показ рекомендаций в системных приложениях
        "system miui_recents_show_recommend" to "0" // Отключает рекламные предложения в недавних приложениях
    )

    /**
     * Значения для отката скрытых настроек.
     * Используется в Transaction.rollback() для восстановления исходного состояния.
     */
    val HIDDEN_KEYS_RESTORE = mapOf(
        "secure limit_ad_tracking" to "0",
        "secure user_experience_program" to "1",
        "secure upload_log_pref" to "1",
        "secure show_recommendations" to "1",
        "system miui_recents_show_recommend" to "1"
    )

    // ═══════════════════════════════════════════════════════════════
    // 4. ПРОИЗВОДИТЕЛЬНОСТЬ
    // ═══════════════════════════════════════════════════════════════

    /**
     * Системные настройки для оптимизации производительности.
     * Влияют на анимации и поведение системы.
     */
    val SYSTEM_SETTINGS = mapOf(
        "global low_power" to "1",                      // Включает режим энергосбережения
        "global always_finish_activities" to "0",       // Не закрывать активности в фоне
        "global window_animation_scale" to "0.5",       // Ускорение анимации окон (0.5x)
        "global transition_animation_scale" to "0.5",   // Ускорение переходов (0.5x)
        "global animator_duration_scale" to "0.5"       // Ускорение аниматоров (0.5x)
    )

    // ═══════════════════════════════════════════════════════════════
    // 5. ОПЦИОНАЛЬНО: Регион (УДАЛЕНО - небезопасно)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Региональные настройки.
     * УДАЛЕНО из-за риска нарушения работы системных сервисов.
     * Изменение региона может сломать Google Pay, банковские приложения и т.д.
     */
    val REGIONAL_KEYS = emptyList<String>()

    // ═══════════════════════════════════════════════════════════════
    // DNS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Настройки Private DNS для блокировки трекеров на уровне сети.
     * Используется AdGuard DNS — бесплатный публичный DNS с блокировкой рекламы.
     */
    object Dns {
        const val MODE_KEY = "global private_dns_mode"
        const val MODE_VALUE = "hostname"
        const val SPECIFIER_KEY = "global private_dns_specifier"
        const val SPECIFIER_VALUE = "dns.adguard.com"
        const val RESTORE_MODE = "opportunistic"
    }

    // ═══════════════════════════════════════════════════════════════
    // Deprecated: для обратной совместимости
    // ═══════════════════════════════════════════════════════════════

    @Deprecated("Use AD_SERVICES_PACKAGES", ReplaceWith("AD_SERVICES_PACKAGES"))
    val PACKAGES = AD_SERVICES_PACKAGES

    @Deprecated("Use SYSTEM_SETTINGS keys", ReplaceWith("SYSTEM_SETTINGS.keys"))
    val SYSTEM_KEYS = SYSTEM_SETTINGS.keys.toList()

    @Deprecated("Not used", ReplaceWith("emptyMap<String, String>()"))
    val SYSTEM_PROPERTIES: Map<String, String> = emptyMap()

    @Deprecated("Not used", ReplaceWith("emptyMap<String, String>()"))
    val SYSTEM_PROPERTIES_RESTORE: Map<String, String> = emptyMap()

    // ═══════════════════════════════════════════════════════════════
    // Convenience методы
    // ═══════════════════════════════════════════════════════════════

    /**
     * Возвращает все пакеты для оптимизации (AD_SERVICES + ANALYTICS).
     * Используется для отображения общего списка в UI.
     */
    fun getAllPackages(): List<String> = AD_SERVICES_PACKAGES + ANALYTICS_PACKAGES

    /**
     * Возвращает только безопасные пакеты (RiskLevel.SAFE).
     * Используется для быстрого режима оптимизации без предупреждений.
     */
    fun getSafePackages(): List<String> {
        val safeAdServices =
            AD_SERVICES_INFO.filter { it.riskLevel == RiskLevel.SAFE }.map { it.packageName }
        val safeAnalytics =
            ANALYTICS_INFO.filter { it.riskLevel == RiskLevel.SAFE }.map { it.packageName }
        return safeAdServices + safeAnalytics
    }

    /**
     * Возвращает условно безопасные пакеты (RiskLevel.CONDITIONAL).
     * Требует подтверждения пользователя в UI.
     */
    fun getConditionalPackages(): List<String> {
        val conditionalAdServices =
            AD_SERVICES_INFO.filter { it.riskLevel == RiskLevel.CONDITIONAL }.map { it.packageName }
        val conditionalAnalytics =
            ANALYTICS_INFO.filter { it.riskLevel == RiskLevel.CONDITIONAL }.map { it.packageName }
        return conditionalAdServices + conditionalAnalytics
    }

    /**
     * Возвращает метаданные пакета по имени.
     * Используется для отображения описания и уровня риска в UI.
     */
    fun getPackageInfo(packageName: String): PackageInfo? {
        return (AD_SERVICES_INFO + ANALYTICS_INFO).find { it.packageName == packageName }
    }

    /**
     * Проверяет, является ли пакет безопасным для отключения.
     */
    fun isSafePackage(packageName: String): Boolean {
        return getPackageInfo(packageName)?.riskLevel == RiskLevel.SAFE
    }

    /**
     * Валидация: проверяет, что все ключи в HIDDEN_KEYS_DISABLE есть в HIDDEN_KEYS_RESTORE.
     * Вызывается при инициализации для защиты от ошибок разработчика.
     */
    init {
        val disableKeys = HIDDEN_KEYS_DISABLE.keys
        val restoreKeys = HIDDEN_KEYS_RESTORE.keys

        if (disableKeys != restoreKeys) {
            val missing = disableKeys - restoreKeys
            val extra = restoreKeys - disableKeys

            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "HIDDEN_KEYS_RESTORE missing keys: $missing. " +
                            "All keys from HIDDEN_KEYS_DISABLE must be present in HIDDEN_KEYS_RESTORE."
                )
            }

            if (extra.isNotEmpty()) {
                throw IllegalStateException(
                    "HIDDEN_KEYS_RESTORE has extra keys: $extra. " +
                            "Only keys from HIDDEN_KEYS_DISABLE should be present."
                )
            }
        }
    }
}