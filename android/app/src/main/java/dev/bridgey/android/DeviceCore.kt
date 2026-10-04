package dev.bridgey.android

import android.content.SharedPreferences
import dev.bridgey.core.discovery.DiscoveredPeer
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// MULTI-DEVICE CORE
//
// Every Bridgey installation is an equal peer: an Android phone or tablet, a Mac, or any future
// platform. The Core is built from these pieces, and nothing in them knows which platform is on
// the other side:
//
//   LocalDevice        this installation: deviceId + identity key + name + platform + deviceType
//   DeviceRegistry     trusted peers keyed by deviceId (existing "bridgey.trust" storage), plus
//                      ephemeral presence (endpoints from discovery)
//   PeerSessionManager sessions[deviceId] -> PeerSession, plus pending sockets that have not yet
//                      said who they are. Sessions are fully independent of each other.
//   DeviceRouting      the routing/compatibility seam: which connected device today's
//                      single-peer features use (activePeer). Not a property of any session.
//
// Identity is deviceId + the pinned identity key. Service name, hostname, IP address, port,
// platform and deviceType are discovery hints and never identify, trust, route or reconnect a
// device. Trust is pairwise and never propagated. CONNECTED != ACTIVE.

internal data class LocalDevice(
    val deviceId: String,
    val name: String,
    val deviceType: String,
) {
    val platform: String get() = "android"

    companion object {
        /** Descriptive only: phone or tablet by the standard 600dp smallest-width split. */
        fun deviceTypeFor(smallestScreenWidthDp: Int): String = if (smallestScreenWidthDp >= 600) "tablet" else "phone"
    }
}

// region Presence (discovery, keyed by deviceId)

internal data class PeerEndpoint(val serviceName: String, val host: String, val port: Int)

internal data class PeerPresence(
    val deviceId: String,
    val name: String,
    val platform: String?,
    val deviceType: String?,
    val protocolVersion: Int?,
    val endpoints: List<PeerEndpoint>,
)

internal object DevicePresence {
    /**
     * Groups discovery adverts by their TXT deviceId. One device may be advertised under several
     * service names (mDNS renames, stale re-registrations); one service name is never a device.
     * Adverts without a valid id, unresolved adverts and our own advert are not peers.
     */
    fun group(peers: List<DiscoveredPeer>, localDeviceId: String): Map<String, PeerPresence> =
        peers.filter { it.deviceIdHint != null && it.deviceIdHint != localDeviceId && it.host != null && it.port != null }
            .groupBy { it.deviceIdHint!! }
            .mapValues { (id, adverts) ->
                val sorted = adverts.sortedBy { it.serviceName }
                val first = sorted.first()
                PeerPresence(
                    deviceId = id,
                    name = first.deviceNameHint,
                    platform = first.platformHint,
                    deviceType = first.deviceTypeHint,
                    protocolVersion = first.protocolVersionHint,
                    endpoints = sorted.map { PeerEndpoint(it.serviceName, it.host!!, it.port!!) },
                )
            }
}

// endregion

// region Registry (trust + presence)

internal enum class TrustEvaluation { TRUSTED, UNKNOWN, IDENTITY_MISMATCH }

internal data class TrustRecord(
    val id: String,
    val name: String,
    val identityKey: String,
    // Optional descriptive metadata. Absent in records written by earlier releases; never identity.
    val platform: String? = null,
    val deviceType: String? = null,
    val protocolVersion: Int? = null,
    val lastSeenMillis: Long? = null,
)

/** The existing "bridgey.trust" store (peer.<id>.name / peer.<id>.identityKey), plus metadata keys. */
internal class AndroidTrustRegistry(private val preferences: SharedPreferences) {
    private val mutableIds = MutableStateFlow(loadIds())
    val trustedDeviceIds: StateFlow<Set<String>> = mutableIds.asStateFlow()
    private val mutableDevices = MutableStateFlow(loadDevices())
    val trustedDevices: StateFlow<List<TrustedDevice>> = mutableDevices.asStateFlow()

