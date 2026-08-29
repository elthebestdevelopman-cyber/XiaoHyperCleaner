package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageManager
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.time.Duration.Companion.milliseconds

/**
 * Реализация AdbExecutor через Shizuku API (MIT License).
 *
 * Используется в Pro-режиме как приоритетный исполнитель (если Shizuku установлен и имеет права).
 *
 * УЛУЧШЕНИЯ:
 * 1. Параллельное чтение stdout/stderr через async — защита от deadlock
 * 2. Корутинный withTimeout вместо ручного таймаута
 * 3. Русские логи для соответствия правилу 1
 * 4. Явные типы для всех переменных
 * 5. Метод isConnected() для consistency с AdbClient
 * 6. Fallback для reflection ошибки
 */
class ShizukuExecutor : AdbExecutor {

    companion object {
        private const val TAG = "ShizukuExecutor"
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /**
         * Проверяет, установлен ли Shizuku.
         * Дублирует ShizukuHelper.isInstalled(), но нужна здесь для checkStatus().
         */
        fun isInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Проверяет статус Shizuku: установлен, запущен, есть ли права.
         *
         * @param context Контекст приложения
         * @return Статус Shizuku (NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, AVAILABLE)
         */
        fun checkStatus(context: Context): Status {
            if (!isInstalled(context)) {
                AppLog.i(TAG, "checkStatus: НЕ УСТАНОВЛЕН (пакет не найден)")
                return Status.NOT_INSTALLED
            }
            return try {
                if (!Shizuku.pingBinder()) {
                    AppLog.i(TAG, "checkStatus: НЕ ЗАПУЩЕН (binder недоступен)")
                    Status.NOT_RUNNING
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    AppLog.i(TAG, "checkStatus: ДОСТУПЕН")
                    Status.AVAILABLE
                } else {
                    AppLog.i(TAG, "checkStatus: ТРЕБУЕТСЯ РАЗРЕШЕНИЕ")
                    Status.PERMISSION_REQUIRED
                }
            } catch (e: Throwable) {
                AppLog.w(TAG, "checkStatus: ошибка binder → НЕ ЗАПУЩЕН: ${e.message}")
                Status.NOT_RUNNING
            }
        }

        /**
         * Запрашивает разрешение у Shizuku для нашего приложения.
         * Это ОБЯЗАТЕЛЬНЫЙ шаг: без него pingBinder() вернёт false.
         *
         * @param requestCode Код запроса для обработки в onRequestPermissionResult
         */
        fun requestPermission(requestCode: Int) {
            AppLog.i(TAG, "requestPermission: requestCode=$requestCode")
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                AppLog.e(TAG, "requestPermission не удался: ${e.message}")
            }
        }

        /**
         * Получает метод Shizuku.newProcess через reflection.
         * Необходим для совместимости с разными версиями Shizuku API.
         */
        private val newProcessMethod: Method? by lazy {
            try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            } catch (e: Throwable) {
                AppLog.e(TAG, "Не удалось получить Shizuku.newProcess: ${e.message}")
                null
            }
        }
    }

    /**
     * Статус Shizuku для отображения в UI.
     */
    enum class Status {
        /** Shizuku не установлен */
        NOT_INSTALLED,

        /** Shizuku установлен, но не запущен */
        NOT_RUNNING,

        /** Shizuku запущен, но нет разрешения для нашего приложения */
        PERMISSION_REQUIRED,

        /** Shizuku готов к использованию */
        AVAILABLE
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ok: Boolean = Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            AppLog.i(TAG, "connect: $ok")
            ok
        } catch (e: Throwable) {
            AppLog.w(TAG, "connect не удался: ${e.message}")
            false
        }
    }

    /**
     * Проверяет, активно ли соединение с Shizuku.
     * Используется для диагностики перед выполнением команд.
     */
    fun isConnected(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Выполняет команду через Shizuku с параллельным чтением stdout/stderr.
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
            val maskedCmd: String = LogMasker.mask(command)
            AppLog.i(TAG, "Выполнение команды: $maskedCmd")

            val method: Method = newProcessMethod
                ?: return@withContext Result.failure(AdbException("Метод Shizuku.newProcess недоступен"))

            try {
                withTimeout(COMMAND_TIMEOUT_MS.milliseconds) {
                    val stripped: String = command.trim().removePrefix("shell ")
                    val cmd: Array<String> = arrayOf("sh", "-c", stripped)

                    val process: ShizukuRemoteProcess =
                        method.invoke(null, cmd, null, null) as? ShizukuRemoteProcess
                            ?: return@withTimeout Result.failure(AdbException("Shizuku.newProcess вернул null"))

                    // Читаем stdout и stderr параллельно через async, чтобы избежать deadlock
                    val stdout: String
                    val stderr: String

                    coroutineScope {
                        val stdoutDeferred = async(Dispatchers.IO) {
                            readStreamWithLimit(process.inputStream, MAX_RESPONSE_SIZE)
                        }
                        val stderrDeferred = async(Dispatchers.IO) {
                            readStreamWithLimit(process.errorStream, MAX_RESPONSE_SIZE)
                        }

                        stdout = stdoutDeferred.await()
                        stderr = stderrDeferred.await()
                    }

                    runCatching { process.waitFor() }

                    val result: String = stdout.trimEnd()
                    val exitCode: Int? = runCatching { process.exitValue() }.getOrNull()

                    AppLog.i(TAG, "executeCommand: exit=$exitCode (${result.length} символов)")

                    if (exitCode == 0) {
                        Result.success(result)
                    } else {
                        val errorMsg: String =
                            if (stderr.isNotBlank()) stderr.trim() else "exit code $exitCode"
                        AppLog.w(TAG, "Команда не удалась: $maskedCmd -> $errorMsg")
                        Result.failure(AdbException("Команда не удалась: $errorMsg"))
                    }
                }
            } catch (e: Throwable) {
                AppLog.e(TAG, "executeCommand не удался: ${LogMasker.mask(e.message ?: "")}")
                Result.failure(AdbException("Команда Shizuku не удалась: ${e.message}"))
            }
        }

    override fun disconnect() {
        AppLog.i(TAG, "disconnect: ничего не делаем (Shizuku не требует явного отключения)")
    }

    /**
     * Читает InputStream с лимитом размера (чтобы не OOM на больших ответах).
     *
     * @param stream InputStream для чтения
     * @param maxSize Максимальное количество символов для чтения
     * @return Прочитанная строка (может быть обрезана)
     */
    private fun readStreamWithLimit(stream: java.io.InputStream, maxSize: Int): String {
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var totalBytes: Int = 0

        try {
            while (true) {
                val line: String = reader.readLine() ?: break
                if (totalBytes + line.length > maxSize) {
                    AppLog.w(TAG, "readStreamWithLimit: ответ слишком большой, обрезан")
                    break
                }
                sb.append(line).append('\n')
                totalBytes += line.length + 1
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "readStreamWithLimit: ошибка чтения: ${e.message}")
        } finally {
            runCatching { reader.close() }
        }

        return sb.toString()
    }
}