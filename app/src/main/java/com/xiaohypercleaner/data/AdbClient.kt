package com.xiaohypercleaner.data

import android.util.Log
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.LogMasker
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

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        for (port in ports) {
            if (tryConnect(port)) return@withContext true
        }
        false
    }

    private fun tryConnect(port: Int): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), AppConstants.ADB_TIMEOUT_MS)
            s.soTimeout = AppConstants.ADB_TIMEOUT_MS
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            sendMessage("host:transport-any")
            readStatus() == "OKAY"
        } catch (e: IOException) {
            Log.w(TAG, "connect to $port failed: ${e.message}")
            disconnect()
            false
        }
    }

    override suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        validateCommand(command)
        try {
            runShell(command)
        } catch (e: AdbException) {
            Log.w(TAG, "reconnecting after: ${LogMasker.mask(command)}")
            if (!connect()) throw e
            runShell(command)
        }
    }

    private fun runShell(command: String): String {
        val s = socket ?: throw AdbException(AdbErrorCode.NOT_CONNECTED, "Not connected")
        if (!s.isConnected) throw AdbException(AdbErrorCode.SOCKET_CLOSED, "Socket closed")
        sendMessage("shell:$command")
        if (readStatus() != "OKAY") {
            throw AdbException(AdbErrorCode.BAD_STATUS, "Bad status: ${LogMasker.mask(command)}")
        }
        return readUntilEof()
    }

    override fun disconnect() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    private fun validateCommand(command: String) {
        if (command.isBlank()) {
            throw AdbException(AdbErrorCode.COMMAND_TOO_LONG, "Empty command")
        }
        if (command.length > AppConstants.MAX_COMMAND_LENGTH) {
            throw AdbException(AdbErrorCode.COMMAND_TOO_LONG, "Command too long")
        }
    }

    private fun sendMessage(message: String) {
        val data = message.toByteArray(Charsets.UTF_8)
        if (data.size > MAX_PAYLOAD) {
            throw AdbException(AdbErrorCode.PAYLOAD_TOO_LARGE, "Payload too large")
        }
        val header = String.format("%04x", data.size).toByteArray(Charsets.US_ASCII)
        output!!.write(header); output!!.write(data); output!!.flush()
    }

    private fun readStatus(): String {
        val buf = ByteArray(4)
        readExact(buf, 4)
        return String(buf, Charsets.US_ASCII)
    }

    private fun readUntilEof(): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        val stream = input ?: return ""
        try {
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "read timeout, partial output")
        }
        return sb.toString()
    }

    private fun readExact(buf: ByteArray, length: Int) {
        val stream = input ?: throw AdbException(AdbErrorCode.NOT_CONNECTED, "No stream")
        var offset = 0
        while (offset < length) {
            val r = stream.read(buf, offset, length - offset)
            if (r < 0) throw AdbException(AdbErrorCode.UNEXPECTED_EOF, "EOF")
            offset += r
        }
    }
}