package app.pixelbridge.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Finds Macs running PixelBridge on the local network via DNS-SD (Bonjour). */
class Discovery(context: Context, private val onChange: () -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val multicast = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("pixelbridge").apply { setReferenceCounted(false) }
    private val executor = Executors.newSingleThreadExecutor()

    /** Bonjour service name (the Mac's name) -> host/port. */
    val services: MutableMap<String, Pair<String, Int>> = ConcurrentHashMap()

    private var active = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discovery failed: $errorCode")
            synchronized(this@Discovery) { active = false }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
        override fun onServiceLost(info: NsdServiceInfo) {
            services.remove(info.serviceName)
        }
    }

    @Synchronized
    fun start() {
        if (active) return
        active = true
        multicast.acquire()
        try {
            nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            active = false
        }
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        if (multicast.isHeld) multicast.release()
    }

    private fun found(name: String, host: String?, port: Int) {
        if (host == null) return
        services[name] = host to port
        onChange()
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    val addr = si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull()
                    found(si.serviceName, addr?.hostAddress, si.port)
                    try { nsd.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            try { nsd.registerServiceInfoCallback(info, executor, cb) } catch (e: Exception) { Log.w(TAG, "resolve", e) }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    found(si.serviceName, si.host?.hostAddress, si.port)
                }
            })
        }
    }

    companion object { private const val TAG = "PixelBridge/NSD" }
}
