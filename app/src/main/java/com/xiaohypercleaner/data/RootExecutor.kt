package com.xiaohypercleaner.data

import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException
import java.io.InputStream

/**
 * Исполнитель команд через root (su).
 */
class RootExecutor : AdbExecutor {

    companion object {
        private const val TAG = "RootExecutor"
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val AVAILABILITY_TIMEOUT_MS = 5_000L
        private const val MAX_RESPONSE_SIZE = 100_000 // 100KB лимит ответа
        private const val BUFFER_SIZE = 8192
    }

    override suspend fun connect(): Boolean = isAvailable()

    override fun disconnect() {
        // Root-сессии не требуют явного отключения
    }

    /**
     * Проверка доступности root-прав с timeout.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(AVAILABILITY_TIMEOUT_MS.milliseconds) {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))

                val output = process.inputStream.use { stream ->
                    readWithLimit(stream, 1024)
                }
                val exitCode = process.waitFor()

                val available = exitCode == 0 && output.contains("uid=0")
                AppLog.d(TAG, "isAvailable: exitCode=$exitCode, available=$available")
                available
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "isAvailable failed: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
            process?.waitFor() // Ждём завершения после destroy
        }
    }

    /**
     * Синхронная версия для обратной совместимости.
     * ⚠️ Блокирует поток — использовать только в тестах.
     */
    fun isAvailableSync(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.use { stream ->
                readWithLimit(stream, 1024)
            }
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            AppLog.w(TAG, "isAvailableSync failed: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
            process?.waitFor()
        }
    }

    override suspend fun executeCommand(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                withTimeout(COMMAND_TIMEOUT_MS.milliseconds) {
                    process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                    // Читаем output и error параллельно, чтобы избежать deadlock
                    val output = process.inputStream.use { stream ->
                        readWithLimit(stream, MAX_RESPONSE_SIZE)
                    }
                    val error = process.errorStream.use { stream ->
                        readWithLimit(stream, MAX_RESPONSE_SIZE)
                    }

                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        Result.success(output.trim())
                    } else {
                        val msg = if (error.isNotBlank()) error.trim() else "exit code $exitCode"
                        AppLog.w(TAG, "Command failed: $command -> $msg")
                        Result.failure(IOException("Command failed: $msg"))
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "executeCommand failed: $command -> ${e.message}")
                Result.failure(e)
            } finally {
                process?.destroyForcibly()
                process?.waitFor()
            }
        }

    /**
     * Читает InputStream с лимитом размера (чтобы не OOM на больших ответах).
     */
    private fun readWithLimit(stream: InputStream, maxSize: Int): String {
        val buffer = StringBuilder()
        val charBuffer = CharArray(BUFFER_SIZE)
        var totalRead = 0

        stream.bufferedReader().use { reader ->
            while (totalRead < maxSize) {
                val charsToRead = minOf(BUFFER_SIZE, maxSize - totalRead)
                val read = reader.read(charBuffer, 0, charsToRead)
                if (read == -1) break

                buffer.appendRange(charBuffer, 0, read)
                totalRead += read
            }
        }

        return buffer.toString()
    }
}