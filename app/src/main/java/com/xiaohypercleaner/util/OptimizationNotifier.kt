package com.xiaohypercleaner.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Нотификатор о результатах оптимизации (Pro-режим).
 *
 * Используется для передачи состояния из OptimizationEngine в UI через StateFlow.
 *
 * ПРИМЕР ИСПОЛЬЗОВАНИЯ:
 *
 * В OptimizationEngine (Pro-режим):
 * ```
 * OptimizationNotifier.setRunning()
 * // ... выполняем оптимизацию ...
 * OptimizationNotifier.setSuccess("Отключено 15 настроек")
 * ```
 *
 * В MainViewModel/UI:
 * ```
 * viewModelScope.launch {
 *     OptimizationNotifier.result.collect { result ->
 *         when (result) {
 *             is Result.Running -> showLoading()
 *             is Result.Success -> showSuccess(result.details)
 *             is Result.Failure -> showError(result.failedActions)
 *             else -> hideOverlay()
 *         }
 *     }
 * }
 * ```
 *
 * УЛУЧШЕНИЯ:
 * 1. Логирование изменений состояния через AppLog
 * 2. Convenience методы для проверки состояния (isRunning, isSuccess, etc.)
 * 3. Метод getLastResult() для синхронного доступа
 * 4. Полная документация с примерами
 */
object OptimizationNotifier {

    private const val TAG = "OptimizationNotifier"

    /**
     * Возможные состояния оптимизации.
     */
    sealed class Result {
        /** Начальное состояние — оптимизация не запущена */
        object Idle : Result()

        /** Оптимизация выполняется */
        object Running : Result()

        /**
         * Оптимизация успешно завершена.
         * @param details Описание выполненных действий (например, "Отключено 15 настроек")
         */
        data class Success(val details: String) : Result()

        /**
         * Оптимизация завершена с ошибками.
         * @param failedActions Список действий, которые не удалось выполнить
         * @param details Дополнительная информация об ошибках
         */
        data class Failure(val failedActions: List<String>, val details: String) : Result()

        /** Требуется режим разработчика (для ADB/Shizuku) */
        object DevModeRequired : Result()
    }

    private val _result = MutableStateFlow<Result>(Result.Idle)

    /**
     * StateFlow для реактивного наблюдения за состоянием оптимизации.
     * Используйте в ViewModel через collect {} или в Compose через collectAsState().
     */
    val result: StateFlow<Result> = _result.asStateFlow()

    /**
     * Устанавливает состояние "Выполняется".
     * Вызывать перед началом оптимизации.
     */
    fun setRunning() {
        AppLog.i(TAG, "Состояние: Running")
        _result.value = Result.Running
    }

    /**
     * Устанавливает состояние "Успех".
     * @param details Описание выполненных действий
     */
    fun setSuccess(details: String) {
        AppLog.i(TAG, "Состояние: Success — $details")
        _result.value = Result.Success(details)
    }

    /**
     * Устанавливает состояние "Ошибка".
     * @param failedActions Список действий, которые не удалось выполнить
     * @param details Дополнительная информация об ошибках
     */
    fun setFailure(failedActions: List<String>, details: String) {
        AppLog.w(TAG, "Состояние: Failure — ${failedActions.size} ошибок: $details")
        _result.value = Result.Failure(failedActions, details)
    }

    /**
     * Устанавливает состояние "Требуется режим разработчика".
     * Используется, когда ADB/Shizuku недоступны.
     */
    fun setDevModeRequired() {
        AppLog.w(TAG, "Состояние: DevModeRequired")
        _result.value = Result.DevModeRequired
    }

    /**
     * Сбрасывает состояние в Idle.
     * Вызывать после закрытия UI или перед новым запуском оптимизации.
     */
    fun reset() {
        AppLog.i(TAG, "Состояние: Idle (сброс)")
        _result.value = Result.Idle
    }

    // ═══ CONVENIENCE МЕТОДЫ ═══

    /**
     * Возвращает текущее состояние (синхронный доступ).
     * Для реактивного наблюдения используйте result.collect {}.
     */
    fun getLastResult(): Result = _result.value

    /** Проверяет, выполняется ли оптимизация */
    fun isRunning(): Boolean = _result.value is Result.Running

    /** Проверяет, успешно ли завершена оптимизация */
    fun isSuccess(): Boolean = _result.value is Result.Success

    /** Проверяет, завершилась ли оптимизация с ошибками */
    fun isFailure(): Boolean = _result.value is Result.Failure

    /** Проверяет, требуется ли режим разработчика */
    fun isDevModeRequired(): Boolean = _result.value is Result.DevModeRequired

    /** Проверяет, находится ли в начальном состоянии */
    fun isIdle(): Boolean = _result.value is Result.Idle

    /**
     * Возвращает детали последнего результата (если есть).
     * @return Строка с деталями или null, если состояние не содержит деталей
     */
    fun getDetails(): String? = when (val current = _result.value) {
        is Result.Success -> current.details
        is Result.Failure -> current.details
        else -> null
    }

    /**
     * Возвращает список неудачных действий (если есть).
     * @return Список или null, если состояние не Failure
     */
    fun getFailedActions(): List<String>? = (_result.value as? Result.Failure)?.failedActions
}