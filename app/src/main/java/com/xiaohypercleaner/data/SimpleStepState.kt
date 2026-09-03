package com.xiaohypercleaner.data

/**
 * Состояние текущего шага Simple Mode.
 * Используется в SimpleModeController для управления UI и логикой выполнения.
 */
data class SimpleStepState(
    /** Индекс текущего шага в SimpleSteps.ALL (0-based) */
    val stepIndex: Int,

    /** Общее количество шагов */
    val totalSteps: Int,

    /** Объект шага с метаданными (drillPath, searchTexts и т.д.) */
    val step: SimpleSteps.Step,

    /** Текущий статус выполнения */
    val status: Status,

    /** Количество успешно завершённых шагов до текущего */
    val completedCount: Int,

    /** Номер текущей попытки (1-based) */
    val attempt: Int = 1,

    /** Максимальное количество попыток перед финальной неудачей */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    /**
     * Статусы выполнения шага.
     *
     * Порядок перехода:
     * IDLE → READY → WORKING → SUCCESS/FAILED
     */
    enum class Status {
        /** Шаг создан, но ещё не готов к запуску */
        IDLE,

        /** Шаг готов к запуску (разрешения получены) */
        READY,

        /** Шаг выполняется (робот кликает по UI) */
        WORKING,

        /** Шаг успешно завершён */
        SUCCESS,

        /** Шаг не удалось выполнить после всех попыток */
        FAILED
    }

    companion object {
        /** Максимальное количество попыток выполнения шага */
        const val DEFAULT_MAX_ATTEMPTS = 2
    }
}