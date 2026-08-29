package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Обнаруживает ADB порты через mDNS (multicast DNS).
 * Используется для автоматического поиска устройств с включенным wireless debugging.
 *
 * УЛУЧШЕНИЯ:
 * 1. resolveAsync() — неблокирующая версия для корутин
 * 2. Опциональное кэширование результатов
 * 3. Детальное логирование найденных сервисов
 * 4. Защита от повторного discovery
 */
@Suppress("DEPRECATION")
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

    /** Кэш последних обнаруженных портов (опционально) */
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
     * @return список обнаруженных портов (может быть пустым)
     */
    suspend fun resolveAsync(): List<Int> = withContext(Dispatchers.IO) {
        // Проверяем кэш
        val cached = cachedPorts
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < cacheValidMs) {
            AppLog.i(TAG, "resolveAsync: using cached ports: $cached")
            return@withContext cached
        }

        val discovered = discoverMdnsAsync()
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

            val listener = createDiscoveryListener(nsdManager, foundPorts, foundServices, latch)

            // Пробуем новый тип сервиса (Android 11+)
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                AppLog.w(TAG, "discoverMdns: new service type failed, trying legacy: ${e.message}")
                // Fallback на legacy тип
                try {
                    nsdManager.discoverServices(
                        SERVICE_TYPE_LEGACY,
                        NsdManager.PROTOCOL_DNS_SD,
                        listener
                    )
                } catch (e2: Exception) {
                    AppLog.e(TAG, "discoverMdns: legacy service type also failed: ${e2.message}")
                    latch.countDown()
                }
            }

            // Ждем завершения discovery
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
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                AppLog.w(TAG, "discoverMdns: stopServiceDiscovery failed: ${e.message}")
            }

        } catch (e: Exception) {
            AppLog.e(TAG, "discoverMdns: exception: ${e.message}")
        }

        AppLog.i(TAG, "discoverMdns: found ${foundPorts.size} ports: $foundPorts")
        AppLog.i(TAG, "discoverMdns: services: $foundServices")
        return foundPorts
    }

    /**
     * Неблокирующая версия discoverMdns() для корутин.
     */
    private suspend fun discoverMdnsAsync(): List<Int> = suspendCancellableCoroutine { cont ->
        val foundPorts = mutableListOf<Int>()
        val foundServices = mutableListOf<String>()

        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                AppLog.w(TAG, "discoverMdnsAsync: NSD_SERVICE not available")
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    AppLog.i(TAG, "discoverMdnsAsync: discovery started")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    AppLog.i(TAG, "discoverMdnsAsync: service found: ${service.serviceName}")

                    try {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int
                            ) {
                                AppLog.w(
                                    TAG,
                                    "discoverMdnsAsync: resolve failed for ${serviceInfo.serviceName}, error=$errorCode"
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
                                        "discoverMdnsAsync: resolved ${serviceInfo.serviceName} on port $port"
                                    )
                                }
                            }
                        })
                    } catch (e: Exception) {
                        AppLog.e(
                            TAG,
                            "discoverMdnsAsync: exception resolving service: ${e.message}"
                        )
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    AppLog.i(TAG, "discoverMdnsAsync: service lost: ${service.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    AppLog.i(TAG, "discoverMdnsAsync: discovery stopped")
                    if (cont.isActive) {
                        cont.resume(foundPorts.toList())
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AppLog.e(TAG, "discoverMdnsAsync: start discovery failed, error=$errorCode")
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AppLog.e(TAG, "discoverMdnsAsync: stop discovery failed, error=$errorCode")
                    if (cont.isActive) {
                        cont.resume(foundPorts.toList())
                    }
                }
            }

            // Обработка отмены корутины
            cont.invokeOnCancellation {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    AppLog.w(TAG, "discoverMdnsAsync: cancellation stop failed: ${e.message}")
                }
            }

            // Пробуем новый тип сервиса
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                AppLog.w(
                    TAG,
                    "discoverMdnsAsync: new service type failed, trying legacy: ${e.message}"
                )
                try {
                    nsdManager.discoverServices(
                        SERVICE_TYPE_LEGACY,
                        NsdManager.PROTOCOL_DNS_SD,
                        listener
                    )
                } catch (e2: Exception) {
                    AppLog.e(
                        TAG,
                        "discoverMdnsAsync: legacy service type also failed: ${e2.message}"
                    )
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }
            }

            // Таймаут через coroutine delay
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(AppConstants.PORT_DISCOVERY_TIMEOUT_MS)
                if (cont.isActive) {
                    AppLog.w(TAG, "discoverMdnsAsync: timeout, stopping discovery")
                    try {
                        nsdManager.stopServiceDiscovery(listener)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "discoverMdnsAsync: timeout stop failed: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            AppLog.e(TAG, "discoverMdnsAsync: exception: ${e.message}")
            if (cont.isActive) {
                cont.resume(emptyList())
            }
        }
    }

    /**
     * Создаёт listener для mDNS discovery.
     * Вынесен в отдельный метод для переиспользования.
     */
    private fun createDiscoveryListener(
        nsdManager: NsdManager,
        foundPorts: MutableList<Int>,
        foundServices: MutableList<String>,
        latch: CountDownLatch
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AppLog.i(TAG, "discoverMdns: discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                AppLog.i(TAG, "discoverMdns: service found: ${service.serviceName}")

                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                        ) {
                            AppLog.w(
                                TAG,
                                "discoverMdns: resolve failed for ${serviceInfo.serviceName}, error=$errorCode"
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
                                    "discoverMdns: resolved ${serviceInfo.serviceName} on port $port"
                                )
                            }
                        }
                    })
                } catch (e: Exception) {
                    AppLog.e(TAG, "discoverMdns: exception resolving service: ${e.message}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                AppLog.i(TAG, "discoverMdns: service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.i(TAG, "discoverMdns: discovery stopped")
                latch.countDown()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.e(TAG, "discoverMdns: start discovery failed, error=$errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.e(TAG, "discoverMdns: stop discovery failed, error=$errorCode")
                latch.countDown()
            }
        }
    }
}