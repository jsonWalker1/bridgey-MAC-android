package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MD-4c per-device telemetry: values belong to the peer that sent them, the subscription follows
 * the shown peer only, and each subscriber of this device is served independently.
 */
class DeviceTelemetryTest {
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"
    private val connected: (String) -> Boolean = { true }

    // region State isolation

    @Test
    fun aUpdateLeavesBUnchangedAndViceVersa() {
        var map = DeviceTelemetryStore.update(emptyMap(), a) { it.copy(battery = RemoteBatteryStatus(82, false)) }
        assertEquals(82, map[a]?.battery?.level)
        assertNull(map[b])
        map = DeviceTelemetryStore.update(map, b) { it.copy(cpu = RemoteCpuStatus.Available(17)) }
        assertEquals(82, map[a]?.battery?.level)
        assertNull("B's CPU never appears for A", map[a]?.cpu)
        assertEquals(RemoteCpuStatus.Available(17), map[b]?.cpu)
        assertNull("A's battery never appears for B", map[b]?.battery)
    }

    @Test
    fun disconnectOfARemovesOnlyA() {
        var map = DeviceTelemetryStore.update(emptyMap(), a) { it.copy(storage = RemoteStorageStatus(1, 2)) }
        map = DeviceTelemetryStore.update(map, b) { it.copy(memory = RemoteMemoryStatus(3, 4)) }
        map = DeviceTelemetryStore.remove(map, a)
        assertNull(map[a])
        assertEquals(RemoteMemoryStatus(3, 4), map[b]?.memory)
    }

    @Test
    fun reconnectStartsFreshWithoutAnotherPeersValues() {
        var map = DeviceTelemetryStore.update(emptyMap(), a) { it.copy(battery = RemoteBatteryStatus(50, true)) }
        map = DeviceTelemetryStore.update(map, b) { it.copy(battery = RemoteBatteryStatus(90, false)) }
        map = DeviceTelemetryStore.remove(map, a)
        assertNull("A comes back with nothing, not with B's values", map[a])
        map = DeviceTelemetryStore.update(map, a) { it.copy(battery = RemoteBatteryStatus(51, true)) }
        assertEquals(51, map[a]?.battery?.level)
        assertEquals(90, map[b]?.battery?.level)
    }

    @Test
    fun aMissingMetricStaysUnavailableAndIsNeverFilledFromAnotherPeer() {
        var map = DeviceTelemetryStore.update(emptyMap(), a) { it.copy(cpu = RemoteCpuStatus.Unavailable) }
        map = DeviceTelemetryStore.update(map, b) { it.copy(cpu = RemoteCpuStatus.Available(40)) }
        assertEquals(RemoteCpuStatus.Unavailable, map[a]?.cpu)
        assertNull(map[a]?.temperature)
    }

    @Test
    fun revokedGrantClearsOnlyThatPeersMetric() {
        var map = DeviceTelemetryStore.update(emptyMap(), a) { it.copy(battery = RemoteBatteryStatus(10, false), cpu = RemoteCpuStatus.Available(5)) }
        map = DeviceTelemetryStore.update(map, b) { it.copy(battery = RemoteBatteryStatus(20, false)) }
        map = DeviceTelemetryStore.prune(map) { id, metric -> !(id == a && metric == TelemetryMetric.BATTERY) }
        assertNull(map[a]?.battery)
        assertEquals(RemoteCpuStatus.Available(5), map[a]?.cpu)
        assertEquals(20, map[b]?.battery?.level)
        map = DeviceTelemetryStore.prune(map) { id, metric -> !(id == a && metric == TelemetryMetric.CPU) }
        assertNull("a peer with no values left is dropped", map[a])
    }

    // endregion

    // region Subscription follows the shown peer

    @Test
    fun openingTheAppSubscribesTheSelectedPeer() {
        val subscription = TelemetrySubscription()
        assertEquals(listOf(TelemetrySubscription.Change.Subscribe(a)), subscription.show(a, connected))
        assertEquals(a, subscription.subscribedDeviceId)
    }

    @Test
    fun changingSelectionUnsubscribesTheOldPeerAndSubscribesTheNewOne() {
        val subscription = TelemetrySubscription()
        subscription.show(a, connected)
        assertEquals(
            listOf(TelemetrySubscription.Change.Unsubscribe(a), TelemetrySubscription.Change.Subscribe(b)),
            subscription.show(b, connected),
        )
        assertEquals("showing the same peer again sends nothing", emptyList<TelemetrySubscription.Change>(), subscription.show(b, connected))
    }

