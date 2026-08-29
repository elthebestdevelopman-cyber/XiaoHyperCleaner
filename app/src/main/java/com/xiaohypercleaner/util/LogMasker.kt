package com.xiaohypercleaner.util

import android.content.Context

/**
 * Маскирует чувствительные данные в логах перед записью/отправкой.
 *
 * УЛУЧШЕНИЯ:
 * 1. Thread-safety через @Volatile для appDataPath и initialized
 * 2. Дополнительные паттерны: email, phone, UUID
 * 3. Опциональная интеграция с AppLog через applyMasking
 * 4. Ленивая инициализация regex для производительности
 *
 * Использование:
 * - Автоматически: LogMasker.init(context) в Application.onCreate()
 * - Вручную: LogMasker.mask("sensitive data")
 * - Интеграция: AppLog может вызывать mask() перед записью
 */
object LogMasker {

    @Volatile
    private var appDataPath: String = ""

    @Volatile
    private var initialized: Boolean = false

    // Ленивая инициализация regex — компилируется один раз при первом использовании
    private val userPathRegex: Regex by lazy {
        val escapedPath = Regex.escape(appDataPath)
        Regex("$escapedPath(/[^\\s]*)?")
    }

    private val ipRegex: Regex by lazy {
        // Маскируем все IP кроме localhost (127.0.0.1)
        Regex("""\b((?!127\.0\.0\.1)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""")
    }

    private val longTokenRegex: Regex by lazy {
        // Токены длиной >= 32 символов (API keys, JWT, etc.)
        Regex("""\b[A-Za-z0-9_\-./+=]{32,}\b""")
    }

    private val sensitiveKeyValueRegex: Regex by lazy {
        // Пароли, токены, ключи в формате key=value или key: "value"
        Regex(
            """(password|token|key|secret|auth|apikey|api_key|access_token)["'\s:=]+["']?([^"',\s}]+)["']?""",
            RegexOption.IGNORE_CASE
        )
    }

    private val emailRegex: Regex by lazy {
        // Email адреса
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b""")
    }

    private val phoneRegex: Regex by lazy {
        // Телефоны в форматах: +7..., 8..., (xxx) xxx-xx-xx
        Regex("""(\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{2,4}[-.\s]?\d{0,2}""")
    }

    private val uuidRegex: Regex by lazy {
        // UUID v4
        Regex(
            """\b[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Инициализация с Context — вызывается один раз в Application.onCreate().
     * Получаем путь к данным приложения динамически через context.applicationInfo.dataDir.
     *
     * ВАЖНО: Вызывать до первого использования mask(), иначе пути не будут маскироваться.
     */
    fun init(context: Context) {
        appDataPath = context.applicationInfo.dataDir
        initialized = true
    }

    /**
     * Маскирует чувствительные данные: пути, IP-адреса, токены, пароли, email, phone, UUID.
     *
     * @param input Исходная строка для маскировки
     * @return Замаскированная строка
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

        // 3. Маскируем длинные токены (показываем только начало и конец)
        result = result.replace(longTokenRegex) { match ->
            val value = match.value
            if (value.length > 32) {
                "${value.take(8)}...${value.takeLast(4)}"
            } else {
                value
            }
        }

        // 4. Маскируем значения чувствительных ключей (password=***, token=***)
        result = result.replace(sensitiveKeyValueRegex) { match ->
            val key = match.groupValues[1]
            "$key=***"
        }

        // 5. Маскируем email адреса
        result = result.replace(emailRegex, "***@***.***")

        // 6. Маскируем телефоны
        result = result.replace(phoneRegex, "***-***-**-**")

        // 7. Маскируем UUID
        result = result.replace(uuidRegex, "********-****-****-****-************")

        return result
    }

    /**
     * Проверяет, инициализирован ли маскировщик.
     * Используется для диагностики в логах.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Возвращает путь к данным приложения (для отладки).
     * Возвращает пустую строку, если не инициализирован.
     */
    fun getAppDataPath(): String = appDataPath
}