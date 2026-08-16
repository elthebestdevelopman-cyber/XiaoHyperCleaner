package com.xiaohypercleaner.data

import com.xiaohypercleaner.data.AdbExecutor
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Исполнитель команд через root (su).
 *
 * Исправления:
 * - isAvailable() теперь suspend с timeout 5с (был блокирующий без timeout)
 * - executeCommand() обёрнут в withContext(IO) + timeout 30с
 * - Безопасная работа с Process (waitFor + destroyForcibly в finally)
 */
class RootExecutor : AdbExecutor {

    companion object {
        private const val TAG = "RootExecutor"
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val AVAILABILITY_TIMEOUT_MS = 5_000L
        private const val MAX_RESPONSE_SIZE = 100_000 // 100KB лимит ответа
    }

    /**
     * Проверка доступности root-прав.
     * КРИТИЧНО: suspend-функция с timeout для безопасности.
     * Раньше была обычной функцией без timeout — могла заблокировать UI.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(AVAILABILITY_TIMEOUT_MS) {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process!!.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process!!.waitFor()

                val available = exitCode == 0 && output.contains("uid=0")
                AppLog.d(TAG, "isAvailable: exitCode=$exitCode, available=$available")
                available
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "isAvailable failed: ${e.message}")
            false
        } finally {
            process?.destroyForcibly()
        }
    }

    /**
     * Синхронная версия для обратной совместимости.
     * ⚠️ НЕ РЕКОМЕНДУЕТСЯ — может заблокировать поток.
     */
    fun isAvailableSync(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            false
        } finally {
            process?.destroyForcibly()
        }
    }

    override suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(COMMAND_TIMEOUT_MS) {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                val output = process!!.inputStream.bufferedReader().use { reader ->
                    reader.readText().take(MAX_RESPONSE_SIZE)
                }
                val error = process!!.errorStream.bufferedReader().use { reader ->
                    reader.readText().take(MAX_RESPONSE_SIZE)
                }
                val exitCode = process!!.waitFor()

                if (exitCode == 0) {
                    Result.success(output.trim())
                } else {
                    val msg = if (error.isNotBlank()) error.trim() else "exit code $exitCode"
                    AppLog.w(TAG, "Command failed: $msg")
                    Result.failure(IOException("Command failed: $msg"))
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "executeCommand failed: ${e.message}")
            Result.failure(e)
        } finally {
            process?.destroyForcibly()
        }
    }
}