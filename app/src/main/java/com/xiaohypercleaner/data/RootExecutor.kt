package com.xiaohypercleaner.data

import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException
import java.io.InputStream

/**
 * Исполнитель команд через root (su).
 *
 * Используется в Pro-режиме как приоритетный исполнитель (если устройство рутировано).
 *
 * УЛУЧШЕНИЯ:
 * 1. Параллельное чтение stdout/stderr через async — защита от deadlock
 * 2. Маскировка команд через LogMasker для consistency с AdbClient
 * 3. Русские логи для соответствия правилу 1
 * 4. Явные типы для всех переменных
 * 5. Константа для команды проверки root
 */
class RootExecutor : AdbExecutor {

    companion object {
        private const val TAG = "RootExecutor"
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val AVAILABILITY_TIMEOUT_MS = 5_000L
        private const val MAX_RESPONSE_SIZE = 100_000 // 100KB лимит ответа
        private const val BUFFER_SIZE = 8192
        private const val ROOT_CHECK_COMMAND = "id"
    }

    override suspend fun connect(): Boolean = isAvailable()

    override fun disconnect() {
        // Root-сессии не требуют явного отключения
        AppLog.d(TAG, "disconnect: ничего не делаем (root не требует явного отключения)")
    }

    /**
     * Проверка доступности root-прав с timeout.
     *
     * Выполняет команду `su -c id` и проверяет:
     * 1. Exit code == 0 (команда выполнена успешно)
     * 2. Вывод содержит "uid=0" (мы действительно root)
     *
     * @return true, если root доступен и работает
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(AVAILABILITY_TIMEOUT_MS.milliseconds) {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", ROOT_CHECK_COMMAND))

                val output: String = process.inputStream.use { stream ->
                    readWithLimit(stream, 1024)
                }
                val exitCode: Int = process.waitFor()

                val available: Boolean = exitCode == 0 && output.contains("uid=0")
                AppLog.d(TAG, "isAvailable: exitCode=$exitCode, available=$available")
                available
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Проверка доступности root не удалась: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
            process?.waitFor() // Ждём завершения после destroy
        }
    }

    /**
     * Синхронная версия для обратной совместимости.
     * ⚠️ Блокирует поток — использовать только в тестах.
     *
     * @return true, если root доступен и работает
     */
    fun isAvailableSync(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", ROOT_CHECK_COMMAND))
            val output: String = process.inputStream.use { stream ->
                readWithLimit(stream, 1024)
            }
            val exitCode: Int = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            AppLog.w(TAG, "isAvailableSync не удалась: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
            process?.waitFor()
        }
    }

    /**
     * Выполняет команду через root с параллельным чтением stdout/stderr.
     *
     * ВАЖНО: stdout и stderr читаются параллельно через async, чтобы избежать deadlock.
     * Если один поток заполнится (например, stderr при ошибке), а другой не читается,
     * process будет висеть до таймаута.
     *
     * @param command Команда для выполнения (без префикса "shell")
     * @return Result с выводом команды или ошибкой
     */
    override suspend fun executeCommand(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            val maskedCommand: String = LogMasker.mask(command)
            AppLog.i(TAG, "Выполнение команды: $maskedCommand")

            var process: Process? = null
            try {
                withTimeout(COMMAND_TIMEOUT_MS.milliseconds) {
                    process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                    // Читаем output и error параллельно через async, чтобы избежать deadlock
                    val output: String
                    val error: String

                    coroutineScope {
                        val outputDeferred = async(Dispatchers.IO) {
                            process.inputStream.use { stream ->
                                readWithLimit(stream, MAX_RESPONSE_SIZE)
                            }
                        }
                        val errorDeferred = async(Dispatchers.IO) {
                            process.errorStream.use { stream ->
                                readWithLimit(stream, MAX_RESPONSE_SIZE)
                            }
                        }

                        output = outputDeferred.await()
                        error = errorDeferred.await()
                    }

                    val exitCode: Int = process.waitFor()

                    if (exitCode == 0) {
                        AppLog.i(TAG, "Команда выполнена успешно")
                        Result.success(output.trim())
                    } else {
                        val msg: String =
                            if (error.isNotBlank()) error.trim() else "exit code $exitCode"
                        AppLog.w(TAG, "Команда не удалась: $maskedCommand -> $msg")
                        Result.failure(IOException("Команда не удалась: $msg"))
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Выполнение команды не удалось: $maskedCommand -> ${e.message}")
                Result.failure(e)
            } finally {
                process?.destroyForcibly()
                process?.waitFor()
            }
        }

    /**
     * Читает InputStream с лимитом размера (чтобы не OOM на больших ответах).
     *
     * @param stream InputStream для чтения
     * @param maxSize Максимальное количество символов для чтения
     * @return Прочитанная строка (может быть обрезана)
     */
    private fun readWithLimit(stream: InputStream, maxSize: Int): String {
        val buffer = StringBuilder()
        val charBuffer = CharArray(BUFFER_SIZE)
        var totalRead: Int = 0

        stream.bufferedReader().use { reader ->
            while (totalRead < maxSize) {
                val charsToRead: Int = minOf(BUFFER_SIZE, maxSize - totalRead)
                val read: Int = reader.read(charBuffer, 0, charsToRead)
                if (read == -1) break

                buffer.appendRange(charBuffer, 0, read)
                totalRead += read
            }
        }

        return buffer.toString()
    }
}