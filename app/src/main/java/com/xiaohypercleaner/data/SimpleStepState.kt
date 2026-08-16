package com.xiaohypercleaner.data

/**
 * Состояние шага простой оптимизации.
 * Вынесено в отдельный файл для общего доступа между UI и ViewModel.
 */
data class SimpleStepState(
    val stepIndex: Int,
    val totalSteps: Int,
    val step: SimpleSteps.Step,
    val status: Status,
    val completedCount: Int
) {
    enum class Status { READY, WORKING, SUCCESS, FAILED }
}
