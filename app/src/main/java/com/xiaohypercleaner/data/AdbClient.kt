package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * ADB-клиент, говорящий по классическому plaintext-протоколу ADB
 * (4-символьный hex-заголовок длины, shell-команды до EOF).
 *
 * ВАЖНО: executeCommand() принимает команды в едином формате —
 * с префиксом "shell " или без. Префикс нормализуется, т.к. runShell()
 * сам добавляет "shell:" для wire-протокола. Это делает AdbClient
 * совместимым с ShizukuExecutor и RootExecutor, которые тоже stripping-уют префикс.
 *
 * УЛУЧШЕНИЯ:
 * 1. Явные типы для всех переменных
 * 2. isConnected() метод для проверки состояния
 * 3. ReadResult data class для readUntilEof() с truncated flag
 * 4. Константы для магических чисел (BUFFER_SIZE)
 * 5. Русские сообщения в логах
 * 6. Улучшенная обработка ошибок
 */
class AdbClient(
    private val host: String = AppConstants.ADB_HOST,
    private val ports: List<Int> = listOf(AppConstants.ADB_DEFAULT_PORT)
) : AdbExecutor {

    companion object {
        private const val TAG = "AdbClient"

        // ── Протокол ADB ──
        private const val MAX_PAYLOAD = 0xFFFF
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024 // 10 MB лимит ответа
        private const val BUFFER_SIZE = 4096

        // ── Таймауты ──
        private const val COMMAND_TIMEOUT_MS = 30_000L // общий таймаут команды
        private const val READ_HARD_TIMEOUT_MS = 25_000L // hard timeout цикла чтения
    }

    /**
     * Результат чтения с информацией о truncation.
     * Используется вместо plain String для диагностики.
     */
    data class ReadResult(
        val data: String,
        val truncated: Boolean,
        val bytesRead: Int,
        val chunks: Int,
        val elapsedMs: Long
    )

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var currentPort: Int = -1
    private var commandCount: Int = 0

    /**
     * Проверяет, активно ли соединение.
     * Используется для диагностики перед выполнением команд.
     */
    fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "connect: пробуем ${ports.size} портов: $ports")
        for (port in ports) {
            AppLog.i(TAG, "connect: пытаемся подключиться к порту $port")
            if (tryConnect(port)) {
                currentPort = port
                AppLog.i(TAG, "connect: УСПЕХ на порту $port")
                return@withContext true
            }
            AppLog.w(TAG, "connect: НЕ УДАЛОСЬ на порту $port")
        }
        AppLog.e(TAG, "connect: все порты не сработали")
        return@withContext false
    }

    private fun tryConnect(port: Int): Boolean {
        return try {
            disconnect()
            val s = Socket()
            AppLog.i(
                TAG,
                "tryConnect: подключение к $host:$port (таймаут=${AppConstants.ADB_TIMEOUT_MS}мс)"
            )
            s.connect(InetSocketAddress(host, port), AppConstants.ADB_TIMEOUT_MS)
            s.soTimeout = AppConstants.ADB_TIMEOUT_MS
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            AppLog.i(TAG, "tryConnect: сокет открыт, отправляем transport-any")

            sendMessage("host:transport-any")
            val status = readStatus()
            AppLog.i(TAG, "tryConnect: transport-any status=$status")

            if (status == "OKAY") {
                commandCount = 0
                true
            } else {
                AppLog.w(TAG, "tryConnect: некорректный transport status: $status")
                disconnect()
                false
            }
        } catch (e: IOException) {
            AppLog.e(
                TAG,
                "tryConnect: IOException на порту $port: ${LogMasker.mask(e.message ?: "")}",
                e
            )
            disconnect()
            false
        } catch (e: Exception) {
            AppLog.e(
                TAG,
                "tryConnect: неожиданное исключение на порту $port: ${LogMasker.mask(e.message ?: "")}",
                e
            )
            disconnect()
            false
        }
    }

    /**
     * Выполняет ADB-команду с общим таймаутом и одноразовым реконнектом.
     *
     * Нормализация: убираем "shell " префикс — runShell() сам добавляет
     * "shell:" для wire-протокола. Без этой нормализации команды вида
     * "shell pm disable-user ..." превращались бы в "shell:shell pm ..."
     * и падали на устройстве.
     */
    override suspend fun executeCommand(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            commandCount++
            val normalized = command.trim().removePrefix("shell ")
            val maskedCmd = LogMasker.mask(normalized)
            AppLog.i(TAG, "cmd#$commandCount: выполнение: $maskedCmd")

            try {
                withTimeout(COMMAND_TIMEOUT_MS) {
                    try {
                        val result = runShell(normalized)
                        val maskedResult = LogMasker.mask(result.take(500))
                        AppLog.i(
                            TAG,
                            "cmd#$commandCount: успех, результат(${result.length} символов): $maskedResult"
                        )
                        Result.success(result)
                    } catch (e: AdbException) {
                        AppLog.w(
                            TAG,
                            "cmd#$commandCount: AdbException: ${LogMasker.mask(e.message ?: "")}, реконнект"
                        )
                        disconnect()
                        if (!connect()) {
                            AppLog.e(
                                TAG,
                                "cmd#$commandCount: реконнект НЕ УДАЛСЯ, пробрасываем исключение"
                            )
                            return@withTimeout Result.failure(e)
                        }
                        AppLog.i(TAG, "cmd#$commandCount: реконнект ОК, повторяем команду")
                        try {
                            val result = runShell(normalized)
                            AppLog.i(TAG, "cmd#$commandCount: повтор успешен")
                            Result.success(result)
                        } catch (e2: Exception) {
                            AppLog.e(
                                TAG,
                                "cmd#$commandCount: повтор тоже НЕ УДАЛСЯ: ${LogMasker.mask(e2.message ?: "")}",
                                e2
                            )
                            Result.failure(e2)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.e(TAG, "cmd#$commandCount: ТАЙМАУТ после ${COMMAND_TIMEOUT_MS}мс")
                disconnect()
                Result.failure(AdbException("Команда превысила таймаут ${COMMAND_TIMEOUT_MS}мс: $normalized"))
            }
        }

    private fun runShell(command: String): String {
        val s = socket ?: throw AdbException("Нет соединения")
        check(s.isConnected && !s.isClosed) { "Сокет неактивен" }

        try {
            sendMessage("shell:$command")
            val status = readStatus()
            AppLog.i(TAG, "runShell: status=$status")
            check(status == "OKAY") { "Некорректный status: $status" }
            val result = readUntilEof()

            AppLog.i(
                TAG,
                "runShell: прочитано ${result.bytesRead} байт за ${result.chunks} чанков " +
                        "(truncated=${result.truncated}, ${result.elapsedMs}мс)"
            )

            return result.data
        } catch (e: Exception) {
            AppLog.w(TAG, "runShell: ошибка, закрываем сокет: ${LogMasker.mask(e.message ?: "")}")
            disconnect()
            throw AdbException("Shell не удался: ${e.message}", e)
        }
    }

    override fun disconnect() {
        AppLog.i(TAG, "disconnect: закрываем сокет на порту $currentPort")
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        currentPort = -1
    }

    private fun sendMessage(message: String) {
        val data = message.toByteArray(Charsets.UTF_8)
        if (data.size > MAX_PAYLOAD) {
            AppLog.e(TAG, "sendMessage: payload слишком большой: ${data.size} байт")
            throw AdbException("Payload слишком большой: ${data.size}")
        }
        val header = String.format("%04x", data.size).toByteArray(Charsets.US_ASCII)
        val out = output ?: throw AdbException("Нет output stream")
        out.write(header)
        out.write(data)
        out.flush()
    }

    private fun readStatus(): String {
        val buf = ByteArray(4)
        readExact(buf, 4)
        val status = String(buf, Charsets.US_ASCII)
        AppLog.i(TAG, "readStatus: '$status'")
        return status
    }

    /**
     * Читает ответ shell до EOF с трёхуровневой защитой от зависания:
     * 1. Hard timeout внутри цикла (READ_HARD_TIMEOUT_MS) — прерывает бесконечный
     *    цикл если wireless ADB шлёт данные каплями без закрытия соединения
     * 2. Лимит размера ответа (MAX_RESPONSE_SIZE) — защита от огромных выводов
     * 3. soTimeout на сокете (AppConstants.ADB_TIMEOUT_MS) — защита от тишины
     *
     * Возвращает ReadResult с информацией о truncation, не бросает исключение.
     */
    private fun readUntilEof(): ReadResult {
        val sb = StringBuilder()
        val buf = ByteArray(BUFFER_SIZE)
        val stream = input ?: return ReadResult("", false, 0, 0, 0L)
        var chunks = 0
        var totalBytes = 0
        var truncated = false
        val startTime = System.currentTimeMillis()

        try {
            while (true) {
                if (System.currentTimeMillis() - startTime > READ_HARD_TIMEOUT_MS) {
                    AppLog.w(
                        TAG,
                        "readUntilEof: hard timeout после ${READ_HARD_TIMEOUT_MS}мс, возвращаем partial (${sb.length} символов)"
                    )
                    break
                }
                val n = stream.read(buf)
                if (n <= 0) break
                if (totalBytes + n > MAX_RESPONSE_SIZE) {
                    val remaining = MAX_RESPONSE_SIZE - totalBytes
                    if (remaining > 0) sb.append(String(buf, 0, remaining, Charsets.UTF_8))
                    truncated = true
                    AppLog.w(
                        TAG,
                        "readUntilEof: ответ превысил ${MAX_RESPONSE_SIZE} байт, обрезан"
                    )
                    break
                }
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                chunks++
                totalBytes += n
            }
        } catch (e: SocketTimeoutException) {
            AppLog.w(
                TAG,
                "readUntilEof: socket timeout после $chunks чанков (${sb.length} символов)"
            )
        } catch (e: IOException) {
            AppLog.e(
                TAG,
                "readUntilEof: IOException после $chunks чанков: ${LogMasker.mask(e.message ?: "")}",
                e
            )
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        AppLog.i(
            TAG,
            "readUntilEof: прочитано ${sb.length} символов за $chunks чанков " +
                    "(truncated=$truncated, ${elapsedMs}мс)"
        )

        return ReadResult(
            data = sb.toString(),
            truncated = truncated,
            bytesRead = totalBytes,
            chunks = chunks,
            elapsedMs = elapsedMs
        )
    }

    private fun readExact(buf: ByteArray, length: Int) {
        val stream = input ?: throw AdbException("Нет stream")
        var offset = 0
        while (offset < length) {
            val r = stream.read(buf, offset, length - offset)
            if (r < 0) {
                AppLog.e(TAG, "readExact: неожиданный EOF на offset $offset/$length")
                throw AdbException("Неожиданный EOF")
            }
            offset += r
        }
    }
}