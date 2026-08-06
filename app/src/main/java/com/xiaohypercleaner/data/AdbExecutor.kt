package com.xiaohypercleaner.data

interface AdbExecutor {
    suspend fun connect(): Boolean
    suspend fun executeCommand(command: String): String
    fun disconnect()
}