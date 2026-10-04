package dev.bridgey.android

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import org.json.JSONArray

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val peerName: String) : PairingState
    data class Verification(val peerName: String, val code: String) : PairingState
    data class Connected(val deviceId: String, val peerName: String) : PairingState
    data class Failed(val message: String) : PairingState
}

enum class ClipboardSendResult {
    DELIVERED,
    EMPTY,
    DISABLED,
    NOT_CONNECTED,
    CONNECTION_LOST,
    NO_ACKNOWLEDGEMENT,
    TOO_LARGE,
}

data class FileTransferState(
    val id: String,
    val name: String,
    val status: String,
    val active: Boolean,
    val progressPercent: Int?,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val retryable: Boolean = false,
)

data class TrustedDevice(val id: String, val name: String)

data class RemoteBatteryStatus(val level: Int, val isCharging: Boolean)

data class RemoteStorageStatus(val usedBytes: Long, val totalBytes: Long)

data class RemoteMemoryStatus(val usedBytes: Long, val totalBytes: Long)

sealed class RemoteCpuStatus {
    data class Available(val percent: Int) : RemoteCpuStatus()
    object Unavailable : RemoteCpuStatus()
}

sealed class RemoteTemperatureStatus {
    data class Known(val thermalState: String, val celsius: Int?) : RemoteTemperatureStatus()
    object Unavailable : RemoteTemperatureStatus()
}

