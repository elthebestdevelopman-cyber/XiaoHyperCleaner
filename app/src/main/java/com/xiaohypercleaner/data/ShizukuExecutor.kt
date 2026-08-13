package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
 */
class ShizukuExecutor : AdbExecutor {

    companion object {
        private const val TAG = "ShizukuExec"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

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

        fun checkStatus(context: Context): Status {
            if (!isInstalled(context)) {
                AppLog.i(TAG, "checkStatus: NOT_INSTALLED (package not found)")
                return Status.NOT_INSTALLED
            }
            return try {
                if (!Shizuku.pingBinder()) {
                    AppLog.i(TAG, "checkStatus: NOT_RUNNING (binder not pingable)")
                    Status.NOT_RUNNING
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    AppLog.i(TAG, "checkStatus: AVAILABLE")
                    Status.AVAILABLE
                } else {
                    AppLog.i(TAG, "checkStatus: PERMISSION_REQUIRED")
                    Status.PERMISSION_REQUIRED
                }
            } catch (e: Throwable) {
                AppLog.w(TAG, "checkStatus: binder error → NOT_RUNNING: ${e.message}")
                Status.NOT_RUNNING
            }
        }

        /**
         * Запрашивает разрешение у Shizuku для нашего приложения.
         * Это ОБЯЗАТЕЛЬНЫЙ шаг: без него pingBinder() вернёт false.
         */
        fun requestPermission(requestCode: Int) {
            AppLog.i(TAG, "requestPermission: requestCode=$requestCode")
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                AppLog.e(TAG, "requestPermission failed: ${e.message}")
            }
        }

        private val newProcessMethod: Method? by lazy {
            try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            } catch (e: Throwable) {
                AppLog.e(TAG, "Failed to obtain Shizuku.newProcess: ${e.message}")
                null
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
        try {
            val ok = Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            AppLog.i(TAG, "connect: $ok")
            ok
        } catch (e: Throwable) {
            AppLog.w(TAG, "connect failed: ${e.message}")
            false
        }
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val maskedCmd = LogMasker.mask(command)
        AppLog.i(TAG, "executeCommand: $maskedCmd")

        val method = newProcessMethod
            ?: throw AdbException("Shizuku.newProcess method not available")

        try {
            val stripped = command.trim().removePrefix("shell ")
            val cmd = arrayOf("sh", "-c", stripped)

            val process = method.invoke(null, cmd, null, null) as? ShizukuRemoteProcess
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