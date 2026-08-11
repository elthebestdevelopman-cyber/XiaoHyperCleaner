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

    fun init(context: Context) {
        if (!logToFile) return

        try {
            val logDir = context.filesDir
            file = File(logDir, "xhc.log")

            // Очищаем старый лог при запуске
            if (file?.exists() == true && file?.length() ?: 0L > 5_000_000L) {
                file?.delete()
            }

            writer = PrintWriter(FileWriter(file, true), true)
            i("AppLog", "========================================")
            i("AppLog", "beta logging started, v1.0-beta2")
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

    fun close() {
        try {
            writer?.close()
            writer = null
        } catch (e: Exception) {
            Log.w("AppLog", "Failed to close log file", e)
        }
    }
}