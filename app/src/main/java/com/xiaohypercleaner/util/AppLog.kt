package com.xiaohypercleaner.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Собственный логгер проекта (НЕ Timber).
 *
 * УЛУЧШЕНИЯ:
 * 1. Проверка размера при каждой записи — ротация не только при init()
 * 2. BufferedWriter — меньше обращений к диску при частых логах
 * 3. clearAllLogs() — очистка всех лог-файлов для настроек/отладки
 * 4. Потокобезопасность — все операции под synchronized(lock)
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val LOG_FILE_NAME = "xhc.log"
    private const val MAX_LOG_SIZE = 2_000_000L // 2MB
    private const val MAX_LOG_FILES = 3
    private const val LOG_VERSION = "v1.0-beta4"

    @Volatile
    private var file: File? = null

    @Volatile
    private var writer: PrintWriter? = null

    @Volatile
    private var contextRef: Context? = null

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val logToFile = true

    /**
     * Инициализация логгера. Вызывать в Application.onCreate() или в первой активности.
     * После инициализации все вызовы i/w/e/d пишут в файл.
     */
    fun init(context: Context) {
        if (!logToFile) return

        synchronized(lock) {
            try {
                contextRef = context.applicationContext
                closeWriter()

                val logDir = context.filesDir
                file = File(logDir, LOG_FILE_NAME)

                rotateLogsIfNeeded()

                // BufferedWriter для лучшей производительности при частых логах
                writer = PrintWriter(BufferedWriter(FileWriter(file, true)), true)

                i(TAG, "========================================")
                i(TAG, "Логгирование запущено, $LOG_VERSION")
                i(TAG, "Устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                i(
                    TAG,
                    "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
                )
                i(TAG, "========================================")
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось инициализировать файловое логгирование", e)
            }
        }
    }

    /**
     * Ротация логов при превышении размера.
     * Вызывается при каждой записи, а не только при init().
     */
    private fun rotateLogsIfNeeded() {
        val logDir = file?.parentFile ?: return

        if (file?.exists() == true && (file?.length() ?: 0L) > MAX_LOG_SIZE) {
            // Удаляем самый старый файл
            val oldestFile = File(logDir, "$LOG_FILE_NAME.${MAX_LOG_FILES - 1}")
            if (oldestFile.exists()) {
                oldestFile.delete()
            }

            // Сдвигаем файлы: xhc.log.2 → xhc.log.3, xhc.log.1 → xhc.log.2
            for (i in (MAX_LOG_FILES - 2) downTo 1) {
                val src = File(logDir, "$LOG_FILE_NAME.$i")
                val dst = File(logDir, "$LOG_FILE_NAME.${i + 1}")
                if (src.exists()) {
                    src.renameTo(dst)
                }
            }

            // Текущий лог → xhc.log.1
            file?.renameTo(File(logDir, "$LOG_FILE_NAME.1"))
        }
    }

    /**
     * Проверка размера и ротация перед записью.
     * Вызывается при каждом логировании, чтобы не превышать лимит.
     */
    private fun checkSizeAndRotateIfNeeded() {
        if (!logToFile || writer == null) return

        try {
            if ((file?.length() ?: 0L) > MAX_LOG_SIZE) {
                closeWriter()
                rotateLogsIfNeeded()
                file?.let { f ->
                    writer = PrintWriter(BufferedWriter(FileWriter(f, true)), true)
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки ротации — логгирование не должно ронять приложение
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

    /**
     * Запись в файл. Потокобезопасная, с проверкой размера.
     */
    private fun writeToFile(level: String, tag: String, msg: String, throwable: Throwable?) {
        if (!logToFile) return

        synchronized(lock) {
            try {
                // Проверяем размер и ротируем при необходимости
                checkSizeAndRotateIfNeeded()

                if (writer == null) return

                val timestamp = dateFormat.format(Date())
                writer?.println("$timestamp $level/$tag: $msg")

                throwable?.let { t ->
                    writer?.println("$timestamp $level/$tag: ${t.javaClass.simpleName}: ${t.message}")
                    t.stackTrace.take(10).forEach { element ->
                        writer?.println("$timestamp $level/$tag:     at $element")
                    }
                }

                writer?.flush()
            } catch (e: Exception) {
                // Игнорируем ошибки записи в лог-файл
                // Логгирование не должно ронять приложение
            }
        }
    }

    /**
     * Возвращает текущий лог-файл, если он существует.
     * Используется для отправки логов в поддержку.
     */
    fun getLogFile(): File? {
        synchronized(lock) {
            val f = file ?: return null
            return if (f.exists()) f else null
        }
    }

    /**
     * Возвращает все лог-файлы (включая ротированные).
     * Используется для экспорта логов.
     */
    fun getAllLogFiles(): List<File> {
        synchronized(lock) {
            val logDir = contextRef?.filesDir ?: return emptyList()

            return logDir.listFiles()
                ?.filter { it.name.startsWith(LOG_FILE_NAME) }
                ?.sortedByDescending { it.name }
                ?: emptyList()
        }
    }

    /**
     * Очистка всех лог-файлов.
     * Вызывается из настроек при нажатии «Очистить логи».
     */
    fun clearAllLogs() {
        synchronized(lock) {
            try {
                closeWriter()

                val logDir = contextRef?.filesDir ?: return

                logDir.listFiles()
                    ?.filter { it.name.startsWith(LOG_FILE_NAME) }
                    ?.forEach { it.delete() }

                // Пересоздаём пустой файл
                file = File(logDir, LOG_FILE_NAME)
                writer = PrintWriter(BufferedWriter(FileWriter(file, true)), true)

                i(TAG, "Все логи очищены")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось очистить логи", e)
            }
        }
    }

    /**
     * Возвращает размер всех лог-файлов в байтах.
     * Используется для отображения в настройках.
     */
    fun getTotalLogSize(): Long {
        synchronized(lock) {
            return getAllLogFiles().sumOf { it.length() }
        }
    }

    /**
     * Закрытие логгера. Вызывать в Application.onTerminate() или при выходе из приложения.
     */
    fun close() {
        synchronized(lock) {
            closeWriter()
            file = null
            contextRef = null
        }
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось закрыть лог-файл", e)
        }
        writer = null
    }
}