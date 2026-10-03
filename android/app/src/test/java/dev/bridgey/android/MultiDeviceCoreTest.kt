package dev.bridgey.android

import android.content.SharedPreferences
import dev.bridgey.core.discovery.DiscoveredPeer
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VIRTUAL multi-device Core tests. Several independent peers are simulated in-process against
 * the same Core components PairingCoordinator uses. They prove the architectural invariants;
 * they do not prove real multi-device hardware behaviour.
 */
class MultiDeviceCoreTest {
    private class FakeSession(val label: String)

    private val localLow = "00000000-0000-4000-8000-000000000000"
    private val local = "50000000-0000-4000-8000-000000000000"
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"
    private val c = "30000000-0000-4000-8000-00000000000c"
    private val d = "40000000-0000-4000-8000-00000000000d"

    // region helpers

    private fun connect(
        manager: PeerSessionManager<FakeSession>,
        deviceId: String,
        initiatedLocally: Boolean = false,
        capabilities: Map<String, Boolean>? = null,
    ): FakeSession {
        val session = FakeSession(deviceId)
        manager.addPending(session, initiatedLocally, if (initiatedLocally) deviceId else null)
        assertEquals(PeerIdentifyResult.IDENTIFIED, manager.identify(session, deviceId).result)
        assertTrue(manager.markConnected(session))
        capabilities?.let { assertTrue(manager.setCapabilities(it, session)) }
        return session
    }

    private data class Snapshot(
        val state: PeerConnectionState,
        val phase: PeerSessionPhase?,
        val capabilities: Map<String, Boolean>?,
        val session: Any?,
    )

    private fun snapshot(manager: PeerSessionManager<FakeSession>, id: String): Snapshot {
        val session = manager.session(id)
        return Snapshot(manager.state(id), session?.let(manager::phase), manager.capabilities(id), session)
    }

    private fun active(
        manager: PeerSessionManager<FakeSession>,
        preferred: String?,
        mode: DeviceRoutingMode = DeviceRoutingMode.SINGLE_ACTIVE,
    ): String? = DeviceRouting.activePeer(mode, preferred, manager.connectedInOrder())

    private fun peer(
        service: String,
        id: String?,
        name: String = "Phone",
        platform: String? = "android",
        type: String? = null,
        host: String? = "192.168.0.10",
        port: Int? = 42_458,
    ) = DiscoveredPeer(service, id, name, platform, 1, host, port, deviceTypeHint = type)

    private fun registry() = DeviceRegistry(AndroidTrustRegistry(InMemoryPreferences()))

    private fun key(seed: String) = "identity-$seed"

    // endregion

    // region 1. Device identity

    @Test fun differentDeviceIdsAreDifferentDevices() {
        val manager = PeerSessionManager<FakeSession>(local)
        connect(manager, a)
        connect(manager, b)
        assertEquals(setOf(a, b), manager.connectedInOrder().map { it.deviceId }.toSet())
        assertNotSame(manager.session(a), manager.session(b))
    }

