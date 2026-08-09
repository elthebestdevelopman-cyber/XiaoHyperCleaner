package com.xiaohypercleaner.util

object LogMasker {

    private val IP = Regex("\\b(?!127\\.0\\.0\\.1\\b)\\d{1,3}(\\.\\d{1,3}){3}\\b")
    private val LONG_HEX = Regex("[0-9A-Fa-f]{16,}")
    private val USER_PATH = Regex("/data/user/\\d+/[\\w.]+")
    private val SETTINGS_VALUE = Regex("(settings put (?:secure|global|system) \\S+) \\S+")

    fun mask(text: String): String = text
        .replace(USER_PATH, "/data/user/*")
        .replace(IP, "*.*.*.*")
        .replace(LONG_HEX, "<hex>")
        .replace(SETTINGS_VALUE, "$1 *")
}