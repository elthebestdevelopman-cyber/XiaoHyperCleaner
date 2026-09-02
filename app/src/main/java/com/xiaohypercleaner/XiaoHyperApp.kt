package com.xiaohypercleaner

import android.annotation.SuppressLint
import android.app.Application
import androidx.annotation.VisibleForTesting
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker

/**
 * Точка входа приложения XiaoHyperCleaner.
 *
 * ОТВЕТСТВЕННОСТЬ:
 * 1. Инициализация логгера (AppLog) и маскировщика (LogMasker)
 * 2. Создание DI-контейнера (AppDependencies)
 * 3. Глобальный обработчик крашей (UncaughtExceptionHandler)
 * 4. Предоставление синглтон-доступа к зависимостям
 *
 * АРХИТЕКТУРНЫЕ РЕШЕНИЯ:
 * - `instance` — синглтон на всё время процесса (Application не утекает)
 * - `deps` — DI-контейнер, созданный один раз при старте приложения
 * - `testDeps` — internal-поле для подмены зависимостей в unit-тестах
 *
 * ПОРЯДОК ИНИЦИАЛИЗАЦИИ (критично!):
 * 1. setupCrashHandler() — ловит краши даже при инициализации
 * 2. AppLog.init() — логгер должен быть первым
 * 3. LogMasker.init() — маскирует пути в логах
 * 4. logAppVersion() — версия для диагностики
 * 5. AppDependencies(this) — DI-контейнер
 *
 * УЛУЧШЕНИЯ:
 * 1. LogMasker.init() — маскировка путей к данным в логах
 * 2. TAG вынесен в companion object
 * 3. Логирование версии приложения для диагностики
 * 4. `testDeps` как internal с @VisibleForTesting — доступ из тестов и контроллеров
 * 5. Convenience методы для инъекции тестовых зависимостей
 */
class XiaoHyperApp : Application() {

    companion object {
        private const val TAG = "App"

        /**
         * Синглтон-ссылка на Application.
         *
         * Application — синглтон на всё время процесса, поэтому статическая
         * ссылка НЕ создаёт реальной утечки памяти (в отличие от Activity/Service).
         * Поле нужно для быстрого доступа из сервисов, receiver'ов и других
         * компонентов, где нет прямого доступа к Application.
         */
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: XiaoHyperApp
            private set

        /**
         * Тестовые зависимости для unit-тестов.
         *
         * Видимость `internal` — доступно только внутри модуля `app`
         * (для тестов и контроллеров типа ProFlowController).
         * В production-коде всегда `null` — используется `deps`.
         *
         * Аннотация @VisibleForTesting явно указывает назначение поля
         * и предупреждает Lint, если оно используется в production-коде.
         */
        @SuppressLint("StaticFieldLeak")
        @VisibleForTesting
        internal var testDeps: AppDependencies? = null

        /**
         * Инъекция тестовых зависимостей (convenience-метод).
         *
         * Используется в instrumentation-тестах для подмены реальных
         * зависимостей моками. Можно также присваивать `testDeps` напрямую.
         *
         * @param deps тестовые зависимости
         */
        fun injectTestDependencies(deps: AppDependencies) {
            testDeps = deps
        }

        /**
         * Возвращает тестовые зависимости, если они были инъектированы.
         * Convenience-метод — можно также читать `testDeps` напрямую.
         *
         * @return тестовые зависимости или null
         */
        fun getTestDependencies(): AppDependencies? = testDeps
    }

    /**
     * DI-контейнер приложения.
     *
     * Инициализируется в onCreate() один раз. Содержит все основные
     * зависимости: PreferencesManager, OptimizationEngine, SimpleRunner и др.
     */
    lateinit var deps: AppDependencies
        private set

    /**
     * Convenience-свойство для быстрого доступа к PreferencesManager.
     * Эквивалентно `deps.preferencesManager`.
     */
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
        // Если инъектированы тестовые зависимости — используем их (для unit-тестов).
        try {
            deps = testDeps ?: AppDependencies(this)
            AppLog.i(
                TAG,
                "AppDependencies created successfully" +
                        (if (testDeps != null) " (using test dependencies)" else "")
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create AppDependencies", e)
            throw e
        }

        AppLog.i(TAG, "=== XiaoHyperApp onCreate completed ===")
    }

    /**
     * Логирует версию приложения, версию кода и информацию о сборке.
     *
     * КРИТИЧНО ДЛЯ ДИАГНОСТИКИ: без этого невозможно понять, на какой версии
     * произошёл баг, когда пользователь присылает лог через shareLog().
     *
     * Пример лога:
     * ```
     * App version: 1.0-beta2 (code=2)
     * Package: com.xiaohypercleaner
     * ```
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
            // НОВОЕ (beta6): диагностика всех Xiaomi-пакетов для отладки пропусков
            val allXiaomiPackages = listOf(
                "com.android.browser", "com.mi.globalbrowser", "com.miui.browser",
                "com.miui.player", "com.miui.music", "com.android.music", "com.mi.music",
                "com.miui.mms", "com.android.mms", "com.miui.mms.global",
                "com.google.android.apps.messaging",
                "com.miui.securitycenter", "com.miui.securitycore",
                "com.android.providers.downloads.ui", "com.miui.android.downloads",
                "com.android.downloads",
                "com.android.thememanager", "com.miui.thememanager", "com.mi.thememanager",
                "com.xiaomi.market", "com.miui.market", "com.mi.global.market",
                "com.miui.videoplayer", "com.miui.video", "com.mi.global.video",
                "com.xiaomi.midrop", "com.mi.android.globalshareme",
                "com.mi.android.globalFileexplorer", "com.android.fileexplorer",
                "com.mi.android.fileexplorer",
                "com.miui.home", "com.mi.android.globallauncher", "com.android.launcher3",
                "com.miui.launcher", "com.mi.global.home",
                "com.miui.personalassistant", "com.mi.android.global.personalassistant",
                "com.android.personalassistant",
                "com.xiaomi.gamecenter", "com.miui.gamecenter", "com.xiaomi.glgm"
            )
            val installed = allXiaomiPackages.filter { pkg ->
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val notInstalled = allXiaomiPackages - installed
            AppLog.i(
                TAG,
                "Xiaomi packages installed (${installed.size}/${allXiaomiPackages.size}): ${
                    installed.joinToString(", ")
                }"
            )
            if (notInstalled.isNotEmpty()) {
                AppLog.i(TAG, "NOT installed: ${notInstalled.joinToString(", ")}")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to read app version: ${e.message}")
        }
    }

    /**
     * Глобальный обработчик необработанных исключений.
     *
     * Логирует краш через AppLog перед тем, как передать его дефолтному обработчику.
     * Это гарантирует, что даже необработанные краши попадут в лог и их можно
     * будет диагностировать через shareLog().
     *
     * ВАЖНО: AppLog.writeToFile() безопасно обрабатывает случай, когда writer == null,
     * поэтому краш до инициализации логгера не вызовет рекурсию.
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.e("CRASH", "Uncaught exception in thread '${thread.name}'", throwable)
                @Suppress("DEPRECATION") // Thread.id deprecated in Java 19+, threadId() requires Kotlin 1.9+
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