    @Test
    fun backgroundingTheAppStopsTheSubscription() {
        val subscription = TelemetrySubscription()
        subscription.show(a, connected)
        assertEquals(listOf(TelemetrySubscription.Change.Unsubscribe(a)), subscription.show(null, connected))
        assertNull(subscription.subscribedDeviceId)
        assertEquals("no background subscription", emptyList<TelemetrySubscription.Change>(), subscription.show(null, connected))
    }

    @Test
    fun disconnectAndReconnectOfTheShownPeerResubscribesItOnly() {
        val subscription = TelemetrySubscription()
        subscription.show(a, connected)
        subscription.sessionEnded(a)
        assertNull(subscription.subscribedDeviceId)
        assertEquals("another peer connecting is not subscribed", emptyList<TelemetrySubscription.Change>(), subscription.sessionStarted(b))
        assertEquals(listOf(TelemetrySubscription.Change.Subscribe(a)), subscription.sessionStarted(a))
    }

    @Test
    fun anEndedSessionOfAnotherPeerDoesNotTouchTheSubscription() {
        val subscription = TelemetrySubscription()
        subscription.show(a, connected)
        subscription.sessionEnded(b)
        assertEquals(a, subscription.subscribedDeviceId)
    }

    // endregion

    // region Publish side

    @Test
    fun samplingRunsWhileAnySubscriberRemains() {
        val subscribers = TelemetrySubscribers()
        assertTrue("first subscriber starts the loop", subscribers.add(a))
        assertFalse(subscribers.add(b))
        assertFalse("B still displays this device", subscribers.remove(a))
        assertTrue("last subscriber stops the loop", subscribers.remove(b))
        assertTrue(subscribers.isEmpty())
    }

    @Test
    fun theDeadBandIsPerSubscriber() {
        val subscribers = TelemetrySubscribers()
        subscribers.add(a)
        val status = LocalStorageStatus(1_000_000_000, 2_000_000_000)
        assertTrue(subscribers.shouldSend(storage = status, to = a))
        subscribers.sent(storage = status, to = a)
        assertFalse("unchanged value is not resent to A", subscribers.shouldSend(storage = status, to = a))
        subscribers.add(b)
        assertTrue("a new subscriber always gets the value", subscribers.shouldSend(storage = status, to = b))
        val moved = LocalStorageStatus(1_000_000_000 + TelemetrySubscribers.CHANGE_THRESHOLD_BYTES, 2_000_000_000)
        assertTrue(subscribers.shouldSend(storage = moved, to = a))
    }

    @Test
    fun aReEnabledGrantResetsOnlyThatSubscribersDeadBand() {
        val subscribers = TelemetrySubscribers()
        subscribers.add(a)
        subscribers.add(b)
        val storage = LocalStorageStatus(7, 9)
        subscribers.sent(storage = storage, to = a)
        subscribers.sent(storage = storage, to = b)
        subscribers.resetDeadBand(a)
        assertTrue("A gets the value again right away", subscribers.shouldSend(storage = storage, to = a))
        assertFalse("B's dead-band is untouched", subscribers.shouldSend(storage = storage, to = b))
    }

    // endregion

    // region Selection vs routing

    @Test
    fun theShownTelemetryFollowsTheSelectionNotTheRoutedPeer() {
        val items = listOf(
            DeviceListItem(a, "S23 Ultra", DevicePlatform.ANDROID, DeviceKind.PHONE, true),
            DeviceListItem(b, "MacBook", DevicePlatform.MACOS, DeviceKind.COMPUTER, true),
        )
        var map = DeviceTelemetryStore.update(emptyMap(), a) { it.copy(battery = RemoteBatteryStatus(82, false)) }
        map = DeviceTelemetryStore.update(map, b) { it.copy(battery = RemoteBatteryStatus(40, true)) }
        val context = SelectedDeviceContext.make(items, selectedDeviceId = a, routedDeviceId = b)
        assertEquals("selected A shows A's battery", 82, map[context.selected!!.deviceId]?.battery?.level)
        assertFalse("legacy features still belong to B", context.legacyFeaturesApply)
        assertEquals(b, context.legacyFeaturesUseOtherPeer?.deviceId)
    }

    // endregion
}
