package com.xiaohypercleaner.service

/**
 * Мост между AdbEnablerService и MainViewModel.
 * Защита openedSpecificScreen=openedIndex<step.intents.lastIndex — не тронута.
 */
object SimpleStepBridge {
    /**
     * Причина остановки прогона: разрешение «Поверх других окон» пропало во время
     * прогона и не восстановилось. Отдельная от `overlay_not_attached` (окно потеряно,
     * но разрешение живо) — эта причина закрывает прогон, а не один шаг.
     */
    const val REASON_OVERLAY_PERMISSION_LOST: String = "overlay_permission_lost"

    /** Сервис доступности не подключён ДО старта шага: шаг не начинался вовсе. */
    const val REASON_SERVICE_NOT_CONNECTED: String = "service_not_connected"

    /**
     * Сервис отвалился ВО ВРЕМЯ шага: провал — следствие слепоты (windows=0), а не
     * состояния устройства. Отчёту это различие нужно: «робот ослеп» и «настройки нет» —
     * разные вещи (прогон rmvikvzqm: 35 слепых шагов подряд).
     */
    const val REASON_SERVICE_LOST: String = "service_lost_during_step"

    /** Результат шага: success + reason (toggled/already_off/already_done/…) */
    var onResult: ((success: Boolean, reason: String) -> Unit)? = null

    /**
     * Шаг пропущен: (stepId, ведро причины — SimpleRunner.SkipKind.name). Ведро нужно
     * отчёту: «настройки нет на устройстве», «её нет в лаунчере» и «робот не нашёл» —
     * разные вещи, один текст на все три врал бы.
     */
    var onSkipped: ((stepId: String, kind: String) -> Unit)? = null

    /**
     * Отмена прогона ПОДТВЕРЖДЕНА пользователем в оверлее (S4): прогон прерван
     * осознанно, состояние помечается частичным (`partial=true`).
     */
    var onPartialCancel: (() -> Unit)? = null
}