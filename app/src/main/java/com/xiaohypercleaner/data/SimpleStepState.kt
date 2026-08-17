package com.xiaohypercleaner.data

/**
 * Состояние шага простой оптимизации.
 * attempt/maxAttempts — для авто-ретраев («без победы не возвращаемся»).
 */
data class SimpleStepState(
    val stepIndex: Int,
    val totalSteps: Int,
    val step: SimpleSteps.Step,
    val status: Status,
    val completedCount: Int,
    val attempt: Int = 1,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    enum class Status { READY, WORKING, SUCCESS, FAILED }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}