package com.xiaohypercleaner.util

import android.content.Context
import android.util.Log
import com.xiaohypercleaner.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    // Версия берётся из BuildConfig — всегда синхронна с versionName приложения
    private val LOG_VERSION: String = "v${BuildConfig.VERSION_NAME}"

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
     * Файловый I/O — только на своём потоке: логирование идёт из main thread сервисов
     * (оверлей: касания и heartbeat), а flush на каждую строку в потоке вызывающего
     * давал jank прогона 23–24.10.
     */
    private val writeExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "AppLogWriter").apply { isDaemon = true } }
    }

    /** Учёт объёма без `file.length()` на каждую строку (это тоже syscall). */
    private var bytesWritten = 0L

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

                reopenWriter()

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

    /** Открывает файл заново и подхватывает фактический размер (вызывать под [lock]). */
    private fun reopenWriter() {
        val f = file ?: return
        writer = PrintWriter(BufferedWriter(FileWriter(f, true)), true)
        bytesWritten = f.length()
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
     * Ставит строку в очередь записи. Файловый I/O и flush — на потоке [writeExecutor]:
     * в прогоне 23–24.10 оверлей логировал каждое касание из main thread, и каждая строка
     * уходила в файл с flush в потоке вызывающего.
     */
    private fun writeToFile(level: String, tag: String, msg: String, throwable: Throwable?) {
        if (!logToFile) return
        if (writer == null) return
        try {
            writeExecutor.execute { writeNow(level, tag, msg, throwable) }
        } catch (_: Exception) {
            // Логгирование не должно ронять приложение (в т.ч. при shutdown пула)
        }
    }

    /** Фактическая запись строки в файл (поток [writeExecutor]). */
    private fun writeNow(level: String, tag: String, msg: String, throwable: Throwable?) {
        synchronized(lock) {
            try {
                if (writer == null) return
                val timestamp = dateFormat.format(Date())
                // Централизованная маскировка чувствительных данных в файловом логе.
                // Файл экспортируется/шарится через UiActions.shareLog — основная точка утечки.
                // Logcat намеренно не маскируется, чтобы не мешать отладке.
                val lines = ArrayList<String>(4)
                lines.add("$timestamp $level/$tag: ${LogMasker.mask(msg)}")
                throwable?.let { t ->
                    lines.add("$timestamp $level/$tag: ${t.javaClass.simpleName}: ${LogMasker.mask(t.message ?: "")}")
                    t.stackTrace.take(10).forEach { element ->
                        lines.add("$timestamp $level/$tag:     at $element")
                    }
                }
                val size = lines.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 1 }
                if (bytesWritten + size > MAX_LOG_SIZE) {
                    closeWriter()
                    rotateLogsIfNeeded()
                    reopenWriter()
                }
                val w = writer ?: return
                lines.forEach { w.println(it) }
                // flush на каждую строку оставляем: буфер терялся бы при убийстве процесса,
                // но теперь он вне потока вызывающего.
                w.flush()
                bytesWritten += size
            } catch (_: Exception) {
                // Игнорируем ошибки записи в лог-файл: логгирование не должно ронять приложение
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
                reopenWriter()

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
            bytesWritten = 0L
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