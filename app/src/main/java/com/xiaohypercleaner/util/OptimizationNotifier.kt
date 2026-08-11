package com.xiaohypercleaner.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OptimizationNotifier {

    sealed class Result {
        object Idle : Result()
        object Running : Result()
        data class Success(val details: String) : Result()
        data class Failure(val failedActions: List<String>, val details: String) : Result()
        object DevModeRequired : Result()
    }

    private val _result = MutableStateFlow<Result>(Result.Idle)
    val result: StateFlow<Result> = _result.asStateFlow()

    fun setRunning() {
        _result.value = Result.Running
    }

    fun setSuccess(details: String) {
        _result.value = Result.Success(details)
    }

    fun setFailure(failedActions: List<String>, details: String) {
        _result.value = Result.Failure(failedActions, details)
    }

    fun setDevModeRequired() {
        _result.value = Result.DevModeRequired
    }

    fun reset() {
        _result.value = Result.Idle
    }
}