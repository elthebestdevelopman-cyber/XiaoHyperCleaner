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

class AdbClient(
    private val host: String = AppConstants.ADB_HOST,
    private val ports: List<Int> = listOf(AppConstants.ADB_DEFAULT_PORT)
) : AdbExecutor {

    companion object {
        private const val TAG = "AdbClient"
        private const val MAX_PAYLOAD = 0xFFFF
        private const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024 // 10 MB лимит
        private const val COMMAND_TIMEOUT_MS = 30_000L // 30 секунд максимум на команду
        private const val READ_HARD_TIMEOUT_MS =
            25_000L // 25 секунд hard timeout внутри цикла чтения
    }

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var currentPort: Int = -1
    private var commandCount = 0

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "connect: trying ${ports.size} ports: $ports")
        for (port in ports) {
            AppLog.i(TAG, "connect: attempting port $port")
            if (tryConnect(port)) {
                currentPort = port
                AppLog.i(TAG, "connect: SUCCESS on port $port")
                return@withContext true
            }
            AppLog.w(TAG, "connect: FAILED on port $port")
        }
        AppLog.e(TAG, "connect: all ports failed")
        return@withContext false
    }

    private fun tryConnect(port: Int): Boolean {
        return try {
            disconnect()
            val s = Socket()
            AppLog.i(
                TAG,
                "tryConnect: connecting to $host:$port (timeout=${AppConstants.ADB_TIMEOUT_MS}ms)"
            )
            s.connect(InetSocketAddress(host, port), AppConstants.ADB_TIMEOUT_MS)
            s.soTimeout = AppConstants.ADB_TIMEOUT_MS
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            AppLog.i(TAG, "tryConnect: socket opened, sending transport-any")

            sendMessage("host:transport-any")
            val status = readStatus()
            AppLog.i(TAG, "tryConnect: transport-any status=$status")

            if (status == "OKAY") {
                commandCount = 0
                true
            } else {
                AppLog.w(TAG, "tryConnect: bad transport status: $status")
                disconnect()
                false
            }
        } catch (e: IOException) {
            AppLog.e(
                TAG,
                "tryConnect: IOException on port $port: ${LogMasker.mask(e.message ?: "")}",
                e
            )
            disconnect()
            false
        } catch (e: Exception) {
            AppLog.e(
                TAG,
                "tryConnect: unexpected exception on port $port: ${LogMasker.mask(e.message ?: "")}",
                e
            )
            disconnect()
            false
        }
    }

    /**
     * Выполняет ADB-команду с общим таймаутом COMMAND_TIMEOUT_MS.
     * Это защита от зависания на длинных shell-командах (logcat, dumpsys, бесконечных процессах).
     */
    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        commandCount++
        val maskedCmd = LogMasker.mask(command)
        AppLog.i(TAG, "cmd#$commandCount: executing: $maskedCmd")

        try {
            withTimeout(COMMAND_TIMEOUT_MS) {
                try {
                    val result = runShell(command)
                    val maskedResult = LogMasker.mask(result.take(500))
                    AppLog.i(
                        TAG,
                        "cmd#$commandCount: success, result(${result.length} chars): $maskedResult"
                    )
                    result
                } catch (e: AdbException) {
                    AppLog.w(
                        TAG,
                        "cmd#$commandCount: AdbException: ${LogMasker.mask(e.message ?: "")}, reconnecting once"
                    )
                    disconnect()
                    if (!connect()) {
                        AppLog.e(TAG, "cmd#$commandCount: reconnect FAILED, rethrowing")
                        throw e
                    }
                    AppLog.i(TAG, "cmd#$commandCount: reconnect OK, retrying command")
                    try {
                        val result = runShell(command)
                        AppLog.i(TAG, "cmd#$commandCount: retry success")
                        result
                    } catch (e2: Exception) {
                        AppLog.e(
                            TAG,
                            "cmd#$commandCount: retry also FAILED: ${LogMasker.mask(e2.message ?: "")}",
                            e2
                        )
                        throw e2
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.e(TAG, "cmd#$commandCount: TIMEOUT after ${COMMAND_TIMEOUT_MS}ms")
            disconnect()
            throw AdbException("Command timed out after ${COMMAND_TIMEOUT_MS}ms: $command")
        }
    }

    private fun runShell(command: String): String {
        val s = socket ?: throw AdbException("Not connected")
        check(s.isConnected && !s.isClosed) { "Socket not usable" }

        try {
            sendMessage("shell:$command")
            val status = readStatus()
            AppLog.i(TAG, "runShell: status=$status")
            check(status == "OKAY") { "Bad status: $status" }
            return readUntilEof()
        } catch (e: Exception) {
            AppLog.w(TAG, "runShell: error, closing socket: ${LogMasker.mask(e.message ?: "")}")
            disconnect()
            throw AdbException("Shell failed: ${e.message}", e)
        }
    }

    override fun disconnect() {
        AppLog.i(TAG, "disconnect: closing socket on port $currentPort")
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
            AppLog.e(TAG, "sendMessage: payload too large: ${data.size} bytes")
            throw AdbException("Payload too large: ${data.size}")
        }
        val header = String.format("%04x", data.size).toByteArray(Charsets.US_ASCII)
        val out = output ?: throw AdbException("No output stream")
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
     * Читает ответ shell до EOF с двойной защитой от зависания:
     * 1. Hard timeout внутри цикла (READ_HARD_TIMEOUT_MS = 25s) — прерывает бесконечный цикл
     *    если wireless ADB шлёт данные каплями без закрытия соединения
     * 2. Лимит размера ответа (MAX_RESPONSE_SIZE = 10MB) — защита от огромных выводов
     * 3. soTimeout на сокете (AppConstants.ADB_TIMEOUT_MS) — защита от полного отсутствия данных
     *
     * Возвращает partial result при таймауте, не бросает исключение (это делает внешний withTimeout).
     */
    private fun readUntilEof(): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        val stream = input ?: return ""
        var chunks = 0
        var totalBytes = 0
        var truncated = false
        val startTime = System.currentTimeMillis()

        try {
            while (true) {
                // Hard timeout: защита от бесконечного цикла если данные идут каплями
                if (System.currentTimeMillis() - startTime > READ_HARD_TIMEOUT_MS) {
                    AppLog.w(
                        TAG,
                        "readUntilEof: hard timeout after ${READ_HARD_TIMEOUT_MS}ms, returning partial (${sb.length} chars)"
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
                        "readUntilEof: response exceeded ${MAX_RESPONSE_SIZE} bytes, truncated"
                    )
                    break
                }
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                chunks++
                totalBytes += n
            }
        } catch (e: SocketTimeoutException) {
            AppLog.w(TAG, "readUntilEof: socket timeout after $chunks chunks (${sb.length} chars)")
        } catch (e: IOException) {
            AppLog.e(
                TAG,
                "readUntilEof: IOException after $chunks chunks: ${LogMasker.mask(e.message ?: "")}",
                e
            )
        }
        AppLog.i(
            TAG,
            "readUntilEof: read ${sb.length} chars in $chunks chunks (truncated=$truncated, elapsed=${System.currentTimeMillis() - startTime}ms)"
        )
        return sb.toString()
    }

    private fun readExact(buf: ByteArray, length: Int) {
        val stream = input ?: throw AdbException("No stream")
        var offset = 0
        while (offset < length) {
            val r = stream.read(buf, offset, length - offset)
            if (r < 0) {
                AppLog.e(TAG, "readExact: unexpected EOF at offset $offset/$length")
                throw AdbException("Unexpected EOF")
            }
            offset += r
        }
    }
}