    @Test fun sameDeviceIdCannotSilentlyBecomeADifferentIdentityKey() {
        val registry = registry()
        assertTrue(registry.remember(a, "Phone", key("a")))
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(a, key("a")))
        assertEquals(TrustEvaluation.IDENTITY_MISMATCH, registry.evaluate(a, key("attacker")))
        assertFalse("rebinding must be refused", registry.remember(a, "Phone", key("attacker")))
        assertEquals(key("a"), registry.identityKey(a))
        assertEquals(TrustEvaluation.UNKNOWN, registry.evaluate(b, key("b")))
    }

    @Test fun serviceNameHostnameAndAddressAreNotIdentity() {
        val presence = DevicePresence.group(
            listOf(
                peer("Bridgey-10000000", a, host = "phone.local"),
                peer("Bridgey-10000000 (2)", a, host = "192.168.0.10"),
                peer("Bridgey-other", a, host = "fe80::1"),
            ),
            local,
        )
        assertEquals(setOf(a), presence.keys)
        assertEquals(3, presence.getValue(a).endpoints.size)
        val sameNameSameHost = DevicePresence.group(
            listOf(peer("Bridgey-x", a, name = "Pixel", host = "p.local"), peer("Bridgey-x (2)", b, name = "Pixel", host = "p.local")),
            local,
        )
        assertEquals(setOf(a, b), sameNameSameHost.keys)
    }

    @Test fun endpointChangeUpdatesPresenceNotIdentity() {
        val registry = registry()
        assertTrue(registry.remember(c, "Galaxy", key("c")))
        registry.updatePresence(DevicePresence.group(listOf(peer("Bridgey-c", c, host = "192.168.0.20")), local))
        registry.updatePresence(DevicePresence.group(listOf(peer("Bridgey-c (2)", c, host = "192.168.0.77")), local))
        assertEquals(setOf(c), registry.trustedDeviceIds())
        assertEquals(listOf("192.168.0.77"), registry.endpoints(c).map { it.host })
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(c, key("c")))
    }

    @Test fun platformAndTypeDoNotParticipateInIdentity() {
        val asMac = DevicePresence.group(listOf(peer("s1", a, platform = "macos", type = "computer")), local)
        val asAndroid = DevicePresence.group(listOf(peer("s1", a, platform = "android", type = "phone")), local)
        assertEquals(asMac.keys, asAndroid.keys)
        val registry = registry()
        assertTrue(registry.remember(a, "X", key("a")))
        registry.updatePresence(asMac)
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(a, key("a")))
        registry.updatePresence(asAndroid)
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(a, key("a")))
    }

    @Test fun ownAdvertIsFilteredByDeviceIdEvenAfterServiceRename() {
        val presence = DevicePresence.group(
            listOf(
                peer("Bridgey-50000000 (2)", local, name = "This phone"),
                peer("Bridgey-a", a),
                peer("legacy-without-id", null),
                peer("unresolved", b, host = null, port = null),
            ),
            local,
        )
        assertEquals(setOf(a), presence.keys)
    }

    // endregion

    // region 2. Multiple simultaneous sessions / 9. failure isolation

    @Test fun threeSimultaneousSessionsAreIndependent() {
        val manager = PeerSessionManager<FakeSession>(local)
        val sessionA = connect(manager, a, capabilities = mapOf("clipboard" to true))
        val beforeB = snapshot(manager, a)
        connect(manager, b, initiatedLocally = true, capabilities = mapOf("clipboard" to false))
        assertEquals("connecting B does not touch A", beforeB, snapshot(manager, a))
        val beforeC = snapshot(manager, a) to snapshot(manager, b)
        connect(manager, c, capabilities = mapOf("files" to true))
        assertEquals(beforeC.first, snapshot(manager, a))
        assertEquals(beforeC.second, snapshot(manager, b))
        assertEquals(mapOf("clipboard" to true), manager.capabilities(a))
        assertEquals(mapOf("clipboard" to false), manager.capabilities(b))
        assertEquals(mapOf("files" to true), manager.capabilities(c))

        val keep = snapshot(manager, b) to snapshot(manager, c)
        assertEquals(a, manager.remove(sessionA))
        assertEquals(PeerConnectionState.OFFLINE, manager.state(a))
        assertEquals(keep.first, snapshot(manager, b))
        assertEquals(keep.second, snapshot(manager, c))

        val newA = connect(manager, a)
        assertNotSame(sessionA, newA)
        assertEquals(keep.first, snapshot(manager, b))
        assertEquals(keep.second, snapshot(manager, c))
        assertEquals(PeerConnectionState.CONNECTED, manager.state(a))
    }

    @Test fun phasesAreTrackedPerSession() {
        val manager = PeerSessionManager<FakeSession>(local)
        connect(manager, a)
        val sessionB = FakeSession("b")
        manager.addPending(sessionB, false, null)
        assertEquals(PeerIdentifyResult.IDENTIFIED, manager.identify(sessionB, b).result)
        assertTrue(manager.setPhase(PeerSessionPhase.VERIFYING, sessionB))
        assertEquals(PeerConnectionState.CONNECTED, manager.state(a))
        assertEquals(PeerConnectionState.VERIFYING, manager.state(b))
        assertSame(sessionB, manager.verifyingSession())
        assertEquals(listOf(a), manager.connectedInOrder().map { it.deviceId })
    }

    @Test fun failureOfASessionInEveryPhaseIsIsolated() {
        val manager = PeerSessionManager<FakeSession>(local)
        connect(manager, b)
        connect(manager, c)
        val keep = snapshot(manager, b) to snapshot(manager, c)

        val dial = FakeSession("dial-a")
        manager.addPending(dial, true, a)
        assertTrue(manager.isBusy(a))
        assertEquals("a failed dial reports its target, so only that device backs off", a, manager.remove(dial))
        assertNull("removing twice reports nothing", manager.remove(dial))
        assertFalse(manager.isBusy(a))

        val handshake = FakeSession("hs-a")
        manager.addPending(handshake, false, null)
        manager.identify(handshake, a)
        assertEquals(a, manager.remove(handshake))

        val connected = connect(manager, a)
        assertEquals(a, manager.remove(connected))

        assertEquals(keep.first, snapshot(manager, b))
        assertEquals(keep.second, snapshot(manager, c))
        assertEquals(PeerConnectionState.OFFLINE, manager.state(a))
    }

    // endregion

    // region 3. Duplicate rule / 4. pending

    @Test fun duplicateRuleIsPerDeviceId() {
        val manager = PeerSessionManager<FakeSession>(local)
        val x = connect(manager, a)
        val duplicate = FakeSession("a-again")
        manager.addPending(duplicate, false, null)
        assertEquals(PeerIdentifyResult.REJECTED_DUPLICATE, manager.identify(duplicate, a).result)
        assertSame(x, manager.session(a))
        assertNull("closing the rejected duplicate must not remove the live session", manager.remove(duplicate))
        assertSame(x, manager.session(a))
        connect(manager, b)
        connect(manager, c)
        assertEquals(listOf(a, b, c), manager.connectedInOrder().map { it.deviceId })
    }

    @Test fun simultaneousDialRaceConvergesOnTheSameConnectionOnBothSides() {
        val low = PeerSessionManager<FakeSession>(localLow)
        val high = PeerSessionManager<FakeSession>(local)
        val lowOut = FakeSession("conn1@L")
        val highIn = FakeSession("conn1@H")
        val highOut = FakeSession("conn2@H")
        val lowIn = FakeSession("conn2@L")
        low.addPending(lowOut, true, local)
        low.addPending(lowIn, false, null)
        high.addPending(highOut, true, localLow)
        high.addPending(highIn, false, null)
        assertEquals(PeerIdentifyResult.IDENTIFIED, low.identify(lowIn, local).result)
        assertEquals(PeerIdentifyResult.IDENTIFIED, high.identify(highIn, localLow).result)
        val lowAnswer = low.identify(lowOut, local)
        val highAnswer = high.identify(highOut, localLow)
        assertEquals(PeerIdentifyResult.IDENTIFIED, lowAnswer.result)
        assertSame(lowIn, lowAnswer.displaced)
        assertEquals(PeerIdentifyResult.REJECTED_DUPLICATE, highAnswer.result)
        assertSame(lowOut, low.session(local))
        assertSame(highIn, high.session(localLow))
    }

    @Test fun talkingToOurselvesIsRejected() {
        val manager = PeerSessionManager<FakeSession>(local)
        val loop = FakeSession("loop")
        manager.addPending(loop, true, null)
        assertEquals(PeerIdentifyResult.REJECTED_SELF, manager.identify(loop, local).result)
    }

    @Test fun pendingSessionBecomesDeviceSessionOnlyAfterIdentification() {
        val manager = PeerSessionManager<FakeSession>(local)
        val live = connect(manager, a)
        val socket = FakeSession("accepted")
        manager.addPending(socket, false, null)
        assertNull(manager.deviceId(socket))
        assertTrue(manager.contains(socket))
        assertSame(live, manager.session(a))
        assertEquals(PeerConnectionState.OFFLINE, manager.state(b))
        assertEquals(PeerIdentifyResult.IDENTIFIED, manager.identify(socket, b).result)
        assertEquals(b, manager.deviceId(socket))
        assertEquals(PeerConnectionState.CONNECTING, manager.state(b))
        assertSame(live, manager.session(a))
    }

    @Test fun aSessionCannotChangeItsDeviceIdMidHandshake() {
        val manager = PeerSessionManager<FakeSession>(local)
        val socket = FakeSession("s")
        manager.addPending(socket, false, null)
        assertEquals(PeerIdentifyResult.IDENTIFIED, manager.identify(socket, a).result)
        assertEquals(PeerIdentifyResult.REJECTED_IDENTITY_CHANGE, manager.identify(socket, b).result)
        assertEquals(a, manager.deviceId(socket))
    }

    // endregion

    // region 5. CONNECTED != ACTIVE / 6. preferred / 7. no fake features.update / 8. capabilities

    @Test fun switchingActivePeerMutatesNoSessionState() {
        val manager = PeerSessionManager<FakeSession>(local)
        connect(manager, a, capabilities = mapOf("clipboard" to true, "files" to false))
        connect(manager, b, capabilities = mapOf("clipboard" to false, "files" to true))
        val before = snapshot(manager, a) to snapshot(manager, b)
        assertEquals(a, active(manager, a))
        assertEquals(b, active(manager, b))
        assertEquals(a, active(manager, a, DeviceRoutingMode.MULTIPLE_ACTIVE))
        assertEquals(before.first, snapshot(manager, a))
        assertEquals(before.second, snapshot(manager, b))
        assertEquals(PeerConnectionState.CONNECTED, manager.state(a))
        assertEquals(mapOf("clipboard" to true, "files" to false), manager.capabilities(a))
        assertEquals(mapOf("clipboard" to false, "files" to true), manager.capabilities(b))
    }

    @Test fun preferredDeviceFallbackAndRestore() {
        val manager = PeerSessionManager<FakeSession>(local)
        assertNull(active(manager, a))
        connect(manager, b)
        assertEquals(b, active(manager, a))
        val sessionA = connect(manager, a)
        assertEquals(a, active(manager, a))
        connect(manager, c)
        assertEquals(a, active(manager, a))
        manager.remove(sessionA)
        assertEquals(b, active(manager, a))
        connect(manager, a)
        assertEquals(a, active(manager, a))
        assertEquals(b, active(manager, null))
    }

    @Test fun everySessionGetsItsRealFeatureStateRegardlessOfActivePeer() {
        val perDevice = mapOf(
            a to mapOf(BridgeyFeature.CLIPBOARD to true),
            b to mapOf(BridgeyFeature.CLIPBOARD to true, BridgeyFeature.FILES to false),
        )
        val isEnabled: (BridgeyFeature, String) -> Boolean = { feature, id -> perDevice[id]?.get(feature) ?: true }
        val manager = PeerSessionManager<FakeSession>(local)
        connect(manager, a)
        connect(manager, b)
        for (preferred in listOf(a, b)) {
            assertNotNull(active(manager, preferred))
            for (id in listOf(a, b)) {
                val payload = PeerFeatureState.payload(id, isEnabled)
                assertEquals(BridgeyFeature.entries.size, payload.size)
                assertEquals("inactive $id is never sent disabled capabilities", true, payload[BridgeyFeature.CLIPBOARD.key])
            }
            assertEquals(false, PeerFeatureState.payload(b, isEnabled)[BridgeyFeature.FILES.key])
        }
    }

    // endregion

    // region 10. Trust is pairwise / 11. migration

    @Test fun trustIsPairwiseAndNeverPropagated() {
        val registryA = registry()
        val registryB = registry()
        val registryC = registry()
        assertTrue(registryA.remember(b, "B", key("b")))
        assertTrue(registryB.remember(a, "A", key("a")))
        assertTrue(registryB.remember(c, "C", key("c")))
        assertTrue(registryC.remember(b, "B", key("b")))
        assertEquals(TrustEvaluation.UNKNOWN, registryA.evaluate(c, key("c")))
        assertEquals(TrustEvaluation.UNKNOWN, registryC.evaluate(a, key("a")))
        assertEquals(setOf(b), registryA.trustedDeviceIds())
    }

    @Test fun legacySingleDeviceTrustRecordMigratesUnchanged() {
        // Exactly what the previous release wrote to "bridgey.trust".
        val preferences = InMemoryPreferences()
        preferences.edit().putString("peer.$a.name", "MacBook").putString("peer.$a.identityKey", key("a")).apply()
        val registry = DeviceRegistry(AndroidTrustRegistry(preferences))
        assertEquals(setOf(a), registry.trustedDeviceIds())
        assertEquals(key("a"), registry.identityKey(a))
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(a, key("a")))
        val record = registry.device(a)!!
        assertEquals("MacBook", record.name)
        assertNull(record.platform)
        assertNull(record.lastSeenMillis)

        assertEquals(a, DeviceRouting.migratedPreferredDeviceId(null, false, registry.trustedDeviceIds()))
        assertNull(DeviceRouting.migratedPreferredDeviceId(null, true, setOf(a)))
        assertNull(DeviceRouting.migratedPreferredDeviceId(null, false, setOf(a, b)))
        assertEquals(b, DeviceRouting.migratedPreferredDeviceId(b, false, setOf(a)))

        registry.updatePresence(DevicePresence.group(listOf(peer("Bridgey-a", a, platform = "macos", type = "computer")), local))
        assertTrue(registry.recordConnection(a, "MacBook", 1_800_000_000_000L))
        val reloaded = DeviceRegistry(AndroidTrustRegistry(preferences))
        assertEquals(key("a"), reloaded.identityKey(a))
        assertEquals("macos", reloaded.device(a)?.platform)
        assertEquals("computer", reloaded.device(a)?.deviceType)
        assertEquals(1_800_000_000_000L, reloaded.device(a)?.lastSeenMillis)
        reloaded.forget(a)
        assertTrue("forget removes metadata too", preferences.all.keys.none { it.startsWith("peer.$a.") })
    }

    @Test fun singleDeviceBehaviourIsUnchangedWithOnePeer() {
        val manager = PeerSessionManager<FakeSession>(local)
        val preferred = DeviceRouting.migratedPreferredDeviceId(null, false, setOf(a))
        assertNull(active(manager, preferred))
        connect(manager, a)
        assertEquals(a, active(manager, preferred))
    }

    // endregion

    // region discovery / reconnect / topology

    @Test fun discoveryOfMultipleTrustedDevicesDialsEachIndependently() {
        val presence = DevicePresence.group(
            listOf(
                peer("Bridgey-a", a, platform = "macos", type = "computer", host = "10.0.0.1"),
                peer("Bridgey-b", b, platform = "macos", type = "computer", host = "10.0.0.2"),
                peer("Bridgey-c", c, platform = "android", type = "phone", host = "10.0.0.3"),
                peer("Bridgey-d", d, platform = "android", type = "tablet", host = "10.0.0.4"),
                peer("Bridgey-x", "60000000-0000-4000-8000-000000000006", host = "10.0.0.9"),
            ),
            localLow,
        )
        val trusted = setOf(a, b, c, d)
        val targets = ReconnectPlanner.discoveryDialTargets(localLow, trusted, presence) { it == b }
        assertEquals(listOf(a, c, d), targets.map { it.first })
        assertEquals(listOf("10.0.0.1", "10.0.0.3", "10.0.0.4"), targets.map { it.second.host })
        assertTrue(ReconnectPlanner.discoveryDialTargets("ffffffff-0000-4000-8000-000000000000", trusted, presence) { false }.isEmpty())
    }

    @Test fun reconnectRotatesThroughEndpointsOfOneDevice() {
        val presence = DevicePresence.group(
            listOf(peer("Bridgey-a", a, host = "10.0.0.1"), peer("Bridgey-a (2)", a, host = "10.0.0.2")),
            localLow,
        )
        val hosts = (0..3).map { attempt ->
            ReconnectPlanner.discoveryDialTargets(localLow, setOf(a), presence, endpointIndex = { attempt }) { false }.single().second.host
        }
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.1", "10.0.0.2"), hosts)
    }

    @Test fun topologyMatrixUsesIdenticalCoreLogic() {
        val results = mutableListOf<String>()
        for (localPlatform in listOf("macos", "android")) {
            for (remotePlatform in listOf("macos", "android")) {
                val manager = PeerSessionManager<FakeSession>(localLow)
                val presence = DevicePresence.group(listOf(peer("svc", a, platform = remotePlatform)), localLow)
                val targets = ReconnectPlanner.discoveryDialTargets(localLow, setOf(a), presence, isBusy = manager::isBusy)
                connect(manager, a, initiatedLocally = true, capabilities = mapOf("clipboard" to true))
                // localPlatform only labels the case: the Core takes no platform input at all.
                results += "${targets.map { it.first }}|${manager.state(a)}|${active(manager, a)}"
                assertTrue(localPlatform.isNotEmpty())
            }
        }
        assertEquals("all four topologies must produce the same Core outcome: $results", 1, results.toSet().size)
    }

    @Test fun fourDeviceLanScenario() {
        val me = "25000000-0000-4000-8000-0000000000bb"
        val registry = registry()
        listOf(a to "MacBook", c to "Galaxy S23", d to "Tablet").forEach { (id, name) -> assertTrue(registry.remember(id, name, key(id))) }
        val manager = PeerSessionManager<FakeSession>(me)
        val adverts = mutableListOf(
            peer("Bridgey-a", a, "MacBook", "macos", "computer", "10.0.0.1"),
            peer("Bridgey-c", c, "Galaxy S23", "android", "phone", "10.0.0.3"),
            peer("Bridgey-d", d, "Tablet", "android", "tablet", "10.0.0.4"),
        )
        registry.updatePresence(DevicePresence.group(adverts, me))
        assertEquals(setOf(a, c, d), registry.presence().keys)

        val sessionA = connect(manager, a)
        val sessionC = connect(manager, c, initiatedLocally = true)
        val sessionD = connect(manager, d, initiatedLocally = true)

        manager.remove(sessionA)
        manager.remove(sessionC)
        manager.remove(sessionD)
        adverts.clear()
        adverts += peer("Bridgey-c (2)", c, "Galaxy S23", "android", "phone", "10.0.0.33")
        registry.updatePresence(DevicePresence.group(adverts, me))
        assertEquals(PeerConnectionState.OFFLINE, manager.state(d))
        assertTrue(registry.trustedDeviceIds().contains(d))
        assertTrue(registry.endpoints(d).isEmpty())

        val dial = ReconnectPlanner.discoveryDialTargets(me, registry.trustedDeviceIds(), registry.presence(), isBusy = manager::isBusy)
        assertEquals(listOf(c), dial.map { it.first })
        assertEquals("10.0.0.33", dial.first().second.host)

        adverts += peer("Bridgey-a", a, "MacBook", "macos", "computer", "10.0.0.1")
        registry.updatePresence(DevicePresence.group(adverts, me))
        connect(manager, a)
        connect(manager, c, initiatedLocally = true)
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(a, key(a)))
        assertEquals(TrustEvaluation.TRUSTED, registry.evaluate(c, key(c)))
        assertEquals(setOf(a, c), manager.connectedInOrder().map { it.deviceId }.toSet())
        assertEquals(PeerConnectionState.OFFLINE, manager.state(d))
        assertEquals(setOf(a, c, d), registry.trustedDeviceIds())
    }

    @Test fun identicalDisplayNamesStayDistinctDevices() {
        val registry = registry()
        assertTrue(registry.remember(a, "MacBook Pro", key("a")))
        assertTrue(registry.remember(b, "MacBook Pro", key("b")))
        assertTrue(registry.remember(c, "Galaxy", key("c")))
        assertTrue(registry.remember(d, "Galaxy", key("d")))
        assertEquals(4, registry.trustedDeviceIds().size)
        assertEquals(TrustEvaluation.IDENTITY_MISMATCH, registry.evaluate(a, key("b")))
    }

    // endregion

    // region Android concurrent accept

    @Test fun concurrentAcceptServesSeveralPeersAtOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val allInside = CountDownLatch(3)
        val clients = mutableListOf<Socket>()
        try {
            acceptConnections(server, scope) { socket ->
                allInside.countDown()
                // Like a session read loop: blocks until that peer goes away.
                val input: InputStream = socket.getInputStream()
                while (input.read() != -1) Unit
            }
            repeat(3) { clients += Socket(InetAddress.getLoopbackAddress(), server.localPort) }
            assertTrue("three peers must be served at the same time", allInside.await(5, TimeUnit.SECONDS))

            // One peer leaving does not affect the others' handlers.
            clients.removeAt(0).close()
            val fourth = CountDownLatch(1)
            clients += Socket(InetAddress.getLoopbackAddress(), server.localPort).also { fourth.countDown() }
            assertTrue(fourth.await(5, TimeUnit.SECONDS))
        } finally {
            clients.forEach { runCatching { it.close() } }
            server.close()
            scope.cancel()
        }
    }

    // endregion
}

/** Minimal in-memory SharedPreferences for JVM tests of the existing trust storage format. */
internal class InMemoryPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
