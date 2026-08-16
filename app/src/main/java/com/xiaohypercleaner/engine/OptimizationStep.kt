package com.xiaohypercleaner.engine

/**
 * Статус выполнения шага оптимизации
 */
enum class OptimizationStepStatus {
    SUCCESS,
    ERROR,
    SKIPPED,
    ROLLED_BACK,
    IN_PROGRESS
}

/**
 * Шаг оптимизации с детальным статусом
 */
data class OptimizationStep(
    val name: String,
    val status: OptimizationStepStatus,
    val message: String = "",
    val rolledBack: Boolean = false
)
