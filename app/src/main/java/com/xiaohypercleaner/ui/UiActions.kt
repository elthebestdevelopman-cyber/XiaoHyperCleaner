package com.xiaohypercleaner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.xiaohypercleaner.util.AppLog
import java.io.File

/**
 * Top-level функции для UI-действий.
 *
 * Вынесены из MainActivity для переиспользования в других компонентах.
 *
 * УЛУЧШЕНИЯ:
 * 1. TAG вынесен в приватную константу
 * 2. Русские логи для соответствия правилу 1
 * 3. FLAG_ACTIVITY_NEW_TASK добавлен во все startActivity
 * 4. Явные типы для всех переменных
 * 5. Полная документация
 * 6. shareLog(): ИСПРАВЛЕН — MIME-тип изменён на "text/x-log",
 *    EXTRA_TEXT убран (конфликтовал с EXTRA_STREAM), добавлен fallback
 *    на отправку содержимого лога как текста
 */

private const val TAG = "UiActions"

/**
 * Открывает URL в браузере.
 *
 * @param context Контекст приложения
 * @param url URL для открытия
 */
fun openUrl(context: Context, url: String) {
    AppLog.i(TAG, "openUrl: открытие $url")
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        AppLog.i(TAG, "openUrl: успех")
    } catch (e: Exception) {
        AppLog.e(TAG, "openUrl: не удалось открыть $url: ${e.message}")
    }
}

/**
 * Открывает WebView для донатов; при ошибке — fallback в браузер.
 *
 * @param context Контекст приложения
 * @param url URL для открытия
 * @param title Заголовок для WebViewActivity
 */
