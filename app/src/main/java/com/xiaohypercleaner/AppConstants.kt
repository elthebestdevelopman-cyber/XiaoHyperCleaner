package com.xiaohypercleaner

object AppConstants {
    const val ADB_HOST = "127.0.0.1"
    const val ADB_DEFAULT_PORT = 5555
    const val ADB_TIMEOUT_MS = 5000
    const val ADB_CONNECT_ATTEMPTS = 3
    const val PORT_DISCOVERY_TIMEOUT_MS = 3000L
    const val UI_POLL_INTERVAL_MS = 400L
    const val UI_WAIT_TIMEOUT_MS = 8000L
    const val OVERLAY_WAIT_TIMEOUT_MS = 10000L
    const val DEV_SETTINGS_FALLBACK_MS = 8000L
    const val COMMAND_DELAY_MS = 80L
    const val RETRY_DELAY_MS = 800L

    const val PROGRESS_START = 0.05f
    const val PROGRESS_CONNECTED = 0.15f
    const val PROGRESS_METHOD2 = 0.45f
    const val PROGRESS_METHOD3 = 0.70f
    const val PROGRESS_RESTORE_KEYS = 0.30f
    const val PROGRESS_RESTORE_PKGS = 0.60f
    const val PROGRESS_FAIL = 0.90f
    const val PROGRESS_SUCCESS = 1.0f

    const val KEY_APPLY_ATTEMPTS = 2
    const val FALLBACK_DELAY_MS = 300L
}