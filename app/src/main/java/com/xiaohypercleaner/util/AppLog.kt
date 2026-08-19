package com.xiaohypercleaner.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private var file: File? = null
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val logToFile = true
    private const val MAX_LOG_SIZE = 2_000_000L // 2MB
    private const val MAX_LOG_FILES = 3

    fun init(context: Context) {
        if (!logToFile) return

        try {
            closeWriter()

            val logDir = context.filesDir
            file = File(logDir, "xhc.log")

            // Ротация логов: если файл > 2MB, сдвигаем старые файлы
            rotateLogsIfNeeded()

            writer = PrintWriter(FileWriter(file, true), true)
            i("AppLog", "========================================")
            i("AppLog", "beta logging started, v1.0-beta3")
            i("AppLog", "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            i(
                "AppLog",
                "android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
            )
            i("AppLog", "========================================")
        } catch (e: Exception) {
            Log.e("AppLog", "Failed to initialize file logging", e)
        }
    }

    private fun rotateLogsIfNeeded() {
        val logDir = file?.parentFile ?: return
        
        // Если текущий файл существует и превышает лимит
        if (file?.exists() == true && (file?.length() ?: 0L) > MAX_LOG_SIZE) {
            // Удаляем самый старый файл
            val oldestFile = File(logDir, "xhc.log.${MAX_LOG_FILES - 1}")
            if (oldestFile.exists()) {
                oldestFile.delete()
            }
            
            // Сдвигаем остальные файлы: xhc.log.2 -> xhc.log.3, xhc.log.1 -> xhc.log.2
            for (i in (MAX_LOG_FILES - 2) downTo 1) {
                val src = File(logDir, "xhc.log.$i")
                val dst = File(logDir, "xhc.log.${i + 1}")
                if (src.exists()) {
                    src.renameTo(dst)
                }
            }
            
            // Текущий файл становится xhc.log.1
            file?.renameTo(File(logDir, "xhc.log.1"))
        }
    }

    fun d(tag: String, msg: String, throwable: Throwable? = null) {
        Log.d(tag, msg, throwable)
        writeToFile("D", tag, msg, throwable)
    }

    fun i(tag: String, msg: String, throwable: Throwable? = null) {
        Log.i(tag, msg, throwable)
        writeToFile("I", tag, msg, throwable)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg, throwable)
        writeToFile("W", tag, msg, throwable)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        writeToFile("E", tag, msg, throwable)
    }

    private fun writeToFile(level: String, tag: String, msg: String, throwable: Throwable?) {
        if (!logToFile || writer == null) return

        try {
            val timestamp = dateFormat.format(Date())
            writer?.println("$timestamp $level/$tag: $msg")
            throwable?.let {
                writer?.println("$timestamp $level/$tag: ${it.javaClass.simpleName}: ${it.message}")
                it.stackTrace.forEach { element ->
                    writer?.println("$timestamp $level/$tag:     at $element")
                }
            }
            writer?.flush()
        } catch (e: Exception) {
            // Игнорируем ошибки записи в лог-файл
        }
    }

    fun getLogFile(): File? {
        val f = file ?: return null
        return if (f.exists()) f else null
    }

    /**
     * Закрывает writer и освобождает файловый дескриптор.
     * ВАЖНО вызывать в tearDown() Robolectric-тестов, иначе Windows не даст удалить
     * временную директорию Robolectric (файл остаётся открытым).
     */
    fun close() {
        closeWriter()
        file = null
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.w("AppLog", "Failed to close log writer", e)
        }
        writer = null
    }
}