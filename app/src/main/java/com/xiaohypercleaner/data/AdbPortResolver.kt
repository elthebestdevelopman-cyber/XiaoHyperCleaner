package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.xiaohypercleaner.AppConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Динамическое определение порта ADB через mDNS с фолбэком на стандартный порт.
 * Обнаружение прерывается досрочно после первого найденного сервиса.
 */
class AdbPortResolver(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortResolver"
        private const val SERVICE_TYPE = "_adb-tls._tcp."

        fun mergePorts(discovered: List<Int>, fallback: Int): List<Int> =
            (discovered + fallback).distinct()
    }

    fun resolve(): List<Int> = mergePorts(discoverMdns(), AppConstants.ADB_DEFAULT_PORT)

    private fun discoverMdns(): List<Int> {
        val found = mutableListOf<Int>()
        val latch = CountDownLatch(1)
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()

        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, error: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        synchronized(found) { found.add(s.port) }
                        runCatching { nsd.stopServiceDiscovery(listener) }
                        latch.countDown()
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(regType: String) {
                latch.countDown()
            }

            override fun onStartDiscoveryFailed(regType: String, error: Int) {
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(regType: String, error: Int) {
                latch.countDown()
            }
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(AppConstants.PORT_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            runCatching { nsd.stopServiceDiscovery(listener) }
            found
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery failed: ${e.message}")
            emptyList()
        }
    }
}