class PairingCoordinator(
    context: Context,
    private val localDeviceId: String,
    localDeviceName: String,
    private val port: Int = 42_458,
    private val settings: BridgeySettings,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // BRIDGEY NOTIFICATION++ RECONCILIATION: notification messages are sent strictly in the order
    // they were issued (a plain Dispatchers.IO launch per message can reorder them). A
    // notifications.sync snapshot must never overtake a post issued after it, or the Mac would
    // remove a notification that is actually still live on Android.
    private val notificationSendDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bridgey-notification-send").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    // Whether notification forwarding was available on the current connected session the last time
    // it was evaluated - a false -> true transition (connect, reconnect, re-enable on either peer)
    // triggers one fresh reconciliation. See refreshNotificationForwardingAvailability().
    private var notificationForwardingAvailable = false
    val quickActions = QuickActions(appContext, scope, ::isFeatureAvailable, ::sendQuickPayload)
    val mediaRemote = MediaContinuityManager(
        appContext,
        available = { mutableState.value is PairingState.Connected && isFeatureAvailable(BridgeyFeature.MEDIA) },
        send = ::sendQuickPayload,
    )
    // M1: transport/security/lifecycle foundation only - no BridgeyFeature gate yet (that's M3),
    // no encoder/decoder/KVM consumer wired up yet (M2/M4/M5).
    internal val videoChannel = VideoChannelManager(
        available = { mutableState.value is PairingState.Connected },
        send = ::sendQuickPayload,
        pairingKeyProvider = { activeSession?.pairingKey },
        sessionIdProvider = { activeSession?.id },
        remoteHostProvider = { activeSession?.remoteHost },
    )
    // M2: adapts the verified screen-capture PoC onto the M1 video channel above.
    internal val screenCapture = ScreenCaptureManager(appContext, videoChannel)
    // Part 3 KVM POC: binds the same-frozen input channel to real Android input injection -
    // independent of screenCapture above (see KvmInputInjector's doc comment).
    internal val kvmInput = KvmInputInjector(appContext, videoChannel)
    private val mutableState = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = mutableState.asStateFlow()
    private val identity = AndroidIdentity(context.applicationContext)
    // MULTI-DEVICE CORE (DeviceCore.kt): trust + presence per deviceId and one independent
    // PeerSession per device. Nothing below the routing seam is limited to a single peer.
    private val registry = DeviceRegistry(
        AndroidTrustRegistry(context.applicationContext.getSharedPreferences("bridgey.trust", Context.MODE_PRIVATE)),
    )
    private val peers = PeerSessionManager<Session>(localDeviceId)
    val trustedDeviceIds: StateFlow<Set<String>> = registry.trustedDeviceIdsFlow
    val trustedDevices: StateFlow<List<TrustedDevice>> = registry.trustedDevicesFlow
    /**
     * COMPATIBILITY SEAM. Today's features are single-peer, so they use [activeSession]: the session
     * of the routed device ([DeviceRouting.activePeer]). This is routing only - inactive sessions
     * stay connected and authenticated and keep their own capabilities. Migrating a feature to
     * multi-device means replacing its `activeSession` with [send] (to: deviceId) /
     * `peers.connectedSession(deviceId)` and reading the sender with `peers.connectedDeviceId(session)`.
     */
    @Volatile private var activePeerId: String? = null
    private val activeSession: Session? get() = activePeerId?.let(peers::session)
    /** MD-1: per-device lifecycle events (session started/ended, authorization changed). */
    val peerLifecycle = PeerLifecycle()
    private val localDeviceType = LocalDevice.deviceTypeFor(appContext.resources.configuration.smallestScreenWidthDp)
    @Volatile private var authorizationSnapshot: Pair<Map<BridgeyFeature, Boolean>, Map<String, Map<BridgeyFeature, Boolean>>> =
        settings.state.value.globalFeatures to settings.state.value.deviceFeatures
    private val coreLock = Any()
    @Volatile private var running = false
    @Volatile private var failureMessage: String? = null
    /** Outgoing dials whose socket is not connected yet: key = target deviceId (or host:port). */
    private val outgoingDials = ConcurrentHashMap<String, String>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val anyPeerConnected = MutableStateFlow(false)
    @Volatile private var localDeviceName = localDeviceName
    private val mutableClipboardStatus = MutableStateFlow<String?>(null)
    val clipboardStatus: StateFlow<String?> = mutableClipboardStatus.asStateFlow()
    private val pendingClipboardSends = ConcurrentHashMap<String, (ClipboardSendResult) -> Unit>()
    private val pendingFileAccepts = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingFileCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutableFileTransferStatus = MutableStateFlow<String?>(null)
    val fileTransferStatus: StateFlow<String?> = mutableFileTransferStatus.asStateFlow()
    private val mutableFileTransferActive = MutableStateFlow(false)
    val fileTransferActive: StateFlow<Boolean> = mutableFileTransferActive.asStateFlow()
    private val mutableFileTransfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val fileTransfers: StateFlow<Map<String, FileTransferState>> = mutableFileTransfers.asStateFlow()
    private val mutablePhoneRinging = MutableStateFlow(false)
    val phoneRinging: StateFlow<Boolean> = mutablePhoneRinging.asStateFlow()
    private val mutableMacRinging = MutableStateFlow(false)
    val macRinging: StateFlow<Boolean> = mutableMacRinging.asStateFlow()
    private val mutableRemoteBattery = MutableStateFlow<RemoteBatteryStatus?>(null)
    val remoteBattery: StateFlow<RemoteBatteryStatus?> = mutableRemoteBattery.asStateFlow()
    private val mutableRemoteStorage = MutableStateFlow<RemoteStorageStatus?>(null)
    val remoteStorage: StateFlow<RemoteStorageStatus?> = mutableRemoteStorage.asStateFlow()
    private var lastSentStorage: LocalStorageStatus? = null
    private val mutableRemoteMemory = MutableStateFlow<RemoteMemoryStatus?>(null)
    val remoteMemory: StateFlow<RemoteMemoryStatus?> = mutableRemoteMemory.asStateFlow()
    private var lastSentMemory: LocalMemoryStatus? = null
    private val mutableRemoteCpu = MutableStateFlow<RemoteCpuStatus?>(null)
    val remoteCpu: StateFlow<RemoteCpuStatus?> = mutableRemoteCpu.asStateFlow()
    private val mutableRemoteTemperature = MutableStateFlow<RemoteTemperatureStatus?>(null)
    val remoteTemperature: StateFlow<RemoteTemperatureStatus?> = mutableRemoteTemperature.asStateFlow()
    private var previousCpuSample: CpuSample? = null
    // ALL telemetry (storage/memory/cpu) is on-demand only, battery-conscious: nothing is sampled or
    // sent in the background. Opening the app (main panel) subscribes; backgrounding it unsubscribes.
    // While subscribed, the peer resends all three every ~3s over the existing telemetry.update kind.
    private var remoteWantsTelemetryUpdates = false
    private var telemetrySamplingJob: Job? = null
    // Whether OUR OWN app is in the foreground wanting the peer's telemetry - survives reconnects
    // (unlike the two fields above, which are per-session) so completeIfConfirmed() can resubscribe.
    private var localWantsRemoteTelemetryUpdates = false
    private val mutablePingStatus = MutableStateFlow<String?>(null)
    val pingStatus: StateFlow<String?> = mutablePingStatus.asStateFlow()
    private var pendingPingId: String? = null
    // Advanced Screen Continuity - Remote Start: peer name of a trusted Mac's remote-start request
    // that still needs the user to complete Android's mandatory MediaProjection consent (Case B).
    // Non-null exactly while BridgeyConnectionService's notification is showing; cleared once the
    // user acts (MainActivity relaunch with EXTRA_REMOTE_START) or the request goes stale.
    private val mutableRemoteScreenShareRequest = MutableStateFlow<String?>(null)
    val remoteScreenShareRequest: StateFlow<String?> = mutableRemoteScreenShareRequest.asStateFlow()
    private var pendingRemoteStartRequestId: String? = null
    private val mutableRemoteFeatures = MutableStateFlow(defaultFeatureState())
    val remoteFeatures: StateFlow<Map<BridgeyFeature, Boolean>> = mutableRemoteFeatures.asStateFlow()
    private val incomingFiles = ConcurrentHashMap<String, IncomingFileTransfer>()
    private val cancelledTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val outgoingFileJobs = ConcurrentHashMap<String, Job>()
    private val outgoingFileSources = ConcurrentHashMap<String, Uri>()
    val deviceId: String get() = localDeviceId
    @Volatile private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var findRingtone: Ringtone? = null
    private val diagnostics = BridgeyDiagnostics()
    private val remoteCallRequest = RemoteCallRequest(appContext)
    private var lastRemoteCallRequestAt = 0L

    // Continuity Core reliability: without these, the CPU can suspend and the Wi-Fi radio can
    // enter power-save mode while the phone is screen-off/idle, both silently delaying the
    // heartbeat read-timeout well past its coded 30s/90s threshold (confirmed live during the
    // Continuity Core audit: a real session took 136s to detect instead of the coded 30s). Scoped
    // strictly to Connected via the state collector below so idle/disconnected periods cost
    // nothing - mirrors ScreenCaptureManager's existing wake-lock convention.
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private var connectionWakeLock: PowerManager.WakeLock? = null
    private var connectionWifiLock: WifiManager.WifiLock? = null
    private var networkCallbackRegistered = false
    private var lastNetworkLossHandledElapsedMs = 0L

    // Neither platform previously reacted to a network change for the *control* connection - only
    // Android's mDNS discovery layer did (NsdDiscoveryService's own NetworkCallback, scoped to
    // restarting discovery). This lets a genuine "the network is gone" signal close a likely-dead
    // session immediately instead of waiting out the full heartbeat timeout. Debounced the same way
    // NsdDiscoveryService.restartNsd() debounces its own NetworkCallback, for the same reason: avoid
    // reacting to bursts of transient callback churn. Reacts only to onLost (not the noisier
    // onCapabilitiesChanged) and only closes the socket - the existing read-loop exit/cleanup path
    // and reconnect backoff handle everything else unchanged.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            val now = SystemClock.elapsedRealtime()
            if (!shouldActOnNetworkLoss(lastNetworkLossHandledElapsedMs, now)) return
            lastNetworkLossHandledElapsedMs = now
            if (!anyPeerConnected.value) return
            android.util.Log.w("Bridgey", "CONNECTION network lost (ConnectivityManager.onLost) - closing sessions early")
            peers.identifiedSessions().forEach { it.close() }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireConnectionLocksIfNeeded() {
        if (connectionWakeLock == null) {
            runCatching {
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bridgey:Connection")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onSuccess { connectionWakeLock = it }
                .onFailure { android.util.Log.w("Bridgey", "CONNECTION wake lock acquire failed: ${it.message}") }
        }
        if (connectionWifiLock == null) {
            runCatching {
                wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Bridgey:Connection")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onSuccess { connectionWifiLock = it }
                .onFailure { android.util.Log.w("Bridgey", "CONNECTION wifi lock acquire failed: ${it.message}") }
        }
    }

    private fun releaseConnectionLocksIfNeeded() {
        connectionWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        connectionWakeLock = null
        connectionWifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        connectionWifiLock = null
    }

    init {
        mediaRemote.start()
        scope.launch {
            settings.state.collect {
                if (!featureEnabled(BridgeyFeature.CLIPBOARD)) mutableClipboardStatus.value = null
                if (!featureEnabled(BridgeyFeature.BATTERY)) mutableRemoteBattery.value = null
                if (!featureEnabled(BridgeyFeature.STORAGE)) mutableRemoteStorage.value = null
                if (!featureEnabled(BridgeyFeature.MEMORY)) mutableRemoteMemory.value = null
                if (!featureEnabled(BridgeyFeature.CPU)) mutableRemoteCpu.value = null
                if (!featureEnabled(BridgeyFeature.TEMPERATURE)) mutableRemoteTemperature.value = null
                if (!featureEnabled(BridgeyFeature.PING)) clearPingStatus()
                quickActions.policyChanged()
                mediaRemote.policyChanged()
                sendFeatureState()
                refreshNotificationForwardingAvailability()
                publishLocalStorage(force = true)
                publishLocalMemory(force = true)
                publishLocalTemperature()
                emitLocalAuthorizationChanges(it)
            }
        }
        // Connectivity locks follow the Core (any connected peer), not the routed feature peer.
        scope.launch {
            anyPeerConnected.collect { connected ->
                if (connected) acquireConnectionLocksIfNeeded() else releaseConnectionLocksIfNeeded()
            }
        }
        settings.migratePreferredDevice(registry.trustedDeviceIds())
        scope.launch {
            settings.state.map { it.deviceRoutingMode to it.preferredDeviceId }
                .distinctUntilChanged()
                .collect { recomputeActivePeer() }
        }
    }

    fun start() {
        if (acceptJob != null) return
        diagnostics.record("transport", "listener_started")
        if (!networkCallbackRegistered) {
            runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
                .onSuccess { networkCallbackRegistered = true }
                .onFailure { android.util.Log.w("Bridgey", "CONNECTION could not register network callback: ${it.message}") }
        }
        val listener = runCatching { ServerSocket(port) }.getOrElse {
            failureMessage = "Pairing listener failed"
            refreshState()
            return
        }
        server = listener
        running = true
        acceptJob = scope.launch {
            // Every accepted socket gets its own coroutine: one peer's session never blocks another.
            acceptConnections(listener, this, onFailure = {
                failureMessage = "Pairing listener failed"
                refreshState()
            }) { socket -> handle(socket, initiatedLocally = false, peerHint = null, expectedDeviceId = null, dialKey = null) }
        }
        connectTrustedPeersIfNeeded()
    }

    /** Discovery snapshot: presence is keyed by the advertised deviceId, never by service name/host. */
    fun onDiscovery(discovered: List<dev.bridgey.core.discovery.DiscoveredPeer>) {
        registry.updatePresence(DevicePresence.group(discovered, localDeviceId))
        connectTrustedPeersIfNeeded()
    }

    /**
     * User action on a discovered device: pairs a new device, or selects a trusted one as the
     * preferred device for today's single-peer features (dialling it only if it has no session).
     */
    fun pair(host: String, port: Int, peerName: String, deviceId: String? = null) {
        failureMessage = null
        if (deviceId != null && deviceId in registry.trustedDeviceIds()) {
            settings.setPreferredDevice(deviceId)
            if (peers.isBusy(deviceId) || outgoingDials.containsKey(deviceId)) {
                refreshState()
                return
            }
        }
        dial(host, port, peerName, deviceId)
    }

    /** Opens one outgoing session. Never touches any other device's session. */
    private fun dial(host: String, port: Int, peerName: String, expectedDeviceId: String?) {
        expectedDeviceId?.let { reconnectJobs.remove(it)?.cancel() }
        val dialKey = expectedDeviceId ?: "$host:$port"
        if (outgoingDials.putIfAbsent(dialKey, peerName) != null) return
        diagnostics.record("pairing", "connection_started")
        android.util.Log.i("Bridgey", "CONNECT attempting $host:$port peer=$peerName")
        refreshState()
        scope.launch {
            runCatching { connectWithTimeout(host, port) }
                .onSuccess {
                    android.util.Log.i("Bridgey", "CONNECT established $host:$port")
                    handle(it, initiatedLocally = true, peerHint = peerName, expectedDeviceId = expectedDeviceId, dialKey = dialKey)
                }
                .onFailure {
                    outgoingDials.remove(dialKey)
                    android.util.Log.w("Bridgey", "CONNECT failed $host:$port: ${it.javaClass.simpleName}: ${it.message}")
                    failureMessage = "Could not connect to $peerName"
                    diagnostics.record("protocol", "session_failed", "rejected")
                    if (expectedDeviceId != null && expectedDeviceId in registry.trustedDeviceIds()) scheduleReconnect(expectedDeviceId)
                    refreshState()
                }
        }
    }

    fun confirm() {
        scope.launch {
            val current = peers.verifyingSession() ?: return@launch
            confirm(current)
        }
    }

    /** Cancels the pairing being verified and clears a shown failure. Connected devices are unaffected. */
    fun cancel() {
        failureMessage = null
        val verifying = peers.verifyingSession()
        if (verifying != null) {
            // send() does a blocking socket write - callers include a Compose UI click handler.
            scope.launch {
                verifying.send(Message(kind = "pairing.cancel", sessionId = verifying.id))
                endSession(verifying, Reconnect.NONE)
            }
        }
        refreshState()
    }

    /** Disconnects the active device. Other sessions stay connected. */
    fun dismiss() {
        failureMessage = null
        val id = activePeerId
        val current = activeSession
        if (id == null || current == null) return refreshState()
        reconnectJobs.remove(id)?.cancel()
        reconnectAttempts.remove(id)
        endSession(current, Reconnect.NONE)
    }

    fun forget(deviceId: String) {
        registry.forget(deviceId)
        settings.removeDevice(deviceId)
        if (settings.state.value.preferredDeviceId == deviceId) settings.setPreferredDevice(null)
        reconnectJobs.remove(deviceId)?.cancel()
        reconnectAttempts.remove(deviceId)
        peers.session(deviceId)?.let { endSession(it, Reconnect.NONE) }
        android.util.Log.i("Bridgey", "PAIRING revoked peerId=${deviceId.take(8)}")
    }

    fun updateDeviceName(value: String) {
        localDeviceName = value.trim().take(64).ifBlank { "Android device" }
    }

    private fun featureEnabled(feature: BridgeyFeature, current: Session? = activeSession): Boolean =
        settings.isEnabled(feature, current?.remoteDeviceId?.takeIf(String::isNotEmpty))

    fun isFeatureAvailable(feature: BridgeyFeature): Boolean =
        effectiveFeatureAvailable(featureEnabled(feature), mutableRemoteFeatures.value[feature] != false)

    fun sendClipboard() {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(appContext)?.toString()
        if (text.isNullOrEmpty()) {
            mutableClipboardStatus.value = "Clipboard unavailable. Copy text, return to Bridgey, and try again."
        } else {
            sendClipboardContent(text, item.htmlText)
        }
    }

    fun sendText(text: String, onResult: (ClipboardSendResult) -> Unit = {}) =
        sendClipboardContent(text, html = null, onResult = onResult)

    private fun sendClipboardContent(
        text: String,
        html: String?,
        onResult: (ClipboardSendResult) -> Unit = {},
    ) {
        if (!isFeatureAvailable(BridgeyFeature.CLIPBOARD)) {
            mutableClipboardStatus.value = "Clipboard is turned off on one of your devices"
            onResult(ClipboardSendResult.DISABLED)
            return
        }
        if (text.isEmpty()) {
            mutableClipboardStatus.value = "Clipboard is empty"
            onResult(ClipboardSendResult.EMPTY)
            return
        }
        if (!clipboardTextFits(text)) {
            mutableClipboardStatus.value = "Clipboard exceeds 32 KiB. Send large text or diagnostics as a file."
            onResult(ClipboardSendResult.TOO_LARGE)
            return
        }
        val connectedSession = activeSession
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableClipboardStatus.value = "Not connected — clipboard was not sent"
            onResult(ClipboardSendResult.NOT_CONNECTED)
            return
        }
        scope.launch {
            val current = connectedSession
            if (activeSession !== current || mutableState.value !is PairingState.Connected) {
                mutableClipboardStatus.value = "Not connected — clipboard was not sent"
                onResult(ClipboardSendResult.NOT_CONNECTED)
                return@launch
            }
            val messageId = UUID.randomUUID().toString()
            pendingClipboardSends[messageId] = onResult
            // BRIDGEY CONNECTIVITY CRASH FIX (2026-09-25): see sendBattery's identical guard for why -
            // same TOCTOU race, same latent whole-process-crashing force-unwrap.
            val pairingKey = current.pairingKey ?: run {
                mutableClipboardStatus.value = "Not connected — clipboard was not sent"
                onResult(ClipboardSendResult.NOT_CONNECTED)
                return@launch
            }
            val richContent = RichClipboardContent.create(text, html)
            val plaintext = richContent?.encode() ?: text.toByteArray(Charsets.UTF_8)
            val encrypted = Crypto.encrypt(pairingKey, plaintext)
            val message = Message(
                kind = if (richContent == null) "clipboard.update" else "clipboard.rich",
                sessionId = current.id,
                messageId = messageId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            )
            if (!current.send(message)) {
                pendingClipboardSends.remove(messageId)?.invoke(ClipboardSendResult.CONNECTION_LOST)
                mutableClipboardStatus.value = "Connection lost"
                current.close()
                return@launch
            }
            mutableClipboardStatus.value = "Sending…"
            android.util.Log.i("Bridgey", "PLUGIN clipboard sent")
            delay(3_000)
            if (pendingClipboardSends.containsKey(messageId) && activeSession === current) {
                if (!current.send(message)) {
                    pendingClipboardSends.remove(messageId)?.invoke(ClipboardSendResult.CONNECTION_LOST)
                    mutableClipboardStatus.value = "Connection lost"
                    current.close()
                    return@launch
                }
                delay(3_000)
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "No delivery acknowledgement"
                    callback(ClipboardSendResult.NO_ACKNOWLEDGEMENT)
                }
            }
        }
    }

    fun sendBattery(level: Int, isCharging: Boolean) {
        if (!isFeatureAvailable(BridgeyFeature.BATTERY)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            // BRIDGEY CONNECTIVITY CRASH FIX (2026-09-25): pairingKey can go null on this SAME session
            // object between the check above and here (e.g. a disconnect racing this coroutine's
            // dispatch) even though `session !== connectedSession` still holds - force-unwrapping it
            // crashed the whole process (NullPointerException in sendBattery, confirmed via
            // AndroidRuntime FATAL EXCEPTION log), taking down BridgeyConnectionService with it and
            // breaking ALL connectivity, not just this one battery update. `?: return@launch` degrades
            // to silently skipping this update instead - exactly what sendQuickPayload/receiveQuickPayload
            // already do elsewhere in this file.
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject()
                .put("level", level.coerceIn(0, 100))
                .put("isCharging", isCharging)
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(pairingKey, payload)
            if (connectedSession.send(
                    Message(
                        kind = "battery.update",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                android.util.Log.i("Bridgey", "PLUGIN battery sent level=$level charging=$isCharging")
            }
        }
    }

    /** Mirrors [sendBattery]'s shape, but self-contained (no OS broadcast triggers storage checks)
     *  and change-gated by [STORAGE_CHANGE_THRESHOLD_BYTES] rather than exact equality, since raw
     *  byte counts churn constantly from routine cache/temp-file activity. */
    fun publishLocalStorage(force: Boolean = false) {
        if (!isFeatureAvailable(BridgeyFeature.STORAGE)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        val status = currentAndroidStorageStatus(appContext) ?: return
        val previous = lastSentStorage
        if (!force && previous != null &&
            status.totalBytes == previous.totalBytes &&
            kotlin.math.abs(status.usedBytes - previous.usedBytes) < STORAGE_CHANGE_THRESHOLD_BYTES
        ) {
            return
        }
        scope.launch {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject()
                .put("version", 1)
                .put("storageUsedBytes", status.usedBytes)
                .put("storageTotalBytes", status.totalBytes)
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(pairingKey, payload)
            if (connectedSession.send(
                    Message(
                        kind = "telemetry.update",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                lastSentStorage = status
                android.util.Log.i("Bridgey", "PLUGIN storage sent usedBytes=${status.usedBytes} totalBytes=${status.totalBytes}")
            }
        }
    }

    private fun resetRemoteMemoryState() {
        mutableRemoteMemory.value = null
        lastSentMemory = null
    }

    private fun resetTelemetrySubscriptionState() {
        mutableRemoteCpu.value = null
        previousCpuSample = null
        mutableRemoteTemperature.value = null
        remoteWantsTelemetryUpdates = false
        telemetrySamplingJob?.cancel()
        telemetrySamplingJob = null
    }

    /** Call when the app becomes visible (foreground). Battery-conscious by design: nothing is
     *  sampled or sent while backgrounded. Storage/memory/CPU/temperature are all refreshed
     *  together, every ~3s, only while the peer confirms someone is actually looking - each metric
     *  independently no-ops in its own publish function if its own Settings toggle is off. */
    fun requestRemoteTelemetryUpdates() {
        localWantsRemoteTelemetryUpdates = true
        sendTelemetrySubscription(subscribe = true)
    }

    /** Call when the app is backgrounded. */
    fun stopRequestingRemoteTelemetryUpdates() {
        localWantsRemoteTelemetryUpdates = false
        sendTelemetrySubscription(subscribe = false)
    }

    /** Not gated by any single telemetry feature - this just signals "my panel is open/closed";
     *  each metric's own publish function independently respects its own Settings toggle. */
    private fun sendTelemetrySubscription(subscribe: Boolean) {
        val current = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (activeSession !== current || mutableState.value !is PairingState.Connected) return@launch
            current.send(
                Message(
                    kind = if (subscribe) "telemetry.subscribe" else "telemetry.unsubscribe",
                    sessionId = current.id,
                ),
            )
        }
    }

    /** Peer's app came to the foreground and wants our telemetry - start the on-demand loop that
     *  resends storage/memory (dead-band gated, as always), CPU, and temperature (always, being
     *  rates/instant readings) every ~3s. Not gated here by any specific feature - each publish call
     *  below independently no-ops if its own Settings toggle is off. */
    private fun receiveTelemetrySubscribe(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        previousCpuSample = null
        remoteWantsTelemetryUpdates = true
        telemetrySamplingJob?.cancel()
        telemetrySamplingJob = scope.launch {
            while (remoteWantsTelemetryUpdates) {
                publishLocalStorage()
                publishLocalMemory()
                publishLocalCpu()
                publishLocalTemperature()
                delay(TELEMETRY_SAMPLING_INTERVAL_MILLIS)
            }
        }
    }

    /** Stops the loop only - does NOT clear the last-known storage/memory/CPU/temperature values,
     *  which stay visible (e.g. on the connected-device card) until the next real disconnect/reconnect. */
    private fun receiveTelemetryUnsubscribe(current: Session, message: Message) {
        if (message.sessionId != current.id) return
        remoteWantsTelemetryUpdates = false
        previousCpuSample = null
        telemetrySamplingJob?.cancel()
        telemetrySamplingJob = null
    }

    /** Reads one /proc/stat sample and, if a previous sample exists, sends the computed delta as
     *  `cpuPercent`. The first sample after a (re)subscribe only seeds the baseline - sending
     *  nothing that tick avoids a flash of "unavailable" before the second tick has a real delta.
     *  A read/parse failure, or a computation the sample math can't trust (rollover, zero elapsed
     *  time), sends explicit `cpuUnavailable: true` rather than a fabricated number. */
    private fun publishLocalCpu() {
        if (!isFeatureAvailable(BridgeyFeature.CPU)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        val sample = readProcStatCpuSample()
        val previous = previousCpuSample
        if (sample != null) previousCpuSample = sample
        val status: CpuStatus? = when {
            sample == null -> CpuStatus.Unavailable
            previous == null -> null // first sample this subscription: seed only, send nothing
            else -> computeCpuPercent(previous, sample)
        }
        if (status == null) return
        scope.launch {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject().put("version", 1)
            when (status) {
                is CpuStatus.Available -> payload.put("cpuPercent", status.percent)
                CpuStatus.Unavailable -> payload.put("cpuUnavailable", true)
            }
            val encrypted = Crypto.encrypt(pairingKey, payload.toString().toByteArray())
            connectedSession.send(
                Message(
                    kind = "telemetry.update",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                ),
            )
            android.util.Log.i("Bridgey", "PLUGIN cpu sent $status")
        }
    }

    /** Mirrors [publishLocalStorage]'s shape and cadence exactly - same background loop, same
     *  dead-band principle - just a second independent metric on the same [Message] kind. */
    fun publishLocalMemory(force: Boolean = false) {
        if (!isFeatureAvailable(BridgeyFeature.MEMORY)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        val status = currentAndroidMemoryStatus(appContext) ?: return
        val previous = lastSentMemory
        if (!force && previous != null &&
            status.totalBytes == previous.totalBytes &&
            kotlin.math.abs(status.usedBytes - previous.usedBytes) < MEMORY_CHANGE_THRESHOLD_BYTES
        ) {
            return
        }
        scope.launch {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject()
                .put("version", 1)
                .put("memoryUsedBytes", status.usedBytes)
                .put("memoryTotalBytes", status.totalBytes)
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(pairingKey, payload)
            if (connectedSession.send(
                    Message(
                        kind = "telemetry.update",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                lastSentMemory = status
                android.util.Log.i("Bridgey", "PLUGIN memory sent usedBytes=${status.usedBytes} totalBytes=${status.totalBytes}")
            }
        }
    }

    /** Thermal state is always sent when known (no dead-band - it rarely changes and is cheap to
     *  encode); the bonus real Celsius reading (best-effort, device-specific) rides along whenever
     *  it's available. An explicit unavailable state is sent rather than silence, matching CPU. */
    private fun publishLocalTemperature() {
        if (!isFeatureAvailable(BridgeyFeature.TEMPERATURE)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        val status = currentAndroidTemperatureStatus(appContext)
        scope.launch {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject().put("version", 1)
            when (status) {
                is TemperatureStatus.Known -> {
                    payload.put("thermalState", status.thermalState)
                    status.celsius?.let { payload.put("temperatureCelsius", it) }
                }
                TemperatureStatus.Unavailable -> payload.put("temperatureUnavailable", true)
            }
            val encrypted = Crypto.encrypt(pairingKey, payload.toString().toByteArray())
            connectedSession.send(
                Message(
                    kind = "telemetry.update",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                ),
            )
            android.util.Log.i("Bridgey", "PLUGIN temperature sent $status")
        }
    }

    fun sendPing() {
        val current = activeSession
        if (current == null || mutableState.value !is PairingState.Connected) {
            mutablePingStatus.value = "Mac is not connected"
            return
        }
        if (!isFeatureAvailable(BridgeyFeature.PING)) {
            mutablePingStatus.value = "Ping requires Bridgey 0.6 on both devices"
            return
        }
        val messageId = UUID.randomUUID().toString()
        val encrypted = Crypto.encrypt(current.pairingKey!!, JSONObject().put("version", 1).toString().toByteArray())
        pendingPingId = messageId
        mutablePingStatus.value = "Pinging Mac…"
        scope.launch {
            if (activeSession !== current || !current.send(
                    Message(
                        kind = "ping.request",
                        sessionId = current.id,
                        messageId = messageId,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                if (pendingPingId == messageId) {
                    pendingPingId = null
                    mutablePingStatus.value = "Ping could not be sent"
                }
                return@launch
            }
            delay(5_000)
            if (pendingPingId == messageId) {
                pendingPingId = null
                mutablePingStatus.value = "Mac did not acknowledge the ping"
            }
        }
    }

    private fun receivePing(current: Session, message: Message) {
        if (!featureEnabled(BridgeyFeature.PING, current)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted ping"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted ping"),
        ) ?: return failSession(current, "Invalid encrypted ping")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getInt("version") }.getOrNull() != 1) {
            return failSession(current, "Invalid ping")
        }
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, "Ping from ${current.peerName}", android.widget.Toast.LENGTH_SHORT).show()
            RingtoneManager.getRingtone(appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
        current.send(Message(kind = "ping.ack", sessionId = current.id, messageId = messageId))
        android.util.Log.i("Bridgey", "PLUGIN ping received")
    }

    /** Advanced Screen Continuity - Remote Start (Case A/B). `current` only ever reaches here after
     *  the full SAS-verified pairing handshake already completed (PairingState.Connected), and -
     *  like every other actionable command Bridgey supports (ping, find-device, ...) - the request
     *  must additionally carry an AES-GCM payload that decrypts with the session's pairingKey.
     *  `kind`/`sessionId`/`messageId` travel as plain JSON alongside the ciphertext and are NOT
     *  cryptographically bound to it, so requiring a successful decrypt (not just a sessionId match)
     *  is what actually proves the sender holds the shared secret from the real ECDH+SAS-verified
     *  handshake, rather than a LAN observer who merely saw/replayed those plaintext envelope fields. */
    private fun receiveRemoteScreenShareStart(current: Session, message: Message) {
        if (!featureEnabled(BridgeyFeature.REMOTE_SCREEN_SHARE, current)) {
            android.util.Log.w("Bridgey", "REMOTE_START rejected: feature disabled locally")
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted remote-start request"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted remote-start request"),
        ) ?: return failSession(current, "Invalid encrypted remote-start request")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getInt("version") }.getOrNull() != 1) {
            return failSession(current, "Invalid remote-start request")
        }
        android.util.Log.i("Bridgey", "REMOTE_START request received from trusted peer=${current.peerName}")
        if (screenCapture.isActive.value) {
            android.util.Log.i("Bridgey", "REMOTE_START: MediaProjection session already alive - resuming with no user interaction")
            current.send(Message(kind = "screenshare.remoteStartResult", sessionId = current.id, messageId = messageId, status = "already_active"))
            return
        }
        if (pendingRemoteStartRequestId != null) {
            android.util.Log.i("Bridgey", "REMOTE_START: a request is already pending user action - not showing a duplicate prompt")
            current.send(Message(kind = "screenshare.remoteStartResult", sessionId = current.id, messageId = messageId, status = "pending_user_action"))
            return
        }
        android.util.Log.i("Bridgey", "REMOTE_START: no active session - requesting minimum legitimate user interaction (notification + system consent)")
        pendingRemoteStartRequestId = messageId
        mutableRemoteScreenShareRequest.value = current.peerName
        current.send(Message(kind = "screenshare.remoteStartResult", sessionId = current.id, messageId = messageId, status = "needs_user_action"))
    }

    private fun receiveRemoteScreenShareStop(current: Session, message: Message) {
        if (!featureEnabled(BridgeyFeature.REMOTE_SCREEN_SHARE, current)) return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted remote-stop request"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted remote-stop request"),
        ) ?: return failSession(current, "Invalid encrypted remote-stop request")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getInt("version") }.getOrNull() != 1) {
            return failSession(current, "Invalid remote-stop request")
        }
        android.util.Log.i("Bridgey", "REMOTE_STOP request received from trusted peer=${current.peerName}, active=${screenCapture.isActive.value}")
        if (screenCapture.isActive.value) screenCapture.stop()
        clearRemoteScreenShareRequest()
    }

    /** BRIDGEY KVM KEYBOARD V1 (switch shortcut): the Mac's Command+K asking this phone to toggle its
     *  active keyboard to/from Bridgey's KVM Keyboard (see KvmKeyboardSwitcher.kt for the actual
     *  Settings.Secure write / picker fallback). No feature-negotiation gate (unlike Remote Screen
     *  Share above) and no reply sent back - fire-and-forget, same decrypt-proves-possession-of-
     *  pairingKey authentication as every other command. */
    private fun receiveSwitchKeyboard(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted switch-keyboard request"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted switch-keyboard request"),
        ) ?: return failSession(current, "Invalid encrypted switch-keyboard request")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getInt("version") }.getOrNull() != 1) {
            return failSession(current, "Invalid switch-keyboard request")
        }
        android.util.Log.i("Bridgey", "KVM switchKeyboard request received from trusted peer=${current.peerName}")
        KvmKeyboardSwitcher.toggle(appContext)
    }

    /** Called once the pending request has been resolved one way or another - the user acted on the
     *  notification (see MainActivity's EXTRA_REMOTE_START handling), the request went stale, or a
     *  remoteStop arrived. Also cancels the notification via BridgeyConnectionService's collector. */
    fun clearRemoteScreenShareRequest() {
        pendingRemoteStartRequestId = null
        mutableRemoteScreenShareRequest.value = null
    }

    private fun clearPingStatus() {
        pendingPingId = null
        mutablePingStatus.value = null
    }

    private fun sendQuickPayload(kind: String, payload: JSONObject): Boolean {
        val current = activeSession ?: return false
        val key = current.pairingKey ?: return false
        if (mutableState.value !is PairingState.Connected) return false
        val encrypted = Crypto.encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
        scope.launch {
            if (activeSession === current) current.send(Message(kind = kind, sessionId = current.id,
                messageId = UUID.randomUUID().toString(), nonce = encrypted.nonce, ciphertext = encrypted.ciphertext))
        }
        return true
    }

    private fun receiveQuickPayload(current: Session, message: Message) {
        if (activeSession !== current || mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val key = current.pairingKey ?: return
        val id = message.messageId ?: return
        if (!current.acceptMessageId(id)) return
        val data = Crypto.decrypt(key, message.nonce ?: return, message.ciphertext ?: return) ?: return
        if (data.size > if (message.kind == "media.state") 32768 else 8192) return
        val payload = runCatching { JSONObject(data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (message.kind == "quick.request") {
            val raw = payload.opt("sequence")
            if (raw !is Int && raw !is Long) return
            val sequence = (raw as Number).toLong()
            if (!current.quickSequence.accept(payload.optString("feature"), sequence)) return
        }
        quickActions.receive(message.kind, payload)
    }

    fun sendNotification(
        packageName: String,
        applicationName: String,
        notificationId: String,
        title: String,
        text: String,
        timestamp: Long,
        actions: List<ForwardedNotificationAction> = emptyList(),
        applicationIcon: String? = null,
        callType: String? = null,
        hasSound: Boolean = true,
        availableAudioRoutes: List<String>? = null,
        bluetoothRouteName: String? = null,
        resync: Boolean = false,
        conversationId: String? = null,
    ) {
        if (!isFeatureAvailable(BridgeyFeature.NOTIFICATIONS)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch(notificationSendDispatcher) {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            // BRIDGEY CONNECTIVITY CRASH FIX (2026-09-25): see sendBattery's identical guard for why.
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject()
                .put("packageName", packageName.take(256))
                .put("applicationName", applicationName.take(128))
                .put("notificationId", notificationId.take(512))
                .put("title", title.take(1_024))
                .put("text", text.take(8_192))
                .put("timestamp", timestamp)
                .put("hasSound", hasSound)
                .apply {
                    if (applicationIcon != null && applicationIcon.length <= MAX_NOTIFICATION_ICON_BASE64_LENGTH) {
                        put("applicationIcon", applicationIcon)
                    }
                    if (callType in setOf("incoming", "ongoing", "screening", "unknown")) {
                        put("callType", callType)
                    }
                    if (!availableAudioRoutes.isNullOrEmpty()) {
                        put("availableAudioRoutes", JSONArray(availableAudioRoutes.take(3)))
                    }
                    if (bluetoothRouteName != null) {
                        put("bluetoothRouteName", bluetoothRouteName.take(64))
                    }
                    if (resync) put("resync", true)
                    // TAP ROUTING POC: the app's own conversation id (Notification.shortcutId), generic
                    // for every app; the Mac decides whether it can map it to a native target.
                    if (!conversationId.isNullOrBlank() && conversationId.length <= 256) put("conversationId", conversationId)
                }
                .put("actions", JSONArray().apply {
                    actions.take(4).forEach { action ->
                        put(JSONObject()
                            .put("actionToken", action.token)
                            .put("title", action.title.take(64))
                            .put("allowsReply", action.allowsReply))
                    }
                })
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(pairingKey, payload)
            if (connectedSession.send(
                    Message(
                        kind = "notifications.post",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                android.util.Log.i("Bridgey", "PLUGIN notification sent package=$packageName")
            }
        }
    }

    fun sendNotificationRemoved(notificationId: String) {
        sendNotificationReference("notifications.remove", notificationId)
    }

    /**
     * BRIDGEY NOTIFICATION++ RECONCILIATION: sends the authoritative set of every notificationId
     * Android currently considers eligible (docs/protocol.md, `notifications.sync`), split into parts
     * of at most [MAX_NOTIFICATION_SYNC_IDS_PER_PART]. An empty list is sent as one empty part. Goes
     * through the same ordered notification send queue as posts and removes.
     */
    fun sendNotificationSync(notificationIds: List<String>) {
        if (!isFeatureAvailable(BridgeyFeature.NOTIFICATIONS)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        val syncId = UUID.randomUUID().toString()
        val parts = notificationSyncParts(notificationIds)
        scope.launch(notificationSendDispatcher) {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val pairingKey = connectedSession.pairingKey ?: return@launch
            parts.forEachIndexed { index, ids ->
                val payload = JSONObject()
                    .put("version", 1)
                    .put("syncId", syncId)
                    .put("part", index + 1)
                    .put("parts", parts.size)
                    .put("notificationIds", JSONArray(ids))
                    .toString()
                    .toByteArray()
                val encrypted = Crypto.encrypt(pairingKey, payload)
                connectedSession.send(
                    Message(
                        kind = "notifications.sync",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            }
            android.util.Log.i("Bridgey", "PLUGIN notification sync sent ids=${notificationIds.size} parts=${parts.size}")
        }
    }

    /**
     * Re-evaluates whether notification forwarding is usable on the current connected session and,
     * on a false -> true transition, asks the listener for one fresh reconciliation (silent resync
     * posts + `notifications.sync`). Called on connect, on every local settings change and on every
     * received `features.update`, so connect, reconnect and re-enabling the feature on either peer
     * all reconcile exactly once; nothing is replayed while it stays available.
     */
    private fun refreshNotificationForwardingAvailability(sessionStarted: Boolean = false) {
        val available = mutableState.value is PairingState.Connected && isFeatureAvailable(BridgeyFeature.NOTIFICATIONS)
        val becameAvailable = synchronized(this) {
            if (sessionStarted) notificationForwardingAvailable = false
            val transition = available && !notificationForwardingAvailable
            notificationForwardingAvailable = available
            transition
        }
        if (becameAvailable) BridgeyNotificationListenerService.requestReconciliation()
    }

    /**
     * Pushes a `calls.state` (v2) update. Nothing on Android currently calls this: the
     * incoming-call signal in production is still the notification-based callType path in
     * sendNotification, driven by BridgeyNotificationListenerService/CallsController. A prior
     * attempt to source this from a dedicated InCallService was reverted because Telecom will
     * not bind a non-UI InCallService for an app that lacks the privileged
     * `CONTROL_INCALL_EXPERIENCE` permission (see docs/architecture.md). This method and the
     * matching Mac-side handling are kept as a ready protocol for a future call-state source.
     */
    fun sendCallState(callId: String, state: String, callerName: String, callerNumber: String) {
        if (!isFeatureAvailable(BridgeyFeature.CALLS)) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            // BRIDGEY CONNECTIVITY CRASH FIX (2026-09-25): see sendBattery's identical guard for why.
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject()
                .put("version", 1)
                .put("callId", callId)
                .put("state", state)
                .put("callerName", callerName.take(128))
                .put("callerNumber", callerNumber.take(32))
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(pairingKey, payload)
            connectedSession.send(
                Message(
                    kind = "calls.state",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                ),
            )
            diagnostics.record("calls", "state_sent", outcome = state)
        }
    }

    private fun sendNotificationReference(kind: String, notificationId: String) {
        if (!isFeatureAvailable(BridgeyFeature.NOTIFICATIONS) || notificationId.isBlank()) return
        val connectedSession = activeSession ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch(notificationSendDispatcher) {
            if (activeSession !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            // BRIDGEY CONNECTIVITY CRASH FIX (2026-09-25): see sendBattery's identical guard for why.
            val pairingKey = connectedSession.pairingKey ?: return@launch
            val payload = JSONObject()
                .put("notificationId", notificationId.take(512))
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(pairingKey, payload)
            connectedSession.send(
                Message(
                    kind = kind,
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                ),
            )
        }
    }

    fun findMac() {
        if (!isFeatureAvailable(BridgeyFeature.FIND_DEVICE)) return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch { sendFindCommand("find.start") }
    }

    fun stopFinding() {
        stopPhoneRinging()
        scope.launch { sendFindCommand("find.stop") }
    }

    private fun sendFindCommand(kind: String): Boolean {
        val current = activeSession ?: return false
        if (kind == "find.start" && !isFeatureAvailable(BridgeyFeature.FIND_DEVICE)) return false
        if (mutableState.value !is PairingState.Connected) return false
        val payload = JSONObject().put("alertId", "active").toString().toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        return current.send(
            Message(
                kind = kind,
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveFindCommand(current: Session, message: Message, start: Boolean) {
        if (start && !settings.isEnabled(BridgeyFeature.FIND_DEVICE, current.remoteDeviceId)) return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted find-device message"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted find-device message"),
        ) ?: return failSession(current, "Invalid encrypted find-device message")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getString("alertId") }.isFailure) {
            return failSession(current, "Invalid find-device message")
        }
        if (start) {
            startPhoneRinging()
            sendFindCommand(if (mutablePhoneRinging.value) "find.started" else "find.stopped")
        } else {
            stopPhoneRinging()
            mutableMacRinging.value = false
            sendFindCommand("find.stopped")
        }
        android.util.Log.i("Bridgey", "PLUGIN find-device ${if (start) "started" else "stopped"}")
    }

    private fun startPhoneRinging() {
        if (mutablePhoneRinging.value) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = true
        findRingtone = ringtone
        mutablePhoneRinging.value = true
        ringtone.play()
    }

    private fun stopPhoneRinging() {
        runCatching { findRingtone?.stop() }
        findRingtone = null
        mutablePhoneRinging.value = false
    }

    fun sendFile(uri: Uri, assetKey: String? = null, onResult: ((Boolean) -> Unit)? = null) {
        val feature = if (assetKey != null) BridgeyFeature.PHOTO_SYNC else BridgeyFeature.FILES
        if (!isFeatureAvailable(feature)) {
            mutableFileTransferStatus.value = "File transfer is turned off on one of your devices"
            onResult?.invoke(false)
            return
        }
        val connectedSession = activeSession
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableFileTransferStatus.value = "Not connected — file was not sent"
            onResult?.invoke(false)
            return
        }
        val transferId = UUID.randomUUID().toString()
        outgoingFileSources[transferId] = uri
        diagnostics.record("transfer", "send_started")
        updateFileTransfer(transferId, "Selected file", "Preparing…", true)
        var succeeded = false
        val job = scope.launch {
            val resolver = appContext.contentResolver
            val metadata = runCatching {
                var name = "file"
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                    }
                }
                Triple(name.take(255), resolver.getType(uri) ?: "application/octet-stream", size)
            }.getOrElse {
                updateFileTransfer(transferId, "Selected file", "Could not read the selected file", false)
                return@launch
            }
            if (metadata.third < 0) {
                mutableFileTransferStatus.value = "This file provider did not report a file size"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }
            if (metadata.third > MAX_FILE_SIZE) {
                mutableFileTransferStatus.value = "File is larger than 10 GB"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }

            updateFileTransfer(transferId, metadata.first, "Preparing ${metadata.first}…", true)
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } ?: error("File unavailable")
                Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            }.getOrElse {
                mutableFileTransferStatus.value = "Could not read the selected file"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }

            val offerPayload = JSONObject()
                .put("transferId", transferId)
                .put("name", metadata.first)
                .put("mimeType", metadata.second)
                .put("size", metadata.third)
                .put("sha256", hash)
                .apply { if (assetKey != null) put("assetKey", assetKey) }
                .toString().toByteArray()
            // BRIDGEY CONNECTIVITY CRASH FIX (2026-09-25): see sendBattery's identical guard for why -
            // unlike the chunk-send/completion encrypts further below (already inside a runCatching
            // that turns a failure into a clean "transfer failed" outcome), this one was unguarded and
            // could crash the whole process exactly like sendBattery did.
            val pairingKey = connectedSession.pairingKey ?: run {
                updateFileTransfer(transferId, metadata.first, "Connection lost — file was not sent", false)
                return@launch
            }
            val offer = Crypto.encrypt(pairingKey, offerPayload)
            val accepted = CompletableDeferred<Boolean>()
            pendingFileAccepts[transferId] = accepted
            if (!connectedSession.send(Message(
                    kind = "files.offer",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    transferId = transferId,
                    nonce = offer.nonce,
                    ciphertext = offer.ciphertext,
                ))) {
                pendingFileAccepts.remove(transferId)
                mutableFileTransferStatus.value = "Connection lost — file was not sent"
                updateFileTransfer(transferId, metadata.first, mutableFileTransferStatus.value!!, false)
                return@launch
            }
            updateFileTransfer(transferId, metadata.first, "Waiting for Mac…", true)
            if (withTimeoutOrNull(10_000) { accepted.await() } != true) {
                pendingFileAccepts.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, metadata.first, "Mac did not accept the file", false)
                return@launch
            }

            val completed = CompletableDeferred<Boolean>()
            pendingFileCompletions[transferId] = completed
            val sent = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    var total = 0L
                    var sequence = 0L
                    val progress = TransferProgress(metadata.third)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, buffer.copyOf(count))
                        check(connectedSession.send(Message(
                            kind = "files.chunk",
                            sessionId = connectedSession.id,
                            messageId = UUID.randomUUID().toString(),
                            transferId = transferId,
                            sequence = sequence++,
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext,
                        )))
                        total += count
                        progress.status(total)?.let {
                            updateFileTransfer(transferId, metadata.first, "Sending ${metadata.first}: $it", true)
                        }
                    }
                    updateFileTransfer(transferId, metadata.first, "Verifying ${metadata.first} on Mac…", true)
                } ?: error("File unavailable")
                val completionPayload = Crypto.encrypt(
                    connectedSession.pairingKey!!,
                    JSONObject().put("transferId", transferId).put("sha256", hash).toString().toByteArray(),
                )
                check(connectedSession.send(Message(
                    kind = "files.complete",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = completionPayload.nonce,
                    ciphertext = completionPayload.ciphertext,
                )))
            }.isSuccess
            if (!sent) {
                pendingFileCompletions.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, metadata.first, "File transfer failed", false)
                return@launch
            }
            if (withTimeoutOrNull(15_000) { completed.await() } == true) {
                outgoingFileSources.remove(transferId)
                updateFileTransfer(transferId, metadata.first, "${metadata.first} saved on Mac", false)
                diagnostics.record("transfer", "send_completed")
                succeeded = true
            } else {
                pendingFileCompletions.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, metadata.first, "Mac did not confirm the saved file", false)
            }
        }
        outgoingFileJobs[transferId] = job
        job.invokeOnCompletion { outgoingFileJobs.remove(transferId, job); onResult?.invoke(succeeded) }
    }

    fun sendSyncAsset(uri: Uri, assetKey: String, onResult: (Boolean) -> Unit) {
        sendFile(uri, assetKey = assetKey, onResult = onResult)
    }

    fun cancelFileTransfer(transferId: String) {
        val current = activeSession
        markTransferCancelled(transferId)
        scope.launch {
            repeat(3) { attempt ->
                if (activeSession === current) current?.send(Message(kind = "files.cancel", sessionId = current.id, transferId = transferId))
                if (attempt < 2) delay(250)
            }
        }
        outgoingFileJobs.remove(transferId)?.cancel()
        incomingFiles.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        val name = mutableFileTransfers.value[transferId]?.name ?: "File"
        removeFileTransfer(transferId, "Transfer cancelled")
        diagnostics.record("transfer", "cancelled")
    }

    fun cancelFileTransfer() = mutableFileTransfers.value.values.filter { it.active }.forEach { cancelFileTransfer(it.id) }

    fun retryFileTransfer(transferId: String) {
        val uri = outgoingFileSources[transferId] ?: return
        if (activeSession == null || mutableState.value !is PairingState.Connected) {
            removeFileTransfer(transferId, "Reconnect before retrying")
            return
        }
        if (!isFeatureAvailable(BridgeyFeature.FILES)) {
            removeFileTransfer(transferId, "File transfer is turned off on one of your devices")
            return
        }
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply { remove(transferId) }
        outgoingFileSources.remove(transferId)
        diagnostics.record("transfer", "retry_started")
        sendFile(uri)
    }

    fun diagnosticsReport(): String {
        val stateName = when (mutableState.value) {
            PairingState.Idle -> "idle"
            is PairingState.Connecting -> "connecting"
            is PairingState.Verification -> "verification"
            is PairingState.Connected -> "connected"
            is PairingState.Failed -> "failed"
        }
        val localFeatures = BridgeyFeature.entries.associateWith { settings.isEnabled(it, null) }
        return diagnostics.report(appContext, stateName, mutableFileTransfers.value.values, localFeatures, mutableRemoteFeatures.value)
    }

    fun clearTransferHistory() {
        val inactiveIds = mutableFileTransfers.value.values.filterNot(FileTransferState::active).map(FileTransferState::id)
        inactiveIds.forEach(outgoingFileSources::remove)
        mutableFileTransfers.value = mutableFileTransfers.value.filterValues(FileTransferState::active)
        refreshFileTransferSummary()
    }

    fun stop() {
        pause()
        scope.cancel()
    }

    fun pause() {
        running = false
        reconnectJobs.values.forEach(Job::cancel)
        reconnectJobs.clear()
        (peers.identifiedSessions() + peers.pendingSessions()).forEach { endSession(it, Reconnect.NONE) }
        failureMessage = null
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteBattery.value = null
        mutableRemoteStorage.value = null
        lastSentStorage = null
        resetRemoteMemoryState()
        resetTelemetrySubscriptionState()
        clearPingStatus()
        quickActions.reset()
        mediaRemote.reset()
        videoChannel.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        server?.close()
        server = null
        acceptJob?.cancel()
        acceptJob = null
        interruptFeatureTransfers()
        refreshState()
    }

    /** Ends every in-flight clipboard/file exchange of the single-peer feature layer. */
    private fun interruptFeatureTransfers() {
        pendingClipboardSends.values.forEach { it(ClipboardSendResult.NOT_CONNECTED) }
        pendingClipboardSends.clear()
        pendingFileAccepts.values.forEach { it.complete(false) }
        pendingFileAccepts.clear()
        pendingFileCompletions.values.forEach { it.complete(false) }
        pendingFileCompletions.clear()
        incomingFiles.values.forEach(IncomingFileTransfer::cancel)
        incomingFiles.clear()
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
    }

    private fun handle(socket: Socket, initiatedLocally: Boolean, peerHint: String?, expectedDeviceId: String?, dialKey: String?) {
        // A socket is pending until it says which device it is; duplicate protection is then applied
        // per deviceId (identify), so it can never displace another device's session.
        socket.keepAlive = true
        socket.tcpNoDelay = true
        socket.soTimeout = HEARTBEAT_INTERVAL_MILLIS.toInt()
        val current = Session(socket, SessionWriter(scope))
        current.initiatedLocally = initiatedLocally
        current.expectedDeviceId = expectedDeviceId
        dialKey?.let(outgoingDials::remove)
        if (!running || peers.pendingSessions().size >= MAX_PENDING_SESSIONS) {
            android.util.Log.w("Bridgey", "CONNECT refusing connection (running=$running)")
            current.close()
            return
        }
        peers.addPending(current, initiatedLocally, expectedDeviceId)
        if (initiatedLocally) {
            current.peerName = peerHint ?: "Bridgey device"
            current.keyPair = Crypto.generateKeyPair()
            current.localEphemeralKey = Crypto.encodePublicKey(current.keyPair!!)
            current.id = UUID.randomUUID().toString()
            current.send(
                Message(
                    kind = "pairing.offer",
                    sessionId = current.id,
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    publicKey = current.localEphemeralKey,
                ),
            )
        }
        val result = runCatching {
            while (true) {
                try {
                    val line = current.input.readProtocolLine()
                    if (line == null) {
                        android.util.Log.w("Bridgey", "CONNECTION read loop: stream ended (readProtocolLine returned null)")
                        break
                    }
                    current.lastReceivedAtMillis = SystemClock.elapsedRealtime()
                    receive(current, Message.decode(line))
                } catch (_: SocketTimeoutException) {
                    if (!peers.contains(current)) break
                    val sinceLastReceivedMs = SystemClock.elapsedRealtime() - current.lastReceivedAtMillis
                    // A socket that never finishes its handshake must not linger as a pending session
                    // now that sessions no longer replace each other (verification waits for the user).
                    // Measured from socket creation, so traffic can never hold off the deadline.
                    val phase = peers.phase(current)
                    if (phase == null || phase == PeerSessionPhase.CONNECTING) {
                        if (SystemClock.elapsedRealtime() - current.createdAtMillis > HANDSHAKE_TIMEOUT_MILLIS) {
                            android.util.Log.w("Bridgey", "TRANSPORT handshake timed out")
                            break
                        }
                    }
                    // Per-session heartbeat: a timeout ends only this device's session.
                    if (phase == PeerSessionPhase.CONNECTED) {
                        if (heartbeatExpired(
                                supported = current.heartbeatSupported,
                                lastReceivedAtMillis = current.lastReceivedAtMillis,
                                nowMillis = SystemClock.elapsedRealtime(),
                            )
                        ) {
                            android.util.Log.w(
                                "Bridgey",
                                "CONNECTION lost: heartbeat timed out (sinceLastReceivedMs=$sinceLastReceivedMs)",
                            )
                            break
                        }
                        if (!current.send(Message(
                                kind = "heartbeat.ping",
                                sessionId = current.id,
                                messageId = UUID.randomUUID().toString(),
                            ))
                        ) {
                            android.util.Log.w("Bridgey", "CONNECTION lost: heartbeat.ping send failed")
                            break
                        }
                    }
                }
            }
        }
        result.exceptionOrNull()?.let {
            android.util.Log.w("Bridgey", "CONNECTION read loop ended with exception: ${it.javaClass.simpleName}: ${it.message}")
        }
        if (!peers.contains(current)) return // already ended (duplicate, displaced, forgotten, paused)
        if (peers.phase(current) == PeerSessionPhase.CONNECTED) {
            current.deviceIdForLog().let { android.util.Log.i("Bridgey", "CONNECTION lost: clean disconnect after being connected peer=$it") }
            diagnostics.record("transport", "disconnected", "reconnecting")
            peers.deviceId(current)?.let(reconnectAttempts::remove)
            endSession(current, Reconnect.IMMEDIATE)
        } else {
            val reason = result.exceptionOrNull()?.let { "disconnected before confirmation: ${it.message}" }
                ?: "disconnected before confirmation"
            android.util.Log.w("Bridgey", "CONNECTION lost: $reason")
            failSession(current, "Pairing connection lost")
        }
    }

    private enum class Reconnect { NONE, IMMEDIATE, BACKOFF }

    /**
     * Ends exactly one session. Other devices' sessions are untouched; single-peer feature state is
     * reset only when this was the routed (active) session.
     */
    private fun endSession(current: Session, reconnect: Reconnect) {
        val wasActive = current === activeSession
        // Lifecycle is bound to this session object: only the session that was started ends its
        // device (a failed pending dial or an older session never ends a newer one).
        val identifiedDeviceId = peers.deviceId(current)
        val deviceId = peers.remove(current)
        current.close()
        if (wasActive) {
            cancelIncomingFiles()
            mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        }
        recomputeActivePeer()
        identifiedDeviceId?.let { peerLifecycle.sessionEnded(it, current) }
        if (deviceId == null || deviceId !in registry.trustedDeviceIds()) return
        when (reconnect) {
            Reconnect.NONE -> Unit
            Reconnect.IMMEDIATE -> connectTrustedPeersIfNeeded()
            Reconnect.BACKOFF -> scheduleReconnect(deviceId)
        }
    }

    /** Fails one session (handshake or protocol error) and backs off reconnecting to that device only. */
    private fun failSession(current: Session, message: String) {
        android.util.Log.w("Bridgey", "PAIRING failed: $message (peer=${current.deviceIdForLog()})")
        failureMessage = message
        diagnostics.record("protocol", "session_failed", "rejected")
        endSession(current, Reconnect.BACKOFF)
    }

    /** Binds a socket to the device it announced (proved later by the identity signature). */
    private fun identify(current: Session, remoteId: String): Boolean {
        val outcome = peers.identify(current, remoteId)
        if (outcome.result != PeerIdentifyResult.IDENTIFIED) {
            android.util.Log.i("Bridgey", "CONNECT rejecting connection for peer=${remoteId.take(8)}: ${outcome.result}")
            val identifiedDeviceId = peers.deviceId(current)
            peers.remove(current)
            identifiedDeviceId?.let { peerLifecycle.sessionEnded(it, current) }
            current.close()
            refreshState()
            return false
        }
        outcome.displaced?.let {
            android.util.Log.i("Bridgey", "CONNECT simultaneous dial with peer=${remoteId.take(8)}: keeping the lower-id initiated connection")
            it.close()
        }
        return true
    }

    /** The routing seam: recomputes which connected device today's single-peer features use. */
    private fun recomputeActivePeer() {
        val previous: Session?
        synchronized(coreLock) {
            val state = settings.state.value
            val next = DeviceRouting.activePeer(state.deviceRoutingMode, state.preferredDeviceId, peers.connectedInOrder())
            if (next == activePeerId) {
                refreshState()
                return
            }
            previous = activeSession
            activePeerId = next
            android.util.Log.i("Bridgey", "ROUTING active peer -> ${next?.take(8) ?: "none"}")
            // Feature resets happen before any message of the new peer can be consumed.
            activePeerChanged(previous)
            refreshState()
        }
    }

    /**
     * Feature layer only. Today's single-peer features restart against the newly routed session.
     * No session, capability, trust or connection state changes, and no capability update is sent.
     */
    private fun activePeerChanged(previous: Session?) {
        if (localWantsRemoteTelemetryUpdates && previous != null && peers.phase(previous) == PeerSessionPhase.CONNECTED) {
            // Battery-conscious: the device we no longer show must stop sampling for us.
            previous.outbox.enqueue { previous.send(Message(kind = "telemetry.unsubscribe", sessionId = previous.id)) }
        }
        // (4) Transfers and acknowledgements bound to the previous peer cannot complete through the
        // feature layer any more; finish them now instead of letting them time out.
        if (previous != null) interruptFeatureTransfers()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteBattery.value = null
        mutableRemoteStorage.value = null
        lastSentStorage = null
        resetRemoteMemoryState()
        resetTelemetrySubscriptionState()
        clearPingStatus()
        quickActions.reset()
        mediaRemote.reset()
        videoChannel.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        val current = activeSession
        if (current == null || peers.phase(current) != PeerSessionPhase.CONNECTED) {
            refreshNotificationForwardingAvailability()
            return
        }
        activePeerId?.let(peers::capabilities)?.let { capabilities ->
            applyRemoteFeatures(BridgeyFeature.entries.associateWith { capabilities[it.key] ?: false })
        }
        mediaRemote.sendFreshState()
        if (localWantsRemoteTelemetryUpdates) sendTelemetrySubscription(subscribe = true)
        // BRIDGEY NOTIFICATION++ RECONCILIATION: a newly routed session starts "not yet reconciled".
        refreshNotificationForwardingAvailability(sessionStarted = true)
    }

    /**
     * Today's single-value status (UI, notification, tiles, feature guards), derived from the Core.
     * [PairingState.Connected] means the routed (active) device is connected.
     */
    private fun refreshState() = synchronized(coreLock) {
        val verifying = peers.verifyingSession()
        val active = activeSession
        anyPeerConnected.value = peers.connectedInOrder().isNotEmpty()
        val verifyingCode = verifying?.code
        val failure = failureMessage
        mutableState.value = when {
            verifying != null && verifyingCode != null -> PairingState.Verification(verifying.peerName, verifyingCode)
            active != null && peers.phase(active) == PeerSessionPhase.CONNECTED ->
                PairingState.Connected(active.remoteDeviceId, active.peerName)
            failure != null -> PairingState.Failed(failure)
            else -> outgoingDials.values.firstOrNull()?.let { PairingState.Connecting(it) }
                ?: (peers.pendingSessions() + peers.identifiedSessions())
                    .firstOrNull { it.initiatedLocally && peers.phase(it) != PeerSessionPhase.CONNECTED }
                    ?.let { PairingState.Connecting(it.peerName) }
                ?: PairingState.Idle
        }
    }

    /** Dials every trusted, discovered device that has no session yet (lower deviceId dials). */
    private fun connectTrustedPeersIfNeeded() {
        if (server == null) return
        synchronized(coreLock) {
            ReconnectPlanner.discoveryDialTargets(
                localDeviceId,
                registry.trustedDeviceIds(),
                registry.presence(),
                endpointIndex = { id -> reconnectAttempts[id] ?: 0 },
            ) { id ->
                peers.isBusy(id) || outgoingDials.containsKey(id) || reconnectJobs.containsKey(id)
            }.forEach { (id, endpoint) ->
                val name = registry.presence()[id]?.name ?: "Bridgey device"
                android.util.Log.i("Bridgey", "RECONNECT auto-pair match peer=$name")
                dial(endpoint.host, endpoint.port, name, id)
            }
        }
    }

    /** Protocol messages owned by the Core; everything else belongs to a feature. */
    private val coreMessageKinds = setOf(
        "pairing.offer", "pairing.answer", "pairing.confirm", "pairing.cancel",
        "heartbeat.ping", "heartbeat.pong", "features.update",
    )

    private fun receive(current: Session, message: Message) {
        // COMPATIBILITY SEAM: features consume only the routed session. An inactive session stays
        // connected; its feature messages are received by the Core but not consumed yet.
        if (message.kind !in coreMessageKinds && current !== activeSession) {
            if (peers.phase(current) == PeerSessionPhase.CONNECTED && current.unconsumedFeatureMessages++ == 0) {
                android.util.Log.i("Bridgey", "ROUTING feature messages from inactive peer=${current.deviceIdForLog()} not consumed (kind=${message.kind})")
                diagnostics.record("routing", "inactive_peer_feature_message", "not_consumed")
            }
            return
        }
        when (message.kind) {
            "quick.request", "quick.result", "media.state" -> receiveQuickPayload(current, message)
            "heartbeat.ping" -> {
                if (message.sessionId != current.id || peers.phase(current) != PeerSessionPhase.CONNECTED) return
                current.heartbeatSupported = true
                current.send(Message(kind = "heartbeat.pong", sessionId = current.id, messageId = message.messageId))
            }
            "heartbeat.pong" -> {
                if (message.sessionId == current.id && peers.phase(current) == PeerSessionPhase.CONNECTED) {
                    current.heartbeatSupported = true
                }
            }
            "pairing.offer" -> {
                current.id = message.sessionId
                current.peerName = message.deviceName ?: "Bridgey device"
                val remoteId = message.deviceId ?: return
                if (!identify(current, remoteId)) return
                current.remoteDeviceId = remoteId
                current.remoteEphemeralKey = message.publicKey ?: return
                current.keyPair = Crypto.generateKeyPair()
                current.localEphemeralKey = Crypto.encodePublicKey(current.keyPair!!)
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                current.send(
                    Message(
                        kind = "pairing.answer",
                        sessionId = current.id,
                        deviceId = localDeviceId,
                        deviceName = localDeviceName,
                        publicKey = current.localEphemeralKey,
                    ),
                )
                authenticateOrPrompt(current)
            }
            "pairing.answer" -> {
                if (message.sessionId != current.id) return
                val remoteId = message.deviceId ?: return
                if (!identify(current, remoteId)) return
                current.peerName = message.deviceName ?: current.peerName
                current.remoteDeviceId = remoteId
                current.remoteEphemeralKey = message.publicKey ?: return
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                authenticateOrPrompt(current)
            }
            "pairing.confirm" -> {
                if (message.sessionId != current.id) return
                val remoteId = message.deviceId ?: return failSession(current, "Invalid pairing confirmation")
                val identityKey = message.identityKey ?: return failSession(current, "Invalid pairing confirmation")
                val proof = message.proof ?: return failSession(current, "Invalid pairing confirmation")
                val signature = message.signature ?: return failSession(current, "Invalid pairing confirmation")
                if (registry.evaluate(remoteId, identityKey) == TrustEvaluation.IDENTITY_MISMATCH) {
                    return failSession(current, "Pinned identity changed")
                }
                if (
                    remoteId != current.remoteDeviceId || !Crypto.verifyConfirmationProof(
                        current.pairingKey!!,
                        current.id,
                        remoteId,
                        identityKey,
                        proof,
                    ) || !Crypto.verifySignature(identityKey, authTranscript(current), signature)
                ) return failSession(current, "Pairing authentication failed")
                current.remoteIdentityKey = identityKey
                current.remoteConfirmed = true
                completeIfConfirmed(current)
            }
            "pairing.cancel" -> endSession(current, Reconnect.NONE)
            "features.update" -> receiveFeatureState(current, message)
            "clipboard.update" -> receiveClipboard(current, message, rich = false)
            "clipboard.rich" -> receiveClipboard(current, message, rich = true)
            "clipboard.ack" -> {
                val messageId = message.messageId ?: return
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "Delivered"
                    callback(ClipboardSendResult.DELIVERED)
                    android.util.Log.i("Bridgey", "PLUGIN clipboard acknowledged")
                }
            }
            "clipboard.rejected" -> {
                val messageId = message.messageId ?: return
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "Clipboard is turned off on Mac"
                    callback(ClipboardSendResult.DISABLED)
                }
            }
            "notifications.dismiss" -> receiveNotificationDismiss(current, message)
            "notifications.dismissMany" -> receiveNotificationDismissMany(current, message)
            "notifications.action" -> receiveNotificationAction(current, message)
            "calls.request" -> receiveCallRequest(current, message)
            "calls.action" -> receiveCallAction(current, message)
            "media.remote.action" -> receiveMediaRemoteAction(current, message)
            "video.offer", "video.accept", "video.reject", "video.stop",
            "input.offer", "input.accept", "input.reject", "input.stop" -> receiveVideoChannelMessage(current, message)
            "find.start" -> receiveFindCommand(current, message, start = true)
            "find.stop" -> receiveFindCommand(current, message, start = false)
            "find.started" -> receiveFindAcknowledgement(current, message, started = true)
            "find.stopped" -> receiveFindAcknowledgement(current, message, started = false)
            "screenshare.remoteStart" -> receiveRemoteScreenShareStart(current, message)
            "screenshare.remoteStop" -> receiveRemoteScreenShareStop(current, message)
            "kvm.switchKeyboard" -> receiveSwitchKeyboard(current, message)
            "ping.request" -> receivePing(current, message)
            "ping.ack" -> {
                if (activeSession !== current || message.sessionId != current.id || mutableState.value !is PairingState.Connected) return
                val messageId = message.messageId ?: return
                if (pendingPingId == messageId) {
                    pendingPingId = null
                    mutablePingStatus.value = "Ping delivered"
                }
            }
            "battery.update" -> receiveBattery(current, message)
            "telemetry.update" -> receiveStorageTelemetry(current, message)
            "telemetry.subscribe" -> receiveTelemetrySubscribe(current, message)
            "telemetry.unsubscribe" -> receiveTelemetryUnsubscribe(current, message)
            "files.accept" -> message.transferId?.let { pendingFileAccepts.remove(it)?.complete(true) }
            "files.complete.ack" -> message.transferId?.let { pendingFileCompletions.remove(it)?.complete(true) }
            "files.offer" -> {
                if (settings.isEnabled(BridgeyFeature.FILES, current.remoteDeviceId)) {
                    receiveFileOffer(current, message)
                } else {
                    current.send(Message(kind = "files.rejected", sessionId = current.id, transferId = message.transferId))
                    sendFeatureState()
                }
            }
            "files.rejected" -> message.transferId?.let { transferId ->
                pendingFileAccepts.remove(transferId)?.complete(false)
                outgoingFileJobs.remove(transferId)?.cancel()
                removeFileTransfer(transferId, "File transfer is turned off on Mac")
            }
            // Once an offer has been accepted, let that transfer finish even if the
            // setting changes. Disabling Files blocks the next offer instead.
            "files.chunk" -> receiveFileChunk(current, message)
            "files.complete" -> receiveFileComplete(current, message)
            "files.cancel" -> receiveFileCancel(message.transferId)
            "files.cancel.ack" -> message.transferId?.let { removeFileTransfer(it, "Transfer cancelled") }
        }
    }

    private fun receiveNotificationDismiss(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted notification command"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted notification command"),
        ) ?: return failSession(current, "Invalid encrypted notification command")
        val notificationId = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8)).getString("notificationId")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: return failSession(current, "Invalid notification command")
        BridgeyNotificationListenerService.dismiss(notificationId)
        diagnostics.record("notification", "dismiss_requested")
    }

    /**
     * BRIDGEY NOTIFICATION++ macOS CLEAR ALL (`notifications.dismissMany`, docs/protocol.md). The Mac
     * inferred that the user cleared the whole Bridgey stack in Notification Center (macOS reports no
     * per-notification callbacks for that). Each id goes through the single-dismiss path, so every
     * Android key of a logical notification is cancelled and the resulting removals are not echoed
     * back. Unknown ids are ignored; ids forwarded within the last minute are kept.
     */
    private fun receiveNotificationDismissMany(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted notification command"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted notification command"),
        ) ?: return failSession(current, "Invalid encrypted notification command")
        val ids = parseNotificationDismissManyPayload(plaintext.toString(Charsets.UTF_8))
            ?: return failSession(current, "Invalid notification command")
        val outcomes = ids.map(BridgeyNotificationListenerService::dismissFromMacClearAll)
        android.util.Log.i(
            "Bridgey",
            "PLUGIN notification dismissMany received=${ids.size} " +
                "dismissed=${outcomes.count { it == MacClearAllDismissOutcome.DISMISSED }} " +
                "unknown=${outcomes.count { it == MacClearAllDismissOutcome.UNKNOWN }} " +
                "tooRecent=${outcomes.count { it == MacClearAllDismissOutcome.TOO_RECENT }}",
        )
        diagnostics.record("notification", "dismiss_many_received")
    }

    private fun receiveCallRequest(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        if (!settings.isEnabled(BridgeyFeature.CALLS, current.remoteDeviceId)) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            sendFeatureState()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastRemoteCallRequestAt != 0L && now - lastRemoteCallRequestAt < 3_000) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            return
        }
        val nonce = message.nonce ?: return failSession(current, "Invalid encrypted call request")
        val ciphertext = message.ciphertext ?: return failSession(current, "Invalid encrypted call request")
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            nonce,
            ciphertext,
        ) ?: return failSession(current, "Invalid encrypted call request")
        val number = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8)).getString("number")
        }.getOrNull()?.let(::normalizedPhoneNumber)
        if (number == null) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            return
        }
        lastRemoteCallRequestAt = now
        val result = remoteCallRequest.execute(number, settings.state.value.directCallsEnabled)
        current.send(Message(kind = result.wireKind, sessionId = current.id, messageId = messageId))
        diagnostics.record("calls", "request", outcome = result.name.lowercase())
    }

    /** Handles a Mac-initiated answer/decline/hangup for a call tracked via Telecom. */
    private fun receiveCallAction(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id ||
            !current.acceptMessageId(messageId)
        ) return
        if (!settings.isEnabled(BridgeyFeature.CALLS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted call action"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted call action"),
        ) ?: return failSession(current, "Invalid encrypted call action")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid call action")
        val callId = payload.optString("callId")
        val action = payload.optString("action")
        val route = payload.optString("route").takeIf { payload.has("route") }
        if (runCatching { UUID.fromString(callId) }.isFailure ||
            action !in setOf("answer", "decline", "hangup") ||
            !isValidAudioRoute(route)
        ) {
            return failSession(current, "Invalid call action")
        }
        // There is no per-call tracking without InCallService (see sendCallState's doc comment),
        // so this always acts on whatever call is currently ringing or active rather than
        // looking up this specific callId.
        val accepted = BridgeyNotificationListenerService.performCallAction(action, route)
        val ackPayload = JSONObject()
            .put("version", 1)
            .put("callId", callId)
            .put("action", action)
            .put("accepted", accepted)
            .toString()
            .toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, ackPayload)
        current.send(
            Message(
                kind = "calls.action.ack",
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
        diagnostics.record("calls", "action_received", outcome = "$action:${if (accepted) "accepted" else "rejected"}")
    }

    /** Handles a Mac-initiated media.remote.action (play/pause/toggle/next/previous/seek)
     * targeting whatever MediaSession MediaContinuityManager currently considers primary. */
    private fun receiveMediaRemoteAction(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id ||
            !current.acceptMessageId(messageId)
        ) return
        if (!isFeatureAvailable(BridgeyFeature.MEDIA)) {
            sendFeatureState()
            return
        }
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted media action"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted media action"),
        ) ?: return failSession(current, "Invalid encrypted media action")
        if (plaintext.size > 4_096) return failSession(current, "Media action payload too large")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid media action")
        val requestId = payload.optString("requestId")
        if (payload.optInt("version") != 1 || runCatching { UUID.fromString(requestId) }.isFailure) {
            return failSession(current, "Invalid media action")
        }
        val (accepted, reason) = mediaRemote.handleAction(payload)
        android.util.Log.i(
            "Bridgey",
            "MEDIA action received action=${payload.optString("action")} accepted=$accepted reason=${reason ?: "-"}",
        )
        val ackPayload = JSONObject()
            .put("version", 1)
            .put("requestId", requestId)
            .put("accepted", accepted)
            .apply { reason?.let { put("reason", it) } }
        sendQuickPayload("media.remote.action.ack", ackPayload)
        diagnostics.record("media", "action_received", outcome = "${payload.optString("action")}:${if (accepted) "accepted" else reason}")
    }

    /** Dedicated control-channel dispatch for the video/input channel negotiation family
     * (video.offer/accept/reject/stop, input.offer/accept/reject/stop) - decrypts and hands the
     * plain JSON payload to VideoChannelManager, which owns all negotiation/establishment logic. */
    private fun receiveVideoChannelMessage(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val nonce = message.nonce ?: return
        val ciphertext = message.ciphertext ?: return
        val plaintext = Crypto.decrypt(current.pairingKey!!, nonce, ciphertext) ?: return
        if (plaintext.size > 8_192) return
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        // KVM PART 3: unlike video (only Android ever offers), Mac is the initiator of "input.offer"
        // - so unlike video's channel-level available() (connection-only), an incoming input.offer
        // must additionally be gated on the user having explicitly opted in to KVM_INPUT. A rejected
        // offer is answered exactly the way VideoChannelManager itself would (same wire shape), so
        // the channel cleanly settles back to idle on the Mac side too.
        if (message.kind == "input.offer" && !settings.isEnabled(BridgeyFeature.KVM_INPUT, current.remoteDeviceId)) {
            val channelId = payload.optString("channelId")
            if (channelId.isNotEmpty()) {
                sendQuickPayload(
                    "input.reject",
                    JSONObject().put("version", 1).put("channelId", channelId).put("reason", "unavailable"),
                )
            }
            return
        }
        videoChannel.receive(message.kind, payload)
    }

    private fun receiveNotificationAction(current: Session, message: Message) {
        if (!settings.isEnabled(BridgeyFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted notification action"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted notification action"),
        ) ?: return failSession(current, "Invalid encrypted notification action")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid notification action")
        val notificationId = payload.optString("notificationId")
        val actionToken = payload.optString("actionToken")
        val replyText = payload.optString("replyText").takeIf { payload.has("replyText") }
        val route = payload.optString("route").takeIf { payload.has("route") }
        if (!isValidNotificationActionPayload(notificationId, actionToken, replyText) || !isValidAudioRoute(route)) {
            return failSession(current, "Invalid notification action")
        }
        BridgeyNotificationListenerService.perform(notificationId, actionToken, replyText, route)
        diagnostics.record("notification", if (replyText == null) "action_requested" else "reply_requested")
    }

    private fun receiveClipboard(current: Session, message: Message, rich: Boolean) {
        if (!settings.isEnabled(BridgeyFeature.CLIPBOARD, current.remoteDeviceId)) {
            current.send(Message(kind = "clipboard.rejected", sessionId = current.id, messageId = message.messageId))
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return failSession(current, "Invalid encrypted clipboard message")
        val clip = if (rich) {
            val content = RichClipboardContent.decode(plaintext)
                ?: return failSession(current, "Invalid rich clipboard message")
            ClipData.newHtmlText("Bridgey", content.text, content.html)
        } else {
            val text = plaintext.toString(Charsets.UTF_8)
            if (!clipboardTextFits(text)) return failSession(current, "Invalid clipboard message")
            ClipData.newPlainText("Bridgey", text)
        }
        appContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        diagnostics.record("clipboard", if (rich) "rich_received" else "text_received")
        android.util.Log.i("Bridgey", "PLUGIN clipboard received")
        current.send(Message(kind = "clipboard.ack", sessionId = current.id, messageId = messageId))
    }

    private fun receiveFindAcknowledgement(current: Session, message: Message, started: Boolean) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted find-device acknowledgement"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted find-device acknowledgement"),
        ) ?: return failSession(current, "Invalid encrypted find-device acknowledgement")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getString("alertId") }.getOrNull() != "active") {
            return failSession(current, "Invalid find-device acknowledgement")
        }
        mutableMacRinging.value = started
    }

    private fun receiveFileOffer(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted file offer"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted file offer"),
        ) ?: return failSession(current, "Invalid encrypted file offer")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid file offer")
        val transferId = payload.optString("transferId")
        val name = payload.optString("name")
        val mimeType = payload.optString("mimeType", "application/octet-stream")
        val size = payload.optLong("size", -1)
        val hash = payload.optString("sha256")
        if (runCatching { UUID.fromString(transferId) }.isFailure || name.isBlank() || size !in 0..MAX_FILE_SIZE ||
            runCatching { Base64.decode(hash, Base64.DEFAULT).size == 32 }.getOrDefault(false).not() ||
            incomingFiles.containsKey(transferId)
        ) return failSession(current, "Invalid file offer")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return failSession(current, "Receiving files requires Android 10 or newer")
        }
        val transfer = runCatching {
            IncomingFileTransfer(appContext, transferId, name, mimeType, size, hash)
        }.getOrElse { return failSession(current, "Could not create file in Downloads") }
        incomingFiles[transferId] = transfer
        updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: ${transfer.progress.status(0, force = true)}", true)
        current.send(Message(kind = "files.accept", sessionId = current.id, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file accepted name=${transfer.displayName} size=$size")
    }

    private fun receiveFileChunk(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val transferId = message.transferId ?: return
        val transfer = incomingFiles[transferId] ?: run {
            if (transferId in cancelledTransferIds) return
            return failSession(current, "Unknown file transfer")
        }
        val chunk = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted file chunk"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted file chunk"),
        ) ?: return failSession(current, "Invalid encrypted file chunk")
        if (!runCatching { transfer.append(chunk, message.sequence ?: -1) }.isSuccess) {
            incomingFiles.remove(transfer.transferId)?.cancel()
            return failSession(current, "Invalid file data")
        }
        transfer.progress.status(transfer.receivedSize)?.let {
            updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: $it", true)
        }
        current.send(
            Message(
                kind = "files.chunk.ack",
                sessionId = current.id,
                transferId = transferId,
                sequence = message.sequence,
            ),
        )
    }

    private fun receiveFileComplete(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted file completion"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted file completion"),
        ) ?: return failSession(current, "Invalid encrypted file completion")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid file completion")
        val transferId = payload.optString("transferId")
        val transfer = incomingFiles.remove(transferId) ?: run {
            if (transferId in cancelledTransferIds) return
            return failSession(current, "Unknown file transfer")
        }
        val savedUri = runCatching { transfer.finish(payload.optString("sha256")) }.getOrNull()
        if (savedUri == null) {
            transfer.cancel()
            mutableFileTransferStatus.value = "File verification failed"
            return failSession(current, "File verification failed")
        }
        updateFileTransfer(transferId, transfer.displayName, "${transfer.displayName} saved to Download/Bridgey", false)
        ReceivedFileNotifier.show(appContext, transfer.displayName, transfer.mimeType, savedUri)
        current.send(Message(kind = "files.complete.ack", sessionId = current.id, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file received name=${transfer.displayName}")
    }

    private fun receiveFileCancel(transferId: String?) {
        if (transferId == null) return
        markTransferCancelled(transferId)
        incomingFiles.remove(transferId)?.cancel()
        outgoingFileJobs.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        removeFileTransfer(transferId, "Transfer cancelled by Mac")
        activeSession?.send(Message(kind = "files.cancel.ack", sessionId = activeSession?.id ?: return, transferId = transferId))
        android.util.Log.i("Bridgey", "PLUGIN file cancellation received transfer=${transferId.take(8)}")
    }

    private fun markTransferCancelled(transferId: String) {
        cancelledTransferIds += transferId
        while (cancelledTransferIds.size > 64) cancelledTransferIds.firstOrNull()?.let(cancelledTransferIds::remove)
    }

    private fun updateFileTransfer(id: String, name: String, status: String, active: Boolean) {
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
            val percent = Regex("(\\d{1,3})%").find(status)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val previous = get(id)
            put(id, FileTransferState(
                id = id,
                name = name,
                status = status,
                active = active,
                progressPercent = percent,
                startedAtMillis = previous?.startedAtMillis ?: System.currentTimeMillis(),
                retryable = !active && outgoingFileSources.containsKey(id),
            ))
        }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
    }

    private fun removeFileTransfer(id: String, status: String) {
        mutableFileTransfers.value[id]?.let { previous ->
            mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
                put(id, previous.copy(
                    status = status,
                    active = false,
                    progressPercent = null,
                    retryable = outgoingFileSources.containsKey(id),
                ))
            }
        }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
    }

    private fun pruneTransferHistory() {
        val active = mutableFileTransfers.value.values.filter(FileTransferState::active)
        val history = mutableFileTransfers.value.values.filterNot(FileTransferState::active)
            .sortedByDescending(FileTransferState::startedAtMillis)
            .take(MAX_TRANSFER_HISTORY)
        mutableFileTransfers.value = (active + history).associateBy(FileTransferState::id)
    }

    private fun refreshFileTransferSummary(fallback: String? = null) {
        val activeTransfers = mutableFileTransfers.value.values.filter { it.active }
        mutableFileTransferActive.value = activeTransfers.isNotEmpty()
        mutableFileTransferStatus.value = activeTransfers.firstOrNull()?.status ?: fallback
    }

    private fun authenticateOrPrompt(current: Session) {
        if (registry.identityKey(current.remoteDeviceId) != null) {
            confirm(current)
            return
        }
        val other = peers.verifyingSession()
        if (other != null && other !== current) {
            // One verification code on screen at a time; the other device can retry.
            android.util.Log.i("Bridgey", "PAIRING another device is being verified, rejecting peer=${current.deviceIdForLog()}")
            current.send(Message(kind = "pairing.cancel", sessionId = current.id))
            endSession(current, Reconnect.NONE)
            return
        }
        peers.setPhase(PeerSessionPhase.VERIFYING, current)
        refreshState()
    }

    private fun confirm(current: Session) {
        current.localConfirmed = true
        val identityKey = identity.publicKey()
        val sent = current.send(
            Message(
                kind = "pairing.confirm",
                sessionId = current.id,
                deviceId = localDeviceId,
                identityKey = identityKey,
                proof = Crypto.confirmationProof(current.pairingKey!!, current.id, localDeviceId, identityKey),
                signature = identity.sign(authTranscript(current)),
            ),
        )
        // ZOMBIE-SESSION GUARD (2026-10-01): a confirmation that never reached the peer must not
        // complete pairing. Closing the socket ends this session's read loop, which then fails the
        // session normally and keeps the reconnect schedule alive.
        if (!sent) {
            android.util.Log.w("Bridgey", "PAIRING confirmation not delivered, closing session")
            current.close()
            return
        }
        completeIfConfirmed(current)
    }

    private fun authTranscript(current: Session): ByteArray {
        val fields = if (current.initiatedLocally) {
            listOf(localDeviceId, current.remoteDeviceId, current.localEphemeralKey, current.remoteEphemeralKey)
        } else {
            listOf(current.remoteDeviceId, localDeviceId, current.remoteEphemeralKey, current.localEphemeralKey)
        }
        return (listOf("bridgey-auth-v1", current.id) + fields).joinToString("\u0000").toByteArray()
    }

    private fun completeIfConfirmed(current: Session) {
        if (current.localConfirmed && current.remoteConfirmed) {
            // ZOMBIE-SESSION GUARD (2026-10-01): only a session still owning its deviceId may become
            // connected. A session replaced or failed meanwhile is closed instead.
            val id = current.remoteDeviceId
            if (peers.deviceId(current) != id) {
                android.util.Log.w("Bridgey", "PAIRING verification ignored: session is no longer active")
                current.close()
                return
            }
            registry.remember(id, current.peerName, current.remoteIdentityKey!!)
            peers.markConnected(current)
            registry.recordConnection(id, current.peerName, System.currentTimeMillis())
            reconnectJobs.remove(id)?.cancel()
            reconnectAttempts.remove(id)
            failureMessage = null
            diagnostics.record("pairing", "connected")
            sendFeatureState(current)
            if (settings.state.value.preferredDeviceId == null) settings.setPreferredDevice(id)
            recomputeActivePeer()
            peerLifecycle.sessionStarted(id, current) { peers.connectedSession(id) === current }
            android.util.Log.i("Bridgey", "PAIRING verified peer=${current.peerName}")
        }
    }

    // region MD-1 routing foundation (see core/messaging/README.md)

    /**
     * Read-only projection of trusted and connected devices for routing and UI. Identity and trust
     * stay in the registry; nothing here can change them.
     */
    internal fun deviceDirectory(): List<DeviceDirectoryEntry> {
        val connectedNames = peers.identifiedSessions().mapNotNull { session ->
            peers.connectedDeviceId(session)?.let { it to session.peerName }
        }.toMap()
        val trusted = registry.trustedDeviceIds().mapNotNull { id ->
            registry.device(id)?.let { DeviceDirectory.TrustedDevice(it.id, it.name, it.platform, it.deviceType) }
        }
        return DeviceDirectory.entries(
            trusted = trusted,
            presence = registry.presence(),
            connectedNames = connectedNames,
            state = peers::state,
            capabilities = peers::capabilities,
            routedDeviceId = activePeerId,
        )
    }

    /**
     * Whether [feature] can be offered from this device to [deviceId] (platform, direction,
     * capability and the local per-device grant).
     */
    internal fun applicability(feature: BridgeyFeature, deviceId: String): FeatureApplicabilityResult {
        val peer = deviceDirectory().firstOrNull { it.deviceId == deviceId }
            ?: return FeatureApplicabilityResult.PEER_LACKS_CAPABILITY
        return FeatureApplicability.evaluate(
            feature,
            localPlatform = DevicePlatform.ANDROID,
            localDeviceType = localDeviceType,
            peer = peer,
            locallyAuthorized = settings.isEnabled(feature, deviceId),
        )
    }

    /**
     * Addressed messaging: queues an encrypted feature message on exactly this device's connected
     * session (its own outbox), independent of the routed (active) peer. False when that device is
     * not connected.
     */
    fun send(to: String, kind: String, payload: ByteArray): Boolean = peers.deliver(to) { session ->
        val key = session.pairingKey ?: return@deliver false
        session.outbox.enqueue {
            val encrypted = Crypto.encrypt(key, payload)
            session.send(
                Message(
                    kind = kind,
                    sessionId = session.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                ),
            )
        }
        true
    }

    private fun emitLocalAuthorizationChanges(state: BridgeySettingsState) {
        val (oldGlobal, oldPerDevice) = authorizationSnapshot
        val changed = DeviceAuthorization.changedDevices(
            oldGlobal = oldGlobal,
            newGlobal = state.globalFeatures,
            oldPerDevice = oldPerDevice,
            newPerDevice = state.deviceFeatures,
            devices = registry.trustedDeviceIds() + peers.connectedInOrder().map { it.deviceId },
        )
        authorizationSnapshot = state.globalFeatures to state.deviceFeatures
        peerLifecycle.authorizationChanged(changed)
    }

    // endregion

    /** Sends every connected session its real local feature state; never depends on the active peer. */
    private fun sendFeatureState() {
        // Session isolation: each peer's features.update goes through that session's own outbox, so
        // one peer that stops reading cannot delay any other peer (or the settings collector).
        peers.identifiedSessions().forEach { session -> session.outbox.enqueue { sendFeatureState(session) } }
    }

    private fun sendFeatureState(current: Session) {
        if (peers.phase(current) != PeerSessionPhase.CONNECTED || current.pairingKey == null) return
        val featureValues = JSONObject()
        PeerFeatureState.payload(current.remoteDeviceId) { feature, id -> settings.isEnabled(feature, id) }
            .forEach { (key, enabled) -> featureValues.put(key, enabled) }
        val payload = JSONObject()
            .put("version", 1)
            .put("features", featureValues)
            .toString()
            .toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        current.send(
            Message(
                kind = "features.update",
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveFeatureState(current: Session, message: Message) {
        if (peers.phase(current) != PeerSessionPhase.CONNECTED || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return failSession(current, "Invalid encrypted feature state")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid feature state")
        val values = payload.optJSONObject("features") ?: return failSession(current, "Invalid feature state")
        if (payload.optInt("version") != 1) return
        val received = BridgeyFeature.entries.associateWith { feature ->
            if (!values.has(feature.key)) {
                if (!featureEnabledByLegacyPeer(feature)) return@associateWith false
                return failSession(current, "Invalid feature state")
            }
            if (values.opt(feature.key) !is Boolean) return failSession(current, "Invalid feature state")
            values.getBoolean(feature.key)
        }
        // Core: every session keeps its own real negotiated capabilities.
        val capabilities = received.mapKeys { it.key.key }
        val previousCapabilities = peers.capabilities(current.remoteDeviceId)
        peers.setCapabilities(capabilities, current)
        // Features: only the routed session's capabilities drive today's single-peer features.
        if (current === activeSession) applyRemoteFeatures(received)
        if (previousCapabilities != capabilities) peerLifecycle.authorizationChanged(setOf(current.remoteDeviceId))
    }

    /** Mirrors the routed session's capabilities into today's single-peer feature state. */
    private fun applyRemoteFeatures(received: Map<BridgeyFeature, Boolean>) {
        mutableRemoteFeatures.value = received
        quickActions.policyChanged()
        mediaRemote.policyChanged()
        refreshNotificationForwardingAvailability()
        if (received[BridgeyFeature.CLIPBOARD] == false) mutableClipboardStatus.value = null
        if (received[BridgeyFeature.BATTERY] == false) mutableRemoteBattery.value = null
        if (received[BridgeyFeature.STORAGE] == false) mutableRemoteStorage.value = null
        if (received[BridgeyFeature.MEMORY] == false) mutableRemoteMemory.value = null
        if (received[BridgeyFeature.CPU] == false) mutableRemoteCpu.value = null
        if (received[BridgeyFeature.TEMPERATURE] == false) mutableRemoteTemperature.value = null
        if (received[BridgeyFeature.PING] == false) clearPingStatus()
        if (received[BridgeyFeature.FIND_DEVICE] == false) {
            stopPhoneRinging()
            mutableMacRinging.value = false
        }
    }

    private fun receiveBattery(current: Session, message: Message) {
        if (!featureEnabled(BridgeyFeature.BATTERY, current)) return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted battery status"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted battery status"),
        ) ?: return failSession(current, "Invalid encrypted battery status")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid battery status")
        val level = payload.optInt("level", -1)
        if (level !in 0..100 || payload.opt("isCharging") !is Boolean) return failSession(current, "Invalid battery status")
        mutableRemoteBattery.value = RemoteBatteryStatus(level, payload.getBoolean("isCharging"))
        android.util.Log.i("Bridgey", "PLUGIN battery received level=$level")
    }

    /** Not gated by a single telemetry feature at the top - each field block below independently
     *  checks its own Settings toggle, since Storage/Memory/CPU/Temperature are now independent. */
    private fun receiveStorageTelemetry(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return failSession(current, "Invalid encrypted storage status"),
            message.ciphertext ?: return failSession(current, "Invalid encrypted storage status"),
        ) ?: return failSession(current, "Invalid encrypted storage status")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return failSession(current, "Invalid storage status")
        if (featureEnabled(BridgeyFeature.STORAGE, current) &&
            (payload.has("storageUsedBytes") || payload.has("storageTotalBytes"))
        ) {
            val usedBytes = payload.optLong("storageUsedBytes", -1)
            val totalBytes = payload.optLong("storageTotalBytes", -1)
            if (usedBytes < 0 || totalBytes <= 0 || usedBytes > totalBytes) return failSession(current, "Invalid storage status")
            mutableRemoteStorage.value = RemoteStorageStatus(usedBytes, totalBytes)
            android.util.Log.i("Bridgey", "PLUGIN storage received usedBytes=$usedBytes totalBytes=$totalBytes")
        }
        if (featureEnabled(BridgeyFeature.MEMORY, current) &&
            (payload.has("memoryUsedBytes") || payload.has("memoryTotalBytes"))
        ) {
            val usedBytes = payload.optLong("memoryUsedBytes", -1)
            val totalBytes = payload.optLong("memoryTotalBytes", -1)
            if (usedBytes < 0 || totalBytes <= 0 || usedBytes > totalBytes) return failSession(current, "Invalid memory status")
            mutableRemoteMemory.value = RemoteMemoryStatus(usedBytes, totalBytes)
            android.util.Log.i("Bridgey", "PLUGIN memory received usedBytes=$usedBytes totalBytes=$totalBytes")
        }
        if (featureEnabled(BridgeyFeature.CPU, current)) {
            if (payload.has("cpuUnavailable")) {
                mutableRemoteCpu.value = RemoteCpuStatus.Unavailable
                android.util.Log.i("Bridgey", "PLUGIN cpu received unavailable")
            } else if (payload.has("cpuPercent")) {
                val percent = payload.optInt("cpuPercent", -1)
                if (percent !in 0..100) return failSession(current, "Invalid cpu status")
                mutableRemoteCpu.value = RemoteCpuStatus.Available(percent)
                android.util.Log.i("Bridgey", "PLUGIN cpu received percent=$percent")
            }
        }
        if (featureEnabled(BridgeyFeature.TEMPERATURE, current)) {
            if (payload.has("temperatureUnavailable")) {
                mutableRemoteTemperature.value = RemoteTemperatureStatus.Unavailable
                android.util.Log.i("Bridgey", "PLUGIN temperature received unavailable")
            } else if (payload.has("thermalState")) {
                val state = payload.optString("thermalState", "")
                if (state.isEmpty()) return failSession(current, "Invalid temperature status")
                val celsius = if (payload.has("temperatureCelsius")) payload.optInt("temperatureCelsius", Int.MIN_VALUE) else null
                if (celsius == Int.MIN_VALUE) return failSession(current, "Invalid temperature status")
                mutableRemoteTemperature.value = RemoteTemperatureStatus.Known(state, celsius)
                android.util.Log.i("Bridgey", "PLUGIN temperature received state=$state celsius=$celsius")
            }
        }
    }

    /**
     * Per-device backoff. `Failed` is still not a dead end: after the delay the failure is cleared
     * and that device is dialled again via discovery (Reliability.kt's reconnectDelayMillis).
     * One device's retries never affect another device.
     */
    private fun scheduleReconnect(deviceId: String) {
        val attempt = reconnectAttempts[deviceId] ?: 0
        reconnectAttempts[deviceId] = (attempt + 1).coerceAtMost(30)
        // Jitter is added here (not inside reconnectDelayMillis, which stays a pure, tested
        // function) so two devices racing to reconnect at the same moment don't stay in lockstep
        // and keep colliding on every subsequent retry.
        val delayMillis = reconnectDelayMillis(attempt) + kotlin.random.Random.nextLong(1_000L)
        android.util.Log.i("Bridgey", "RECONNECT scheduling retry for peer=${deviceId.take(8)} in ${delayMillis}ms (attempt=$attempt)")
        diagnostics.record("reconnect", "scheduled")
        val job = scope.launch {
            delay(delayMillis)
            reconnectJobs.remove(deviceId)
            android.util.Log.i("Bridgey", "RECONNECT attempt $attempt for peer=${deviceId.take(8)}: retrying discovery")
            diagnostics.record("reconnect", "attempt")
            failureMessage = null
            refreshState()
            connectTrustedPeersIfNeeded()
        }
        reconnectJobs.put(deviceId, job)?.cancel()
    }

    private fun cancelIncomingFiles() {
        val hadActiveTransfers = mutableFileTransfers.value.values.any(FileTransferState::active)
        incomingFiles.values.forEach(IncomingFileTransfer::cancel)
        incomingFiles.clear()
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        pendingFileAccepts.values.forEach { it.complete(false) }
        pendingFileAccepts.clear()
        pendingFileCompletions.values.forEach { it.complete(false) }
        pendingFileCompletions.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        if (hadActiveTransfers) diagnostics.record("transfer", "interrupted", "retry_available")
        if (mutableFileTransferStatus.value?.startsWith("Receiving ") == true) {
            mutableFileTransferStatus.value = "File transfer interrupted"
        }
    }

    private companion object {
        fun defaultFeatureState(): Map<BridgeyFeature, Boolean> = BridgeyFeature.entries.associateWith(::featureEnabledByLegacyPeer)
        const val FILE_CHUNK_SIZE = 24 * 1024
        const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024
        const val MAX_NOTIFICATION_ICON_BASE64_LENGTH = 28 * 1024
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
        const val MAX_PENDING_SESSIONS = 16
        const val TELEMETRY_SAMPLING_INTERVAL_MILLIS = 3_000L
        const val STORAGE_CHANGE_THRESHOLD_BYTES = 100L * 1024 * 1024
        const val MEMORY_CHANGE_THRESHOLD_BYTES = 100L * 1024 * 1024
    }

    private class Session(private val socket: Socket, val outbox: SessionWriter) {
        // Used by VideoChannelManager to dial the dedicated video/input socket to the same peer
        // this control session is already talking to - no separate discovery/addressing needed.
        val remoteHost: String? = socket.inetAddress?.hostAddress
        val input = BufferedInputStream(socket.getInputStream())
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        var id = ""
        var peerName = "Device"
        var remoteDeviceId = ""
        var remoteIdentityKey: String? = null
        var initiatedLocally = false
        var localEphemeralKey = ""
        var remoteEphemeralKey = ""
        var keyPair: KeyPair? = null
        var code: String? = null
        var pairingKey: ByteArray? = null
        var localConfirmed = false
        var remoteConfirmed = false
        @Volatile var lastReceivedAtMillis = SystemClock.elapsedRealtime()
        @Volatile var heartbeatSupported = false
        /** The device an outgoing dial targets (discovery hint) until the peer identifies itself. */
        var expectedDeviceId: String? = null
        val createdAtMillis = SystemClock.elapsedRealtime()
        var unconsumedFeatureMessages = 0
        private val seenMessageIds = LinkedHashSet<String>()

        fun deviceIdForLog(): String = remoteDeviceId.ifEmpty { expectedDeviceId ?: "unknown" }.take(8)

        @Synchronized fun send(message: Message): Boolean {
            val result = runCatching {
                writer.write(message.encode())
                writer.newLine()
                writer.flush()
            }
            result.exceptionOrNull()?.let {
                android.util.Log.e("Bridgey", "TRANSPORT send failed kind=${message.kind}", it)
            }
            return result.isSuccess
        }

        fun close() = runCatching { socket.close() }.let { Unit }

        val quickSequence = QuickRequestSequence()

        @Synchronized fun acceptMessageId(id: String): Boolean {
            if (!seenMessageIds.add(id)) return false
            while (seenMessageIds.size > 256) seenMessageIds.remove(seenMessageIds.first())
            return true
        }
    }
}

