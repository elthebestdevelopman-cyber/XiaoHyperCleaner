package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Обнаруживает ADB порты через mDNS (multicast DNS).
 * Используется для автоматического поиска устройств с включенным wireless debugging.
 */
@Suppress("DEPRECATION")
class AdbPortResolver(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortResolver"
        private const val SERVICE_TYPE = "_adb._tcp."

        /**
         * Объединяет обнаруженные порты с fallback-портом.
         * Используется для обеспечения наличия хотя бы одного порта для подключения.
         */
        fun mergePorts(discovered: List<Int>, fallback: Int): List<Int> =
            (discovered + fallback).distinct()
    }

    /**
     * Обнаруживает все доступные ADB порты через mDNS.
     * Блокирует поток на время поиска (используется в background thread).
     *
     * @return список обнаруженных портов (может быть пустым)
     */
    fun resolve(): List<Int> = mergePorts(discoverMdns(), AppConstants.ADB_DEFAULT_PORT)

    /**
     * Внутренний метод для mDNS discovery.
     * Использует CountDownLatch для синхронного ожидания результатов.
     *
     * @return список обнаруженных портов
     */
    private fun discoverMdns(): List<Int> {
        val foundPorts = mutableListOf<Int>()
        val latch = CountDownLatch(1)

        try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                AppLog.w(TAG, "discoverMdns: NSD_SERVICE not available")
                return emptyList()
            }

            val listener = object : NsdManager.DiscoveryListener {
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

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

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
        return foundPorts
    }
}