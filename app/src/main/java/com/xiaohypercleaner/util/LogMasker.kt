package com.xiaohypercleaner.util

object LogMasker {
    private val IP = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
    private val LONG_HEX = Regex("[0-9A-Fa-f]{16,}")
    private val USER_PATH = Regex("/data/user/\\d+/[\\w.]+")

    fun mask(text: String): String = text
        .replace(IP, "*.*.*.*")
        .replace(LONG_HEX, "<hex>")
        .replace(USER_PATH, "/data/user/*")
}