private class TransferProgress(private val totalBytes: Long) {
    private var lastUpdateAt = SystemClock.elapsedRealtime()
    private var lastBytes = 0L
    private var smoothedBytesPerSecond = 0.0

    fun status(transferred: Long, force: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastUpdateAt
        if (!force && elapsedMs < 250 && transferred < totalBytes) return null
        if (elapsedMs > 0) {
            val sample = (transferred - lastBytes).coerceAtLeast(0) * 1000.0 / elapsedMs
            smoothedBytesPerSecond = if (smoothedBytesPerSecond == 0.0) sample else smoothedBytesPerSecond * 0.7 + sample * 0.3
        }
        lastUpdateAt = now
        lastBytes = transferred
        val percent = if (totalBytes == 0L) 100 else ((transferred * 100) / totalBytes).coerceIn(0, 100)
        val remaining = (totalBytes - transferred).coerceAtLeast(0)
        val eta = if (smoothedBytesPerSecond >= 1 && remaining > 0) formatDuration((remaining / smoothedBytesPerSecond).toLong()) else "calculating…"
        return "$percent% · ${formatBytes(transferred)} / ${formatBytes(totalBytes)} · ${formatBytes(smoothedBytesPerSecond.toLong())}/s · $eta left"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 10) "%.0f %s".format(java.util.Locale.US, value, units[unit])
        else "%.1f %s".format(java.util.Locale.US, value, units[unit])
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 1 -> "<1 sec"
        seconds < 60 -> "$seconds sec"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} sec"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private class IncomingFileTransfer(
    private val context: Context,
    val transferId: String,
    offeredName: String,
    val mimeType: String,
    val expectedSize: Long,
    private val expectedHash: String,
) {
    val displayName = sanitize(offeredName)
    val progress = TransferProgress(expectedSize)
    private val resolver = context.contentResolver
    private val uri: Uri
    private val output: OutputStream
    private val digest = MessageDigest.getInstance("SHA-256")
    var receivedSize = 0L
        private set
    private var nextSequence = 0L

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.take(255))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Bridgey")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not allocate download")
        output = resolver.openOutputStream(uri, "w") ?: run {
            resolver.delete(uri, null, null)
            error("Could not open download")
        }
    }

    @Synchronized fun append(bytes: ByteArray, sequence: Long) {
        check(sequence == nextSequence)
        check(bytes.size <= 24 * 1024)
        check(receivedSize + bytes.size <= expectedSize)
        output.write(bytes)
        digest.update(bytes)
        receivedSize += bytes.size
        nextSequence++
    }

    @Synchronized fun finish(completionHash: String): Uri? {
        output.flush()
        output.close()
        val actual = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        if (receivedSize != expectedSize || completionHash != expectedHash || actual != expectedHash) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Synchronized fun cancel() {
        runCatching { output.close() }
        resolver.delete(uri, null, null)
    }

    private fun sanitize(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:]"), "_")
            .take(255)
        return leaf.ifBlank { "file" }
    }
}

