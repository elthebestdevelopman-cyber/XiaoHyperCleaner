package com.xiaohypercleaner.service

/**
 * Мост между AdbEnablerService и MainViewModel.
 * Защита openedSpecificScreen=openedIndex<step.intents.lastIndex — не тронута.
 */
object SimpleStepBridge {
    /** Результат шага: success + reason (toggled/already_off/already_done/…) */
    var onResult: ((success: Boolean, reason: String) -> Unit)? = null

    /**
     * Шаг пропущен: (stepId, ведро причины — SimpleRunner.SkipKind.name). Ведро нужно
     * отчёту: «настройки нет на устройстве», «её нет в лаунчере» и «робот не нашёл» —
     * разные вещи, один текст на все три врал бы.
     */
    var onSkipped: ((stepId: String, kind: String) -> Unit)? = null
}