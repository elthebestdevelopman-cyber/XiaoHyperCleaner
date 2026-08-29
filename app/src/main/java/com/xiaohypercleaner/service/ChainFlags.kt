package com.xiaohypercleaner.service

/**
 * Флаги для координации между MainActivity и AdbEnablerService.
 *
 * Используется для отслеживания состояния цепочки разрешений:
 * 1. Когда пользователь уходит в настройки, флаг устанавливается в true
 * 2. AdbEnablerService проверяет флаг и возвращает пользователя в приложение
 * 3. При возврате флаг сбрасывается
 */
object ChainFlags {

    /**
     * True — мы открыли спец. возможности и ждём, что пользователь включит службу.
     * Как только служба включится, она сама вернёт пользователя в приложение.
     *
     * Устанавливается в: SimpleModeController.onAppInfoDialogAgreed(), onDialogAgreed()
     * Сбрасывается в: AdbEnablerService (после включения службы)
     */
    @Volatile
    var waitingAccessibilityReturn: Boolean = false

    /**
     * Время последнего редиректа в настройки (для watchdog).
     * Используется AdbEnablerService для определения зависшего пользователя.
     *
     * Устанавливается в: AdbEnablerService (при редиректе в настройки)
     * Читается в: AdbEnablerService (watchdog для возврата пользователя)
     */
    @Volatile
    var lastRedirectTime: Long = 0L

    /**
     * Сбрасывает все флаги в начальное состояние.
     * Вызывается при завершении Simple Mode или при отмене.
     */
    fun reset() {
        waitingAccessibilityReturn = false
        lastRedirectTime = 0L
    }
}