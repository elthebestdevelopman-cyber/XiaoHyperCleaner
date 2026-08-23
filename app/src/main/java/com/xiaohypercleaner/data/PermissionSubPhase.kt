package com.xiaohypercleaner.data

/**
 * Под-фазы процесса запроса разрешений в Simple Mode.
 * Порядок перехода (см. SimpleModeController.advance()):
 * INACTIVE → RESTRICTED_SETTINGS → APP_INFO → OVERLAY → ACCESSIBILITY → TEST_CLICK → BATTERY_OPTIMIZATION → DONE
 */
enum class PermissionSubPhase {
    INACTIVE,

    /** НОВОЕ: экран с инструкцией для разблокировки Restricted Settings (Android 13+ sideload) */
    RESTRICTED_SETTINGS,

    /** Открытие App Info для разблокировки ограниченных настроек */
    APP_INFO,
    OVERLAY,
    ACCESSIBILITY,

    /** НОВОЕ: снятие ограничений HyperOS Battery Optimization */
    BATTERY_OPTIMIZATION,
    DONE
}