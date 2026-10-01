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

    // NSD SELF-HEALING (2026-10-01): every registration/browse cycle uses FRESH listener instances,
    // tagged with a generation. NsdManager tears down a stopped listener asynchronously and refuses
    // to reuse an instance until then ("listener already in use"); reusing the same two listeners
    // across a restart made the new registration fail while the old one was still being torn
    // down, leaving the phone neither published nor browsing until the app restarted (reproduced
    // live on a Wi-Fi off/on: "browse start failed: listener already in use" followed 14 ms later
    // by "browsing stopped" + "service unpublished", then no reconnect for 8+ minutes). Callbacks
    // from an older generation are ignored; any failure or unexpected stop of the CURRENT
    // generation schedules a retry with backoff instead of giving up.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredGeneration = -1
    private var browsingGeneration = -1
    private var retryAttempt = 0
    private val retryRunnable = Runnable { restartNsd(reason = "retry", force = true) }
    private val deferredRestartRunnable = Runnable { restartNsd(reason = "deferred network change", force = true) }
    private val beginRunnable = Runnable { beginNsd() }

    private fun newRegistrationListener(gen: Int) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "DISCOVERY service published name=${info.serviceName}")
            onCycleHealthy(gen, registered = true)
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "DISCOVERY publish failed code=$errorCode")
            onCycleFailed(gen, "publish failed code=$errorCode")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "DISCOVERY service unpublished")
            onCycleFailed(gen, "service unpublished unexpectedly")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "DISCOVERY unpublish failed code=$errorCode")
        }
    }

    private fun newDiscoveryListener(gen: Int) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            Log.i(TAG, "DISCOVERY browsing started")
            onCycleHealthy(gen, registered = false)
        }
        override fun onDiscoveryStopped(type: String) {
            Log.i(TAG, "DISCOVERY browsing stopped")
            onCycleFailed(gen, "browsing stopped unexpectedly")
        }
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(TAG, "DISCOVERY browse start failed code=$errorCode")
            onCycleFailed(gen, "browse start failed code=$errorCode")
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
        retryAttempt = 0
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
        // registerNetworkCallback delivers an immediate onAvailable for a network that is already
        // up (the common cold-start case). Seeding the debounce window makes that callback defer to
        // the end of the window instead of tearing down this brand-new registration at once.
        lastRestartElapsedMs = SystemClock.elapsedRealtime()
        beginNsd()
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(deferredRestartRunnable)
        handler.removeCallbacks(beginRunnable)
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        tearDownCurrentCycle()
        runCatching { multicastLock?.release() }
        multicastLock = null
        found.clear()
        emitPeers()
    }

    /** Re-issues registration + browse against whatever network is current, with fresh listeners.
     * Network changes inside the debounce window are deferred to its end, never dropped. */
    @Synchronized
    private fun restartNsd(reason: String = "network available", force: Boolean = false) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastRestartElapsedMs
        if (!force && sinceLast < RESTART_DEBOUNCE_MS) {
            handler.removeCallbacks(deferredRestartRunnable)
            handler.postDelayed(deferredRestartRunnable, RESTART_DEBOUNCE_MS - sinceLast)
            return
        }
        lastRestartElapsedMs = now
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(deferredRestartRunnable)
        Log.i(TAG, "DISCOVERY restarting ($reason)")
        tearDownCurrentCycle()
        found.clear()
        emitPeers()
        // Fresh listeners can never collide with the old ones; the short pause only gives the
        // asynchronous unregister time to finish so the service keeps its exact mDNS name.
        handler.removeCallbacks(beginRunnable)
        handler.postDelayed(beginRunnable, REREGISTER_DELAY_MS)
    }

    /** Stops the current cycle. Bumping the generation first makes its stop callbacks "old". */
    private fun tearDownCurrentCycle() {
        generation++
        registrationListener?.let { listener -> runCatching { nsd.unregisterService(listener) } }
        discoveryListener?.let { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        registrationListener = null
        discoveryListener = null
    }

    @Synchronized
    private fun beginNsd() {
        if (!running) return
        tearDownCurrentCycle()
        val gen = generation
        val info = NsdServiceInfo().apply {
            serviceName = registeredServiceName
            serviceType = SERVICE_TYPE
            port = servicePort
            setAttribute("id", identity.deviceId)
            setAttribute("name", identity.deviceName.take(64))
            setAttribute("version", PROTOCOL_VERSION.toString())
            setAttribute("platform", "android")
        }
        val registration = newRegistrationListener(gen).also { registrationListener = it }
        val browse = newDiscoveryListener(gen).also { discoveryListener = it }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                Log.w(TAG, "DISCOVERY publish start failed: ${it.message}")
                onCycleFailed(gen, "publish start threw")
            }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, browse) }
            .onFailure {
                Log.w(TAG, "DISCOVERY browse start failed: ${it.message}")
                onCycleFailed(gen, "browse start threw")
            }
    }

    @Synchronized
    private fun onCycleHealthy(gen: Int, registered: Boolean) {
        if (gen != generation) return
        if (registered) registeredGeneration = gen else browsingGeneration = gen
        if (registeredGeneration == gen && browsingGeneration == gen) {
            retryAttempt = 0
            handler.removeCallbacks(retryRunnable)
        }
    }

    /** A failure or unexpected stop of the CURRENT cycle: never give up while running. */
    @Synchronized
    private fun onCycleFailed(gen: Int, reason: String) {
        if (!running || gen != generation) return
        val delay = discoveryRetryDelayMillis(retryAttempt++)
        Log.w(TAG, "DISCOVERY self-heal: $reason, retrying in ${delay}ms")
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
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
        private const val RESTART_DEBOUNCE_MS = 3_000L
        private const val REREGISTER_DELAY_MS = 750L
    }
}

data class LocalDiscoveryIdentity(val deviceId: String, val deviceName: String) {
    init { require(runCatching { UUID.fromString(deviceId) }.isSuccess) }
}

/** Backoff for re-establishing NSD after a failure: 2 s, 5 s, 15 s, 30 s, then every 60 s. */
internal fun discoveryRetryDelayMillis(attempt: Int): Long = when {
    attempt <= 0 -> 2_000L
    attempt == 1 -> 5_000L
    attempt == 2 -> 15_000L
    attempt == 3 -> 30_000L
    else -> 60_000L
}
