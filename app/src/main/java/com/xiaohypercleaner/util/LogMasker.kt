package com.xiaohypercleaner.util

object LogMasker {
    private val SETTINGS_VALUE = Regex("(settings\\s+put\\s+\\S+\\s+\\S+\\s+)\\S+")
    private val IP_ADDRESS = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b")
    private val FILE_PATH = Regex("/[\\w/.-]{10,}")

    fun mask(input: String): String = input
        .replace(SETTINGS_VALUE, "$1***")
        .replace(IP_ADDRESS, "***")
        .replace(FILE_PATH, "***")
}