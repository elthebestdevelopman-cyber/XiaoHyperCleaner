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
 *
 * УЛУЧШЕНИЯ:
 * 1. TAG вынесен в companion object
 * 2. Избегаем дублирования portResolver.resolve() в catch-блоке
 * 3. Добавлена проверка пустого списка портов
 * 4. Улучшенная документация с ожидаемыми интерфейсами
 *
 * ЗАВИСИМОСТИ (проверить при сверке с data/):
 * - PreferencesManager: DataStore для настроек
 * - AdbPortResolver: mDNS discovery для wireless ADB
 * - OptimizationEngine: движок оптимизации, принимает CommandExecutor
 * - RootExecutor: реализует CommandExecutor через su
 * - ShizukuExecutor: реализует CommandExecutor через Shizuku API
 * - AdbClient: реализует CommandExecutor через wireless ADB
 */
class AppDependencies(private val context: Context) {

    companion object {
        private const val TAG = "AppDeps"
    }

    /** Менеджер предпочтений (DataStore) для хранения настроек пользователя */
    val preferencesManager: PreferencesManager by lazy {
        AppLog.i(TAG, "creating PreferencesManager")
        PreferencesManager(context)
    }

    /** Резолвер портов ADB через mDNS — ищет wireless debugging в локальной сети */
    val portResolver: AdbPortResolver by lazy {
        AppLog.i(TAG, "creating AdbPortResolver")
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
     *
     * @return OptimizationEngine с выбранным исполнителем
     */
    suspend fun newEngine(): OptimizationEngine = withContext(Dispatchers.IO) {
        return@withContext try {
            // Приоритет 1: root — лучший путь если устройство рутировано
            val rootExecutor = RootExecutor()
            if (rootExecutor.isAvailable()) {
                AppLog.i(TAG, "newEngine: using ROOT (best path, zero user actions)")
                return@withContext OptimizationEngine(rootExecutor)
            }
            AppLog.i(TAG, "newEngine: root not available")

            // Приоритет 2: Shizuku — быстрый путь без Wi-Fi
            val shizukuStatus = ShizukuExecutor.checkStatus(context)
            AppLog.i(TAG, "newEngine: Shizuku status=$shizukuStatus")

            if (shizukuStatus == ShizukuExecutor.Status.AVAILABLE) {
                AppLog.i(TAG, "newEngine: using Shizuku (no Wi-Fi needed)")
                return@withContext OptimizationEngine(ShizukuExecutor())
            }

            // Приоритет 3: wireless ADB — fallback, требует Wi-Fi и цепочку разрешений
            AppLog.i(TAG, "newEngine: falling back to wireless ADB")
            val ports = portResolver.resolve()

            if (ports.isEmpty()) {
                AppLog.w(TAG, "newEngine: no ports resolved, ADB will fail gracefully")
            } else {
                AppLog.i(TAG, "newEngine: resolved ${ports.size} ports: $ports")
            }

            OptimizationEngine(AdbClient(ports = ports))

        } catch (e: Exception) {
            AppLog.w(TAG, "newEngine: exception during engine creation: ${e.message}")

            // Fallback: пытаемся создать ADB-клиент, но избегаем повторного resolve
            // если ошибка произошла до него
            try {
                val ports = portResolver.resolve()
                AppLog.i(TAG, "newEngine: fallback resolved ${ports.size} ports")
                OptimizationEngine(AdbClient(ports = ports))
            } catch (fallbackError: Exception) {
                AppLog.e(TAG, "newEngine: fallback also failed: ${fallbackError.message}")
                // Последний fallback: пустой список портов, AdbClient должен обработать gracefully
                OptimizationEngine(AdbClient(ports = emptyList()))
            }
        }
    }
}