package com.xiaohypercleaner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket

class AdbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5555
) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = Socket(host, port).apply { soTimeout = 5000 }
            input = BufferedInputStream(socket!!.getInputStream())
            output = socket!!.getOutputStream()
            sendMessage("host:transport-any")
            readStatus() == "OKAY"
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IllegalStateException("Not connected")
        if (!s.isConnected) throw IllegalStateException("Socket closed")
        sendMessage("shell:$command")
        if (readStatus() != "OKAY") return@withContext "Error: bad status"
        readUntilEof()
    }

    fun disconnect() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun sendMessage(message: String) {
        val data = message.toByteArray(Charsets.UTF_8)
        // ADB требует НИЖНИЙ регистр hex
        val header = String.format("%04x", data.size).toByteArray(Charsets.US_ASCII)
        output!!.write(header)
        output!!.write(data)
        output!!.flush()
    }

    // Читает 4-байтовый статус (OKAY/FAIL) — для host- и shell-команд
    private fun readStatus(): String {
        val buf = ByteArray(4)
        readExact(buf, 4)
        return String(buf, Charsets.US_ASCII)
    }

    // Для shell-команд: читает вывод до EOF без указания длины
    private fun readUntilEof(): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        val stream = input ?: return ""
        while (true) {
            val n = try {
                stream.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return sb.toString()
    }

    private fun readExact(buf: ByteArray, length: Int) {
        val stream = input ?: throw IllegalStateException("No stream")
        var offset = 0
        while (offset < length) {
            val r = stream.read(buf, offset, length - offset)
            if (r < 0) throw IllegalStateException("EOF")
            offset += r
        }
    }
}