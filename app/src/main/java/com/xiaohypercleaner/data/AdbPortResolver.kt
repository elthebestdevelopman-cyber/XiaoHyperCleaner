package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.xiaohypercleaner.AppConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbPortResolver(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortResolver"
        private const val SERVICE_TYPE = "_adb-tls._tcp."
    }

    fun resolve(): List<Int> {
        val ports = linkedSetOf<Int>()
        ports.addAll(discoverMdns())
        ports.add(AppConstants.ADB_DEFAULT_PORT)
        return ports.toList()
    }

    private fun discoverMdns(): List<Int> {
        val found = mutableListOf<Int>()
        val latch = CountDownLatch(1)
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, error: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        synchronized(found) { found.add(s.port) }
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