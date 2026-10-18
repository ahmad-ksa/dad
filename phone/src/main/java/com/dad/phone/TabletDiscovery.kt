package com.dad.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Finds the tablet on the local network via NSD so the parent never types an IP.
 * Resolves to host:port and reports it through [onResolved].
 */
class TabletDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start(onResolved: (host: String, port: Int) -> Unit, onLost: () -> Unit) {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.contains(Protocol.SERVICE_NAME)) {
                    resolve(info, onResolved)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceName.contains(Protocol.SERVICE_NAME)) onLost()
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.e(TAG, "discoverServices failed", it) }
    }

    private fun resolve(info: NsdServiceInfo, onResolved: (String, Int) -> Unit) {
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "resolve failed: $errorCode")
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                onResolved(host, resolved.port)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsd.registerServiceInfoCallback(info, { it.run() }, object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val host = info.hostAddresses.firstOrNull()?.hostAddress ?: return
                    onResolved(host, info.port)
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, cb)
        }
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    companion object { private const val TAG = "TabletDiscovery" }
}
