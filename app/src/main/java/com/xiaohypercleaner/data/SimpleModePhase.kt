package com.xiaohypercleaner.data

/**
 * Фазы Simple Mode — машина состояний для процесса оптимизации.
 *
 * Порядок перехода:
 * INACTIVE → PERMISSIONS → STEPS → DONE
 */
enum class SimpleModePhase {
    /** Начальное состояние — Simple Mode не активен */
    INACTIVE,

    /** Запрос разрешений (overlay, accessibility, battery optimization) */
    PERMISSIONS,

    /** Выполнение 26 шагов автоматизации через SimpleRunner */
    STEPS,

    /** Все шаги выполнены — показ результатов */
    DONE
}