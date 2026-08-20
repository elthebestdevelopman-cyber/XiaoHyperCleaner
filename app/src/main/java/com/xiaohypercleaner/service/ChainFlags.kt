package com.xiaohypercleaner.service

/**
 * Флаги для координации между MainActivity и AdbEnablerService.
 */
object ChainFlags {
    /** True — мы открыли спец. возможности и ждём, что пользователь включит службу.
     *  Как только служба включится, она сама вернёт пользователя в приложение. */
    @Volatile
    var waitingAccessibilityReturn: Boolean = false
    
    /** Время последнего редиректа в настройки (для watchdog) */
    @Volatile
    var lastRedirectTime: Long = 0L
}