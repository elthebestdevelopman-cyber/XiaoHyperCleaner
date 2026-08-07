package com.xiaohypercleaner.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.xiaohypercleaner.AppConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

class AdbPortResolver(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortResolver"
        private const val SERVICE_TYPE = "_adb-tls._tcp."

        fun mergePorts(discovered: List<Int>, fallback: Int): List<Int> =
            (discovered + fallback).distinct()
    }

    suspend fun resolve(): List<Int> {
        val discovered = try {
            withTimeout(AppConstants.PORT_DISCOVERY_TIMEOUT_MS) { discoverMdns() }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed or timeout: ${e.message}")
            emptyList()
        }
        return mergePorts(discovered, AppConstants.ADB_DEFAULT_PORT)
    }

    private suspend fun discoverMdns(): List<Int> = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            cont.resume(emptyList()); return@suspendCancellableCoroutine
        }

        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, error: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        if (cont.isActive) runCatching { cont.resume(listOf(s.port)) }
                        runCatching { nsd.stopServiceDiscovery(listener) }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(regType: String) {
                if (cont.isActive) runCatching { cont.resume(emptyList()) }
            }

            override fun onStartDiscoveryFailed(regType: String, error: Int) {
                if (cont.isActive) runCatching { cont.resume(emptyList()) }
            }

            override fun onStopDiscoveryFailed(regType: String, error: Int) {}
        }

        cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices failed: ${e.message}")
            if (cont.isActive) runCatching { cont.resume(emptyList()) }
        }
    }
}