    @Synchronized
    fun save(deviceId: String, name: String, identityKey: String) {
        preferences.edit()
            .putString("peer.$deviceId.name", name)
            .putString("peer.$deviceId.identityKey", identityKey)
            .apply()
        publish()
    }

    @Synchronized
    fun updateMetadata(deviceId: String, name: String, platform: String?, deviceType: String?, protocolVersion: Int?, lastSeenMillis: Long): Boolean {
        if (identityKey(deviceId) == null) return false
        preferences.edit().apply {
            if (name.isNotBlank()) putString("peer.$deviceId.name", name)
            platform?.let { putString("peer.$deviceId.platform", it) }
            deviceType?.let { putString("peer.$deviceId.deviceType", it) }
            protocolVersion?.let { putInt("peer.$deviceId.protocolVersion", it) }
            putLong("peer.$deviceId.lastSeen", lastSeenMillis)
        }.apply()
        publish()
        return true
    }

    @Synchronized
    fun remove(deviceId: String) {
        preferences.edit().apply {
            METADATA_SUFFIXES.forEach { remove("peer.$deviceId.$it") }
        }.apply()
        publish()
    }

    fun identityKey(deviceId: String): String? = preferences.getString("peer.$deviceId.identityKey", null)

    fun record(deviceId: String): TrustRecord? {
        val identityKey = identityKey(deviceId) ?: return null
        return TrustRecord(
            id = deviceId,
            name = preferences.getString("peer.$deviceId.name", null) ?: "Unknown device",
            identityKey = identityKey,
            platform = preferences.getString("peer.$deviceId.platform", null),
            deviceType = preferences.getString("peer.$deviceId.deviceType", null),
            protocolVersion = preferences.getInt("peer.$deviceId.protocolVersion", 0).takeIf { it > 0 },
            lastSeenMillis = preferences.getLong("peer.$deviceId.lastSeen", 0L).takeIf { it > 0 },
        )
    }

    private fun publish() {
        mutableIds.value = loadIds()
        mutableDevices.value = loadDevices()
    }

    private fun loadIds(): Set<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith("peer.") && it.endsWith(".identityKey") }
        .map { it.removePrefix("peer.").removeSuffix(".identityKey") }
        .toSet()

    private fun loadDevices(): List<TrustedDevice> = loadIds().map { id ->
        TrustedDevice(id, preferences.getString("peer.$id.name", null) ?: "Unknown device")
    }.sortedBy { it.name.lowercase() }

    private companion object {
        val METADATA_SUFFIXES = listOf("name", "identityKey", "platform", "deviceType", "protocolVersion", "lastSeen")
    }
}

internal class DeviceRegistry(private val trust: AndroidTrustRegistry) {
    @Volatile private var presenceByDevice: Map<String, PeerPresence> = emptyMap()

    val trustedDeviceIdsFlow: StateFlow<Set<String>> get() = trust.trustedDeviceIds
    val trustedDevicesFlow: StateFlow<List<TrustedDevice>> get() = trust.trustedDevices

    fun trustedDeviceIds(): Set<String> = trust.trustedDeviceIds.value
    fun device(deviceId: String): TrustRecord? = trust.record(deviceId)
    fun identityKey(deviceId: String): String? = trust.identityKey(deviceId)
    fun presence(): Map<String, PeerPresence> = presenceByDevice

    fun evaluate(deviceId: String, identityKey: String): TrustEvaluation {
        val pinned = trust.identityKey(deviceId) ?: return TrustEvaluation.UNKNOWN
        return if (pinned == identityKey) TrustEvaluation.TRUSTED else TrustEvaluation.IDENTITY_MISMATCH
    }

    /** Creates or refreshes a pairwise trust record. Never rebinds a deviceId to another key. */
    fun remember(deviceId: String, name: String, identityKey: String): Boolean {
        if (evaluate(deviceId, identityKey) == TrustEvaluation.IDENTITY_MISMATCH) return false
        trust.save(deviceId, name, identityKey)
        return true
    }

