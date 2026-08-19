package com.xiaohypercleaner.util

import android.content.Context

/**
 * Маскирует чувствительные данные в логах перед записью/отправкой.
 * Используется как singleton — не требует Context для базовой маскировки.
 * Путь к данным приложения берётся динамически при первом вызове.
 */
object LogMasker {

    // Путь инициализируется при первой необходимости через init(context)
    private var appDataPath: String = ""
    private var initialized = false

    private val userPathRegex: Regex by lazy {
        val escapedPath = Regex.escape(appDataPath)
        Regex("$escapedPath(/[^\\s]*)?")
    }

    private val ipRegex: Regex by lazy {
        Regex("""\b((?!127\.0\.0\.1)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""")
    }

    private val longTokenRegex: Regex by lazy {
        Regex("""\b[A-Za-z0-9_\-./+=]{32,}\b""")
    }

    private val sensitiveKeyValueRegex: Regex by lazy {
        Regex(
            """(password|token|key|secret|auth)["'\s:=]+["']?([^"',\s}]+)["']?""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Инициализация с Context — вызывается один раз в Application.onCreate().
     * Получаем путь к данным приложения динамически через context.applicationInfo.dataDir.
     */
    fun init(context: Context) {
        appDataPath = context.applicationInfo.dataDir
        initialized = true
    }

    /**
     * Маскирует чувствительные данные: пути, IP-адреса, токены, пароли.
     * Может вызываться как top-level функция или companion-метод.
     */
    fun mask(input: String): String {
        if (input.isBlank()) return input

        var result = input

        // 1. Маскируем путь к данным приложения (если инициализирован)
        if (initialized && appDataPath.isNotEmpty()) {
            result = result.replace(userPathRegex, "$appDataPath/*")
        }

        // 2. Маскируем IP-адреса (кроме localhost)
        result = result.replace(ipRegex, "***.***.***.***")

        // 3. Маскируем длинные токены
        result = result.replace(longTokenRegex) { match ->
            val value = match.value
            if (value.length > 32) {
                "${value.take(8)}...${value.takeLast(4)}"
            } else {
                value
            }
        }

        // 4. Маскируем значения чувствительных ключей
        result = result.replace(sensitiveKeyValueRegex) { match ->
            val key = match.groupValues[1]
            "$key=***"
        }

        return result
    }
}