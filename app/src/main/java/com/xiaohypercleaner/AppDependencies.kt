package com.xiaohypercleaner

import android.content.Context
import com.xiaohypercleaner.data.AdbClient
import com.xiaohypercleaner.data.AdbPortResolver
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ручной DI-контейнер приложения.
 * Инициализируется в XiaoHyperApp.onCreate().
 */
class AppDependencies(private val context: Context) {

    /** Менеджер предпочтений (DataStore) */
    val preferencesManager: PreferencesManager by lazy {
        AppLog.i("AppDeps", "creating PreferencesManager")
        PreferencesManager(context)
    }

    /** Резолвер портов ADB через mDNS */
    val portResolver: AdbPortResolver by lazy {
        AdbPortResolver(context)
    }

    /**
     * Создаёт OptimizationEngine с лучшим доступным исполнителем:
     * 1. Shizuku (если установлен, запущен и разрешён) — мгновенно, без Wi-Fi
     * 2. Иначе wireless ADB — требует Wi-Fi и цепочку разрешений
     */
    suspend fun newEngine(): OptimizationEngine = withContext(Dispatchers.IO) {
        return@withContext try {
            val status = ShizukuExecutor.checkStatus()
            if (status == ShizukuExecutor.Status.AVAILABLE) {
                AppLog.i("AppDeps", "newEngine: using Shizuku")
                OptimizationEngine(ShizukuExecutor())
            } else {
                AppLog.i("AppDeps", "newEngine: Shizuku=$status, falling back to ADB")
                OptimizationEngine(AdbClient(ports = portResolver.resolve()))
            }
        } catch (e: Exception) {
            AppLog.w("AppDeps", "newEngine: exception, fallback to ADB: ${e.message}")
            OptimizationEngine(AdbClient(ports = portResolver.resolve()))
        }
    }
}