    /** lastSeen plus the discovery hints for this deviceId; hints never change the identity. */
    fun recordConnection(deviceId: String, name: String, atMillis: Long): Boolean {
        val hints = presenceByDevice[deviceId]
        return trust.updateMetadata(deviceId, name, hints?.platform, hints?.deviceType, hints?.protocolVersion, atMillis)
    }

    fun forget(deviceId: String) = trust.remove(deviceId)

    fun updatePresence(presence: Map<String, PeerPresence>) {
        presenceByDevice = presence
    }

    fun endpoints(deviceId: String): List<PeerEndpoint> = presenceByDevice[deviceId]?.endpoints.orEmpty()
}

// endregion

// region Sessions

internal enum class PeerSessionPhase { CONNECTING, VERIFYING, CONNECTED }

internal enum class PeerConnectionState { OFFLINE, CONNECTING, VERIFYING, CONNECTED }

internal enum class PeerIdentifyResult {
    IDENTIFIED,
    /** Another session already owns this deviceId; the new one must be closed. */
    REJECTED_DUPLICATE,
    /** The socket claims to be this device. */
    REJECTED_SELF,
    /** The session was already identified as a different device. */
    REJECTED_IDENTITY_CHANGE,
    /** Not a session this manager knows (already removed). */
    REJECTED_UNKNOWN,
}

internal data class IdentifyOutcome<S>(val result: PeerIdentifyResult, val displaced: S?)

data class ConnectedPeer(val deviceId: String, val order: Int)

/**
 * Owns which session belongs to which device. One session per deviceId; any number of devices.
 * Thread-safe: each session's read loop runs on its own coroutine. Never touches I/O.
 */
internal class PeerSessionManager<S : Any>(val localDeviceId: String) {
    private class Entry<S>(
        val session: S,
        val initiatedLocally: Boolean,
        var phase: PeerSessionPhase,
        var capabilities: Map<String, Boolean>? = null,
        var connectionOrder: Int? = null,
    )

    private class Pending<S>(val session: S, val initiatedLocally: Boolean, val expectedDeviceId: String?)

    private val entries = linkedMapOf<String, Entry<S>>()
    private val pending = mutableListOf<Pending<S>>()
    private var nextConnectionOrder = 0

    /** A socket that has not yet identified its device (accepted, or dialled before the answer). */
    @Synchronized
    fun addPending(session: S, initiatedLocally: Boolean, expectedDeviceId: String?) {
        if (containsLocked(session)) return
        pending += Pending(session, initiatedLocally, expectedDeviceId)
    }

    /**
     * Binds a session to the deviceId it announced. Duplicate protection is scoped to that
     * deviceId: a connected session always wins; while both are still handshaking, the connection
     * initiated by the lower deviceId wins on both sides, so a simultaneous dial converges instead
     * of tearing down both. `displaced` is a losing session the caller must close.
     */
    @Synchronized
    fun identify(session: S, deviceId: String): IdentifyOutcome<S> {
        deviceIdLocked(session)?.let {
            return IdentifyOutcome(if (it == deviceId) PeerIdentifyResult.IDENTIFIED else PeerIdentifyResult.REJECTED_IDENTITY_CHANGE, null)
        }
        val index = pending.indexOfFirst { it.session === session }
        if (index < 0) return IdentifyOutcome(PeerIdentifyResult.REJECTED_UNKNOWN, null)
        if (deviceId == localDeviceId) return IdentifyOutcome(PeerIdentifyResult.REJECTED_SELF, null)
        val candidate = pending[index]
        var displaced: S? = null
        entries[deviceId]?.let { existing ->
            val preferredInitiator = minOf(localDeviceId, deviceId)
            fun initiator(initiatedLocally: Boolean) = if (initiatedLocally) localDeviceId else deviceId
            if (existing.phase == PeerSessionPhase.CONNECTED ||
                initiator(candidate.initiatedLocally) != preferredInitiator ||
                initiator(existing.initiatedLocally) == preferredInitiator
            ) {
                return IdentifyOutcome(PeerIdentifyResult.REJECTED_DUPLICATE, null)
            }
            displaced = existing.session
        }
        pending.removeAt(index)
        entries[deviceId] = Entry(session, candidate.initiatedLocally, PeerSessionPhase.CONNECTING)
        return IdentifyOutcome(PeerIdentifyResult.IDENTIFIED, displaced)
    }

