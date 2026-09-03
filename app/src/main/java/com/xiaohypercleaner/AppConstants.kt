package com.xiaohypercleaner

object AppConstants {

    // ═══════════════════════════════════════════════════════════════
    // ADB хост и порты
    // ═══════════════════════════════════════════════════════════════
    const val ADB_HOST = "127.0.0.1"
    const val ADB_DEFAULT_PORT = 5555

    // ═══════════════════════════════════════════════════════════════
    // Тайм-ауты
    // ADB_TIMEOUT_MS — Int, т.к. Socket.connect() и soTimeout требуют Int,
    // остальные тайм-ауты — Long для delay() в корутинах
    // ═══════════════════════════════════════════════════════════════
    const val ADB_TIMEOUT_MS = 5000
    const val PORT_DISCOVERY_TIMEOUT_MS = 5000L
    const val UI_POLL_INTERVAL_MS = 500L

    // ═══════════════════════════════════════════════════════════════
    // Задержки между операциями
    // ═══════════════════════════════════════════════════════════════
    const val DELAY_AFTER_CONNECT_MS = 500L
    const val DELAY_BEFORE_REBOOT_MS = 1500L
    const val COMMAND_DELAY_MS = 150L
    const val RETRY_DELAY_MS = 800L
    const val AUTO_ADVANCE_DELAY_MS = 400L

    // ═══════════════════════════════════════════════════════════════
    // Попытки и повторы
    // ═══════════════════════════════════════════════════════════════
    const val MAX_ACCESSIBILITY_ATTEMPTS = 3

    // ═══════════════════════════════════════════════════════════════
    // Прогресс — Float для ProgressBar и обратных вызовов
    // ═══════════════════════════════════════════════════════════════
    const val PROGRESS_START = 5f
    const val PROGRESS_CONNECTED = 15f
    const val PROGRESS_METHOD1 = 25f
    const val PROGRESS_METHOD2 = 45f
    const val PROGRESS_METHOD3 = 60f
    const val PROGRESS_METHOD4 = 75f
    const val PROGRESS_METHOD5_DNS = 90f
    const val PROGRESS_VERIFYING = 95f
    const val PROGRESS_RESTORE_KEYS = 30f
    const val PROGRESS_RESTORE_PACKAGES = 80f
    const val PROGRESS_DONE = 100f
    const val PROGRESS_FAIL = -1f

    // ═══════════════════════════════════════════════════════════════
    // DataStore
    // ═══════════════════════════════════════════════════════════════
    const val DATASTORE_NAME = "xhc_settings"
}