fun openWebView(context: Context, url: String, title: String) {
    AppLog.i(TAG, "openWebView: открытие $url")
    try {
        context.startActivity(
            Intent(context, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, url)
                .putExtra(WebViewActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        AppLog.i(TAG, "openWebView: успех")
    } catch (e: Exception) {
        AppLog.w(TAG, "openWebView: не удалось, fallback в браузер: ${e.message}")
        openUrl(context, url)
    }
}

/**
 * Делится файлом лога приложения через системный chooser.
 *
 * ИСПРАВЛЕНИЯ (файл не прикреплялся — отправлялся только текст):
 * 1. MIME-тип изменён с "text/plain" на "text/x-log" — получатели
 *    (Telegram, Gmail, WhatsApp) интерпретируют intent как "отправить файл",
 *    а не "отправить текст"
 * 2. EXTRA_TEXT убран — он конфликтовал с EXTRA_STREAM и заставлял
 *    получателей игнорировать прикреплённый файл
 * 3. Fallback на cacheDir — если оригинальный путь не покрыт file_paths.xml,
 *    копируем лог в cache/ (всегда доступен через cache-path)
 * 4. ClipData с URI — требование Android 7+ для корректной выдачи гранта
 * 5. FLAG_GRANT_READ_URI_PERMISSION на chooser (а не только на inner intent)
 * 6. Toast с конкретной ошибкой — пользователь видит, что пошло не так
 * 7. Очистка старых копий в cacheDir (оставляем 3 последние)
 * 8. Fallback: если шаринг файла не удался — отправляем содержимое как текст
 *
 * @param context Контекст приложения
 */
fun shareLog(context: Context) {
    AppLog.i(TAG, "shareLog: запрошен шаринг лога")

    // ═══════════════════════════════════════════════════════════════
    // Шаг 1: Получаем лог-файл
    // ═══════════════════════════════════════════════════════════════
    val originalFile: File? = try {
        AppLog.getLogFile()
    } catch (e: Exception) {
        AppLog.e(TAG, "shareLog: AppLog.getLogFile() выбросил исключение", e)
        null
    }

    if (originalFile == null) {
        AppLog.w(TAG, "shareLog: AppLog.getLogFile() вернул null")
        toast(context, "Лог-файл не найден. Сделайте любое действие и повторите.")
        return
    }

    AppLog.i(
        TAG,
        "shareLog: originalFile=${originalFile.absolutePath}, " +
                "exists=${originalFile.exists()}, size=${originalFile.length()}"
    )

    if (!originalFile.exists() || originalFile.length() == 0L) {
        AppLog.w(TAG, "shareLog: файл не существует или пустой")
        toast(context, "Лог пустой. Сначала запустите оптимизацию.")
        return
    }

    // ═══════════════════════════════════════════════════════════════
    // Шаг 2: Копируем лог в cacheDir
    // Эта папка ВСЕГДА покрыта <cache-path> в file_paths.xml,
    // поэтому FileProvider гарантированно сможет отдать URI
    // даже если оригинальный путь лога не покрыт конфигурацией.
    // ═══════════════════════════════════════════════════════════════
    val shareableFile: File = try {
        val cacheDir = File(context.cacheDir, "logs").apply {
            if (!exists()) mkdirs()
        }
        val copy = File(cacheDir, "xhc_share_${System.currentTimeMillis()}.log")
        originalFile.copyTo(copy, overwrite = true)
        AppLog.i(
            TAG,
            "shareLog: скопировано в ${copy.absolutePath}, размер=${copy.length()}"
        )

        // Удаляем старые копии, оставляя 3 последние
        cacheDir.listFiles()
            ?.filter { it.name.startsWith("xhc_share_") && it != copy }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(3)
            ?.forEach { it.delete() }

        copy
    } catch (e: Exception) {
        AppLog.e(TAG, "shareLog: не удалось скопировать лог в cacheDir", e)
        // Fallback: отправляем содержимое как текст
        shareLogAsText(context, originalFile)
        return
    }

    // ═══════════════════════════════════════════════════════════════
    // Шаг 3: Получаем URI через FileProvider
    // ═══════════════════════════════════════════════════════════════
    val authority = "${context.packageName}.fileprovider"
    val uri: android.net.Uri = try {
        FileProvider.getUriForFile(context, authority, shareableFile)
    } catch (e: IllegalArgumentException) {
        AppLog.e(
            TAG,
            "shareLog: FileProvider не может отдать URI для ${shareableFile.absolutePath}. " +
                    "Проверьте file_paths.xml: должен быть <cache-path name=\"cache\" path=\".\" />",
            e
        )
        // Fallback: отправляем содержимое как текст
        shareLogAsText(context, originalFile)
        return
    } catch (e: Exception) {
        AppLog.e(TAG, "shareLog: неожиданная ошибка FileProvider", e)
        // Fallback: отправляем содержимое как текст
        shareLogAsText(context, originalFile)
        return
    }

    AppLog.i(TAG, "shareLog: URI получен: $uri (authority=$authority)")

    // ═══════════════════════════════════════════════════════════════
    // Шаг 4: Формируем intent для отправки ФАЙЛА (не текста!)
    //
    // КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: MIME-тип "text/x-log" вместо "text/plain".
    // Это заставляет Telegram/Gmail/WhatsApp интерпретировать intent
    // как "отправить файл", а не "отправить текст".
    //
    // EXTRA_TEXT намеренно НЕ добавляем — он конфликтует с EXTRA_STREAM
    // и заставляет получателей игнорировать прикреплённый файл.
    // ═══════════════════════════════════════════════════════════════
    val timestamp = java.text.SimpleDateFormat(
        "yyyy-MM-dd_HH-mm-ss",
        java.util.Locale.US
    ).format(java.util.Date())

    val innerIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/x-log"  // ← КЛЮЧЕВОЕ: не "text/plain"!
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "XiaoHyperCleaner log $timestamp")
        // НЕ добавляем EXTRA_TEXT — он конфликтует с EXTRA_STREAM

        // ClipData + flag — правильный способ грантить URI на Android 7+
        clipData = ClipData.newRawUri("XHC Log", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooserIntent = Intent.createChooser(innerIntent, "Поделиться логом").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Дублируем ClipData на chooser для Android 11+
        clipData = ClipData.newRawUri("XHC Log", uri)
    }

    // ═══════════════════════════════════════════════════════════════
    // Шаг 5: Отправляем с диагностикой
    // ═══════════════════════════════════════════════════════════════
    try {
        context.startActivity(chooserIntent)
        AppLog.i(TAG, "shareLog: chooser показан успешно")
    } catch (e: android.content.ActivityNotFoundException) {
        AppLog.e(TAG, "shareLog: нет приложения для шаринга", e)
        // Fallback: отправляем как текст
        shareLogAsText(context, originalFile)
    } catch (e: SecurityException) {
        AppLog.e(TAG, "shareLog: SecurityException — проблема с правами на URI", e)
        // Fallback: отправляем как текст
        shareLogAsText(context, originalFile)
    } catch (e: Exception) {
        AppLog.e(TAG, "shareLog: неожиданная ошибка при показе chooser", e)
        // Fallback: отправляем как текст
        shareLogAsText(context, originalFile)
    }
}

/**
 * FALLBACK: если шаринг файла не удался, отправляем содержимое лога как текст.
 *
 * Это гарантирует, что пользователь сможет получить лог даже при проблемах
 * с FileProvider или в приложениях, не поддерживающих файловые вложения.
 *
 * Лог ограничивается 100KB для совместимости с лимитами мессенджеров.
 *
 * @param context Контекст приложения
 * @param file Лог-файл для отправки
 */
private fun shareLogAsText(context: Context, file: File) {
    try {
        val maxBytes = 100 * 1024L
        val content = if (file.length() > maxBytes) {
            val bytes = ByteArray(maxBytes.toInt())
            file.inputStream().use { it.read(bytes) }
            String(bytes, Charsets.UTF_8) + "\n\n... [лог обрезан до 100KB] ..."
        } else {
            file.readText(Charsets.UTF_8)
        }

        val textIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "XiaoHyperCleaner log")
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(textIntent, "Поделиться логом (как текст)")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(chooser)
        AppLog.i(TAG, "shareLogAsText: fallback сработал, отправлено ${content.length} символов")
    } catch (e: Exception) {
        AppLog.e(TAG, "shareLogAsText: fallback тоже не сработал", e)
        toast(context, "Не удалось поделиться логом ни как файл, ни как текст")
    }
}