private data class Message(
    val kind: String,
    val sessionId: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val publicKey: String? = null,
    val identityKey: String? = null,
    val proof: String? = null,
    val signature: String? = null,
    val messageId: String? = null,
    val nonce: String? = null,
    val ciphertext: String? = null,
    val transferId: String? = null,
    val sequence: Long? = null,
    val status: String? = null,
) {
    fun encode(): String = JSONObject().apply {
        put("kind", kind)
        put("sessionId", sessionId)
        deviceId?.let { put("deviceId", it) }
        deviceName?.let { put("deviceName", it) }
        publicKey?.let { put("publicKey", it) }
        identityKey?.let { put("identityKey", it) }
        proof?.let { put("proof", it) }
        signature?.let { put("signature", it) }
        messageId?.let { put("messageId", it) }
        nonce?.let { put("nonce", it) }
        ciphertext?.let { put("ciphertext", it) }
        transferId?.let { put("transferId", it) }
        sequence?.let { put("sequence", it) }
        status?.let { put("status", it) }
    }.toString()

    companion object {
        fun decode(value: String): Message = JSONObject(value).let {
            Message(
                kind = it.getString("kind"),
                sessionId = it.getString("sessionId"),
                deviceId = it.optString("deviceId").takeIf(String::isNotEmpty),
                deviceName = it.optString("deviceName").takeIf(String::isNotEmpty),
                publicKey = it.optString("publicKey").takeIf(String::isNotEmpty),
                identityKey = it.optString("identityKey").takeIf(String::isNotEmpty),
                proof = it.optString("proof").takeIf(String::isNotEmpty),
                signature = it.optString("signature").takeIf(String::isNotEmpty),
                messageId = it.optString("messageId").takeIf(String::isNotEmpty),
                nonce = it.optString("nonce").takeIf(String::isNotEmpty),
                ciphertext = it.optString("ciphertext").takeIf(String::isNotEmpty),
                transferId = it.optString("transferId").takeIf(String::isNotEmpty),
                sequence = if (it.has("sequence")) it.getLong("sequence") else null,
                status = it.optString("status").takeIf(String::isNotEmpty),
            )
        }
    }
}