    @Synchronized
    fun setPhase(phase: PeerSessionPhase, session: S): Boolean {
        val entry = entryLocked(session) ?: return false
        entry.phase = phase
        return true
    }

    @Synchronized
    fun markConnected(session: S): Boolean {
        val entry = entryLocked(session) ?: return false
        entry.phase = PeerSessionPhase.CONNECTED
        if (entry.connectionOrder == null) entry.connectionOrder = nextConnectionOrder++
        return true
    }

    /** Stores the capabilities this peer negotiated (its features.update). Per session, never shared. */
    @Synchronized
    fun setCapabilities(capabilities: Map<String, Boolean>, session: S): Boolean {
        val entry = entryLocked(session) ?: return false
        entry.capabilities = capabilities
        return true
    }

    /**
     * Removes exactly this session object. A rejected duplicate never removes the live session
     * registered for the same deviceId. Returns the device this session was for: its identified
     * deviceId, or the target of a still-pending dial. Null if it was already removed.
     */
    @Synchronized
    fun remove(session: S): String? {
        val index = pending.indexOfFirst { it.session === session }
        if (index >= 0) return pending.removeAt(index).expectedDeviceId
        val id = deviceIdLocked(session) ?: return null
        entries.remove(id)
        return id
    }

    @Synchronized fun contains(session: S): Boolean = containsLocked(session)
    @Synchronized fun deviceId(session: S): String? = deviceIdLocked(session)
    @Synchronized fun session(deviceId: String): S? = entries[deviceId]?.session
    @Synchronized fun phase(session: S): PeerSessionPhase? = entryLocked(session)?.phase
    @Synchronized fun capabilities(deviceId: String): Map<String, Boolean>? = entries[deviceId]?.capabilities

    @Synchronized
    fun state(deviceId: String): PeerConnectionState = when (entries[deviceId]?.phase) {
        null -> PeerConnectionState.OFFLINE
        PeerSessionPhase.CONNECTING -> PeerConnectionState.CONNECTING
        PeerSessionPhase.VERIFYING -> PeerConnectionState.VERIFYING
        PeerSessionPhase.CONNECTED -> PeerConnectionState.CONNECTED
    }

    /** Connected devices in the order they connected (the routing fallback order). */
    @Synchronized
    fun connectedInOrder(): List<ConnectedPeer> = entries.mapNotNull { (id, entry) ->
        if (entry.phase == PeerSessionPhase.CONNECTED) entry.connectionOrder?.let { ConnectedPeer(id, it) } else null
    }.sortedBy { it.order }

    /** A device with a session, or an outgoing dial still waiting for its answer. */
    @Synchronized
    fun isBusy(deviceId: String): Boolean = entries.containsKey(deviceId) || pending.any { it.expectedDeviceId == deviceId }

    @Synchronized
    fun verifyingSession(): S? = entries.values.firstOrNull { it.phase == PeerSessionPhase.VERIFYING }?.session

    @Synchronized fun identifiedSessions(): List<S> = entries.values.map { it.session }
    @Synchronized fun pendingSessions(): List<S> = pending.map { it.session }

    private fun containsLocked(session: S) = pending.any { it.session === session } || entryLocked(session) != null
    private fun entryLocked(session: S) = entries.values.firstOrNull { it.session === session }
    private fun deviceIdLocked(session: S) = entries.entries.firstOrNull { it.value.session === session }?.key
}

/**
 * Accepts every incoming connection on its own coroutine, so one peer's long-lived session never
 * blocks another peer from connecting (the previous loop served one socket at a time).
 */
