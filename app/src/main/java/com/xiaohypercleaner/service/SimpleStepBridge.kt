package com.xiaohypercleaner.service

/**
 * Мост между AdbEnablerService и MainViewModel.
 * Защита openedSpecificScreen=openedIndex<step.intents.lastIndex — не тронута.
 */
object SimpleStepBridge {
    /** Результат шага: true = успех, false = ошибка (будут ретраи) */
    var onResult: ((Boolean) -> Unit)? = null

    /** НОВОЕ: шаг пропущен (приложение не установлено) — ретраев НЕТ, сразу следующий */
    var onSkipped: ((String) -> Unit)? = null
}