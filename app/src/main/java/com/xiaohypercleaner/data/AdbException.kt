package com.xiaohypercleaner.data

import java.io.IOException

/**
 * Исключение для ошибок ADB-протокола.
 * Наследуется от IOException для совместимости с сетевыми операциями.
 */
class AdbException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause) {

    /**
     * Convenience конструктор для ошибок с кодом.
     * Пример: AdbException("Command failed", "FAIL", cause)
     */
    constructor(message: String, errorCode: String, cause: Throwable? = null) :
            this("$message (code=$errorCode)", cause)

    /**
     * Convenience конструктор для тайм-аутов.
     * Пример: AdbException.timeout(5000)
     */
    companion object {
        fun timeout(timeoutMs: Long): AdbException =
            AdbException("Operation timed out after ${timeoutMs}ms")

        fun connectionFailed(host: String, port: Int, cause: Throwable? = null): AdbException =
            AdbException("Failed to connect to $host:$port", cause)
    }
}