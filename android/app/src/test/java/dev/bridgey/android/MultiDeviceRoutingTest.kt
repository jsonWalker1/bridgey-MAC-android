package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MD-1 routing foundation: addressed delivery, receive identity, per-device lifecycle, device-
 * scoped authorization changes, the device directory and feature applicability. In-memory peers
 * against the same Core components PairingCoordinator uses; no network.
 */
class MultiDeviceRoutingTest {
    private class FakeSession(val label: String) {
        val delivered = mutableListOf<String>()
    }

    /** A feature-style per-device store driven only by lifecycle events. */
    private class PerDeviceStore : PeerLifecycleObserver {
        val state = mutableMapOf<String, String>()
        val events = mutableListOf<String>()
        override fun sessionStarted(deviceId: String) { state[deviceId] = "live"; events += "started:$deviceId" }
        override fun sessionEnded(deviceId: String) { state.remove(deviceId); events += "ended:$deviceId" }
        override fun authorizationChanged(deviceId: String) { events += "auth:$deviceId" }
    }

    private val local = "50000000-0000-4000-8000-000000000000"
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"

    private fun connect(manager: PeerSessionManager<FakeSession>, deviceId: String): FakeSession {
        val session = FakeSession(deviceId)
        manager.addPending(session, initiatedLocally = false, expectedDeviceId = null)
        assertEquals(PeerIdentifyResult.IDENTIFIED, manager.identify(session, deviceId).result)
        assertTrue(manager.markConnected(session))
        return session
    }

    private fun send(manager: PeerSessionManager<FakeSession>, to: String, message: String): Boolean =
        manager.deliver(to) { session ->
            session.delivered += message
            true
        }

    // region Addressing and receive identity

    @Test
    fun twoPeerSessionsCoexist() {
        val manager = PeerSessionManager<FakeSession>(local)
        val sessionA = connect(manager, a)
        val sessionB = connect(manager, b)
        assertSame(sessionA, manager.connectedSession(a))
        assertSame(sessionB, manager.connectedSession(b))
        assertNotSame(sessionA, sessionB)
    }

    @Test
    fun sendToAReachesOnlyAAndSendToBReachesOnlyB() {
        val manager = PeerSessionManager<FakeSession>(local)
        val sessionA = connect(manager, a)
        val sessionB = connect(manager, b)

        assertTrue(send(manager, a, "one"))
        assertEquals(listOf("one"), sessionA.delivered)
        assertEquals("sending to A never touches B", emptyList<String>(), sessionB.delivered)

        assertTrue(send(manager, b, "two"))
        assertEquals(listOf("one"), sessionA.delivered)
        assertEquals(listOf("two"), sessionB.delivered)
    }

    @Test
    fun sendNeverFallsBackToAnotherDeviceOrAnUnauthenticatedSession() {
        val manager = PeerSessionManager<FakeSession>(local)
        val sessionA = connect(manager, a)
        assertFalse("B is not connected: nothing is delivered, not even to A", send(manager, b, "x"))
        assertEquals(emptyList<String>(), sessionA.delivered)

        val handshaking = FakeSession(b)
        manager.addPending(handshaking, initiatedLocally = false, expectedDeviceId = null)
        assertEquals(PeerIdentifyResult.IDENTIFIED, manager.identify(handshaking, b).result)
        assertFalse("a session that is not yet authenticated is not addressable", send(manager, b, "y"))
        assertEquals(emptyList<String>(), handshaking.delivered)
    }

    @Test
    fun receiveIdentifiesAVersusB() {
        val manager = PeerSessionManager<FakeSession>(local)
        val sessionA = connect(manager, a)
        val sessionB = connect(manager, b)
        assertEquals(a, manager.connectedDeviceId(sessionA))
        assertEquals(b, manager.connectedDeviceId(sessionB))

        val pending = FakeSession("pending")
        manager.addPending(pending, initiatedLocally = false, expectedDeviceId = null)
        assertNull("an unidentified socket has no sender identity", manager.connectedDeviceId(pending))
    }

    @Test
    fun addressingIsIndependentOfTheRoutedPeer() {
        val manager = PeerSessionManager<FakeSession>(local)
        val sessionA = connect(manager, a)
        val sessionB = connect(manager, b)
        assertEquals(a, DeviceRouting.activePeer(DeviceRoutingMode.SINGLE_ACTIVE, a, manager.connectedInOrder()))
        assertTrue(send(manager, b, "to-b"))
        assertEquals(listOf("to-b"), sessionB.delivered)
        assertEquals("the routed peer is not involved in addressed delivery", emptyList<String>(), sessionA.delivered)
    }

    // endregion

    // region Lifecycle

