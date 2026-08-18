package com.xiaohypercleaner.service

import com.xiaohypercleaner.util.AppLog

/**
 * Мост для связи SimpleStepActivity с MainViewModel.
 * 
 * Используется для передачи результата шага простой оптимизации от AccessibilityService
 * обратно в ViewModel через callback. Это временное решение для обхода ограничений
 * Android Architecture Components при работе с AccessibilityService.
 * 
 * ## Пример использования:
 * 
 * ### В SimpleStepActivity или Service:
 * ```kotlin
 * // После выполнения шага
 * SimpleStepBridge.notifyResult(success = true)
 * ```
 * 
 * ### В MainViewModel:
 * ```kotlin
 * init {
 *     SimpleStepBridge.onResult = { success ->
 *         onSimpleStepResult(currentStepIndex, success, null)
 *     }
 * }
 * ```
 * 
 * @see com.xiaohypercleaner.service.AdbEnablerService
 * @see com.xiaohypercleaner.ui.MainViewModel
 */
object SimpleStepBridge {
    /**
     * Callback для получения результата выполнения шага оптимизации.
     * Устанавливается из MainViewModel при старте простой оптимизации.
     */
    var onResult: ((Boolean) -> Unit)? = null
    
    private const val TAG = "SimpleStepBridge"
    
    /**
     * Уведомляет подписчиков о результате выполнения шага.
     * 
     * @param success true если шаг выполнен успешно, false иначе
     */
    fun notifyResult(success: Boolean) {
        AppLog.i(TAG, "notifyResult: success=$success")
        onResult?.invoke(success)
    }
}
