package com.xiaohypercleaner.service

/**
 * Флаги для координации между MainActivity и AdbEnablerService.
 */
object ChainFlags {
    /** true — мы открыли спец. возможности и ждём, что пользователь включит службу.
     *  Как только служба включится, она сама вернёт пользователя в приложение. */
    @Volatile
    var waitingAccessibilityReturn: Boolean = false
}