    @Test
    fun sessionEndedForAKeepsBState() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        val sessionA = FakeSession(a)
        val sessionB = FakeSession(b)
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(a, sessionA)
        lifecycle.sessionStarted(b, sessionB)
        lifecycle.sessionEnded(a, sessionA)
        assertNull(store.state[a])
        assertEquals("live", store.state[b])
        assertEquals(listOf("started:$a", "started:$b", "ended:$a"), store.events)
    }

    @Test
    fun sessionEndedIsEmittedOncePerStartedSessionOnly() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        val session = FakeSession(a)
        lifecycle.addObserver(store)
        lifecycle.sessionEnded(a, session) // handshake failed before it ever started
        lifecycle.sessionStarted(a, session)
        lifecycle.sessionStarted(a, session) // duplicate start is ignored
        lifecycle.sessionEnded(a, session)
        lifecycle.sessionEnded(a, session)
        assertEquals(listOf("started:$a", "ended:$a"), store.events)
    }

    @Test
    fun anOlderSessionEndingNeverEndsTheNewerSessionOfTheSameDevice() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        val old = FakeSession(a)
        val new = FakeSession(a)
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(a, old)
        lifecycle.sessionStarted(a, new) // reconnect before the old end was seen
        lifecycle.sessionEnded(a, old) // late end of the old session
        assertEquals("live", store.state[a])
        assertEquals(listOf("started:$a", "ended:$a", "started:$a"), store.events)
        lifecycle.sessionEnded(a, new)
        assertNull(store.state[a])
    }

    @Test
    fun aStartForASessionThatIsNoLongerCurrentIsIgnored() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(a, FakeSession(a)) { false }
        assertEquals("a session that already ended must not leave a ghost start", emptyList<String>(), store.events)
        assertEquals(emptySet<String>(), lifecycle.startedDeviceIds())
    }

    @Test
    fun concurrentStartAndEndOfManySessionsStayPaired() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        lifecycle.addObserver(store)
        val threads = (0 until 8).map { index ->
            Thread {
                val device = "device-$index"
                repeat(200) {
                    val session = FakeSession(device)
                    lifecycle.sessionStarted(device, session)
                    lifecycle.sessionEnded(device, session)
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(emptySet<String>(), lifecycle.startedDeviceIds())
        assertTrue(store.state.isEmpty())
        assertEquals(8 * 200 * 2, store.events.size)
    }

    @Test
    fun aThrowingObserverBreaksNeitherTheCoreNorOtherObservers() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        lifecycle.addObserver(object : PeerLifecycleObserver {
            override fun sessionStarted(deviceId: String) = error("observer bug")
        })
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(a, FakeSession(a))
        assertEquals(listOf("started:$a"), store.events)
    }

    @Test
    fun removedObserverReceivesNothing() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        lifecycle.addObserver(store)
        lifecycle.addObserver(store)
        lifecycle.removeObserver(store)
        lifecycle.sessionStarted(a, FakeSession(a))
        assertEquals(emptyList<String>(), store.events)
        assertEquals(setOf(a), lifecycle.startedDeviceIds())
    }

    // endregion

    // region Authorization

    @Test
    fun perDeviceGrantChangeIsScopedToThatDevice() {
        val changed = DeviceAuthorization.changedDevices(
            oldGlobal = mapOf(BridgeyFeature.CLIPBOARD to true),
            newGlobal = mapOf(BridgeyFeature.CLIPBOARD to true),
            oldPerDevice = mapOf(a to mapOf(BridgeyFeature.CLIPBOARD to true), b to mapOf(BridgeyFeature.FILES to true)),
            newPerDevice = mapOf(a to mapOf(BridgeyFeature.CLIPBOARD to false), b to mapOf(BridgeyFeature.FILES to true)),
            devices = setOf(a, b),
        )
        assertEquals(setOf(a), changed)
    }

    @Test
    fun globalGrantChangeAffectsEveryDevice() {
        val changed = DeviceAuthorization.changedDevices(
            oldGlobal = mapOf(BridgeyFeature.CLIPBOARD to true),
            newGlobal = mapOf(BridgeyFeature.CLIPBOARD to false),
            oldPerDevice = emptyMap<String, Map<BridgeyFeature, Boolean>>(),
            newPerDevice = emptyMap(),
            devices = setOf(a, b),
        )
        assertEquals(setOf(a, b), changed)
    }

    @Test
    fun forgottenDevicesAreNeverReported() {
        val forgotten = "30000000-0000-4000-8000-00000000000c"
        val changed = DeviceAuthorization.changedDevices(
            oldGlobal = mapOf(BridgeyFeature.CLIPBOARD to true),
            newGlobal = mapOf(BridgeyFeature.CLIPBOARD to true),
            oldPerDevice = mapOf(forgotten to mapOf(BridgeyFeature.CLIPBOARD to false)),
            newPerDevice = emptyMap(),
            devices = setOf(a, b),
        )
        assertEquals(emptySet<String>(), changed)
    }

    @Test
    fun authorizationEventsReachOnlyTheChangedDevices() {
        val lifecycle = PeerLifecycle()
        val store = PerDeviceStore()
        lifecycle.addObserver(store)
        lifecycle.authorizationChanged(setOf(b))
        assertEquals(listOf("auth:$b"), store.events)
    }

    @Test
    fun perDeviceGrantOnlyAffectsItsDevice() {
        val perDevice = mapOf(a to mapOf(BridgeyFeature.CLIPBOARD to false))
        assertFalse(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = perDevice[a]?.get(BridgeyFeature.CLIPBOARD)))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = perDevice[b]?.get(BridgeyFeature.CLIPBOARD)))
    }

    // endregion

    // region Directory

    @Test
    fun directoryProjectsTrustPresenceAndSessions() {
        val entries = DeviceDirectory.entries(
            trusted = listOf(
                DeviceDirectory.TrustedDevice(a, "Phone (stored)", "android", "phone"),
                DeviceDirectory.TrustedDevice(b, "Work Mac", null, null),
            ),
            presence = mapOf(b to PeerPresence(b, "Work Mac", "macos", "computer", 1, emptyList())),
            connectedNames = mapOf(a to "Galaxy"),
            state = { if (it == a) PeerConnectionState.CONNECTED else PeerConnectionState.OFFLINE },
            capabilities = { if (it == a) mapOf("clipboard" to true) else null },
            routedDeviceId = a,
        )
        assertEquals(listOf(a, b), entries.map { it.deviceId })
        val phone = entries[0]
        assertEquals("the session's announced name wins", "Galaxy", phone.name)
        assertEquals("metadata recorded on an authenticated connection", DevicePlatform.ANDROID, phone.platform)
        assertEquals(PeerConnectionState.CONNECTED, phone.connection)
        assertEquals(mapOf("clipboard" to true), phone.capabilities)
        assertTrue(phone.isRouted)
        val mac = entries[1]
        assertEquals("a live discovery hint fills in missing metadata", DevicePlatform.MACOS, mac.platform)
        assertEquals(PeerConnectionState.OFFLINE, mac.connection)
        assertNull(mac.capabilities)
        assertFalse(mac.isRouted)
    }

    @Test
    fun recordedMetadataWinsOverASpoofableLiveHint() {
        val entries = DeviceDirectory.entries(
            trusted = listOf(DeviceDirectory.TrustedDevice(a, "Phone", "android", "phone")),
            presence = mapOf(a to PeerPresence(a, "Phone", "macos", "tablet", 1, emptyList())),
            connectedNames = emptyMap(),
            state = { PeerConnectionState.CONNECTED },
            capabilities = { null },
            routedDeviceId = null,
        )
        assertEquals(DevicePlatform.ANDROID, entries[0].platform)
        assertEquals("phone", entries[0].deviceType)
    }

    @Test
    fun unknownPlatformHintStaysUnknown() {
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromHint(null))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromHint("windows"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.fromHint("unknown"))
        assertEquals(DevicePlatform.MACOS, DevicePlatform.fromHint("macOS"))
    }

    // endregion

    // region Applicability

    private fun peer(
        platform: DevicePlatform,
        deviceType: String? = null,
        capabilities: Map<String, Boolean>? = BridgeyFeature.entries.associate { it.key to true },
    ) = DeviceDirectoryEntry(b, "peer", true, PeerConnectionState.CONNECTED, capabilities, platform, deviceType, false)

    private fun offer(
        feature: BridgeyFeature,
        from: DevicePlatform,
        to: DeviceDirectoryEntry,
        localType: String? = null,
        authorized: Boolean = true,
    ) = FeatureApplicability.evaluate(feature, from, localType, to, authorized)

    @Test
    fun webLinksAreOfferedAndroidToMacButNotMacToMac() {
        assertEquals(FeatureApplicabilityResult.OFFERED, offer(BridgeyFeature.LINKS, DevicePlatform.ANDROID, peer(DevicePlatform.MACOS)))
        assertEquals(FeatureApplicabilityResult.OFFERED, offer(BridgeyFeature.LINKS, DevicePlatform.MACOS, peer(DevicePlatform.ANDROID)))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.LINKS, DevicePlatform.MACOS, peer(DevicePlatform.MACOS)))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.LINKS, DevicePlatform.ANDROID, peer(DevicePlatform.ANDROID)))
    }

    @Test
    fun callsAreNeverOfferedBetweenMacsAndNeedAPhone() {
        assertEquals(FeatureApplicabilityResult.OFFERED, offer(BridgeyFeature.CALLS, DevicePlatform.MACOS, peer(DevicePlatform.ANDROID, "phone")))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.CALLS, DevicePlatform.MACOS, peer(DevicePlatform.MACOS)))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.CALLS, DevicePlatform.MACOS, peer(DevicePlatform.ANDROID, "tablet")))
        assertEquals(FeatureApplicabilityResult.OFFERED, offer(BridgeyFeature.CALLS, DevicePlatform.ANDROID, peer(DevicePlatform.MACOS), localType = "phone"))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.CALLS, DevicePlatform.ANDROID, peer(DevicePlatform.MACOS), localType = "tablet"))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.CALLS, DevicePlatform.ANDROID, peer(DevicePlatform.ANDROID)))
    }

    @Test
    fun notificationsFlowOnlyFromAndroidToMac() {
        assertEquals(FeatureApplicabilityResult.OFFERED, offer(BridgeyFeature.NOTIFICATIONS, DevicePlatform.ANDROID, peer(DevicePlatform.MACOS)))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.NOTIFICATIONS, DevicePlatform.MACOS, peer(DevicePlatform.ANDROID)))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.NOTIFICATIONS, DevicePlatform.MACOS, peer(DevicePlatform.MACOS)))
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.NOTIFICATIONS, DevicePlatform.ANDROID, peer(DevicePlatform.ANDROID)))
    }

    @Test
    fun filesFindAndPingAreOfferedInEveryDirection() {
        val platforms = listOf(DevicePlatform.ANDROID, DevicePlatform.MACOS)
        for (feature in listOf(BridgeyFeature.FILES, BridgeyFeature.FIND_DEVICE, BridgeyFeature.PING)) {
            for (from in platforms) for (to in platforms) {
                assertEquals("$feature $from → $to", FeatureApplicabilityResult.OFFERED, offer(feature, from, peer(to)))
            }
        }
    }

    @Test
    fun clipboardIsNotOfferedMacToMac() {
        assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(BridgeyFeature.CLIPBOARD, DevicePlatform.MACOS, peer(DevicePlatform.MACOS)))
        assertEquals(FeatureApplicabilityResult.OFFERED, offer(BridgeyFeature.CLIPBOARD, DevicePlatform.ANDROID, peer(DevicePlatform.ANDROID)))
    }

    @Test
    fun remoteStartAndKvmAreMacToAndroidOnly() {
        for (feature in listOf(BridgeyFeature.REMOTE_SCREEN_SHARE, BridgeyFeature.KVM_INPUT)) {
            assertEquals(FeatureApplicabilityResult.OFFERED, offer(feature, DevicePlatform.MACOS, peer(DevicePlatform.ANDROID)))
            assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(feature, DevicePlatform.ANDROID, peer(DevicePlatform.MACOS)))
            assertEquals(FeatureApplicabilityResult.NOT_APPLICABLE, offer(feature, DevicePlatform.ANDROID, peer(DevicePlatform.ANDROID)))
        }
    }

    @Test
    fun capabilityAndAuthorizationAreSeparateFromPlatform() {
        val android = DevicePlatform.ANDROID
        assertEquals(FeatureApplicabilityResult.PEER_LACKS_CAPABILITY,
            offer(BridgeyFeature.CLIPBOARD, DevicePlatform.MACOS, peer(android, capabilities = mapOf("clipboard" to false))))
        assertEquals(FeatureApplicabilityResult.PEER_LACKS_CAPABILITY,
            offer(BridgeyFeature.CLIPBOARD, DevicePlatform.MACOS, peer(android, capabilities = null)))
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED,
            offer(BridgeyFeature.CLIPBOARD, DevicePlatform.MACOS, peer(android), authorized = false))
    }

    @Test
    fun unknownPlatformNeverHidesAFeatureButSecurityStillApplies() {
        val unknown = DevicePlatform.UNKNOWN
        assertEquals("a missing hint never restricts", FeatureApplicabilityResult.OFFERED,
            offer(BridgeyFeature.LINKS, DevicePlatform.ANDROID, peer(unknown)))
        assertEquals(FeatureApplicabilityResult.NOT_AUTHORIZED,
            offer(BridgeyFeature.LINKS, DevicePlatform.ANDROID, peer(unknown), authorized = false))
        assertEquals(FeatureApplicabilityResult.PEER_LACKS_CAPABILITY,
            offer(BridgeyFeature.LINKS, DevicePlatform.ANDROID, peer(unknown, capabilities = emptyMap())))
    }

    @Test
    fun everyFeatureHasAnApplicabilityRule() {
        for (feature in BridgeyFeature.entries) {
            assertTrue("$feature has directions", FeatureApplicability.directions(feature).isNotEmpty())
        }
    }

    // endregion
}
