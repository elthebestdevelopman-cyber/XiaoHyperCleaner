package com.xiaohypercleaner

import android.content.Context
import com.xiaohypercleaner.data.AdbClient
import com.xiaohypercleaner.data.AdbPortResolver
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.data.RootExecutor
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ручной DI-контейнер приложения.
 * Инициализируется в XiaoHyperApp.onCreate().
 *
 * Отвечает за создание и связывание всех основных компонентов приложения:
 * - PreferencesManager (хранилище настроек пользователя)
 * - AdbPortResolver (обнаружение портов wireless debugging через mDNS)
 * - OptimizationEngine (движок оптимизации с выбором лучшего исполнителя)
 */
class AppDependencies(private val context: Context) {

    /** Менеджер предпочтений (DataStore) для хранения настроек пользователя */
    val preferencesManager: PreferencesManager by lazy {
        AppLog.i("AppDeps", "creating PreferencesManager")
        PreferencesManager(context)
    }

    /** Резолвер портов ADB через mDNS — ищет wireless debugging в локальной сети */
    val portResolver: AdbPortResolver by lazy {
        AdbPortResolver(context)
    }

    /**
     * Создаёт OptimizationEngine с лучшим доступным исполнителем.
     *
     * Цепочка приоритетов (от лучшего к fallback):
     * 1. Root (su) — мгновенно, без Wi-Fi, без цепочек разрешений
     * 2. Shizuku (если установлен, запущен и разрешён) — мгновенно, без Wi-Fi
     * 3. Wireless ADB (fallback) — требует Wi-Fi и цепочку разрешений
     *
     * Если все источники прав недоступны — fallback на wireless ADB.
     * Пользователю будет предложено включить wireless debugging через цепочку.
     */
    suspend fun newEngine(): OptimizationEngine = withContext(Dispatchers.IO) {
        return@withContext try {
            // Приоритет 1: root — лучший путь если устройство рутировано
            if (RootExecutor.isAvailable()) {
                AppLog.i("AppDeps", "newEngine: using ROOT (best path)")
                return@withContext OptimizationEngine(RootExecutor())
            }

            // Приоритет 2: Shizuku — быстрый путь без Wi-Fi
            val shizukuStatus = ShizukuExecutor.checkStatus()
            if (shizukuStatus == ShizukuExecutor.Status.AVAILABLE) {
                AppLog.i("AppDeps", "newEngine: using Shizuku")
                return@withContext OptimizationEngine(ShizukuExecutor())
            }
            AppLog.i("AppDeps", "newEngine: Shizuku=$shizukuStatus, not available")

            // Приоритет 3: wireless ADB — fallback, требует Wi-Fi и цепочку разрешений
            AppLog.i("AppDeps", "newEngine: falling back to wireless ADB")
            val ports = portResolver.resolve()
            AppLog.i("AppDeps", "newEngine: resolved ports: $ports")
            OptimizationEngine(AdbClient(ports = ports))

        } catch (e: Exception) {
            AppLog.w("AppDeps", "newEngine: exception, fallback to ADB: ${e.message}")
            val ports = portResolver.resolve()
            OptimizationEngine(AdbClient(ports = ports))
        }
    }
}