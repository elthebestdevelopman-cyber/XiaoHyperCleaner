package com.xiaohypercleaner.data

data class SimpleStepState(
    val stepIndex: Int,
    val totalSteps: Int,
    val step: SimpleSteps.Step,
    val status: Status,
    val completedCount: Int,
    val attempt: Int = 1,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    enum class Status { IDLE, READY, WORKING, SUCCESS, FAILED }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}