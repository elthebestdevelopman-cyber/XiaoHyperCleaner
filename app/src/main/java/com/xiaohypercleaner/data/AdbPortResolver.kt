package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.xiaohypercleaner.AppConstants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Динамическое определение порта ADB через mDNS с фолбэком на стандартный порт.
 * Обнаружение прерывается досрочно после первого найденного сервиса.
 * Реализует корректную очистку ресурсов и обработку ошибок.
 */
class AdbPortResolver(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortResolver"
        private const val SERVICE_TYPE = "_adb-tls._tcp."
        private const val RESOLVE_TIMEOUT_MS = 5000L

        /**
         * Объединяет найденные порты с фолбэк-портом, удаляя дубликаты.
         * @param discovered список найденных портов
         * @param fallback порт по умолчанию
         * @return уникальный список портов
         */
        fun mergePorts(discovered: List<Int>, fallback: Int): List<Int> =
            (discovered + fallback).distinct()
    }

    /**
     * Разрешает порт ADB через mDNS discovery.
     * @return список портов (найденные + фолбэк)
     */
    fun resolve(): List<Int> = mergePorts(discoverMdns(), AppConstants.ADB_DEFAULT_PORT)

    private fun discoverMdns(): List<Int> {
        val found = mutableListOf<Int>()
        val latch = CountDownLatch(1)
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()

        var discoveryListener: NsdManager.DiscoveryListener? = null

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS discovery started for $regType")
            }
            
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS service found: ${service.serviceName}")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, error: Int) {
                        Log.w(TAG, "Resolve failed with error code: $error")
                        latch.countDown()
                    }
                    
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        Log.d(TAG, "mDNS service resolved: port=${s.port}")
                        synchronized(found) { found.add(s.port) }
                        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                        latch.countDown()
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS service lost: ${service.serviceName}")
            }
            
            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "mDNS discovery stopped")
                latch.countDown()
            }

            override fun onStartDiscoveryFailed(regType: String, error: Int) {
                Log.w(TAG, "mDNS start discovery failed with error: $error")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(regType: String, error: Int) {
                Log.w(TAG, "mDNS stop discovery failed with error: $error")
                latch.countDown()
            }
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            // Таймаут для ожидания результата discovery
            val discovered = if (latch.await(AppConstants.PORT_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                found
            } else {
                Log.w(TAG, "mDNS discovery timed out")
                emptyList()
            }
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            discovered
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery failed: ${e.message}")
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            emptyList()
        }
    }
}