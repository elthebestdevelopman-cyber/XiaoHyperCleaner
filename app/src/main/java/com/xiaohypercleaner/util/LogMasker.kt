package com.xiaohypercleaner.util

object LogMasker {
    fun mask(input: String): String {
        return input
            .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "*.*.*.*")
            .replace(Regex("[a-fA-F0-9]{24,}"), "***MASKED***")
    }
}