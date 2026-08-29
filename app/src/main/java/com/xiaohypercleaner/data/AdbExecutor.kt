package com.xiaohypercleaner.data

/**
 * Интерфейс для исполнителей команд (Root, Shizuku, ADB).
 *
 * Реализации:
 * - RootExecutor: через su (требует root)
 * - ShizukuExecutor: через Shizuku API (требует Shizuku)
 * - AdbClient: через wireless ADB (требует Wi-Fi + dev mode)
 *
 * Используется в OptimizationEngine для полиморфного выполнения команд.
 */
interface AdbExecutor {

    /**
     * Устанавливает соединение.
     * @return true, если соединение успешно установлено
     */
    suspend fun connect(): Boolean

    /**
     * Выполняет команду.
     * @param command Команда в формате "shell pm disable-user ..." или без префикса "shell"
     * @return Result с выводом команды или ошибкой
     */
    suspend fun executeCommand(command: String): Result<String>

    /**
     * Закрывает соединение.
     * Безопасно вызывать несколько раз.
     */
    fun disconnect()

    /**
     * Проверяет, активно ли соединение.
     * Опциональный метод для диагностики.
     *
     * @return true, если соединение активно
     */
    fun isConnected(): Boolean = false
}