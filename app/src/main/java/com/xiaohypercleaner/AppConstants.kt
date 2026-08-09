package com.xiaohypercleaner

object AppConstants {
    // ADB хост и порты
    const val ADB_HOST = "127.0.0.1"
    const val ADB_DEFAULT_PORT = 5555
    const val ADB_PORT_FALLBACK = 5555

    // Таймауты
    // ADB_TIMEOUT_MS — Int, потому что Socket.connect() и soTimeout принимают Int
    const val ADB_TIMEOUT_MS = 5000
    const val ADB_CONNECT_TIMEOUT_MS = 3000L
    const val ADB_COMMAND_TIMEOUT_MS = 5000L
    const val PORT_DISCOVERY_TIMEOUT_MS = 5000L
    const val OVERLAY_WAIT_TIMEOUT_MS = 5000L
    const val UI_WAIT_TIMEOUT_MS = 10000L
    const val DEV_SETTINGS_FALLBACK_MS = 3000L
    const val UI_POLL_INTERVAL_MS = 500L

    // Задержки между операциями (Long для delay() в корутинах)
    const val DELAY_AFTER_CONNECT_MS = 500L
    const val DELAY_BETWEEN_COMMANDS_MS = 150L
    const val DELAY_BEFORE_REBOOT_MS = 1500L
    const val COMMAND_DELAY_MS = 150L
    const val RETRY_DELAY_MS = 1000L

    // Попытки подключения
    const val ADB_CONNECT_ATTEMPTS = 1

    // mDNS
    const val MDNS_SERVICE_TYPE = "_adb-tls._tcp."

    // Прогресс — Float для ProgressBar и колбэков
    const val PROGRESS_START = 5f
    const val PROGRESS_CONNECTED = 15f
    const val PROGRESS_METHOD2 = 45f
    const val PROGRESS_METHOD3 = 75f
    const val PROGRESS_RESTORE_KEYS = 30f
    const val PROGRESS_RESTORE_PACKAGES = 80f
    const val PROGRESS_DONE = 100f
    const val PROGRESS_FAIL = -1f

    // Ключ хранилища
    const val DATASTORE_NAME = "xhc_prefs"
}