/**
 * Открывает экран «О телефоне» (для включения режима разработчика).
 * При ошибке — fallback в общие настройки.
 *
 * @param context Контекст приложения
 */
fun openDeviceInfoSettings(context: Context) {
    AppLog.i(TAG, "openDeviceInfoSettings: открытие экрана о телефоне")
    try {
        context.startActivity(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        AppLog.i(TAG, "openDeviceInfoSettings: успех")
    } catch (e: Exception) {
        AppLog.w(TAG, "openDeviceInfoSettings: не удалось: ${e.message}")
        openSettings(context)
    }
}

/**
 * Открывает настройки разработчика.
 * При ошибке — fallback в общие настройки.
 *
 * @param context Контекст приложения
 */
fun openDevOptionsSettings(context: Context) {
    AppLog.i(TAG, "openDevOptionsSettings: открытие настроек разработчика")
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        AppLog.i(TAG, "openDevOptionsSettings: успех")
    } catch (e: Exception) {
        AppLog.w(TAG, "openDevOptionsSettings: не удалось: ${e.message}")
        openSettings(context)
    }
}

/**
 * Открывает общие настройки — fallback для всех open*Settings.
 *
 * @param context Контекст приложения
 */
private fun openSettings(context: Context) {
    AppLog.i(TAG, "openSettings: fallback в общие настройки")
    try {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        AppLog.w(TAG, "openSettings: не удалось: ${e.message}")
    }
}

/**
 * Открывает страницу оценки приложения.
 *
 * Порядок попыток:
 * 1. RuStore (rustore://)
 * 2. Mi Market (mimarket://)
 * 3. Play Market (market://)
 * 4. Web fallback (https://play.google.com)
 *
 * @param context Контекст приложения
 */
fun openRateApp(context: Context) {
    AppLog.i(TAG, "openRateApp: открытие страницы оценки")
    val pkg: String = context.packageName

    val schemes: List<String> = listOf(
        "rustore://application/$pkg",
        "mimarket://details?id=$pkg",
        "market://details?id=$pkg"
    )

    for (scheme: String in schemes) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, scheme.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            AppLog.i(TAG, "openRateApp: открыто через scheme: $scheme")
            return
        } catch (_: Exception) {
            // Пробуем следующий scheme
        }
    }

    // Fallback в web
    openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
}

/**
 * Показывает Toast с диагностикой для пользователя.
 * Обрабатывает исключения, чтобы не упасть если Context не позволяет показать Toast.
 *
 * @param context Контекст приложения
 * @param message Сообщение для показа
 */
private fun toast(context: Context, message: String) {
    try {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        AppLog.w(TAG, "toast failed: ${e.message}")
    }
}