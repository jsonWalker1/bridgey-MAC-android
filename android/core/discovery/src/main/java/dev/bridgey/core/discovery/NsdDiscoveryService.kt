package dev.bridgey.core.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NsdDiscoveryService(
    context: Context,
    private var identity: LocalDiscoveryIdentity,
    private val servicePort: Int = DEFAULT_PORT,
) : DiscoveryService {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val found = ConcurrentHashMap<String, DiscoveredPeer>()
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = mutablePeers.asStateFlow()
    private var running = false
    private var lastRestartElapsedMs = 0L

    // mDNS browsing has no notion of "the network changed" — after a real interface swap (Wi-Fi
    // drop/reconnect, roam to a different network), NsdManager keeps browsing on its own internal
    // backoff schedule instead of immediately re-querying, which can leave a phone that regains
    // Wi-Fi in the background silently undiscoverable for many minutes until that schedule happens
    // to line up. Restarting discovery on every "network available" event forces an immediate
    // fresh query instead of waiting on that backoff.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "DISCOVERY network available, restarting discovery")
            restartNsd()
        }
    }

    // Without this, the Wi-Fi radio can silently drop incoming mDNS multicast packets to save
    // power (observed in practice: our own service registers fine, but the peer's advertisement
    // is never received — most reliably reproduced right after a device reboot or Wi-Fi
    // reassociation). NsdManager does not acquire this on the app's behalf.
    private var multicastLock: WifiManager.MulticastLock? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "DISCOVERY service published name=${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "DISCOVERY publish failed code=$errorCode")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "DISCOVERY service unpublished")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "DISCOVERY unpublish failed code=$errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            Log.i(TAG, "DISCOVERY browsing started")
        }
        override fun onDiscoveryStopped(type: String) {
            Log.i(TAG, "DISCOVERY browsing stopped")
        }
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(TAG, "DISCOVERY browse start failed code=$errorCode")
            running = false
        }
        override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(TAG, "DISCOVERY browse stop failed code=$errorCode")
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName == registeredServiceName) return
            resolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            found.remove(info.serviceName)
            emitPeers()
            Log.i(TAG, "DISCOVERY peer lost service=${info.serviceName}")
        }
    }

    private val registeredServiceName = "Bridgey-${identity.deviceId.take(8)}"

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        runCatching {
            wifiManager?.createMulticastLock("bridgey-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess { multicastLock = it }
            .onFailure { Log.w(TAG, "DISCOVERY could not acquire multicast lock: ${it.message}") }
        runCatching {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                networkCallback,
            )
        }.onFailure { Log.w(TAG, "DISCOVERY could not register network callback: ${it.message}") }
        beginNsd()
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        runCatching { nsd.unregisterService(registrationListener) }
        runCatching { multicastLock?.release() }
        multicastLock = null
        found.clear()
        emitPeers()
    }

    /** Re-issues registration + browse against whatever network is current. Safe to call while
     * already running (unlike [start], which is a one-time no-op guard). */
    @Synchronized
    private fun restartNsd() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartElapsedMs < 3_000) return
        lastRestartElapsedMs = now
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        runCatching { nsd.unregisterService(registrationListener) }
        found.clear()
        emitPeers()
        beginNsd()
    }

    private fun beginNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = registeredServiceName
            serviceType = SERVICE_TYPE
            port = servicePort
            setAttribute("id", identity.deviceId)
            setAttribute("name", identity.deviceName.take(64))
            setAttribute("version", PROTOCOL_VERSION.toString())
            setAttribute("platform", "android")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    @Synchronized
    fun updateDeviceName(value: String) {
        val name = value.trim().take(64).ifBlank { "Android device" }
        if (name == identity.deviceName) return
        val restart = running
        if (restart) stop()
        identity = identity.copy(deviceName = name)
        if (restart) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start() }, 300)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "DISCOVERY resolve failed code=$errorCode")
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = accept(serviceInfo)
        }
        // The legacy resolver is retained as the minSdk-compatible path. Calls are
        // serialized by NsdManager on supported releases and discovery hints remain untrusted.
        nsd.resolveService(info, listener)
    }

    private fun accept(info: NsdServiceInfo) {
        val attributes = info.attributes.mapValues { it.value ?: byteArrayOf() }
        val parsed = DiscoveryTxtRecord.parse(info.serviceName, attributes)
        val host = resolvedHost(info)
        found[parsed.key] = parsed.copy(host = host?.hostAddress, port = info.port.takeIf { it in 1..65535 })
        emitPeers()
        Log.i(TAG, "DISCOVERY peer discovered service=${info.serviceName}")
    }

    @Suppress("DEPRECATION")
    private fun resolvedHost(info: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host

    private fun emitPeers() {
        mutablePeers.value = found.values.sortedBy { it.deviceNameHint.lowercase() }
    }

    companion object {
        const val SERVICE_TYPE = "_bridgey._tcp."
        const val DEFAULT_PORT = 42_458
        const val PROTOCOL_VERSION = 1
        private const val TAG = "Bridgey"
    }
}

data class LocalDiscoveryIdentity(val deviceId: String, val deviceName: String) {
    init { require(runCatching { UUID.fromString(deviceId) }.isSuccess) }
}
