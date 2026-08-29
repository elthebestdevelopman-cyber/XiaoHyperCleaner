package com.xiaohypercleaner.engine

/**
 * Статус выполнения шага оптимизации Pro-режима.
 *
 * Порядок перехода:
 * IN_PROGRESS → SUCCESS / ERROR / SKIPPED
 * ERROR → ROLLED_BACK (если откат прошёл успешно)
 */
enum class OptimizationStepStatus {
    /** Шаг выполнен успешно */
    SUCCESS,

    /** Шаг завершился с ошибкой */
    ERROR,

    /** Шаг пропущен (например, приложение не установлено) */
    SKIPPED,

    /** Шаг был откачен после ошибки верификации */
    ROLLED_BACK,

    /** Шаг выполняется в данный момент */
    IN_PROGRESS
}

/**
 * Шаг оптимизации с детальным статусом.
 *
 * Используется в:
 * - OptimizationReport для хранения результатов
 * - OptimizationReportFormatter для форматирования отчёта
 * - OptimizationEngine для трекинга выполнения
 *
 * @param name Название шага для отображения в отчёте
 * @param status Текущий статус выполнения
 * @param message Дополнительное сообщение (например, текст ошибки)
 * @param rolledBack Был ли шаг откачен после ошибки верификации
 */
data class OptimizationStep(
    val name: String,
    val status: OptimizationStepStatus,
    val message: String = "",
    val rolledBack: Boolean = false
)