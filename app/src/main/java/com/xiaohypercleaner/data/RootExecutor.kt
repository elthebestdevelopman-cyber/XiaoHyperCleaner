package com.xiaohypercleaner.data

import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Реализация AdbExecutor через root (su).
 * Работает мгновенно, без Wi-Fi, без Shizuku, без цепочек разрешений.
 *
 * Используется на рутированных устройствах как приоритетный путь выполнения команд.
 */
class RootExecutor : AdbExecutor {

    companion object {
        private const val TAG = "RootExec"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024

        /**
         * Проверяет наличие root: выполняет `su -c id` и ждёт uid=0.
         * Возвращает true если root доступен и работает.
         */
        fun isAvailable(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process.inputStream.bufferedReader().readText()
                runCatching { process.destroy() }
                val available = output.contains("uid=0")
                AppLog.i(TAG, "isAvailable: $available ($output)")
                available
            } catch (e: Throwable) {
                AppLog.i(TAG, "isAvailable: no root (${e.message})")
                false
            }
        }
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        isAvailable()
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val maskedCmd = LogMasker.mask(command)
        AppLog.i(TAG, "executeCommand: $maskedCmd")

        try {
            val stripped = command.removePrefix("shell ")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", stripped))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            val start = System.currentTimeMillis()
            var totalBytes = 0

            while (true) {
                if (System.currentTimeMillis() - start > READ_TIMEOUT_MS) {
                    AppLog.w(TAG, "executeCommand: hard timeout")
                    runCatching { process.destroy() }
                    break
                }
                val line = reader.readLine() ?: break
                if (totalBytes + line.length > MAX_RESPONSE_SIZE) {
                    AppLog.w(TAG, "executeCommand: response too large, truncated")
                    break
                }
                sb.append(line).append('\n')
                totalBytes += line.length + 1
            }
            runCatching { reader.close() }
            runCatching { process.waitFor() }

            val result = sb.toString().trimEnd()
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            AppLog.i(TAG, "executeCommand: exit=$exitCode (${result.length} chars)")
            result
        } catch (e: Throwable) {
            AppLog.e(TAG, "executeCommand failed: ${LogMasker.mask(e.message ?: "")}")
            throw AdbException("Root command failed: ${e.message}")
        }
    }

    override fun disconnect() {
        AppLog.i(TAG, "disconnect: no-op for root")
    }
}