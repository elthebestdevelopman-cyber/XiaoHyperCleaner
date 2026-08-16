package com.xiaohypercleaner.service

import com.xiaohypercleaner.util.AppLog

/**
 * Мост для связи SimpleStepActivity с MainViewModel.
 * Используется для передачи результата шага простой оптимизации.
 */
object SimpleStepBridge {
    var onResult: ((Boolean) -> Unit)? = null
    
    private const val TAG = "SimpleStepBridge"
    
    fun notifyResult(success: Boolean) {
        AppLog.i(TAG, "notifyResult: success=$success")
        onResult?.invoke(success)
    }
}
