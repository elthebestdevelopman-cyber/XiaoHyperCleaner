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
 * Отвечает за создание и связывание всех основных компонентов:
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
     * Цепочка приоритетов (от самого лёгкого для пользователя к самому сложному):
     *
     * 1. Root (su) — мгновенно, ноль действий от пользователя.
     *    Если устройство рутировано — это лучший путь.
     *
     * 2. Shizuku — мгновенно, требует одну установку и запуск.
     *    Работает без Wi-Fi и без цепочек разрешений.
     *
     * 3. Wireless ADB — в последнюю очередь.
     *    Требует Wi-Fi и цепочку разрешений (accessibility → overlay → dev mode).
     *
     * Если все источники прав недоступны — fallback на wireless ADB,
     * пользователю будет предложена цепочка с карточками-подсказками.
     */
    suspend fun newEngine(): OptimizationEngine = withContext(Dispatchers.IO) {
        return@withContext try {
            // Приоритет 1: root — лучший путь если устройство рутировано
            if (RootExecutor.isAvailable()) {
                AppLog.i("AppDeps", "newEngine: using ROOT (best path, zero user actions)")
                return@withContext OptimizationEngine(RootExecutor())
            }
            AppLog.i("AppDeps", "newEngine: root not available")

            // Приоритет 2: Shizuku — быстрый путь без Wi-Fi
            val shizukuStatus = ShizukuExecutor.checkStatus(context)
            if (shizukuStatus == ShizukuExecutor.Status.AVAILABLE) {
                AppLog.i("AppDeps", "newEngine: using Shizuku (no Wi-Fi needed)")
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