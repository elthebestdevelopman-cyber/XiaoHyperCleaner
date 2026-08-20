package com.xiaohypercleaner

import android.app.Application
import android.annotation.SuppressLint
import com.xiaohypercleaner.util.AppLog

class XiaoHyperApp : Application() {

    lateinit var deps: AppDependencies
        private set

    val preferencesManager get() = deps.preferencesManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Устанавливаем глобальный обработчик крашей ДО инициализации логгера
        setupCrashHandler()

        AppLog.init(this)
        AppLog.i("App", "XiaoHyperApp: onCreate started")

        try {
            deps = AppDependencies(this)
            AppLog.i("App", "XiaoHyperApp: deps created successfully")
        } catch (e: Exception) {
            AppLog.e("App", "XiaoHyperApp: failed to create deps", e)
            throw e
        }

        AppLog.i("App", "XiaoHyperApp: onCreate completed")
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.e("CRASH", "Uncaught exception in thread ${thread.name}", throwable)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        // Application — синглтон на всё время процесса, поэтому статическая
        // ссылка не создаёт реальной утечки памяти. Поля нужны для быстрого
        // доступа из сервисов и подмены зависимостей в тестах.
        @SuppressLint("StaticFieldLeak")
        lateinit var instance: XiaoHyperApp
            private set

        @SuppressLint("StaticFieldLeak")
        var testDeps: AppDependencies? = null
    }
}