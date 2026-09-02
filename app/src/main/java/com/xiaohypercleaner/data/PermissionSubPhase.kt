package com.xiaohypercleaner.data

/**
 * Под-фазы процесса запроса разрешений в Simple Mode.
 *
 * Порядок перехода (см. SimpleModeController.advance()):
 * INACTIVE → RESTRICTED_SETTINGS → APP_INFO → OVERLAY → ACCESSIBILITY →
 * BATTERY_OPTIMIZATION → STEPS → DONE
 *
 * ИСПРАВЛЕНО:
 *  - Восстановлены корректные закрытия KDoc-комментариев
 *  - Удалено упоминание TEST_CLICK (фаза была удалена из логики)
 *  - Добавлена полная документация для каждой фазы
 */
enum class PermissionSubPhase {

    /** Начальное состояние — разрешения ещё не запрашивались */
    INACTIVE,

    /**
     * Экран с инструкцией для разблокировки Restricted Settings.
     * Актуально только для Android 13+ и sideloaded apps.
     * Пользователь видит карточку с объяснением, зачем нужно разблокировать настройки.
     */
    RESTRICTED_SETTINGS,

    /**
     * Открытие App Info для разблокировки ограниченных настроек.
     * Пользователь нажимает ⋮ → «Разрешить» (или аналогичную кнопку).
     * Показывается стрелка-указатель через OverlayService.
     */
    APP_INFO,

    /**
     * Запрос разрешения на отображение поверх других приложений.
     * Необходимо для показа оверлея с робокотом и подсказками.
     */
    OVERLAY,

    /**
     * Включение Accessibility Service (AdbEnablerService).
     * Критично для Simple Mode — без него робот не может кликать по UI.
     * Показывается стрелка-указатель на сервис в списке.
     */
    ACCESSIBILITY,

    /**
     * Исключение приложения из Battery Optimization.
     * Необходимо, чтобы система не убивала Accessibility Service в фоне.
     * Особенно важно для MIUI/HyperOS с агрессивной экономией батареи.
     */
    BATTERY_OPTIMIZATION,

    /** Все разрешения получены — можно переходить к выполнению шагов */
    DONE
}