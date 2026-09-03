package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/**
 * Помощник установки Shizuku из всех доступных источников.
 *
 * Источники (в порядке приоритета):
 * 1. Google Play (официальный)
 * 2. GetApps (Xiaomi, есть на всех устройствах)
 * 3. Aurora Store (альтернатива Play)
 * 4. GitHub releases (прямая ссылка)
 * 5. APKPure (веб-версия)
 *
 * УЛУЧШЕНИЯ:
 * 1. Убран неиспользуемый FDROID_PACKAGE (dead code)
 * 2. Включены GetApps/Aurora в fallback-цепочку openShizukuInStore()
 * 3. Добавлены явные типы для всех методов
 * 4. Добавлен метод isShizukuGranted() для проверки привилегий
 * 5. Улучшенное логирование с TAG
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PLAY_PACKAGE = "com.android.vending"
    private const val AURORA_PACKAGE = "com.aurora.store"
    private const val GETAPPS_PACKAGE = "com.xiaomi.market"

    private const val URL_GITHUB = "https://github.com/RikkaApps/Shizuku/releases/latest"
    private const val URL_PLAY_WEB =
        "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"
    private const val URL_APKPURE = "https://apkpure.com/shizuku/$SHIZUKU_PACKAGE"

    /**
     * Проверяет, установлен ли Shizuku.
     * НЕ проверяет, что Shizuku запущен и имеет привилегии — используйте isShizukuGranted() для этого.
     *
     * @param context Контекст приложения
     * @return true, если пакет установлен
     */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            AppLog.i(TAG, "Shizuku установлен")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            AppLog.i(TAG, "Shizuku НЕ установлен")
            false
        } catch (e: Exception) {
            AppLog.w(TAG, "Проверка установки Shizuku не удалась: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Проверяет наличие Google Play Store.
     * @param context Контекст приложения
     * @return true, если Play Store установлен
     */
    fun hasPlayStore(context: Context): Boolean = hasPackage(context, PLAY_PACKAGE)

    /**
     * Проверяет наличие Aurora Store.
     * @param context Контекст приложения
     * @return true, если Aurora Store установлен
     */
    fun hasAurora(context: Context): Boolean = hasPackage(context, AURORA_PACKAGE)

    /**
     * Проверяет наличие GetApps (Xiaomi).
     * @param context Контекст приложения
     * @return true, если GetApps установлен
     */
    fun hasGetApps(context: Context): Boolean = hasPackage(context, GETAPPS_PACKAGE)

    /**
     * Проверяет наличие указанного пакета.
     * @param context Контекст приложения
     * @param pkg Имя пакета для проверки
     * @return true, если пакет установлен
     */
    private fun hasPackage(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Автоматическая цепочка открытия Shizuku в магазине.
     *
     * Порядок попыток:
     * 1. Google Play (если установлен)
     * 2. GetApps (есть на всех Xiaomi)
     * 3. Aurora Store (если установлен)
     * 4. GitHub releases (прямая ссылка)
     * 5. APKPure (веб-версия)
     *
     * @param context Контекст приложения
     */
    fun openShizukuInStore(context: Context) {
        AppLog.i(TAG, "=== openShizukuInStore START ===")

        // 1. Google Play (официальный источник)
        if (hasPlayStore(context) && openPlay(context)) {
            AppLog.i(TAG, "Открыто через Google Play")
            return
        }

        // 2. GetApps (есть на всех Xiaomi/Redmi/Poco)
        if (hasGetApps(context) && openGetApps(context)) {
            AppLog.i(TAG, "Открыто через GetApps")
            return
        }

        // 3. Aurora Store (альтернатива Play)
        if (hasAurora(context) && openAurora(context)) {
            AppLog.i(TAG, "Открыто через Aurora Store")
            return
        }

        // 4. GitHub releases (прямая ссылка на APK)
        if (openGithub(context)) {
            AppLog.i(TAG, "Открыто через GitHub")
            return
        }

        // 5. APKPure (веб-версия как последний fallback)
        openApkPure(context)
        AppLog.i(TAG, "Открыто через APKPure")
    }

    /**
     * Открывает страницу Shizuku в Google Play.
     *
     * Порядок попыток:
     * 1. https-ссылка + setPackage(com.android.vending) — точная страница внутри Play Store
     * 2. market://details?id=... — классический deep link
     * 3. https-ссылка без пакета — браузер откроет ту же страницу
     *
     * @param context Контекст приложения
     * @return true, если удалось открыть
     */
    fun openPlay(context: Context): Boolean {
        // 1. Принудительно в приложение Play Store через https-ссылку
        try {
            AppLog.i(TAG, "Попытка: Play https + setPackage...")
            val intent = Intent(Intent.ACTION_VIEW, URL_PLAY_WEB.toUri())
            intent.setPackage(PLAY_PACKAGE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i(TAG, "Play https + setPackage: УСПЕХ (точная страница)")
            return true
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "Play https + setPackage не удался: ${e.javaClass.simpleName}: ${e.message}"
            )
        }

        // 2. Классический market:// deep link
        if (tryOpen(context, "market://details?id=$SHIZUKU_PACKAGE", "Play market://")) {
            return true
        }

        // 3. Браузер на ту же страницу
        return tryOpen(context, URL_PLAY_WEB, "Play web (браузер)")
    }

    /**
     * Открывает Aurora Store для поиска Shizuku.
     * @param context Контекст приложения
     * @return true, если удалось открыть
     */
    fun openAurora(context: Context): Boolean {
        AppLog.i(TAG, "Попытка: Aurora Store...")
        val intent = context.packageManager.getLaunchIntentForPackage(AURORA_PACKAGE)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i(TAG, "Aurora: УСПЕХ (пользователь ищет Shizuku внутри)")
            true
        } else {
            AppLog.w(TAG, "Aurora: launch intent null")
            false
        }
    }

    /**
     * Открывает GetApps для поиска Shizuku.
     * @param context Контекст приложения
     * @return true, если удалось открыть
     */
    fun openGetApps(context: Context): Boolean {
        AppLog.i(TAG, "Попытка: GetApps...")
        val intent = context.packageManager.getLaunchIntentForPackage(GETAPPS_PACKAGE)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i(TAG, "GetApps: УСПЕХ (пользователь ищет Shizuku внутри)")
            true
        } else {
            AppLog.w(TAG, "GetApps: launch intent null")
            false
        }
    }

    /**
     * Открывает страницу releases Shizuku на GitHub.
     * @param context Контекст приложения
     * @return true, если удалось открыть
     */
    fun openGithub(context: Context): Boolean =
        tryOpen(context, URL_GITHUB, "GitHub releases")

    /**
     * Открывает страницу Shizuku на APKPure.
     * @param context Контекст приложения
     * @return true, если удалось открыть
     */
    fun openApkPure(context: Context): Boolean =
        tryOpen(context, URL_APKPURE, "APKPure web")

    /**
     * Открывает приложение Shizuku (если установлено).
     * @param context Контекст приложения
     * @return true, если удалось открыть
     */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLog.i(TAG, "Приложение Shizuku открыто")
                true
            } else {
                AppLog.w(TAG, "Shizuku launch intent is null")
                false
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "openShizukuApp не удался: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Открывает экран беспроводной отладки (Android 11+).
     * Нужен для старта Shizuku без ПК — новичкам это самый сложный шаг.
     */
    fun openWirelessDebuggingSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    AppLog.i(TAG, "Opened developer / wireless debugging settings")
                    return true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "openWirelessDebugging failed: ${e.message}")
            }
        }
        return false
    }

    /**
     * Проверяет, что Shizuku установлен, запущен и имеет привилегии.
     */
    fun isShizukuGranted(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            AppLog.w(TAG, "Проверка привилегий Shizuku не удалась: ${e.message}")
            false
        }
    }

    /**
     * Универсальный метод открытия URI.
     * @param context Контекст приложения
     * @param uri Строка URI для открытия
     * @param name Имя источника для логирования
     * @return true, если удалось открыть
     */
    private fun tryOpen(context: Context, uri: String, name: String): Boolean {
        return try {
            AppLog.i(TAG, "Попытка $name: $uri")
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i(TAG, "$name: УСПЕХ")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "$name не удался: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}