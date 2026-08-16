package com.xiaohypercleaner.engine

/**
 * Отчёт о результатах оптимизации
 */
data class OptimizationReport(
    val timestamp: String,
    val steps: List<OptimizationStep>,
    val totalCount: Int = steps.size,
    val successCount: Int = steps.count { it.status == OptimizationStepStatus.SUCCESS },
    val errorCount: Int = steps.count { it.status == OptimizationStepStatus.ERROR },
    val skippedCount: Int = steps.count { it.status == OptimizationStepStatus.SKIPPED }
)
