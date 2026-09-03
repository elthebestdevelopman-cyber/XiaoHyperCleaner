package com.xiaohypercleaner.service

/**
 * Мост между AdbEnablerService и MainViewModel.
 * Защита openedSpecificScreen=openedIndex<step.intents.lastIndex — не тронута.
 */
object SimpleStepBridge {
    /** Результат шага: success + reason (toggled/already_done/…) */
    var onResult: ((success: Boolean, reason: String) -> Unit)? = null

    /** Шаг пропущен (приложение не установлено) — ретраев НЕТ, сразу следующий */
    var onSkipped: ((String) -> Unit)? = null
}