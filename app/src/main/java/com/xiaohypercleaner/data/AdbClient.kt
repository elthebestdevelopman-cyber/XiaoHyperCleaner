package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            disconnect() // закрываем старый сокет если был
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
            AppLog.e(TAG, "tryConnect: IOException on port $port: ${e.message}", e)
            disconnect()
            false
        } catch (e: Exception) {
            AppLog.e(TAG, "tryConnect: unexpected exception on port $port", e)
            disconnect()
            false
        }
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        commandCount++
        val maskedCmd = com.xiaohypercleaner.util.LogMasker.mask(command)
        AppLog.i(TAG, "cmd#$commandCount: executing: $maskedCmd")

        try {
            val result = runShell(command)
            val maskedResult = com.xiaohypercleaner.util.LogMasker.mask(result.take(500))
            AppLog.i(
                TAG,
                "cmd#$commandCount: success, result(${result.length} chars): $maskedResult"
            )
            result
        } catch (e: AdbException) {
            AppLog.w(TAG, "cmd#$commandCount: AdbException: ${e.message}, reconnecting once")
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
                AppLog.e(TAG, "cmd#$commandCount: retry also FAILED", e2)
                throw e2
            }
        }
    }

    private fun runShell(command: String): String {
        val s = socket ?: throw AdbException("Not connected")
        if (!s.isConnected) throw AdbException("Socket closed")
        if (s.isClosed) throw AdbException("Socket was closed")

        sendMessage("shell:$command")
        val status = readStatus()
        AppLog.i(TAG, "runShell: status=$status")
        if (status != "OKAY") throw AdbException("Bad status: $status")

        val result = readUntilEof()
        return result
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

    private fun readUntilEof(): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        val stream = input ?: return ""
        var chunks = 0
        try {
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                chunks++
            }
        } catch (e: SocketTimeoutException) {
            AppLog.w(TAG, "readUntilEof: timeout after $chunks chunks, returning partial")
        } catch (e: IOException) {
            AppLog.e(TAG, "readUntilEof: IOException after $chunks chunks", e)
        }
        AppLog.i(TAG, "readUntilEof: read ${sb.length} chars in $chunks chunks")
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