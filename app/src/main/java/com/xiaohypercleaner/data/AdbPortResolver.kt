package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Обнаруживает ADB порты через mDNS (multicast DNS).
 * Используется для автоматического поиска устройств с включенным wireless debugging.
 *
 * АРХИТЕКТУРА:
 * - resolve() — блокирующая версия для background threads
 * - resolveAsync() — неблокирующая версия для корутин
 * - Внутреннее кэширование на 30 секунд для избежания повторных запросов
 * - Поддержка нового (Android 11+) и legacy типов сервисов
 *
 * ИСПРАВЛЕНИЯ:
 * 1. 🔴 Убран GlobalScope.launch — теперь используется coroutineScope
 * 2. 🟡 Таймаут связан с lifecycle корутины через withTimeoutOrNull
 * 3. 🟡 Вынесена общая логика listener'а в helper-метод
 * 4. 🟢 Улучшена обработка ошибок и логирование
 */
class AdbPortResolver(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortResolver"
        private const val SERVICE_TYPE = "_adb-tls-connect._tcp."
        private const val SERVICE_TYPE_LEGACY = "_adb._tcp."

        /**
         * Объединяет обнаруженные порты с fallback-портом.
         * Используется для обеспечения наличия хотя бы одного порта для подключения.
         */
        fun mergePorts(discovered: List<Int>, fallback: Int): List<Int> =
            (discovered + fallback).distinct()
    }

    /** Кэш последних обнаруженных портов */
    private var cachedPorts: List<Int>? = null
    private var cacheTimestamp: Long = 0L
    private val cacheValidMs: Long = 30_000L // 30 секунд

    /**
     * Обнаруживает все доступные ADB порты через mDNS.
     * Блокирует поток на время поиска (используется в background thread).
     *
     * @return список обнаруженных портов (может быть пустым)
     */
    fun resolve(): List<Int> {
        // Проверяем кэш
        val cached = cachedPorts
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < cacheValidMs) {
            AppLog.i(TAG, "resolve: using cached ports: $cached")
            return cached
        }

        val discovered = discoverMdns()
        val result = mergePorts(discovered, AppConstants.ADB_DEFAULT_PORT)

        // Сохраняем в кэш
        cachedPorts = result
        cacheTimestamp = System.currentTimeMillis()

        return result
    }

    /**
     * Неблокирующая версия resolve() для использования в корутинах.
     * НЕ блокирует поток, использует suspendCancellableCoroutine.
     *
     * ИСПРАВЛЕНО: больше не использует GlobalScope для таймаута —
     * теперь таймаут связан с lifecycle корутины через withTimeoutOrNull.
     *
     * @return список обнаруженных портов (может быть пустым)
     */
    suspend fun resolveAsync(): List<Int> = withContext(Dispatchers.IO) {
        // Проверяем кэш
        val cached = cachedPorts
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < cacheValidMs) {
            AppLog.i(TAG, "resolveAsync: using cached ports: $cached")
            return@withContext cached
        }

        // ИСПРАВЛЕНО: используем withTimeoutOrNull вместо GlobalScope.launch
        val discovered = withTimeoutOrNull(AppConstants.PORT_DISCOVERY_TIMEOUT_MS) {
            discoverMdnsAsync()
        } ?: emptyList<Int>().also {
            AppLog.w(TAG, "resolveAsync: timeout after ${AppConstants.PORT_DISCOVERY_TIMEOUT_MS}ms")
        }

        val result = mergePorts(discovered, AppConstants.ADB_DEFAULT_PORT)

        // Сохраняем в кэш
        cachedPorts = result
        cacheTimestamp = System.currentTimeMillis()

        result
    }

    /**
     * Очищает кэш обнаруженных портов.
     * Вызывать перед повторным поиском, если нужны свежие данные.
     */
    fun clearCache() {
        cachedPorts = null
        cacheTimestamp = 0L
        AppLog.i(TAG, "clearCache: cache cleared")
    }

    /**
     * Внутренний метод для mDNS discovery (блокирующая версия).
     * Использует CountDownLatch для синхронного ожидания результатов.
     *
     * @return список обнаруженных портов
     */
    @Suppress("DEPRECATION")
    private fun discoverMdns(): List<Int> {
        val foundPorts = mutableListOf<Int>()
        val foundServices = mutableListOf<String>()
        val latch = CountDownLatch(1)

        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                AppLog.w(TAG, "discoverMdns: NSD_SERVICE not available")
                return emptyList()
            }

            val listener = createDiscoveryListener(
                nsdManager = nsdManager,
                foundPorts = foundPorts,
                foundServices = foundServices,
                onComplete = { latch.countDown() }
            )

            startDiscoveryWithFallback(nsdManager, listener)

            // Ждём завершения discovery
            val completed = latch.await(
                AppConstants.PORT_DISCOVERY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )

            if (!completed) {
                AppLog.w(
                    TAG,
                    "discoverMdns: timeout after ${AppConstants.PORT_DISCOVERY_TIMEOUT_MS}ms"
                )
            }

            // Останавливаем discovery
            stopDiscoverySafely(nsdManager, listener)

        } catch (e: Exception) {
            AppLog.e(TAG, "discoverMdns: exception: ${e.message}")
        }

        AppLog.i(TAG, "discoverMdns: found ${foundPorts.size} ports: $foundPorts")
        AppLog.i(TAG, "discoverMdns: services: $foundServices")
        return foundPorts
    }

    /**
     * Неблокирующая версия discoverMdns() для корутин.
     *
     * ИСПРАВЛЕНО: таймаут теперь управляется снаружи через withTimeoutOrNull,
     * что гарантирует корректную отмену при таймауте.
     */
    @Suppress("DEPRECATION")
    private suspend fun discoverMdnsAsync(): List<Int> = coroutineScope {
        suspendCancellableCoroutine { cont ->
            val foundPorts = mutableListOf<Int>()
            val foundServices = mutableListOf<String>()

            try {
                val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                if (nsdManager == null) {
                    AppLog.w(TAG, "discoverMdnsAsync: NSD_SERVICE not available")
                    if (cont.isActive) cont.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                val listener = createDiscoveryListener(
                    nsdManager = nsdManager,
                    foundPorts = foundPorts,
                    foundServices = foundServices,
                    onComplete = {
                        AppLog.i(TAG, "discoverMdnsAsync: completed with ${foundPorts.size} ports")
                        if (cont.isActive) {
                            cont.resume(foundPorts.toList())
                        }
                    }
                )

                // Обработка отмены корутины
                cont.invokeOnCancellation {
                    AppLog.i(TAG, "discoverMdnsAsync: coroutine cancelled, stopping discovery")
                    stopDiscoverySafely(nsdManager, listener)
                }

                startDiscoveryWithFallback(nsdManager, listener)

            } catch (e: Exception) {
                AppLog.e(TAG, "discoverMdnsAsync: exception: ${e.message}")
                if (cont.isActive) {
                    cont.resume(emptyList())
                }
            }
        }
    }

    /**
     * Создаёт listener для mDNS discovery.
     *
     * УНИВЕРСАЛЬНЫЙ: используется и в блокирующей (через onComplete → latch.countDown),
     * и в асинхронной (через onComplete → cont.resume) версиях.
     *
     * @param nsdManager NsdManager для resolveService
     * @param foundPorts список для накопления найденных портов (thread-safe через synchronized)
     * @param foundServices список для накопления имен сервисов
     * @param onComplete callback при завершении discovery (stopped/failed)
     */
    @Suppress("DEPRECATION") // resolveService deprecated in API 34, but still functional
    private fun createDiscoveryListener(
        nsdManager: NsdManager,
        foundPorts: MutableList<Int>,
        foundServices: MutableList<String>,
        onComplete: () -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AppLog.i(TAG, "listener: discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                AppLog.i(TAG, "listener: service found: ${service.serviceName}")

                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                        ) {
                            AppLog.w(
                                TAG,
                                "listener: resolve failed for ${serviceInfo.serviceName}, error=$errorCode"
                            )
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val port = serviceInfo.port
                            if (port > 0) {
                                synchronized(foundPorts) {
                                    foundPorts.add(port)
                                    foundServices.add(serviceInfo.serviceName)
                                }
                                AppLog.i(
                                    TAG,
                                    "listener: resolved ${serviceInfo.serviceName} on port $port"
                                )
                            } else {
                                AppLog.w(
                                    TAG,
                                    "listener: resolved ${serviceInfo.serviceName} but port=$port (invalid)"
                                )
                            }
                        }
                    })
                } catch (e: Exception) {
                    AppLog.e(
                        TAG,
                        "listener: exception resolving service: ${e.message}"
                    )
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                AppLog.i(TAG, "listener: service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.i(TAG, "listener: discovery stopped for $serviceType")
                onComplete()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.e(TAG, "listener: start discovery failed for $serviceType, error=$errorCode")
                onComplete()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.e(TAG, "listener: stop discovery failed for $serviceType, error=$errorCode")
                onComplete()
            }
        }
    }

    /**
     * Запускает discovery с fallback'ом на legacy тип сервиса.
     *
     * Логика:
     * 1. Пробуем новый тип (_adb-tls-connect._tcp.) — Android 11+
     * 2. Если не получилось — пробуем legacy (_adb._tcp.) — Android 10 и ниже
     * 3. Если оба не сработали — логируем ошибку (listener сам вызовет onComplete через onStartDiscoveryFailed)
     */
    private fun startDiscoveryWithFallback(
        nsdManager: NsdManager,
        listener: NsdManager.DiscoveryListener
    ) {
        try {
            AppLog.i(TAG, "startDiscovery: trying new service type: $SERVICE_TYPE")
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLog.w(TAG, "startDiscovery: new service type failed: ${e.message}, trying legacy")
            try {
                nsdManager.discoverServices(
                    SERVICE_TYPE_LEGACY,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (e2: Exception) {
                AppLog.e(TAG, "startDiscovery: legacy service type also failed: ${e2.message}")
                // listener.onStartDiscoveryFailed вызовет onComplete
            }
        }
    }

    /**
     * Безопасно останавливает discovery, игнорируя исключения.
     * Используется при таймауте, отмене корутины и нормальном завершении.
     */
    private fun stopDiscoverySafely(
        nsdManager: NsdManager,
        listener: NsdManager.DiscoveryListener
    ) {
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            AppLog.w(TAG, "stopDiscoverySafely: failed: ${e.message}")
        }
    }
}