package com.xiaohypercleaner.engine

/**
 * Отчёт о результатах оптимизации Pro-режима.
 * 
 * Генерируется движком оптимизации после выполнения всех шагов.
 * Форматируется через OptimizationReportFormatter для отображения пользователю.
 *
 * ИСПРАВЛЕНИЯ:
 * 1. Удалено дублирование в конце файла (артефакт копирования)
 * 2. Изменён тип `timestamp` с `String` на `Long` для совместимости
 *    с `OptimizationReportFormatter.formatTimestamp(Long)`
 * 3. Добавлена документация
 * 4. Добавлена валидация
 *
 * ЗАВИСИМОСТИ (проверить при сверке с engine/):
 * - OptimizationStep: данные о каждом шаге (name, status, message, rolledBack)
 * - OptimizationStepStatus: enum статусов (SUCCESS, ERROR, SKIPPED, ROLLED_BACK, IN_PROGRESS)
 *
 * @param timestamp Временная метка создания отчёта (миллисекунды с эпохи)
 * @param steps Список шагов оптимизации
 * @param totalCount Общее количество шагов
 * @param successCount Количество успешных шагов
 * @param errorCount Количество шагов с ошибками
 * @param skippedCount Количество пропущенных шагов
 */
data class OptimizationReport(
    /** Временная метка создания отчёта (миллисекунды с эпохи) */
    val timestamp: Long,

    /** Список шагов оптимизации */
    val steps: List<OptimizationStep>,

    /** Общее количество шагов (вычисляется автоматически) */
    val totalCount: Int = steps.size,

    /** Количество успешных шагов (вычисляется автоматически) */
    val successCount: Int = steps.count { it.status == OptimizationStepStatus.SUCCESS },

    /** Количество шагов с ошибками (вычисляется автоматически) */
    val errorCount: Int = steps.count { it.status == OptimizationStepStatus.ERROR },

    /** Количество пропущенных шагов (вычисляется автоматически) */
    val skippedCount: Int = steps.count { it.status == OptimizationStepStatus.SKIPPED }
) {
    /**
     * Проверяет, пустой ли отчёт (нет шагов).
     * Используется в OptimizationReportFormatter для защиты от пустых данных.
     */
    val isEmpty: Boolean
        get() = steps.isEmpty() && totalCount == 0

    /**
     * Проверяет, полностью ли успешна оптимизация (все шаги успешны).
     */
    val isFullySuccessful: Boolean
        get() = errorCount == 0 && skippedCount == 0 && successCount == totalCount

    /**
     * Возвращает процент успешных шагов (0-100).
     */
    val successRate: Int
        get() = if (totalCount > 0) (successCount * 100) / totalCount else 0

    /**
     * Создаёт отчёт с текущей временной меткой.
     *
     * @param steps Список шагов оптимизации
     * @return Новый отчёт с текущим временем
     */
    companion object {
        fun create(steps: List<OptimizationStep>): OptimizationReport {
            return OptimizationReport(
                timestamp = System.currentTimeMillis(),
                steps = steps
            )
        }

        /**
         * Создаёт пустой отчёт (когда оптимизация не запускалась).
         */
        fun empty(): OptimizationReport {
            return OptimizationReport(
                timestamp = System.currentTimeMillis(),
                steps = emptyList()
            )
        }
    }
}