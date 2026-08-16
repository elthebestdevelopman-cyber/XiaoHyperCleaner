package com.xiaohypercleaner.util

/**
 * Централизованный реестр локализованных текстов UI-элементов
 * для AccessibilityService (AdbEnablerService).
 *
 * Раньше эти массивы были захардкожены внутри AdbEnablerService.
 * Вынесены сюда для:
 * - упрощения добавления новых языков
 * - избежания дублирования между сервисами
 * - централизованного управления версиями прошивок MIUI/HyperOS
 *
 * ПРИМЕЧАНИЕ ПО ПОИСКУ:
 * Основным приоритетом поиска должен быть viewIdResourceName (стабилен
 * между языками и версиями). Поиск по тексту — fallback. Поэтому здесь
 * вместе с текстами хранятся также viewId-паттерны, где они известны.
 */
object AccessibilityTexts {

    /**
     * Тексты для поиска пункта "Параметры разработчика" в системных настройках.
     */
    val DEV_OPTIONS_TEXTS: Array<String> = arrayOf(
        "Developer options", "Параметры разработчика", "Для разработчиков",
        "Режим разработчика", "Настройки разработчика",
        // Дополнительные локализации
        "开发人员选项", "開發人員選項", "개발자 옵션",
        "Opções do desenvolvedor", "Opciones de desarrollador",
        "Options pour les développeurs", "Entwickleroptionen"
    )

    /**
     * Тексты для поиска пункта "Беспроводная отладка" / "Wireless debugging".
     */
    val WIRELESS_DEBUG_TEXTS: Array<String> = arrayOf(
        "Wireless debugging", "Беспроводная отладка", "Отладка по Wi-Fi",
        // Дополнительные локализации
        "无线调试", "無線偵錯", "무선 디버깅",
        "Depuração sem fio", "Depuración inalámbrica",
        "Débogage sans fil", "Drahtloses Debugging"
    )

    /**
     * Тексты для поиска кнопки "Разрешить" в диалоге Wireless debugging.
     */
    val ALLOW_TEXTS: Array<String> = arrayOf(
        "Allow", "Разрешить", "OK", "ОК", "Да", "Yes",
        // Дополнительные локализации
        "允许", "允許", "허용",
        "Permitir", "Permitir", "Autoriser", "Erlauben"
    )

    /**
     * viewIdResourceName паттерны для поиска (стабильнее текста).
     * Используются в качестве первичного метода поиска.
     * Пустой список = поиск только по тексту.
     */
    val DEV_OPTIONS_VIEW_IDS: Array<String> = arrayOf(
        "developer_options",
        "development_settings",
        "developer_settings"
    )

    val WIRELESS_DEBUG_VIEW_IDS: Array<String> = arrayOf(
        "wireless_debugging",
        "wireless_debug",
        "adb_wireless"
    )

    val ALLOW_VIEW_IDS: Array<String> = arrayOf(
        "allow_button",
        "button_allow",
        "positive_button"
    )
}