package com.xiaohypercleaner.data

import android.content.pm.PackageManager
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Реализация AdbExecutor через Shizuku API (MIT License).
 * Выполняет shell-команды с правами shell без wireless debugging и Wi-Fi.
 */
class ShizukuExecutor : AdbExecutor {

    companion object {
        private const val TAG = "ShizukuExec"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024

        /**
         * Получаем приватный метод newProcess через reflection.
         * В Shizuku 13.1.5 этот метод private, но сигнатура стабильна:
         * static ShizukuRemoteProcess newProcess(String[], String[], String)
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
                AppLog.e(TAG, "Failed to obtain Shizuku.newProcess method: ${e.message}")
                null
            }
        }

        fun checkStatus(): Status {
            return try {
                if (!Shizuku.pingBinder()) {
                    AppLog.i(TAG, "checkStatus: binder not pingable")
                    return Status.NOT_RUNNING
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    AppLog.i(TAG, "checkStatus: available and granted")
                    Status.AVAILABLE
                } else {
                    AppLog.i(TAG, "checkStatus: installed but not granted")
                    Status.PERMISSION_REQUIRED
                }
            } catch (e: Throwable) {
                AppLog.w(TAG, "checkStatus: Shizuku not installed: ${e.message}")
                Status.NOT_INSTALLED
            }
        }
    }

    enum class Status {
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_REQUIRED,
        AVAILABLE
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "connect: checking Shizuku")
        checkStatus() == Status.AVAILABLE
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val maskedCmd = LogMasker.mask(command)
        AppLog.i(TAG, "executeCommand: $maskedCmd")

        val method =
            newProcessMethod ?: throw AdbException("Shizuku.newProcess method not available")

        try {
            val stripped = command.removePrefix("shell ")
            val cmd: Array<String> = arrayOf("sh", "-c", stripped)
            val envp: Array<String>? = null
            val dir: String? = null

            // Вызов через reflection (метод private)
            val process = method.invoke(null, cmd, envp, dir) as? ShizukuRemoteProcess
                ?: throw AdbException("Shizuku.newProcess returned null")

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
            throw AdbException("Shizuku command failed: ${e.message}")
        }
    }

    override fun disconnect() {
        AppLog.i(TAG, "disconnect: no-op for Shizuku")
    }
}