internal fun acceptConnections(
    server: ServerSocket,
    scope: CoroutineScope,
    onFailure: (Throwable) -> Unit = {},
    handle: (Socket) -> Unit,
) {
    scope.launch {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (error: java.io.IOException) {
                if (!server.isClosed) onFailure(error)
                break
            }
            scope.launch {
                // One peer's socket failing must never take down the process or other sessions.
                runCatching { handle(socket) }.onFailure { runCatching { socket.close() } }
            }
        }
    }
}

/**
 * One serial writer per session. Writes fanned out to several peers are queued here instead of
 * being written one after another on the caller's thread, so a peer that stops reading (zero TCP
 * window) can only ever block its own queue - never another session's writes or the caller.
 * Writes of one session stay in order and never overlap.
 */
internal class SessionWriter(private val scope: CoroutineScope) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    fun enqueue(write: () -> Unit) {
        scope.launch(dispatcher) { runCatching(write) }
    }
}

// endregion

// region Routing compatibility seam

enum class DeviceRoutingMode(val key: String) {
    /** Several devices may be connected; one is selected for today's single-peer features. */
    SINGLE_ACTIVE("singleActive"),
    /**
     * Several devices may be active. Features will route by deviceId once they are migrated;
     * until then the legacy single-peer features still read activePeer.
     */
    MULTIPLE_ACTIVE("multipleActive"),
    ;

    companion object {
        fun fromKey(key: String?): DeviceRoutingMode = entries.firstOrNull { it.key == key } ?: SINGLE_ACTIVE
    }
}

object DeviceRouting {
    /**
     * The device today's single-peer features use. A pure function of the connected sessions and
     * the user's preference: it never changes trust, capabilities or connection state.
     * Preferred device if connected, otherwise the earliest-connected device.
     */
    @Suppress("UNUSED_PARAMETER")
    fun activePeer(mode: DeviceRoutingMode, preferredDeviceId: String?, connected: List<ConnectedPeer>): String? {
        // Both modes select the same legacy peer until features route by deviceId themselves.
        if (preferredDeviceId != null && connected.any { it.deviceId == preferredDeviceId }) return preferredDeviceId
        return connected.minByOrNull { it.order }?.deviceId
    }

    /**
     * One-time migration from the single-device era: the only trusted device becomes the
     * preferred one, so an existing user keeps routing features to the same device.
     */
    fun migratedPreferredDeviceId(stored: String?, migrated: Boolean, trustedDeviceIds: Set<String>): String? {
        if (stored != null) return stored
        if (migrated || trustedDeviceIds.size != 1) return null
        return trustedDeviceIds.first()
    }
}

// endregion

// region Reconnect / features.update

internal object ReconnectPlanner {
    /**
     * Trusted, discovered devices without a session or dial in flight. Per pair the lower deviceId
     * dials (the higher one accepts); no platform takes part in the decision.
     */
    fun discoveryDialTargets(
        localDeviceId: String,
        trustedDeviceIds: Set<String>,
        presence: Map<String, PeerPresence>,
        endpointIndex: (String) -> Int = { 0 },
        isBusy: (String) -> Boolean,
    ): List<Pair<String, PeerEndpoint>> = presence.keys.sorted().mapNotNull { id ->
        if (id !in trustedDeviceIds || localDeviceId >= id || isBusy(id)) return@mapNotNull null
        // Retries rotate through a device's endpoints, so a stale advert cannot pin every attempt.
        val endpoints = presence.getValue(id).endpoints
        if (endpoints.isEmpty()) null else id to endpoints[endpointIndex(id).mod(endpoints.size)]
    }
}

internal object PeerFeatureState {
    /**
     * The real local feature state for one peer. Every connected session receives this, active or
     * not: inactivity is routing, never a capability.
     */
    fun payload(deviceId: String, isEnabled: (BridgeyFeature, String) -> Boolean): Map<String, Boolean> =
        BridgeyFeature.entries.associate { it.key to isEnabled(it, deviceId) }
}

// endregion
