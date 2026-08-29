package com.xiaohypercleaner

import android.annotation.SuppressLint
import android.app.Application
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker

/**
 * Точка входа приложения.
 *
 * Отвечает за:
 * 1. Инициализацию логгера (AppLog) и маскировщика (LogMasker)
 * 2. Создание DI-контейнера (AppDependencies)
 * 3. Глобальный обработчик крашей
 *
 * УЛУЧШЕНИЯ:
 * 1. LogMasker.init() — маскировка путей к данным в логах
 * 2. TAG вынесен в companion object
 * 3. Логирование версии приложения для диагностики
 * 4. Convenience метод для инъекции тестовых зависимостей
 */
class XiaoHyperApp : Application() {

    companion object {
        private const val TAG = "App"

        // Application — синглтон на всё время процесса, поэтому статическая
        // ссылка не создаёт реальной утечки памяти. Поля нужны для быстрого
        // доступа из сервисов и подмены зависимостей в тестах.
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: XiaoHyperApp
            private set

        @SuppressLint("StaticFieldLeak")
        private var testDeps: AppDependencies? = null

        /**
         * Инъекция тестовых зависимостей.
         * Используется в instrumentation-тестах для подмены реальных зависимостей моками.
         */
        fun injectTestDependencies(deps: AppDependencies) {
            testDeps = deps
        }

        /**
         * Возвращает тестовые зависимости, если они были инъектированы.
         */
        fun getTestDependencies(): AppDependencies? = testDeps
    }

    lateinit var deps: AppDependencies
        private set

    val preferencesManager get() = deps.preferencesManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Устанавливаем глобальный обработчик крашей ДО инициализации логгера,
        // чтобы поймать даже краши при инициализации.
        setupCrashHandler()

        // 1. Инициализация логгера — должна быть первой.
        AppLog.init(this)
        AppLog.i(TAG, "=== XiaoHyperApp onCreate started ===")

        // 2. Инициализация маскировщика — для сокрытия путей к данным в логах.
        // ВАЖНО: вызывать после AppLog.init(), чтобы логи маскировались.
        LogMasker.init(this)
        AppLog.i(TAG, "LogMasker initialized, dataDir=${LogMasker.getAppDataPath()}")

        // 3. Логирование версии приложения — критично для диагностики багрепортов.
        logAppVersion()

        // 4. Создание DI-контейнера.
        try {
            deps = AppDependencies(this)
            AppLog.i(TAG, "AppDependencies created successfully")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create AppDependencies", e)
            throw e
        }

        AppLog.i(TAG, "=== XiaoHyperApp onCreate completed ===")
    }

    /**
     * Логирует версию приложения, версию кода и информацию о сборке.
     * Критично для диагностики: без этого невозможно понять, на какой версии
     * произошёл баг, когда пользователь присылает лог.
     */
    private fun logAppVersion() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"

            @Suppress("DEPRECATION")
            val versionCode =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }

            AppLog.i(TAG, "App version: $versionName (code=$versionCode)")
            AppLog.i(TAG, "Package: $packageName")
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to read app version: ${e.message}")
        }
    }

    /**
     * Глобальный обработчик необработанных исключений.
     * Логирует краш через AppLog перед тем, как передать его дефолтному обработчику.
     *
     * ВАЖНО: AppLog.writeToFile() безопасно обрабатывает случай, когда writer == null,
     * поэтому краш до инициализации логгера не вызовет рекурсию.
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.e("CRASH", "Uncaught exception in thread '${thread.name}'", throwable)
                AppLog.e("CRASH", "Thread ID: ${thread.id}, priority: ${thread.priority}")

                // Принудительно сбрасываем буфер, чтобы лог краша точно записался.
                AppLog.close()
            } catch (_: Exception) {
                // Игнорируем: если логгер не инициализирован, ничего не поделаешь.
            }

            // Передаём краш дефолтному обработчику (системный диалог + process death).
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}