internal object Crypto {
    data class PairingMaterial(val code: String, val key: ByteArray)
    data class EncryptedPayload(val nonce: String, val ciphertext: String)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun encodePublicKey(pair: KeyPair): String {
        val public = pair.public as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    fun pairingMaterial(pair: KeyPair, remoteBase64: String, sessionId: String): PairingMaterial {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(decodePublicKey(java.util.Base64.getDecoder().decode(remoteBase64)), true)
        val sharedSecret = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(sharedSecret)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val output = expand.doFinal("bridgey-pairing-v1".toByteArray() + byteArrayOf(1))
        val number = ((output[0].toLong() and 0xff) shl 24) or
            ((output[1].toLong() and 0xff) shl 16) or
            ((output[2].toLong() and 0xff) shl 8) or (output[3].toLong() and 0xff)
        return PairingMaterial((number % 1_000_000).toString().padStart(6, '0'), output)
    }

    fun confirmationProof(key: ByteArray, sessionId: String, deviceId: String, identityKey: String): String {
        val data = "bridgey-confirm-v1\u0000$sessionId\u0000$deviceId\u0000$identityKey".toByteArray()
        val proof = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data)
        return java.util.Base64.getEncoder().encodeToString(proof)
    }

    fun verifyConfirmationProof(
        key: ByteArray,
        sessionId: String,
        deviceId: String,
        identityKey: String,
        proof: String,
    ): Boolean = runCatching {
        MessageDigest.isEqual(
            java.util.Base64.getDecoder().decode(proof),
            java.util.Base64.getDecoder().decode(confirmationProof(key, sessionId, deviceId, identityKey)),
        )
    }.getOrDefault(false)

    fun encrypt(key: ByteArray, plaintext: ByteArray): EncryptedPayload {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        return EncryptedPayload(
            java.util.Base64.getEncoder().encodeToString(nonce),
            java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
        )
    }

    fun decrypt(key: ByteArray, nonce: String, ciphertext: String): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, java.util.Base64.getDecoder().decode(nonce)),
            )
        }.doFinal(java.util.Base64.getDecoder().decode(ciphertext))
    }.getOrNull()

    fun verifySignature(identityKey: String, data: ByteArray, signature: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(decodePublicKey(java.util.Base64.getDecoder().decode(identityKey)))
            update(data)
        }.verify(java.util.Base64.getDecoder().decode(signature))
    }.getOrDefault(false)

    private fun decodePublicKey(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte())
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

private class AndroidIdentity(context: Context) {
    private val alias = "bridgey.identity.p256.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    fun publicKey(): String {
        val public = store.getCertificate(alias).publicKey as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun sign(data: ByteArray): String {
        val privateKey = store.getKey(alias, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
